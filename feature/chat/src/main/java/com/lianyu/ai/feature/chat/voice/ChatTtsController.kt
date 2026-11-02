package com.lianyu.ai.feature.chat.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.network.tts.ChatTtsConfig
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.network.tts.TtsService
import com.lianyu.ai.network.tts.TtsTextCleaner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 聊天页语音回复控制器。
 * 唯一职责：把一段文本合成语音文件，返回 (文件路径, 时长秒)，
 * 由 AiResponseFinalizer 写成 ChatMessage(type=VOICE)，复用 VoiceMessageBubble 渲染。
 */
class ChatTtsController(
    private val context: Context,
    private val ttsService: TtsService,
    private val configProvider: () -> ChatTtsConfig
) {
    /** 是否开启语音回复模式 */
    fun isVoiceReplyEnabled(): Boolean = configProvider().mode == ChatTtsMode.VOICE_BAR

    /**
     * 合成一段语音回复。
     * @return (音频文件路径, 实际时长秒) 或 null（合成失败/文本为空）
     */
    suspend fun synthesizeVoiceReply(text: String): Pair<String, Int>? {
        val cfg = configProvider()
        val cleaned = TtsTextCleaner.clean(text, cfg.skipParentheses)
        if (cleaned.isBlank()) return null

        val path = try {
            // 多段回复会同时排队合成：先串行（超时只从轮到自己时开始算），
            // 并且本地 VITS 在 LLM 同时推理（如剧情摘要）时会很慢，10 秒太短 → 放宽到 60 秒。
            synthMutex.withLock {
                withTimeoutOrNull(LOCAL_SYNTH_TIMEOUT_MS) { ttsService.synthesize(cleaned) }
            }
        } catch (e: Exception) {
            SecureLog.e("ChatTtsController", "synthesizeVoiceReply failed", e)
            null
        } ?: return null

        val file = File(path)
        if (!file.exists()) return null

        val durationSec = measureDurationSeconds(path)
        return path to durationSec
    }

    private fun measureDurationSeconds(path: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            SecureLog.w("ChatTtsController", "measureDurationSeconds failed: ${e.message}")
            1
        } finally {
            retriever.release()
        }
    }

    private companion object {
        val synthMutex = Mutex()
        const val LOCAL_SYNTH_TIMEOUT_MS = 60_000L
    }

    /** 保留空实现：ChatViewModel 里如果还有旧的 stop() 调用不至于编译报错，可直接删掉调用处 */
    fun stop() {}
}
