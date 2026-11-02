package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.GroupMessageDao
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.GroupMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupMessageRepository(private val groupMessageDao: GroupMessageDao) {
    // --- 获取最近的消息，按时间正序排列（UI直接显示） ---
    fun getMessagesForGroup(groupId: Long, limit: Int = 50): Flow<List<GroupMessage>> =
        groupMessageDao.getRecentMessagesForGroup(groupId, limit)
            .map { list -> list.map { ChatMessageCrypto.decryptFromStorage(it) }.reversed() }

    suspend fun sendMessage(message: GroupMessage): Long =
        groupMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))

    suspend fun deleteMessage(message: GroupMessage) = groupMessageDao.deleteMessage(message)

    suspend fun clearGroupHistory(groupId: Long) = groupMessageDao.deleteMessagesForGroup(groupId)

    suspend fun getMessagesBeforeSync(groupId: Long, beforeTimestamp: Long, limit: Int): List<GroupMessage> =
        groupMessageDao.getMessagesBeforeSync(groupId, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessagesByFileFormatBefore(
        groupId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        limit: Int = 50
    ): List<GroupMessage> =
        groupMessageDao.getMessagesByFileFormatBefore(groupId, fileFormat, beforeTimestamp, limit)
            .map { ChatMessageCrypto.decryptFromStorage(it) }

    suspend fun getMessageCount(groupId: Long): Int =
        groupMessageDao.getMessageCount(groupId)
}
