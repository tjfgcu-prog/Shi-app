package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.network.TlsConfig
import com.lianyu.ai.network.CertificatePins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MiniMax Audio (Speech-02) TTS。
 * 2026 年评测中"剧情/漫剧"类情感表现力公认最自然的中文语音模型之一，10 秒声音克隆。
 * 官方文档：https://platform.minimaxi.com/docs/api-reference/speech-t2a-http
 */
class MinimaxTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val client = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        TlsConfig.enforceTls(builder)
        builder.certificatePinner(CertificatePins.certificatePinner)
        builder.build()
    }

    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        synthesize(context, text, voiceId, null)

    override suspend fun synthesize(
        context: Context,
        text: String,
        voiceId: String?,
        speedOverride: Float?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val apiKey = config.minimaxApiKey
            val groupId = config.minimaxGroupId
            if (apiKey.isBlank() || groupId.isBlank()) {
                SecureLog.w("MinimaxTts", "MiniMax TTS not configured")
                return@withContext null
            }

            val url = "https://api.minimaxi.com/v1/t2a_v2?GroupId=$groupId"

            val requestBody = JSONObject().apply {
                put("model", "speech-2.6-hd")
                put("text", text)
                put("stream", false)
                put("voice_setting", JSONObject().apply {
                    put("voice_id", voiceId ?: config.minimaxVoiceId.ifBlank { "female-shaonv" })
                    // 语速跟随设置（MiniMax 支持 0.5~2.0）
                    put("speed", (speedOverride ?: config.localTtsSpeed).coerceIn(0.5f, 2.0f).toDouble())
                    put("vol", 1.0)
                    put("pitch", 0)
                    // 情绪：空 = 自动（不传，由模型按文本判断）；以前写死 happy，哪怕文本是难过/生气也是开心语气
                    if (config.minimaxEmotion.isNotBlank()) put("emotion", config.minimaxEmotion)
                })
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", 32000)
                    put("bitrate", 128000)
                    put("format", "mp3")
                    put("channel", 1)
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            if (!response.isSuccessful || bodyStr == null) {
                SecureLog.e("MinimaxTts", "HTTP ${response.code}")
                return@withContext null
            }

            val json = JSONObject(bodyStr)
            val statusCode = json.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
            if (statusCode != 0) {
                SecureLog.e("MinimaxTts", "API error: ${json.optJSONObject("base_resp")?.optString("status_msg")}")
                return@withContext null
            }
            val hexAudio = json.optJSONObject("data")?.optString("audio")
            if (hexAudio.isNullOrBlank()) return@withContext null

            val outputFile = VoiceAudioStore.newFile(context, "minimax", "mp3")
            outputFile.writeBytes(hexToBytes(hexAudio))
            VoiceAudioStore.trim(context)

            outputFile.absolutePath
        } catch (e: Exception) {
            SecureLog.e("MinimaxTts", "Synthesis failed", e)
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = ((Character.digit(clean[idx], 16) shl 4) + Character.digit(clean[idx + 1], 16)).toByte()
        }
        return out
    }

    override fun getVoices(): List<TtsVoice> {
        // voice_id 会持续更新，建议以 MiniMax 控制台"音色列表"为准，这里给几个常用预置音色
        return listOf(
            TtsVoice("female-shaonv", "少女音", "女", "zh-CN", "年轻女声，情感丰富，最贴近AI漫剧女主"),
            TtsVoice("female-tianmei", "甜美女声", "女", "zh-CN", "甜美可爱"),
            TtsVoice("female-yujie", "御姐音", "女", "zh-CN", "成熟女声，气场强"),
            TtsVoice("male-qn-qingse", "青涩青年音", "男", "zh-CN", "少年感男声")
        )
    }

    override suspend fun testConnection(): Boolean {
        return config.minimaxApiKey.isNotBlank() && config.minimaxGroupId.isNotBlank()
    }
}
