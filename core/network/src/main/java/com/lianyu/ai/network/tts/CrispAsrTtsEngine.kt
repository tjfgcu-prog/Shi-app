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
        val error: String? = null
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

    fun isLoaded(): Boolean = session != null

    fun dir(context: Context): File =
        File(File(context.applicationContext.filesDir, "models"), "tts/breeze")

    private fun fileOf(context: Context, kind: Kind): File = File(dir(context), kind.fileName)

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
        _state.update {
            it.copy(modelName = nameOf(Kind.MODEL), codecName = nameOf(Kind.CODEC), loaded = session != null)
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
                _state.update { it.copy(totalBytes = size, loaded = false) }

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
                val lib = nativeApi()
                val threads = PerfPrefs.ttsThreads(app)
                val s = lib.crispasr_session_open(modelFile.absolutePath, threads)
                    ?: throw IllegalStateException("模型打不开：可能是 so 里没有 breeze 后端，或内存不足")
                val rc = lib.crispasr_session_set_codec_path(s, codecFile.absolutePath)
                if (rc != 0) SecureLog.w(TAG, "set_codec_path 返回 $rc")
                val backend = runCatching { lib.crispasr_session_backend(s) }.getOrNull()
                sampleRate = runCatching { lib.crispasr_session_output_sample_rate(s) }.getOrDefault(0)
                    .takeIf { it > 0 } ?: 24000
                session = s
                SecureLog.i(TAG, "Breeze 已加载 backend=$backend sampleRate=$sampleRate threads=$threads")
                _state.update { it.copy(loading = false, loaded = true, error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                SecureLog.e(TAG, "加载失败", t)
                val msg = if (t is UnsatisfiedLinkError) "原生库加载失败：${t.message}" else (t.message ?: t.javaClass.simpleName)
                _state.update { it.copy(loading = false, loaded = false, error = msg) }
            }
        }
    }

    fun unload() {
        scope.launch {
            mutex.withLock { unloadLocked() }
            _state.update { it.copy(loaded = false, loading = false) }
        }
    }

    private fun unloadLocked() {
        val s = session ?: return
        session = null
        runCatching { api?.crispasr_session_close(s) }
        SecureLog.i(TAG, "Breeze 已从内存卸载")
    }

    // ── 删除文件 ──

    fun deleteFiles(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                unloadLocked()
                dir(app).deleteRecursively()
                prefs(app).edit().clear().apply()
            }
            _state.update { it.copy(error = null, loaded = false, loading = false) }
            refresh(app)
        }
    }

    // ── 合成 ──

    /** 合成一段文字，返回（单声道 PCM -1..1，采样率）。必须先 [load]。 */
    suspend fun synthesize(text: String): Pair<FloatArray, Int> = mutex.withLock {
        val s = session ?: throw IllegalStateException("Breeze 模型未加载，请先在语音设置里打开开关")
        val lib = api ?: throw IllegalStateException("原生库未初始化")
        val count = IntByReference(0)
        val ptr = lib.crispasr_session_synthesize(s, text, count)
        if (ptr == null || count.value <= 0) {
            val detail = runCatching { lib.crispasr_session_last_synth_error(s) }.getOrNull()
            throw IllegalStateException("合成失败" + (detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""))
        }
        try {
            ptr.getFloatArray(0, count.value) to sampleRate
        } finally {
            lib.crispasr_pcm_free(ptr)
        }
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
