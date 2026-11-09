package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.VoiceWaveform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 Breeze TTS Provider（CrispASR + GGUF）。
 *
 * 流程：引擎已加载 → [CrispAsrTtsEngine.synthesize] 得到 PCM → [AudioPostProcess] 去静音/统一响度
 * → [LocalTtsWavWriter] 写 WAV。后面的流程和 [SherpaLocalTtsProvider] 一致，聊天页无需任何改动。
 *
 * 暂不支持：语速覆盖（Breeze 没有语速参数）、sid 音色（单音色）。
 */
class CrispAsrTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

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
            if (!CrispAsrTtsEngine.isLoaded()) {
                lastError = "Breeze 模型未加载，请先在语音设置里打开「加载到内存」开关"
                SecureLog.w(TAG, lastError ?: "")
                return@withContext null
            }
            val startedAt = System.currentTimeMillis()
            val (pcm, sampleRate) = CrispAsrTtsEngine.synthesize(text)
            val cost = System.currentTimeMillis() - startedAt

            val processed = AudioPostProcess.process(pcm, sampleRate)
            val outputFile = VoiceAudioStore.newFile(context, "breeze", "wav")
            LocalTtsWavWriter.writePcmToWav(processed, sampleRate, outputFile)
            VoiceWaveform.write(outputFile.absolutePath, VoiceWaveform.fromSamples(processed))
            VoiceAudioStore.trim(context)

            SecureLog.i(
                TAG,
                "Breeze 合成成功 字数=${text.length} 时长=${"%.2f".format(pcm.size / sampleRate.toFloat())}s 耗时=${cost}ms"
            )
            lastError = null
            outputFile.absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            SecureLog.e(TAG, "Breeze 合成失败", e)
            null
        }
    }

    override fun getVoices(): List<TtsVoice> =
        listOf(TtsVoice("breeze_default", "Breeze 默认音色", "自定义", "zh-CN", "Breeze TTS 2"))

    override suspend fun testConnection(): Boolean = CrispAsrTtsEngine.isLoaded()

    companion object {
        private const val TAG = "CrispAsrTts"

        /** 最近一次合成失败的原因，供设置页"试听"展示 */
        @Volatile
        var lastError: String? = null
            private set
    }
}
