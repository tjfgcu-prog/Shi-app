package com.lianyu.ai.feature.localmodel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.nehuatl.llamacpp.LlamaAndroid
import org.nehuatl.llamacpp.LlamaHelper

/**
 * 本地大模型的底层引擎。两种实现：
 * - [StockLlamaBackend]：库自带的 LlamaHelper，线程数由库决定（n_threads=0，自动）。
 * - [ThreadedLlamaBackend]：自己接管加载流程，可以指定线程数。
 *
 * 线程数选"自动"时走前者（和以前完全一样），手动指定线程数时走后者。
 */
internal interface LlamaBackend {
    fun load(path: String, contextLength: Int, onLoaded: (Long) -> Unit)
    fun predict(prompt: String)
    fun abort()
    fun release()
}

internal class StockLlamaBackend(private val helper: LlamaHelper) : LlamaBackend {
    override fun load(path: String, contextLength: Int, onLoaded: (Long) -> Unit) {
        helper.load(path = path, contextLength = contextLength) { id -> onLoaded(id) }
    }

    override fun predict(prompt: String) {
        helper.predict(prompt)
    }

    override fun abort() {
        helper.abort()
    }

    override fun release() {
        helper.release()
    }
}

/**
 * 按库里 LlamaHelper 的字节码逐步还原的等价实现，唯一的区别是参数表里的 `n_threads`
 * 用我们指定的值（LlamaHelper 把它写死成 0）。其余参数（n_batch=512、use_mmap=false、
 * n_gpu_layers=0 等）与 LlamaHelper 保持完全一致。
 *
 * 事件仍然发到同一个 [events]，上层（GgufLocalModel）不用区分是哪种引擎。
 */
internal class ThreadedLlamaBackend(
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<LlamaHelper.LLMEvent>,
    private val threads: Int
) : LlamaBackend {

    private val llama by lazy { LlamaAndroid(contentResolver) }

    @Volatile private var loadJob: Job? = null
    @Volatile private var completionJob: Job? = null
    @Volatile private var currentContext: Int? = null
    @Volatile private var tokenCount = 0
    @Volatile private var allText = ""

    override fun load(path: String, contextLength: Int, onLoaded: (Long) -> Unit) {
        currentContext?.let { runCatching { llama.releaseContext(it) } }
        currentContext = null

        val fd: Int = try {
            contentResolver.openFileDescriptor(Uri.parse(path), "r")?.detachFd()
                ?: throw IllegalArgumentException("无法打开模型文件")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare model loading", e)
            events.tryEmit(LlamaHelper.LLMEvent.Error("Failed to prepare model loading"))
            return
        }

        val config = mutableMapOf<String, Any>(
            "model" to path,
            "model_fd" to fd,
            "use_mmap" to false,
            "use_mlock" to false,
            "n_ctx" to contextLength,
            "embedding" to false,
            "n_batch" to 512,
            "n_threads" to threads,
            "n_gpu_layers" to 0,
            "vocab_only" to false,
            "lora" to "",
            "lora_scaled" to 1.0,
            "rope_freq_base" to 0.0,
            "rope_freq_scale" to 0.0
        )

        loadJob = scope.launch {
            val result = try {
                llama.startEngine(config) { token -> onToken(token) }
            } catch (e: Exception) {
                Log.e(TAG, "Engine start failed", e)
                events.tryEmit(LlamaHelper.LLMEvent.Error("Model initialization failed"))
                return@launch
            }
            val contextId = (result["contextId"] as? Number)?.toInt()
            if (contextId == null) {
                Log.e(TAG, "contextId not found in result map")
                events.tryEmit(LlamaHelper.LLMEvent.Error("Model initialization failed"))
                return@launch
            }
            currentContext = contextId
            Log.d(TAG, "Loaded with n_threads=$threads, contextId=$contextId")
            events.tryEmit(LlamaHelper.LLMEvent.Loaded(path))
            onLoaded(contextId.toLong())
        }
    }

    private fun onToken(token: String) {
        allText += token
        tokenCount++
        events.tryEmit(LlamaHelper.LLMEvent.Ongoing(token, tokenCount))
    }

    override fun predict(prompt: String) {
        val context = currentContext
        if (context == null) {
            events.tryEmit(LlamaHelper.LLMEvent.Error("Model was not loaded yet"))
            return
        }
        val startedAt = System.currentTimeMillis()
        tokenCount = 0
        allText = ""
        val params = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "emit_partial_completion" to true
        )
        completionJob = scope.launch {
            events.tryEmit(LlamaHelper.LLMEvent.Started(prompt))
            try {
                llama.launchCompletion(context, params)
            } catch (e: Exception) {
                Log.e(TAG, "Completion failed", e)
                events.tryEmit(LlamaHelper.LLMEvent.Error("Completion failed"))
                return@launch
            }
            events.tryEmit(
                LlamaHelper.LLMEvent.Done(allText, tokenCount, System.currentTimeMillis() - startedAt)
            )
        }
    }

    private fun stopPrediction() {
        val context = currentContext ?: return
        scope.launch { runCatching { llama.stopCompletion(context) } }
        completionJob?.cancel()
    }

    override fun abort() {
        loadJob?.cancel()
        stopPrediction()
    }

    override fun release() {
        currentContext?.let { runCatching { llama.releaseContext(it) } }
        currentContext = null
    }

    private companion object {
        const val TAG = "LlamaHelper"
    }
}
