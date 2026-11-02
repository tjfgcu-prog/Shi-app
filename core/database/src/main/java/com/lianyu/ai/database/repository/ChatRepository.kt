package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.ChatMessageDao
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.FileFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    /**
     * 内存缓存：companionId -> 最近一页解密后的消息。
     * HomeViewModel 加载列表时预热，ChatViewModel 进入时先读缓存避免 loading。
     * 放在 companion object 中，所有 ChatRepository 实例共享同一份缓存。
     */
    companion object {
        // [C2 FIX] 使用 ConcurrentHashMap 替代 mutableMapOf：多个 ViewModel/Worker 并发读写会触发
        // ConcurrentModificationException 或丢失数据。ConcurrentHashMap 提供线程安全读写。
        private val recentCache = java.util.concurrent.ConcurrentHashMap<Long, List<ChatMessage>>()
    }



    /** 读取缓存（可能为 null），ChatViewModel 进入时先用它做初始数据 */
    fun getCachedRecent(companionId: Long): List<ChatMessage>? = recentCache[companionId]

    // --- 获取最近一页消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        chatMessageDao.getRecentMessagesForCompanion(companionId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }
            .onEach { decrypted -> recentCache[companionId] = decrypted }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        chatMessageDao.getLastMessageForCompanion(companionId)
            .map { it?.let(ChatMessageCrypto::decryptFromStorage) }

    suspend fun sendMessage(message: ChatMessage): Long =
        chatMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))

    suspend fun deleteMessage(message: ChatMessage) = chatMessageDao.deleteMessage(message)

    suspend fun clearChatHistory(companionId: Long) {
        chatMessageDao.deleteMessagesForCompanion(companionId)
        recentCache.remove(companionId)
    }

    // [BUGFIX] chatMessageDao.getRecentMessagesSync 按 timestamp DESC（新→旧）查询，
    // 但本函数此前直接把 DESC 结果返回给调用方，只把 reversed() 之后的正序副本存进了
    // UI 内存缓存（recentCache）。而 ChatContextResolver.getHistoryForAi()/
    // getShortHistoryForAi()、以及最终喂给 AiPromptBuilder 的历史消息列表都假设本函数
    // 返回时间正序（旧→新，与 getMessagesForCompanion 的约定一致）。
    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getRecentMessagesSync(companionId, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }
            .reversed()
            .also { recentCache[companionId] = it }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, limit: Int): List<ChatMessage> =
        chatMessageDao.getMessagesBeforeSync(companionId, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        chatMessageDao.getMessagesForCompanionSync(companionId)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormatBefore(
        companionId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        limit: Int = 50
    ): List<ChatMessage> =
        chatMessageDao.getMessagesByFileFormatBefore(companionId, fileFormat, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun sendMessageAndGetId(message: ChatMessage): Long {
        return chatMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))
    }

    /**
     * 把一条已发送的文字消息原地升级为语音消息（TTS 异步合成完成后调用）。
     * content 同样需要加密存储（与 ChatMessageCrypto.encrypt 保持一致）。
     */
    suspend fun updateMessageAsVoice(messageId: Long, displayContent: String, linkString: String) {
        chatMessageDao.updateMessageAsVoice(
            messageId,
            ChatMessageCrypto.encrypt(displayContent),
            com.lianyu.ai.database.model.MessageType.VOICE,
            linkString
        )
    }
}
