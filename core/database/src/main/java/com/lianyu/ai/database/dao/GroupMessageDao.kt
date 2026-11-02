package com.lianyu.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.GroupMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesBeforeSync(groupId: Long, beforeTimestamp: Long, limit: Int): List<GroupMessage>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessagesForGroup(groupId: Long, limit: Int): Flow<List<GroupMessage>>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND fileFormat = :fileFormat AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesByFileFormatBefore(groupId: Long, fileFormat: FileFormat, beforeTimestamp: Long, limit: Int): List<GroupMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: GroupMessage): Long

    @Delete
    suspend fun deleteMessage(message: GroupMessage): Int

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun deleteMessagesForGroup(groupId: Long): Int

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getMessagesForGroupSync(groupId: Long): List<GroupMessage>

    @Query("SELECT COUNT(*) FROM group_messages WHERE groupId = :groupId")
    suspend fun getMessageCount(groupId: Long): Int
}
