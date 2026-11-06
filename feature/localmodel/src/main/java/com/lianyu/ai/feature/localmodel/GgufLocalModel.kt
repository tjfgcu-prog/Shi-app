package com.lianyu.ai.feature.localmodel

import android.content.Context
import com.lianyu.ai.common.PerfPrefs
import com.lianyu.ai.domain.LocalModelStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 对 llamacpp-kotlin 库的简单封装，负责加载用户选择的 .gguf 文件并生成回复。
 */
class GgufLocalModel(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 只在可调试（debug）包里写诊断日志：以前每个 token 都会打开/追加/关闭一次文件，
    // 而且 release 包里也在写（还把 AI 生成的每个字明文落盘）。
    private val debuggable =
        (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun logD(msg: String) {
        if (!debuggable) return
        try {
            val f = File(appContext.filesDir, "chatvm_debug.log")
            f.appendText("${System.currentTimeMillis()} [GgufLocalModel] $msg\n")
        } catch (_: Exception) { }
    }

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 卸载后丢弃旧实例、下次加载时新建一个：
    // 库文档只说明 release() 用于清理，没有说明释放后能否继续复用同一个实例。
    // 线程数选"自动"(0) 时用库自带的 LlamaHelper；手动指定线程数时用我们自己的实现（见 LlamaBackend.kt）。
    @Volatile private var backend: LlamaBackend? = null
    private fun obtainBackend(threads: Int): LlamaBackend =
        backend ?: (
            if (threads > 0) {
                ThreadedLlamaBackend(appContext.contentResolver, scope, llmFlow, threads)
            } else {
                StockLlamaBackend(LlamaHelper(appContext.contentResolver, scope, llmFlow))
            }
            ).also { backend = it }

    @Volatile private var loadedUri: String? = null
    @Volatile private var loadedContextLength: Int = -1
    @Volatile private var loadedThreads: Int = -1
    @Volatile private var loadMs: Long = 0L

    /** 当前真实状态，供设置页显示。 */
    @Volatile
    var status: LocalModelStatus = LocalModelStatus.Unloaded
        private set

    // 本地模型同一时刻只能跑一次推理：聊天回复与后台剧情摘要共用 llmFlow，不加锁事件会串台。
    // 加载、卸载也走这把锁，避免和生成同时发生。
    private val generateMutex = Mutex()

    /** 释放原生内存并回到"未加载"。 */
    private fun releaseNative() {
        val h = backend
        backend = null
        loadedUri = null
        loadedContextLength = -1
        loadedThreads = -1
        loadMs = 0L
        if (h != null) {
            runCatching { h.abort() }
            runCatching { h.release() }
        }
        status = LocalModelStatus.Unloaded
        logD("releaseNative: done")
    }

    private fun failLoad(reason: String): Nothing {
        releaseNative()
        status = LocalModelStatus.Failed(reason)
        logD("ensureLoaded: FAILED, $reason")
        throw IllegalStateException(reason)
    }

    /**
     * 调用方必须已持有 generateMutex。
     * @return true 表示这次真的执行了加载；false 表示已经是加载好的状态。
     */
    private suspend fun ensureLoaded(modelUri: String, contextLength: Int): Boolean {
        val wantedThreads = PerfPrefs.llmThreads(appContext)
        if (loadedUri == modelUri && loadedContextLength == contextLength && loadedThreads == wantedThreads) {
            logD("ensureLoaded: already loaded with same contextLength, skip. uri=$modelUri")
            return false
        }
        if (loadedUri != null) {
            // 换了模型、上下文长度或线程数：先释放旧的，避免两份模型同时占内存
            logD("ensureLoaded: params changed, releasing old context first")
            releaseNative()
        }
        val startedAt = System.currentTimeMillis()
        status = LocalModelStatus.Loading(startedAt)
        logD("ensureLoaded: begin load, uri=$modelUri, contextLength=$contextLength, threads=$wantedThreads(0=自动)")

        // load() 的回调只在成功时触发，失败没有回调：
        // 一方面监听引擎的 Error 事件，另一方面设置超时，保证不会无限等待。
        val outcome: Boolean? = try {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    lateinit var errorJob: Job
                    errorJob = scope.launch {
                        llmFlow.collect { event ->
                            if (event is LlamaHelper.LLMEvent.Error) {
                                if (cont.isActive) cont.resume(false)
                                errorJob.cancel()
                            }
                        }
                    }
                    cont.invokeOnCancellation { errorJob.cancel() }
                    obtainBackend(wantedThreads).load(modelUri, contextLength) { id ->
                        logD("ensureLoaded: load callback fired, id=$id")
                        errorJob.cancel()
                        if (cont.isActive) cont.resume(true)
                    }
                }
            }
        } catch (e: CancellationException) {
            releaseNative()
            throw e
        }

        return when (outcome) {
            true -> {
                loadedUri = modelUri
                loadedContextLength = contextLength
                loadedThreads = wantedThreads
                loadMs = System.currentTimeMillis() - startedAt
                status = LocalModelStatus.Loaded(contextLength, loadMs)
                logD("ensureLoaded: loaded in ${loadMs}ms")
                true
            }
            false -> failLoad("加载过程中引擎报错")
            null -> failLoad("加载超过 ${LOAD_TIMEOUT_MS / 1000} 秒仍未完成")
        }
    }

    /** 加载模型。@return true 表示这次真的执行了加载。失败抛异常。 */
    suspend fun load(modelUri: String, contextLength: Int): Boolean =
        generateMutex.withLock { ensureLoaded(modelUri, contextLength) }

    /** 卸载模型释放内存。正在生成时会等这一条结束。 */
    suspend fun unload() {
        generateMutex.withLock {
            status = LocalModelStatus.Unloading
            withContext(Dispatchers.IO) { releaseNative() }
        }
    }

    suspend fun generate(
        modelUri: String,
        systemPrompt: String,
        userPrompt: String,
        contextLength: Int = 8192
    ): String {
        val chatMlPrompt = buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n")
                append(systemPrompt)
                append("<|im_end|>\n")
            }
            append("<|im_start|>user\n")
            append(userPrompt)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            // [FIX 5] 手动插入空的 think 块，强制关闭 Qwen3 的思考模式。
            // 这是 llama.cpp 生态里对 Qwen3 系列模型的标准做法：
            // 系统提示词里的"不要输出思考过程"只是文字要求，模型可能不理会；
            // 而预先在 assistant 回合里放一个空 <think></think>，模型会认为思考阶段已经结束，
            // 直接从后面开始生成正文，不会再吐 <think>...</think> 内容。
            append("<think>\n\n</think>\n\n")
        }
        logD("generate: called, chatMlPrompt.length=${chatMlPrompt.length}")
        return generateMutex.withLock {
            ensureLoaded(modelUri, contextLength)
            logD("generate: ensureLoaded returned, about to predict")
            status = LocalModelStatus.Generating(loadedContextLength, loadMs)
            try {
                val builder = StringBuilder()
                val startedAt = System.currentTimeMillis()
                var firstTokenAt = 0L
                var tokenCount = 0
                suspendCancellableCoroutine<String> { cont ->
                    lateinit var collectJob: Job
                    collectJob = scope.launch {
                        llmFlow.collect { event ->
                            when (event) {
                                is LlamaHelper.LLMEvent.Ongoing -> {
                                    if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                                    tokenCount++
                                    builder.append(event.word)
                                }
                                is LlamaHelper.LLMEvent.Done -> {
                                    val now = System.currentTimeMillis()
                                    logD("event: Done, totalLength=${builder.length}, tokens=$tokenCount, " +
                                        "firstToken=${if (firstTokenAt > 0) firstTokenAt - startedAt else -1}ms, total=${now - startedAt}ms")
                                    if (cont.isActive) cont.resume(builder.toString())
                                    collectJob.cancel()
                                }
                                is LlamaHelper.LLMEvent.Error -> {
                                    logD("event: Error")
                                    if (cont.isActive) cont.resumeWithException(
                                        IllegalStateException("GGUF 本地模型生成失败")
                                    )
                                    collectJob.cancel()
                                }
                                else -> {}
                            }
                        }
                    }
                    cont.invokeOnCancellation { collectJob.cancel() }
                    logD("generate: calling llamaHelper.predict()")
                    scope.launch { obtainBackend(loadedThreads.coerceAtLeast(0)).predict(chatMlPrompt) }
                }
            } finally {
                if (loadedUri != null) {
                    status = LocalModelStatus.Loaded(loadedContextLength, loadMs)
                }
            }
        }
    }

    private companion object {
        /** 7B 的 Q4 模型在手机上加载约几十秒，给 3 分钟。 */
        const val LOAD_TIMEOUT_MS = 180_000L
    }
}
