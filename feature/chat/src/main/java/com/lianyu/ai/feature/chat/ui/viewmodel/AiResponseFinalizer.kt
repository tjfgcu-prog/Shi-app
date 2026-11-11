package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.common.text.MessageSegmenter

import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.feature.chat.data.ChatContextResolver
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.voice.ChatTtsController
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.common.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 语音合成升级任务专用的进程级作用域：不随聊天页销毁而取消 */
private val voiceUpgradeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * AI 回复落地处理器（从 ChatViewModel 抽取，方案B：独立类 + 委托存根）。
 *
 * 职责链：
 * 1. reasoning 展示
 * 2. 表情包标签处理
 * 3. L1+L2 关键词/向量安全检查 → 贝叶斯模型输出校验（fail-closed）
 * 4. 分段发送（模拟真人连续发消息）
 * 5. 微信广播
 * 6. 记忆提取
 * 7. 连续追问（概率触发）
 * 8. 流式分段朗读（READ_ALOUD 模式）
 *
 * @param companionId 当前伴侣 ID
 * @param chatRepository 消息持久化
 * @param memoryRepository 记忆提取
 * @param stickerManager 表情包管理
 * @param chatDetailSettingsStore 聊天设置（表情包概率、追问开关等）
 * @param appSettingsStore 应用设置（reasoning 展示开关）
 * @param contextResolver 上下文解析（追问用）
 * @param aiService AI 服务（追问调用 generateFollowUpQuestion）
 * @param applicationApiScope 应用级作用域（追问异步发起）
 * @param reasoningText reasoning 文本 StateFlow（引用，非值拷贝）
 * @param isReasoning reasoning 显示开关 StateFlow（引用）
 * @param turnState 单轮状态（表情包互斥、stale sticker）
 * @param chatTtsController TTS 控制器（自动朗读）
 * @param application Application（用于微信广播）
 * @param questionRegex 问句正则（追问触发判断）
 */
class AiResponseFinalizer(
    private val companionId: Long,
    private val chatRepository: ChatRepository,
    private val memoryProvider: MemoryProvider,
    private val stickerManager: StickerManager,
    private val chatDetailSettingsStore: ChatDetailSettingsStore,
    private val appSettingsStore: AppSettingsStore,
    private val contextResolver: ChatContextResolver,
    private val aiService: AiServiceProvider,
    private val applicationApiScope: CoroutineScope,
    private val reasoningText: MutableStateFlow<String>,
    private val isReasoning: MutableStateFlow<Boolean>,
    private val turnState: ChatTurnState,
    private val chatTtsController: ChatTtsController,
    private val application: Application,
    private val questionRegex: Regex,
) {
    /**
     * Process and save an AI response: reasoning display, sticker processing, DB commit,
     * Returns the message ID for the saved response.
     */
    suspend fun finalizeResponse(
        aiContent: String,
        reasoning: String?,
        userContentForMemory: String? = null,
        logMessage: String = "AI response received"
    ): Long {
        if (!reasoning.isNullOrBlank() && appSettingsStore.getShowReasoning()) {
            isReasoning.value = true
            reasoningText.value = reasoning
        }

        val settings = chatDetailSettingsStore.getSettings(companionId)
        val processedText = TextProcessor.processStickerTagsForSplit(aiContent, stickerManager, settings.stickerProbability) { sendStickerMessage(it) }

        // 分段发送：将AI回复拆分为多条短消息，模拟真人连续发送
        // 语音回复模式下，每段再按 60 秒上限二次切分
        val rawSegments = splitIntoSegments(processedText)
        val segments = if (chatTtsController.isVoiceReplyEnabled()) {
            rawSegments.flatMap { chunkForVoice(it) }
        } else {
            rawSegments
        }
        val hasPendingSticker = turnState.pendingSticker != null
        val stickerBeforeText = hasPendingSticker && kotlin.random.Random.nextFloat() < 0.5f

        // [P0 FIX] 空内容保护和日志记录
        if (processedText.isBlank() && aiContent.isNotBlank()) {
            SecureLog.w("ChatViewModel", "WARNING: processedText is blank but aiContent has ${aiContent.length} chars. Original: '${aiContent.take(80)}'")
        }

        val aiMessageId = if (segments.size <= 1) {
            // 单条回复，走原有逻辑
            val safeProcessed = processedText.ifBlank {
                if (aiContent.isNotBlank()) {
                    SecureLog.w("ChatViewModel", "Falling back to zero-width space. aiContent length=${aiContent.length}")
                    "\u200B"
                } else {
                    SecureLog.w("ChatViewModel", "Both processedText and aiContent are blank, storing empty message")
                    ""
                }
            }
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            val aiMessage = buildAiMessage(safeProcessed)
            val id = chatRepository.sendMessageAndGetId(aiMessage)
            scheduleVoiceUpgrade(id, safeProcessed)
            SecureLog.d("ChatViewModel", "$logMessage, length=${aiContent.length}, id=$id")
            reasoningText.value = ""
            isReasoning.value = false
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            
            id
        } else {
            // 多条回复：每段间隔0.8~2秒，模拟打字
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            var lastId = -1L
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) {
                    delay(800L + kotlin.random.Random.nextLong(1200L))
                }
                val safeSegment = segment.ifBlank { "\u200B" }
                val msg = buildAiMessage(safeSegment)
                val id = chatRepository.sendMessageAndGetId(msg)
                scheduleVoiceUpgrade(id, safeSegment)
                lastId = id
                SecureLog.d("ChatViewModel", "$logMessage segment ${index + 1}/${segments.size}, length=${segment.length}, id=$id")
            }
            reasoningText.value = ""
            isReasoning.value = false
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            
            lastId
        }

        

        // Save memory
        if (userContentForMemory != null && aiContent.isNotBlank()) {
            runCatching {
                withTimeoutOrNull(TimeoutBudgets.CHAT_VM_MEMORY_EXTRACT_MS) {
                    val companionName = companionInfoProvider?.invoke()?.name ?: "角色"
                                        if (settings.storyModeEnabled) {
                        memoryProvider.recordStoryTurn(companionId, companionName, userContentForMemory, aiContent)
                                        }
                    memoryProvider.extractAndSaveFromConversation(userContentForMemory, aiContent, companionId, groupId = null)
                }
            }.onFailure {
                SecureLog.e("ChatViewModel", "Memory save failed: ${it.message}")
            }
        }

        // 连续追问：AI回复后按概率触发追问
        triggerFollowUpIfNeeded(aiContent, settings.allowFollowUpMessage)

        return aiMessageId
    }

    // ── 辅助方法（从 ChatViewModel 迁移）──

    

    /**
     * 连续追问：AI回复后按概率触发追问，让对话继续下去。
     * 条件：1) 设置允许追问 2) AI回复不含问句 3) 50%概率触发
     */
    private fun triggerFollowUpIfNeeded(aiContent: String, allowFollowUp: Boolean) {
        if (!allowFollowUp) return
        if (questionRegex.containsMatchIn(aiContent)) return
        if (kotlin.random.Random.nextFloat() > 0.5f) return

        applicationApiScope.launch {
            try {
                delay(ChatConstants.FOLLOW_UP_BASE_DELAY_MS + kotlin.random.Random.nextLong(ChatConstants.FOLLOW_UP_RANDOM_DELAY_MS))

                val history = contextResolver.getShortHistoryForAi(companionId, shortLimit = ChatConstants.SHORT_HISTORY_LIMIT)
                // 取 companion 数据用于 AI 调用——通过回调从 ChatViewModel 获取
                val companionInfo = companionInfoProvider?.invoke() ?: return@launch
                val followUp = aiService.generateFollowUpQuestion(
                    companionInfo, history.map { msg ->
                        com.lianyu.ai.domain.AiChatMessage(
                            isFromUser = msg.isFromUser,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            type = if (msg.type == com.lianyu.ai.database.model.MessageType.IMAGE)
                                com.lianyu.ai.domain.AiMessageType.IMAGE
                            else com.lianyu.ai.domain.AiMessageType.TEXT,
                            companionId = companionId
                        )
                    }, aiContent
                ) ?: return@launch

                val followUpMsg = buildAiMessage(followUp)
                val msgId = chatRepository.sendMessageAndGetId(followUpMsg)
                scheduleVoiceUpgrade(msgId, followUp)
                
                SecureLog.d("ChatViewModel", "Follow-up question sent: $followUp")
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "Follow-up question failed: ${e.message}")
            }
        }
    }

    /**
     * 语音回复模式下，单条语音气泡最长约 60 秒。
     * 用"按字数截断"来控制时长（比合成后剪音频文件简单可靠，不同供应商都适用）：
     * 中文正常语速约 4~5 字/秒，200 字约 45~50 秒，留出安全余量。
     * 超过 200 字的文本会被切成多段，作为连续多条语音气泡发送（跟真人连发语音一样）。
     */
    private fun chunkForVoice(text: String, maxChars: Int = 200): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            // 优先在句号/感叹号/问号/换行处切，找不到就硬切
            val window = remaining.substring(0, maxChars)
            val cutIdx = window.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '，'))
            val cut = if (cutIdx > maxChars / 2) cutIdx + 1 else maxChars
            chunks.add(remaining.substring(0, cut))
            remaining = remaining.substring(cut)
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        return chunks
    }

    /**
     * 把一段文本构建为 ChatMessage：始终先构建为文字消息（不等待 TTS），
     * 避免消息展示被语音合成阻塞（合成耗时不定，阻塞会导致"正在输入…"长时间卡住甚至看似卡死）。
     * 语音回复模式下，消息落库后会异步合成语音并原地升级为语音气泡，见 [scheduleVoiceUpgrade]。
     */
    private fun buildAiMessage(text: String): ChatMessage {
        return ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 语音回复模式下，消息文字先落库展示，这里异步合成语音，成功后把该消息原地升级为语音气泡。
     * 用进程级作用域 [voiceUpgradeScope]（不是 applicationApiScope：它属于 ChatViewModel 实例，
     * 离开聊天页 onCleared 时会被取消），这样用户切走聊天页后，语音合成仍会继续完成并正确更新消息。
     * 合成失败或超时：消息保持文字形式，不影响消息本身已经展示。
     */
    private fun scheduleVoiceUpgrade(messageId: Long, text: String) {
        if (!chatTtsController.isVoiceReplyEnabled()) return
        voiceUpgradeScope.launch {
            try {
                val startedAt = System.currentTimeMillis()
                val result = chatTtsController.synthesizeVoiceReply(text)
                if (result != null) {
                    val (path, duration) = result
                    chatRepository.updateMessageAsVoice(
                        messageId,
                        com.lianyu.ai.common.VoiceMessageCodec.encode(duration, text),
                        path
                    )
                    ChatDebugLog.log("[TTS] 语音合成成功 id=$messageId, 耗时=${System.currentTimeMillis() - startedAt}ms, 语音${duration}秒")
                } else {
                    SecureLog.w("ChatViewModel", "voice synthesis failed, message stays as text (id=$messageId)")
                    ChatDebugLog.log("[TTS] 语音合成失败/超时，消息保持文字 id=$messageId, 原因=${com.lianyu.ai.network.tts.CrispAsrTtsProvider.lastError ?: com.lianyu.ai.network.tts.SherpaLocalTtsProvider.lastError}")
                }
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "voice upgrade failed for id=$messageId: ${e.message}")
            }
        }
    }

    private fun splitIntoSegments(text: String): List<String> {
        return MessageSegmenter.split(text, MessageSegmenter.SplitMode.SIMPLE)
    }
    

    /**
     * Queue a sticker for this turn instead of sending it immediately.
     */
    private suspend fun sendStickerMessage(sticker: StickerInfo): Long {
        return turnState.stickerMutex.withLock {
            if (turnState.stickerSentThisTurn) return@withLock -1
            turnState.stickerSentThisTurn = true
            turnState.pendingSticker = sticker
            -1
        }
    }

    /**
     * Persist the pending sticker message and record its broadcast metadata.
     */
    private suspend fun flushPendingSticker(): Long {
        val sticker = turnState.pendingSticker ?: return -1
        turnState.pendingSticker = null
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        val stickerContent = "[$stickerId]"
        val stickerMessage = ChatMessage(
            companionId = companionId,
            content = stickerContent,
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
        return chatRepository.sendMessageAndGetId(stickerMessage)
    }

    // companionInfoProvider 由 ChatViewModel 注入，用于 triggerFollowUp 获取当前 companion 数据
    var companionInfoProvider: (() -> com.lianyu.ai.domain.AiCompanionInfo?)? = null
}
