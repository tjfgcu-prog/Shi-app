package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class TtsService(private val context: Context) {

    private val providers = mutableMapOf<TtsProvider, TtsProviderInterface>()
    private var currentProvider: TtsProvider = TtsProvider.ANDROID
    private val androidTts = AndroidTtsProvider()
    private val sherpaLocalTts = SherpaLocalTtsProvider()
    private var currentConfig: TtsConfig = TtsConfig.fromSharedPreferences(context)

    init {
        providers[TtsProvider.ANDROID] = androidTts
        providers[TtsProvider.LOCAL] = sherpaLocalTts
        providers[TtsProvider.BREEZE] = CrispAsrTtsProvider()
        providers[TtsProvider.CLOUD] = MinimaxTtsProvider()
        // [FIX] 进程重启后，必须从 SharedPreferences 恢复"上次选的引擎"和音色/语速。
        // 以前 currentProvider 永远从 ANDROID 起步，只有打开语音设置页点保存才会切到 LOCAL，
        // 导致重启后聊天里用系统 TTS（合成失败 → 消息保持文字），并且 sid/语速也是默认值。
        runCatching {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            val name = prefs.getString("tts_provider", TtsProvider.ANDROID.name)
            currentProvider = TtsProvider.entries.find { it.name == name } ?: TtsProvider.ANDROID
            providers.values.forEach { p ->
                if (p is ConfigurableTtsProvider) p.updateConfig(currentConfig)
            }
            SecureLog.i("TtsService", "Restored provider=${currentProvider.displayName} from prefs")
        }
    }

    /** 本地离线 TTS 模型管理器（下载/校验/启用），供设置页 UI 消费 */
    val localTtsManager: LocalTtsModelManager by lazy {
        LocalTtsModelManager.getInstance(context)
    }

    fun getCurrentProvider(): TtsProvider = currentProvider

    fun setProvider(provider: TtsProvider) {
        currentProvider = provider
        SecureLog.i("TtsService", "Switched TTS provider to ${provider.displayName}")
    }

    

    

    fun updateConfig(config: TtsConfig) {
        currentConfig = config
        providers.forEach { (provider, providerInterface) ->
            if (providerInterface is ConfigurableTtsProvider) {
                providerInterface.updateConfig(config)
            }
        }
        SecureLog.i("TtsService", "TTS configuration updated")
    }

    

    /**
     * @param speed 语速覆盖（角色单独设置），null = 用全局设置
     * @param localSid 本地 VITS 的音色编号覆盖（角色单独设置）；只在当前引擎是"本地"时生效
     */
    suspend fun synthesize(
        text: String,
        voiceId: String? = null,
        speed: Float? = null,
        localSid: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (currentProvider == TtsProvider.ANDROID && !androidTts.isInitialized()) {
                androidTts.initialize(context)
            }
            val provider = providers[currentProvider]
                ?: throw IllegalStateException("Provider ${currentProvider.name} not initialized")

            SecureLog.d("TtsService", "Synthesizing text with ${currentProvider.displayName}, length=${text.length}")
            val effectiveVoiceId = if (currentProvider == TtsProvider.LOCAL && localSid != null) "speaker_$localSid" else voiceId
            val result = provider.synthesize(context, text, effectiveVoiceId, speed)

            if (result != null) {
                SecureLog.i("TtsService", "TTS synthesis successful: $result")
            } else {
                SecureLog.w("TtsService", "TTS synthesis returned null")
            }
            result
        } catch (e: Exception) {
            SecureLog.e("TtsService", "TTS synthesis failed", e)
            null
        }
    }

    fun getVoices(provider: TtsProvider = currentProvider): List<TtsVoice> {
        return providers[provider]?.getVoices() ?: emptyList()
    }

    suspend fun testProvider(provider: TtsProvider): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = providers[provider] ?: return@withContext false

            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            val isConfigured = currentConfig.isProviderConfigured(provider)
            if (!isConfigured && provider != TtsProvider.ANDROID) {
                SecureLog.w("TtsService", "Provider ${provider.displayName} not configured")
                return@withContext false
            }

            p.testConnection()
        } catch (e: Exception) {
            SecureLog.e("TtsService", "Test provider ${provider.displayName} failed", e)
            false
        }
    }

    suspend fun testWithSampleText(provider: TtsProvider = currentProvider, text: String = "你好，这是一个语音合成测试。"): String? {
        return try {
            if (provider == TtsProvider.ANDROID && !androidTts.isInitialized()) {
                androidTts.initialize(context)
            }

            val p = providers[provider] ?: return null
            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            SecureLog.i("TtsService", "Testing TTS with sample text for ${provider.displayName}")
            p.synthesize(context, text, null)
        } catch (e: Throwable) {
            SecureLog.e("TtsService", "Test synthesis failed", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: TtsService? = null

        fun getInstance(context: Context): TtsService {
            return instance ?: synchronized(this) {
                instance ?: TtsService(context.applicationContext).also { instance = it }
            }
        }
    }
}
