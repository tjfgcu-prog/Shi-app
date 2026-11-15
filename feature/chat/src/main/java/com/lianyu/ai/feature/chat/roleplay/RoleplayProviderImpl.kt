package com.lianyu.ai.feature.chat.roleplay

import android.content.Context
import com.lianyu.ai.domain.RoleplayPrompt
import com.lianyu.ai.domain.RoleplayProvider
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.data.CompanionChatDetailSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 角色扮演提供者实现：把每个角色的角色扮演设置缓存在内存里（随设置变化自动刷新），
 * 拼提示词时同步读取，不做 IO。
 */
class RoleplayProviderImpl(context: Context) : RoleplayProvider {

    private val appContext = context.applicationContext
    private val store = ChatDetailSettingsStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var settings: Map<Long, CompanionChatDetailSettings> = emptyMap()
    @Volatile private var loaded = false

    init {
        WorldBookStore.init(appContext)
        scope.launch {
            store.settingsMapFlow.collect { map ->
                settings = map
                loaded = true
            }
        }
    }

    /** 冷启动后第一次查询时，设置可能还没读出来：同步读一次（DataStore 很快，且调用方在 IO 线程）。 */
    private fun current(): Map<Long, CompanionChatDetailSettings> {
        if (!loaded) {
            runCatching { runBlocking { store.settingsMapFlow.first() } }
                .onSuccess { settings = it; loaded = true }
        }
        return settings
    }

    override fun isRoleplay(companionId: Long): Boolean = current()[companionId]?.roleplayMode == true

    override fun getPrompt(companionId: Long, name: String, recentText: String): RoleplayPrompt? {
        val s = current()[companionId] ?: return null
        if (!s.roleplayMode) return null

        val world = WorldBookStore.match(companionId, recentText)
        val context = buildString {
            if (world.isNotBlank()) {
                appendLine("【世界设定】")
                appendLine(world)
            }
            val card = s.stateCard.trim()
            if (card.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("【当前状态】")
                appendLine(card.take(400))
            }
        }.trim()

        return RoleplayPrompt(
            identityLine = RoleplayPromptBuilder.identityLine(name),
            contextText = context,
            rulesText = RoleplayPromptBuilder.rules(s.romanceRatio, s.replyLength)
        )
    }
}
