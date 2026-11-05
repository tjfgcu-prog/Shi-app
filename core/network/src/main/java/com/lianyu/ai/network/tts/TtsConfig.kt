package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog

data class TtsConfig(
    // Local offline TTS (sherpa-onnx)
    // 语速：本地与云端共用（MiniMax 取值范围 0.5~2.0）
    val localTtsSpeed: Float = 1.0f,
    val localTtsSid: Int = 0,
    // MiniMax Audio (唯一云端供应商)
    val minimaxApiKey: String = "",
    val minimaxGroupId: String = "",
    val minimaxVoiceId: String = "female-shaonv",
    // 空 = 自动（不传 emotion，由 MiniMax 按文本判断）；否则 happy/calm/sad/angry/surprised
    val minimaxEmotion: String = ""
) {
    fun isProviderConfigured(provider: TtsProvider): Boolean {
        return when (provider) {
            TtsProvider.ANDROID -> true
            TtsProvider.LOCAL -> true
            TtsProvider.CLOUD -> minimaxApiKey.isNotBlank() && minimaxGroupId.isNotBlank()
        }
    }

    companion object {
        fun fromSharedPreferences(context: Context): TtsConfig {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            return TtsConfig(
                localTtsSpeed = prefs.getFloat("local_tts_speed", 1.0f),
                localTtsSid = prefs.getInt("local_tts_sid", 0),
                minimaxApiKey = prefs.getString("minimax_api_key", "") ?: "",
                minimaxGroupId = prefs.getString("minimax_group_id", "") ?: "",
                minimaxVoiceId = prefs.getString("minimax_voice_id", "female-shaonv") ?: "female-shaonv",
                minimaxEmotion = prefs.getString("minimax_emotion", "") ?: ""
            )
        }

        fun saveToSharedPreferences(context: Context, config: TtsConfig) {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("local_tts_speed", config.localTtsSpeed)
                putInt("local_tts_sid", config.localTtsSid)
                putString("minimax_api_key", config.minimaxApiKey)
                putString("minimax_group_id", config.minimaxGroupId)
                putString("minimax_voice_id", config.minimaxVoiceId)
                putString("minimax_emotion", config.minimaxEmotion)
                apply()
            }
            SecureLog.i("TtsConfig", "Configuration saved to SharedPreferences")
        }
    }
}
