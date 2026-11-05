package com.lianyu.ai.network.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import android.content.pm.ApplicationInfo
import com.lianyu.ai.common.PerfPrefs
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.VoiceWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地离线 TTS Provider - 基于 sherpa-onnx [OfflineTts]。
 *
 * 端上运行，无需联网。首次需通过 [LocalTtsModelManager] 下载模型文件。
 *
 * 推理流程：
 * 1. 从 [LocalTtsPreferences] 读选中模型 + enabled 状态
 * 2. 懒加载 [OfflineTts]（file-based，mutex 保护）
 * 3. [OfflineTts.generate] → [GeneratedAudio] (FloatArray PCM)
 * 4. [LocalTtsWavWriter] 写 WAV 到 cacheDir
 *
 * voiceId 约定：`"speaker_42"` → sid=42；空或无法解析 → 用 [TtsConfig.localTtsSid]。
 */
class SherpaLocalTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val mutex = Mutex()
    private var offlineTts: OfflineTts? = null
    private var loadedModelId: String? = null
    private var loadedThreads: Int = -1
    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val preferences = LocalTtsPreferences(context)
                val modelId = preferences.selectedModelId.first()
                val modelState = preferences.modelState(modelId).first()
                if (!modelState.isEnabled) {
                    lastError = "本地 TTS 模型未启用"
                    SecureLog.w(TAG, "本地 TTS 未启用 (model=$modelId)")
                    return@withContext null
                }
                val model = LocalTtsCatalog.findById(modelId)
                if (!model.isAllFilesPresent(context)) {
                    lastError = "模型文件缺失，请重新下载"
                    SecureLog.w(TAG, "模型文件缺失: ${model.id}")
                    return@withContext null
                }
                val tts = getOrLoadOfflineTts(context, model)
                val sid = parseSid(voiceId, model)
                val speed = config.localTtsSpeed.coerceIn(0.5f, 2.0f)

                SecureLog.d(TAG, "合成: model=${model.id}, sid=$sid, speed=$speed, len=${text.length}")
                val startedAt = System.currentTimeMillis()
                val audio = tts.generate(text, sid, speed)
                val generateMs = System.currentTimeMillis() - startedAt

                // 去掉首尾静音 + 统一响度，再落盘
                val processed = AudioPostProcess.process(audio.samples, audio.sampleRate)
                val outputFile = VoiceAudioStore.newFile(context, "local", "wav")
                LocalTtsWavWriter.writePcmToWav(processed, audio.sampleRate, outputFile)
                // 真实波形：与音频同名的 .wf 伴随文件
                VoiceWaveform.write(outputFile.absolutePath, VoiceWaveform.fromSamples(processed))
                VoiceAudioStore.trim(context)

                diag(
                    context,
                    "合成 sid=$sid speed=$speed threads=$loadedThreads 字数=${text.length} " +
                        "原始=${"%.2f".format(audio.samples.size / audio.sampleRate.toFloat())}s " +
                        "处理后=${"%.2f".format(processed.size / audio.sampleRate.toFloat())}s " +
                        "耗时=${generateMs}ms"
                )
                SecureLog.i(TAG, "本地 TTS 合成成功: ${outputFile.absolutePath}")
                lastError = null
                outputFile.absolutePath
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                SecureLog.e(TAG, "本地 TTS 合成失败", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> {
        // 返回通用音色列表；具体数量取决于选中模型
        // UI 根据 model.numSpeakers 动态调整 sid
        return listOf(
            TtsVoice("speaker_0", "默认音色", "自定义", "zh-CN", "sid=0"),
            TtsVoice("__custom_sid__", "自定义 sid", "自定义", "zh-CN", "在设置页填入 sid")
        )
    }

    override suspend fun testConnection(): Boolean {
        return try {
            // testConnection 在 TtsService 里会被调用，但本地模型的"连接"
            // 实际是文件是否存在 + 是否启用
            true
        } catch (_: Exception) {
            false
        }
    }



    // ── 内部 ──

    private suspend fun getOrLoadOfflineTts(context: Context, model: LocalTtsModel): OfflineTts {
        val threads = PerfPrefs.ttsThreads(context)
        if (offlineTts != null && loadedModelId == model.id && loadedThreads == threads) {
            return offlineTts!!
        }
        return mutex.withLock {
            if (offlineTts != null && loadedModelId == model.id && loadedThreads == threads) {
                offlineTts!!
            } else {
                // 释放旧引擎（换模型或用户改了线程数都要重建）
                try { offlineTts?.release() } catch (_: Exception) {}
                val tts = createOfflineTts(context, model, threads)
                offlineTts = tts
                loadedModelId = model.id
                loadedThreads = threads
                SecureLog.i(TAG, "OfflineTts 加载成功: ${model.displayName}, sampleRate=${tts.sampleRate()}, threads=$threads")
                tts
            }
        }
    }

    /** 仅 debug 包写诊断日志（release 不落盘，且不记录文本内容） */
    private fun diag(context: Context, message: String) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        runCatching {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            File(context.filesDir, "chatvm_debug.log").appendText("$time [TTS] $message\n")
        }
    }

    /**
     * 构建 sherpa OfflineTts 实例。
     *
     * file-based 模式：assetManager=null → native 层走 newFromFile（javap 确认存在）。
     * OfflineTts 构造器是 Kotlin 带默认参数的，`assetManager` 默认 null，
     * 故用命名参数显式传 null 即可走 2-arg 构造器。
     */
    private fun createOfflineTts(context: Context, model: LocalTtsModel, threads: Int): OfflineTts {
        val modelPath = model.file(context, model.modelFileName).absolutePath
        val tokensPath = model.file(context, model.tokensFileName).absolutePath
        val lexiconPath = model.lexiconFileName?.let { model.file(context, it).absolutePath } ?: ""

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelPath,
            lexicon = lexiconPath,
            tokens = tokensPath,
            dataDir = "",    // VITS 不需要 dataDir
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            // 语速只在每次合成时通过 generate(text, sid, speed) 传入；
            // 这里固定 1.0，避免与合成时的语速重复生效，也保证设置里改语速立刻生效。
            lengthScale = 1.0f
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = threads
        )
        val ttsConfig = OfflineTtsConfig(
            model = modelConfig
        )
        // assetManager=null → native 走 newFromFile，读绝对路径
        return OfflineTts(assetManager = null, config = ttsConfig)
    }

    /**
     * 解析 voiceId 为 sid。
     * - `"speaker_42"` → 42
     * - `"42"` → 42
     * - 空/无法解析 → [TtsConfig.localTtsSid]
     */
    private fun parseSid(voiceId: String?, model: LocalTtsModel): Int {
        val raw = voiceId?.removePrefix("speaker_")?.trim()
        val parsed = raw?.toIntOrNull()
        val sid = parsed ?: config.localTtsSid
        return sid.coerceIn(0, (model.numSpeakers - 1).coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "SherpaLocalTts"

        /**
         * 最近一次合成失败的原因，供设置页"试听"展示诊断信息。
         */
        @Volatile
        var lastError: String? = null
            private set


    }
}
