package com.lianyu.ai.common

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内「实时日志」的唯一数据源：App 里所有日志都汇到这里，设置里的「实时日志」页只读这里。
 *
 * 来源：
 *  - [SecureLog]（全部级别）、ChatDebugLog、本地模型 / 语音 / 记忆的诊断日志，直接调用本类；
 *  - 崩溃记录、上次进程退出原因（[fatal] / [diag]）；
 *  - 本进程的 logcat（原生库、第三方库、系统对本进程的警告；release 包只收警告及以上），由 [init] 启动的后台线程抓取。
 *
 * 隐私规则（沿用原来的设计）：
 *  - 调试包（debuggable）：d / i / w / e 全部记录；
 *  - release 包：d / i / w / e 一律不记录（这些日志里可能带聊天内容片段，release 里本来也被 R8 删掉了），
 *    只记录 [fatal]（崩溃）、[diag]（诊断：退出原因、记忆摘要状态、模型加载信息等不含聊天内容的信息）
 *    和 logcat 里的警告 / 错误。
 *
 * 存储：内存里保留最近 [MAX_ENTRIES] 条供界面实时显示，同时追加写入 files/app_live.log，
 * 文件超过 2MB 自动只保留最后 512KB；下次启动会把文件里最近的记录读回来，所以重启后还能看到上一次的日志。
 */
object AppLog {

    enum class Level(val letter: Char, val priority: Int) {
        VERBOSE('V', 2), DEBUG('D', 3), INFO('I', 4), WARN('W', 5), ERROR('E', 6)
    }

    /** 一条日志。[id] 在本进程内唯一，供列表做 key。 */
    data class Entry(
        val id: Long,
        val time: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun toLine(): String = "$time ${level.letter}/$tag: $message"
    }

    private const val MAX_ENTRIES = 3000
    private const val MAX_MESSAGE_CHARS = 4000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_FILE_BYTES = 512L * 1024
    private const val HISTORY_READ_BYTES = 400L * 1024
    private const val FILE_NAME = "app_live.log"

    /** 来自 logcat、但对排查没有帮助的系统噪音标签 */
    private val NOISY_LOGCAT_TAGS = setOf(
        "Choreographer", "ViewRootImpl", "InputMethodManager", "InsetsController", "ImeTracker",
        "OpenGLRenderer", "HWUI", "BufferQueueProducer", "BLASTBufferQueue", "SurfaceView",
        "InputTransport", "DecorView", "WindowManager", "Compatibility", "ActivityThread",
        "nativeloader", "ziparchive", "AssistStructure", "FrameTracker", "Gralloc4", "AdrenoGLES",
        "ScrollView", "CompatChangeReporter", "ProfileInstaller", "AppCompatDelegate"
    )

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()
    private var nextId = 0L

    /** 每新增 / 清空一次 +1，界面据此判断要不要刷新 */
    @Volatile
    var version: Long = 0L
        private set

    @Volatile
    private var initialized = false

    /** 是否记录 d / i / w / e（调试包 = true）。release 包为 false。 */
    @Volatile
    var isVerbose: Boolean = false
        private set

    @Volatile
    private var logFile: File? = null

    private val pending = ConcurrentLinkedQueue<String>()
    private val flushScheduled = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog-writer").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val headerRegex = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) ([VDIWE])/(.*?): (.*)$""")
    private val logcatRegex = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: (.*)$""")

    @Volatile
    private var logcatStarted = false

    // ───────────────────────── 初始化 ─────────────────────────

    /** 在 Application.onCreate 最开头调用一次。之前产生的日志只在内存里，这里一并补写进文件。 */
    fun init(context: Context) {
        val app = context.applicationContext
        val f = File(app.filesDir, FILE_NAME)
        synchronized(lock) {
            if (initialized) return
            initialized = true
            isVerbose = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            logFile = f
        }
        // 旧版本的调试日志文件已经不再使用
        runCatching { File(app.filesDir, "chatvm_debug.log").delete() }

        val history = loadHistory(f)
        synchronized(lock) {
            val current = ring.toList()
            ring.clear()
            // 历史记录放最前面，本次启动已经产生的放后面；全部重新编号，避免 id 冲突
            history.forEach { ring.addLast(it.copy(id = nextId++)) }
            current.forEach {
                val e = it.copy(id = nextId++)
                ring.addLast(e)
                pending.add(e.toLine())
            }
            while (ring.size > MAX_ENTRIES) ring.pollFirst()
            version++
        }
        diag("AppLog", "──── 应用启动 · ${if (isVerbose) "debug 包：记录全部级别" else "release 包：只记录崩溃和诊断信息"} ────")
        startLogcatCapture()
    }

    // ───────────────────────── 写日志 ─────────────────────────

    fun d(tag: String, msg: String) {
        if (isVerbose) add(Level.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (isVerbose) add(Level.INFO, tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (isVerbose) add(Level.WARN, tag, msg)
    }

    fun e(tag: String, msg: String) {
        if (isVerbose) add(Level.ERROR, tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (isVerbose) add(Level.ERROR, tag, msg + "\n" + describe(tr))
    }

    /** 诊断信息：任何包都记录。只能写不含聊天内容的信息。 */
    fun diag(tag: String, msg: String) {
        add(Level.INFO, tag, msg)
    }

    /** 严重错误（崩溃等）：任何包都记录，并立刻落盘。 */
    fun fatal(tag: String, msg: String) {
        add(Level.ERROR, tag, msg)
        flushBlocking()
    }

    /** 当前内存里全部日志（旧 → 新） */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    /** 清空内存和文件 */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            version++
        }
        pending.clear()
        writer.execute { runCatching { logFile?.writeText("") } }
        diag("AppLog", "日志已清空")
    }

    /** 等后台线程把已排队的日志写进文件（崩溃时用，最多等 [timeoutMs] 毫秒） */
    fun flushBlocking(timeoutMs: Long = 1500L) {
        runCatching {
            scheduleFlush()
            writer.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private fun add(level: Level, tag: String, rawMessage: String) {
        val msg = if (rawMessage.length > MAX_MESSAGE_CHARS) {
            rawMessage.take(MAX_MESSAGE_CHARS) + "…（已截断）"
        } else {
            rawMessage
        }
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = synchronized(lock) {
            val e = Entry(nextId++, time, level, tag, msg)
            ring.addLast(e)
            while (ring.size > MAX_ENTRIES) ring.pollFirst()
            version++
            e
        }
        if (logFile != null) {
            pending.add(entry.toLine())
            scheduleFlush()
        }
    }

    /** 异常描述：类型 + 栈帧。release 包不带异常文字（可能含聊天内容），和崩溃记录保持一致。 */
    private fun describe(tr: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = tr
        var depth = 0
        while (cur != null && depth < 3) {
            if (depth > 0) sb.append("Caused by ")
            sb.append(cur.javaClass.name)
            if (isVerbose) cur.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            for (el in cur.stackTrace.take(if (depth == 0) 10 else 6)) {
                sb.append("    at ").append(el.className).append('.').append(el.methodName)
                    .append('(').append(el.fileName ?: "?").append(':').append(el.lineNumber).append(")\n")
            }
            cur = cur.cause
            depth++
        }
        return sb.toString().trimEnd()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        writer.execute {
            flushScheduled.set(false)
            drainToFile()
        }
    }

    private fun drainToFile() {
        val f = logFile ?: return
        val sb = StringBuilder()
        while (true) {
            val line = pending.poll() ?: break
            sb.append(line).append('\n')
        }
        if (sb.isEmpty()) return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) trimFile(f)
            f.appendText(sb.toString())
        }
    }

    /** 文件太大：只保留最后 [KEEP_FILE_BYTES]，并从下一行开始（避免半行） */
    private fun trimFile(f: File) {
        val len = f.length()
        val keep = minOf(len, KEEP_FILE_BYTES)
        val bytes = RandomAccessFile(f, "r").use { raf ->
            raf.seek(len - keep)
            ByteArray(keep.toInt()).also { raf.readFully(it) }
        }
        f.writeText(String(bytes, Charsets.UTF_8).substringAfter('\n', ""))
    }

    private fun loadHistory(f: File): List<Entry> {
        if (!f.isFile) return emptyList()
        return runCatching {
            val len = f.length()
            val take = minOf(len, HISTORY_READ_BYTES)
            val bytes = RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - take)
                ByteArray(take.toInt()).also { raf.readFully(it) }
            }
            var text = String(bytes, Charsets.UTF_8)
            // 从文件中间开始读时，第一行可能是半行，丢掉
            if (take < len) text = text.substringAfter('\n', "")
            parse(text).takeLast(MAX_ENTRIES / 2)
        }.getOrDefault(emptyList())
    }

    private fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val m = headerRegex.find(line)
            if (m != null) {
                val level = Level.entries.firstOrNull { it.letter == m.groupValues[2][0] } ?: Level.INFO
                out.add(Entry(0L, m.groupValues[1], level, m.groupValues[3], m.groupValues[4]))
            } else if (out.isNotEmpty()) {
                // 多行日志的后续行
                val last = out.removeAt(out.size - 1)
                out.add(last.copy(message = last.message + "\n" + line))
            }
        }
        return out
    }

    // ───────────────────────── logcat 抓取 ─────────────────────────

    /**
     * 读本进程的 logcat：把原生库（llama.cpp、sherpa 等）、第三方库和系统对本进程的输出也收进来。
     * 应用自己通过 [SecureLog] 写的日志标签是 "LianYu"，已经直接记录过了，这里跳过避免重复。
     * 个别手机系统（如部分 vivo / OriginOS）会过滤 logcat，抓不到时只是少一些来源，不影响其他日志。
     */
    private fun startLogcatCapture() {
        if (logcatStarted) return
        logcatStarted = true
        val thread = Thread({
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "threadtime", "-T", "1", "--pid=${android.os.Process.myPid()}")
                )
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) onLogcatLine(line)
                }
            } catch (err: Throwable) {
                diag("AppLog", "logcat 抓取不可用：${err.javaClass.simpleName}")
            }
        }, "AppLog-logcat")
        thread.isDaemon = true
        thread.start()
    }

    private fun onLogcatLine(line: String) {
        val m = logcatRegex.find(line) ?: return
        val tag = m.groupValues[2]
        if (tag == "LianYu" || tag in NOISY_LOGCAT_TAGS) return
        val level = when (m.groupValues[1][0]) {
            'V' -> Level.VERBOSE
            'D' -> Level.DEBUG
            'I' -> Level.INFO
            'W' -> Level.WARN
            else -> Level.ERROR
        }
        // debug 包收 INFO 及以上；release 包只收 WARN 及以上（原生库的 INFO 输出里可能带聊天文字）
        val minPriority = if (isVerbose) Level.INFO.priority else Level.WARN.priority
        if (level.priority < minPriority) return
        add(level, "sys/$tag", m.groupValues[3])
    }
}
