package com.lianyu.ai.network.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lianyu.ai.common.PerfPrefs
import com.lianyu.ai.common.SecureLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Breeze TTS 2（GGUF）本地合成引擎，基于 CrispASR（libcrispasr.so）。
 *
 * 文件：用户手动选 GGUF，复制进 `<filesDir>/models/tts/breeze/`：
 *   - breeze-model.gguf  Breeze TTS 2 主模型（如 breeze-tts-2-q8_0.gguf）
 *   - breeze-codec.gguf  音频 codec（qwen3-tts-tokenizer-12hz.gguf）
 *   - voice.wav          （可选）自定义音色的参考录音；配套文字稿存在 SharedPreferences
 *     有参考录音时，每次加载模型都会自动设置为当前音色，之后所有合成都用这个音色。
 *
 * 加载：开关打开 = 加载进内存；关闭 = 释放内存（不删文件）。
 * 加载状态不落盘：进程重启后自动回到"未加载"，和本地 LLM 的开关一致。
 *
 * 复制 / 加载 / 删除都在引擎自己的协程里跑，退出设置页不会中断复制。
 */
object CrispAsrTtsEngine {

    private const val TAG = "CrispAsrTts"
    private const val PREFS = "breeze_tts"
    private const val COPY_BUFFER = 1024 * 1024
    private const val PROGRESS_STEP = 8L * 1024 * 1024

    private const val VOICE_FILE = "voice.wav"
    private const val MAX_VOICE_BYTES = 30L * 1024 * 1024
    private const val MIN_VOICE_SECONDS = 2f
    private const val MAX_VOICE_SECONDS = 30f
    private const val PREF_VOICE_NAME = "voice_name"
    private const val PREF_VOICE_TEXT = "voice_text"
    private const val PREF_VOICE_SECONDS = "voice_seconds"
    private const val PREF_VOICE_CONSENT_AT = "voice_consent_at"

    enum class Kind(val fileName: String, val label: String, val prefKey: String) {
        MODEL("breeze-model.gguf", "模型文件", "model_name"),
        CODEC("breeze-codec.gguf", "codec 文件", "codec_name")
    }

    data class State(
        /** 已导入的模型文件原名；没有文件 = null */
        val modelName: String? = null,
        /** 已导入的 codec 文件原名；没有文件 = null */
        val codecName: String? = null,
        /** 正在复制的文件标签（"模型文件"/"codec 文件"）；不在复制 = null */
        val copyingLabel: String? = null,
        val copiedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: String? = null,
        /** 已保存的自定义音色参考录音原名；没有 = null */
        val voiceName: String? = null,
        val voiceSeconds: Float = 0f,
        val voiceText: String = "",
        /** 当前已加载的模型是否正在使用自定义音色 */
        val voiceActive: Boolean = false,
        /** CrispASR 识别出的后端名（如 breeze-tts-2），加载成功后显示，排查用 */
        val backend: String? = null
    ) {
        val filesReady: Boolean get() = modelName != null && codecName != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 保护 session：加载 / 卸载 / 合成 / 删除互斥（native 会话不是线程安全的） */
    private val mutex = Mutex()

    @Volatile private var api: CrispAsrNative? = null
    @Volatile private var session: Pointer? = null
    @Volatile private var sampleRate: Int = 24000
    @Volatile private var voiceApplied: Boolean = false
    @Volatile private var nativeLogFile: File? = null
    @Volatile private var backendName: String? = null

    fun isLoaded(): Boolean = session != null

    fun dir(context: Context): File =
        File(File(context.applicationContext.filesDir, "models"), "tts/breeze")

    private fun fileOf(context: Context, kind: Kind): File = File(dir(context), kind.fileName)

    private fun voiceFile(context: Context): File = File(dir(context), VOICE_FILE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 状态刷新 ──

    /** 进入设置页时调用：按磁盘上的文件重新算"已导入"状态 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        fun nameOf(kind: Kind): String? {
            val f = fileOf(app, kind)
            return if (f.isFile && f.length() > 0L) (p.getString(kind.prefKey, null) ?: kind.fileName) else null
        }
        val vf = voiceFile(app)
        val hasVoice = vf.isFile && vf.length() > 0L
        _state.update {
            it.copy(
                modelName = nameOf(Kind.MODEL),
                codecName = nameOf(Kind.CODEC),
                loaded = session != null,
                voiceName = if (hasVoice) (p.getString(PREF_VOICE_NAME, null) ?: VOICE_FILE) else null,
                voiceSeconds = if (hasVoice) p.getFloat(PREF_VOICE_SECONDS, 0f) else 0f,
                voiceText = if (hasVoice) (p.getString(PREF_VOICE_TEXT, "") ?: "") else "",
                voiceActive = session != null && voiceApplied && hasVoice
            )
        }
    }

    // ── 导入（复制进 App） ──

    fun startImport(context: Context, uri: Uri, kind: Kind) {
        val app = context.applicationContext
        if (_state.value.copyingLabel != null || _state.value.loading) return
        _state.update { it.copy(error = null, copyingLabel = kind.label, copiedBytes = 0L, totalBytes = 0L) }
        scope.launch {
            val target = fileOf(app, kind)
            val tmp = File(target.parentFile, kind.fileName + ".part")
            try {
                var pickedName: String? = null
                var size = 0L
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) pickedName = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }

                // 换文件前先释放内存里的旧模型
                mutex.withLock { unloadLocked() }

                target.parentFile?.mkdirs()
                if (size > 0L && app.filesDir.usableSpace < size + 64L * 1024 * 1024) {
                    throw IllegalStateException("手机剩余空间不足：需要约 ${size / 1024 / 1024} MB")
                }
                _state.update { it.copy(totalBytes = size, loaded = false, voiceActive = false) }

                if (tmp.exists()) tmp.delete()
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                input.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(COPY_BUFFER)
                        var total = 0L
                        var lastReported = 0L
                        var first = true
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (first) {
                                first = false
                                // GGUF 文件开头固定是 "GGUF" 四个字节
                                val ok = n >= 4 && buf[0] == 'G'.code.toByte() && buf[1] == 'G'.code.toByte() &&
                                    buf[2] == 'U'.code.toByte() && buf[3] == 'F'.code.toByte()
                                if (!ok) throw IllegalStateException("这不是 GGUF 文件，请重新选择")
                            }
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastReported >= PROGRESS_STEP) {
                                lastReported = total
                                _state.update { it.copy(copiedBytes = total) }
                            }
                        }
                        if (total < 1024L) throw IllegalStateException("文件太小，不是有效的模型文件")
                        _state.update { it.copy(copiedBytes = total) }
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("保存文件失败")
                prefs(app).edit().putString(kind.prefKey, pickedName ?: kind.fileName).apply()
                SecureLog.i(TAG, "已导入 ${kind.label}: ${target.length()} bytes")
                _state.update { it.copy(copyingLabel = null, copiedBytes = 0L, totalBytes = 0L) }
                refresh(app)
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (t: Throwable) {
                tmp.delete()
                SecureLog.e(TAG, "导入失败", t)
                _state.update {
                    it.copy(copyingLabel = null, copiedBytes = 0L, totalBytes = 0L, error = "导入失败：${t.message ?: t.javaClass.simpleName}")
                }
                refresh(app)
            }
        }
    }

    // ── 加载 / 卸载 ──

    fun load(context: Context) {
        val app = context.applicationContext
        scope.launch { loadInternal(app) }
    }

    /**
     * 确保模型已在内存里：没加载但文件齐全就现场加载（重启 App 后第一次语音回复会走这里）。
     * 返回是否可以合成；失败原因在 [state] 的 error 里。
     */
    suspend fun ensureLoaded(context: Context): Boolean {
        if (session != null) return true
        val app = context.applicationContext
        refresh(app)
        if (!_state.value.filesReady) return false
        loadInternal(app)
        return session != null
    }

    private suspend fun loadInternal(app: Context) {
        mutex.withLock {
            if (session != null) {
                _state.update { it.copy(loaded = true, loading = false, error = null) }
                return
            }
            val modelFile = fileOf(app, Kind.MODEL)
            val codecFile = fileOf(app, Kind.CODEC)
            if (!modelFile.isFile || !codecFile.isFile) {
                _state.update { it.copy(loaded = false, loading = false, error = "请先导入模型文件和 codec 文件") }
                return
            }
            _state.update { it.copy(loading = true, loaded = false, error = null) }
            try {
                installNativeLogCapture(app)
                val lib = nativeApi()
                val threads = PerfPrefs.ttsThreads(app)
                val s = lib.crispasr_session_open(modelFile.absolutePath, threads)
                    ?: throw IllegalStateException("模型打不开：可能是 so 里没有 breeze 后端，或内存不足")
                val rc = lib.crispasr_session_set_codec_path(s, codecFile.absolutePath)
                if (rc != 0) SecureLog.w(TAG, "set_codec_path 返回 $rc")

                // 自定义音色：有参考录音就设成当前音色（必须在 codec 设置之后，编码参考音频要用 codec）
                var voiceOk = false
                val vf = voiceFile(app)
                if (vf.isFile && vf.length() > 0L) {
                    val refText = prefs(app).getString(PREF_VOICE_TEXT, null)?.trim().orEmpty()
                    val vrc = lib.crispasr_session_set_voice(s, vf.absolutePath, refText.ifEmpty { null })
                    if (vrc != 0) {
                        runCatching { lib.crispasr_session_close(s) }
                        throw IllegalStateException(
                            "自定义音色设置失败（返回码 $vrc）。可能是这个后端暂不支持声音克隆，" +
                                "或参考录音/文字稿有问题。可以先点「删除自定义音色」，改用默认音色"
                        )
                    }
                    voiceOk = true
                }
                voiceApplied = voiceOk
                val backend = runCatching { lib.crispasr_session_backend(s) }.getOrNull()
                backendName = backend
                sampleRate = runCatching { lib.crispasr_session_output_sample_rate(s) }.getOrDefault(0)
                    .takeIf { it > 0 } ?: 24000
                session = s
                SecureLog.i(TAG, "Breeze 已加载 backend=$backend sampleRate=$sampleRate threads=$threads")
                _state.update { it.copy(loading = false, loaded = true, error = null, voiceActive = voiceOk, backend = backend) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                SecureLog.e(TAG, "加载失败", t)
                val msg = if (t is UnsatisfiedLinkError) "原生库加载失败：${t.message}" else (t.message ?: t.javaClass.simpleName)
                voiceApplied = false
                _state.update { it.copy(loading = false, loaded = false, error = msg, voiceActive = false) }
            }
        }
    }

    fun unload() {
        scope.launch {
            mutex.withLock { unloadLocked() }
            _state.update { it.copy(loaded = false, loading = false, voiceActive = false, backend = null) }
        }
    }

    private fun unloadLocked() {
        voiceApplied = false
        val s = session ?: return
        session = null
        runCatching { api?.crispasr_session_close(s) }
        SecureLog.i(TAG, "Breeze 已从内存卸载")
    }

    // ── 删除文件 ──

    /** 删除已导入的模型和 codec（自定义音色单独管理，不受影响） */
    fun deleteFiles(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                unloadLocked()
                Kind.values().forEach { fileOf(app, it).delete() }
                prefs(app).edit().remove(Kind.MODEL.prefKey).remove(Kind.CODEC.prefKey).apply()
            }
            _state.update { it.copy(error = null, loaded = false, loading = false, voiceActive = false) }
            refresh(app)
        }
    }

    // ── 自定义音色 ──

    /**
     * 保存自定义音色：参考录音（WAV，2–30 秒）+ 它的逐字文字稿。
     * [consent] 必须为 true：调用方要先让用户确认"声音是本人的，或所有者已同意"。
     * 如果模型正在内存里，保存后会自动重新加载一次，让新音色立刻生效。
     */
    fun importVoice(context: Context, uri: Uri, refText: String, consent: Boolean) {
        val app = context.applicationContext
        val text = refText.trim()
        if (!consent) {
            _state.update { it.copy(error = "请先勾选确认：声音是本人的，或所有者已同意") }
            return
        }
        if (text.isEmpty()) {
            _state.update { it.copy(error = "请先填写参考录音的逐字文字稿") }
            return
        }
        if (_state.value.copyingLabel != null || _state.value.loading) return
        _state.update { it.copy(error = null, copyingLabel = "参考音频", copiedBytes = 0L, totalBytes = 0L) }
        scope.launch {
            val target = voiceFile(app)
            val tmp = File(target.parentFile, "$VOICE_FILE.part")
            try {
                var pickedName: String? = null
                var size = 0L
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) pickedName = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                if (size > MAX_VOICE_BYTES) {
                    throw IllegalStateException("文件太大（${size / 1024 / 1024} MB），参考录音请剪到 30 秒以内")
                }
                target.parentFile?.mkdirs()
                if (tmp.exists()) tmp.delete()
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                input.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total > MAX_VOICE_BYTES) {
                                throw IllegalStateException("文件太大，参考录音请剪到 30 秒以内")
                            }
                        }
                    }
                }
                val seconds = readWavSeconds(tmp)
                if (seconds < MIN_VOICE_SECONDS) {
                    throw IllegalStateException("录音太短（%.1f 秒），请用 2 秒以上、最好 5–15 秒的录音".format(seconds))
                }
                if (seconds > MAX_VOICE_SECONDS) {
                    throw IllegalStateException("录音太长（%.0f 秒），请剪到 30 秒以内".format(seconds))
                }

                val wasLoaded = session != null
                mutex.withLock { unloadLocked() }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("保存参考录音失败")
                prefs(app).edit()
                    .putString(PREF_VOICE_NAME, pickedName ?: VOICE_FILE)
                    .putString(PREF_VOICE_TEXT, text)
                    .putFloat(PREF_VOICE_SECONDS, seconds)
                    .putLong(PREF_VOICE_CONSENT_AT, System.currentTimeMillis())
                    .apply()
                // 留一条同意确认的本地记录（用户在界面上勾选了确认）
                SecureLog.i(TAG, "[CONSENT] 自定义音色已保存，用户已确认声音为本人或已获所有者同意 seconds=$seconds")
                _state.update { it.copy(copyingLabel = null, copiedBytes = 0L, totalBytes = 0L, loaded = false, voiceActive = false) }
                refresh(app)
                if (wasLoaded) loadInternal(app)
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (t: Throwable) {
                tmp.delete()
                SecureLog.e(TAG, "保存自定义音色失败", t)
                _state.update {
                    it.copy(copyingLabel = null, copiedBytes = 0L, totalBytes = 0L, error = "保存音色失败：${t.message ?: t.javaClass.simpleName}")
                }
                refresh(app)
            }
        }
    }

    /** 删除自定义音色，恢复默认音色。模型如果正在内存里会被卸载，需要重新打开开关。 */
    fun deleteVoice(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                unloadLocked()
                voiceFile(app).delete()
                prefs(app).edit()
                    .remove(PREF_VOICE_NAME).remove(PREF_VOICE_TEXT)
                    .remove(PREF_VOICE_SECONDS).remove(PREF_VOICE_CONSENT_AT)
                    .apply()
            }
            _state.update { it.copy(error = null, loaded = false, loading = false, voiceActive = false) }
            refresh(app)
        }
    }

    /** 读 WAV 头算时长（秒）；不是可用的 WAV 就抛带中文提示的异常。 */
    private fun readWavSeconds(f: File): Float {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val notWav = IllegalStateException("不是有效的 WAV 文件，请选择 .wav 格式的录音（其他格式请先转成 WAV）")
            if (len < 44L) throw notWav
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") throw notWav

            var channels = 0
            var rate = 0
            var bits = 0
            var dataBytes = -1L
            var pos = 12L
            val ch = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(ch)
                val id = String(ch, 0, 4, Charsets.US_ASCII)
                val size = le32(ch, 4)
                if (id == "fmt ") {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    val tag = le16(fmt, 0)
                    if (tag != 1 && tag != 3 && tag != 0xFFFE) {
                        throw IllegalStateException("WAV 编码不支持，请用 16 位 PCM 的 WAV")
                    }
                    channels = le16(fmt, 2)
                    rate = le32(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                } else if (id == "data") {
                    dataBytes = if (size == 0L || size == 0xFFFFFFFFL || pos + 8 + size > len) len - (pos + 8) else size
                    break
                }
                pos += 8 + size + (size and 1L)
            }
            if (channels <= 0 || rate <= 0 || bits < 8 || dataBytes < 0L) throw notWav
            return dataBytes.toFloat() / (rate.toFloat() * channels * (bits / 8f))
        }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)

    // ── 合成 ──

    /** 合成一段文字，返回（单声道 PCM -1..1，采样率）。必须先 [load]。 */
    suspend fun synthesize(text: String): Pair<FloatArray, Int> = mutex.withLock {
        val s = session ?: throw IllegalStateException("Breeze 模型未加载，请先在语音设置里打开开关")
        val lib = api ?: throw IllegalStateException("原生库未初始化")
        val count = IntByReference(0)
        val logStart = nativeLogFile?.length() ?: 0L
        val ptr = lib.crispasr_session_synthesize(s, text, count)
        if (ptr == null || count.value <= 0) {
            if (ptr != null) runCatching { lib.crispasr_pcm_free(ptr) }
            val detail = try {
                lib.crispasr_session_last_synth_error(s)
            } catch (t: Throwable) {
                "（读取错误详情失败：${t.message}）"
            }
            val nativeLog = nativeLogTail(logStart)
            SecureLog.w(TAG, "synthesize 失败 backend=$backendName samples=${count.value} detail=$detail log=$nativeLog")
            val sb = StringBuilder("合成失败（后端=${backendName ?: "未知"}，样本数=${count.value}，字数=${text.length}）")
            if (!detail.isNullOrBlank()) sb.append("\n错误：").append(detail)
            if (nativeLog.isNotBlank()) sb.append("\n原生日志：").append(nativeLog)
            throw IllegalStateException(sb.toString())
        }
        try {
            ptr.getFloatArray(0, count.value) to sampleRate
        } finally {
            lib.crispasr_pcm_free(ptr)
        }
    }

    // ── 原生日志（排查用）──

    /** 把 native 层写到 stderr 的内容重定向到 cacheDir 下的文件，合成失败时把最后一段附在报错里 */
    private fun installNativeLogCapture(app: Context) {
        if (nativeLogFile != null) return
        runCatching {
            val f = File(app.cacheDir, "crispasr-native.log")
            if (f.exists() && f.length() > 1_000_000L) f.delete()
            val fos = java.io.FileOutputStream(f, true)
            android.system.Os.dup2(fos.fd, android.system.OsConstants.STDERR_FILENO)
            fos.close()
            nativeLogFile = f
        }.onFailure { SecureLog.w(TAG, "无法捕获原生日志：${it.message}") }
    }

    private fun nativeLogTail(fromOffset: Long): String {
        val f = nativeLogFile ?: return ""
        return runCatching {
            val len = f.length()
            val start = maxOf(fromOffset, len - 1500L).coerceAtLeast(0L)
            if (len <= start) return@runCatching ""
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val b = ByteArray((len - start).toInt())
                raf.readFully(b)
                String(b, Charsets.UTF_8).trim()
            }
        }.getOrDefault("")
    }

    // ── 原生库 ──

    private fun nativeApi(): CrispAsrNative {
        api?.let { return it }
        val loaded = Native.load(
            "crispasr",
            CrispAsrNative::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")   // 中文文本必须按 UTF-8 传给 C 层
        )
        api = loaded
        return loaded
    }
}
