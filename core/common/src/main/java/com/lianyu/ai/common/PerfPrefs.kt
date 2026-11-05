package com.lianyu.ai.common

import android.content.Context

/**
 * 性能相关的用户设置（线程数）。
 * 单一数据源：设置页写入，合成引擎 / 记忆向量引擎读取。
 */
object PerfPrefs {
    private const val PREFS = "perf_prefs"
    private const val KEY_TTS_THREADS = "tts_threads"
    private const val KEY_EMBEDDING_THREADS = "embedding_threads"

    /** 语音合成默认线程数：2 个，给同时运行的大模型留出 CPU */
    const val DEFAULT_TTS_THREADS = 2

    fun cpuCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun ttsThreads(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TTS_THREADS, DEFAULT_TTS_THREADS)
            .coerceIn(1, cpuCores())

    fun setTtsThreads(context: Context, value: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TTS_THREADS, value.coerceIn(1, cpuCores())).apply()
    }

    /** 记忆向量线程数：0 = 自动（交给 ONNX Runtime 决定） */
    fun embeddingThreads(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_EMBEDDING_THREADS, 0)
            .coerceIn(0, cpuCores())

    fun setEmbeddingThreads(context: Context, value: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_EMBEDDING_THREADS, value.coerceIn(0, cpuCores())).apply()
    }
}
