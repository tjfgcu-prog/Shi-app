package com.lianyu.ai.database.repository

import android.content.Context
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.dao.TokenUsageDao
import com.lianyu.ai.database.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class TokenUsageRepository(context: Context) {

    private val dao: TokenUsageDao
    private val deviceId: String
    private val calendar: Calendar = Calendar.getInstance()

    init {
        val appContext = context.applicationContext
        val database = AppDatabase.getDatabase(appContext)
        dao = database.tokenUsageDao()
        deviceId = DeviceIdProvider.getDeviceId(appContext)
    }

    fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    fun getDateDaysAgo(days: Int): String {
        calendar.time = Date()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
    }

    suspend fun recordTokenUsage(
        companionId: Long,
        inputTokens: Long,
        outputTokens: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val todayDate = getTodayDateString()
            val timestamp = System.currentTimeMillis()
            
            dao.insertOrUpdate(
                companionId = companionId,
                deviceId = deviceId,
                date = todayDate,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                timestamp = timestamp
            )
            
            dao.insertOrUpdate(
                companionId = -1L,
                deviceId = deviceId,
                date = todayDate,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                timestamp = timestamp
            )
            
            SecureLog.d("TokenUsage", "Recorded usage for companion=$companionId, in=$inputTokens, out=$outputTokens")
            true
        } catch (e: Exception) {
            SecureLog.e("TokenUsage", "Failed to record token usage", e)
            false
        }
    }

    suspend fun getTodayUsage(companionId: Long? = null): TokenUsage? = withContext(Dispatchers.IO) {
        try {
            val todayDate = getTodayDateString()
            if (companionId != null && companionId > 0) {
                dao.getUsageByCompanionAndDate(companionId, deviceId, todayDate)
            } else {
                dao.getGlobalUsageByDate(deviceId, todayDate)
            }
        } catch (e: Exception) {
            SecureLog.e("TokenUsage", "Failed to get today usage", e)
            null
        }
    }

    suspend fun getWeekUsage(companionId: Long? = null): TokenUsageDao.TotalStats? = withContext(Dispatchers.IO) {
        try {
            val weekAgo = getDateDaysAgo(7)
            if (companionId != null && companionId > 0) {
                dao.getTotalStatsByCompanion(companionId, deviceId, weekAgo)
            } else {
                dao.getTotalStats(deviceId, weekAgo)
            }
        } catch (e: Exception) {
            SecureLog.e("TokenUsage", "Failed to get week usage", e)
            null
        }
    }

    suspend fun getMonthUsage(companionId: Long? = null): TokenUsageDao.TotalStats? = withContext(Dispatchers.IO) {
        try {
            val monthAgo = getDateDaysAgo(30)
            if (companionId != null && companionId > 0) {
                dao.getTotalStatsByCompanion(companionId, deviceId, monthAgo)
            } else {
                dao.getTotalStats(deviceId, monthAgo)
            }
        } catch (e: Exception) {
            SecureLog.e("TokenUsage", "Failed to get month usage", e)
            null
        }
    }

    suspend fun getAllUsageHistory(limit: Int = 30): List<TokenUsage> = withContext(Dispatchers.IO) {
        try {
            dao.getUsageSince(deviceId, getDateDaysAgo(limit))
        } catch (e: Exception) {
            SecureLog.e("TokenUsage", "Failed to get all usage history", e)
            emptyList()
        }
    }

    

    companion object {
        @Volatile
        private var INSTANCE: TokenUsageRepository? = null

        fun getInstance(context: Context): TokenUsageRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenUsageRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
