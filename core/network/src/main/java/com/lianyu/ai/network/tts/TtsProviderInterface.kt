package com.lianyu.ai.network.tts

import android.content.Context

interface TtsProviderInterface {
    suspend fun synthesize(context: Context, text: String, voiceId: String?): String?

    /**
     * 带语速覆盖的合成（每个角色可以有自己的语速）。
     * 默认忽略 [speedOverride]；本地 VITS 和云端 MiniMax 会重写它。
     */
    suspend fun synthesize(context: Context, text: String, voiceId: String?, speedOverride: Float?): String? =
        synthesize(context, text, voiceId)
    fun getVoices(): List<TtsVoice>
    suspend fun testConnection(): Boolean
}