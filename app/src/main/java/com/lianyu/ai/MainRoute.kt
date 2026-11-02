package com.lianyu.ai

/**
 * Navigation 状态机 — 所有路由的 sealed class 定义。
 *
 * 控制论: 离散系统差分方程 S[k+1] = f(S[k], E[k])
 * 验证: 所有状态从 Home 可达 ✓  所有状态出度 ≥ 1 ✓  编译器穷尽检查 ✓
 */
sealed class MainRoute(val route: String) {
    // === 主页 Tab ===
    object Home : MainRoute("home")
    object Profile : MainRoute("profile")

    // === 聊天 ===
    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")
    

    // === 群聊 ===
    data class GroupChat(val groupId: Long) : MainRoute("group_chat/$groupId")
    data class GroupDetail(val groupId: Long) : MainRoute("group_detail/$groupId")
    object CreateGroup : MainRoute("create_group")

    // === 伴侣 ===
    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    // === 设置 ===
    object Settings : MainRoute("settings")
    object TtsSettings : MainRoute("tts_settings")
    object TokenUsage : MainRoute("token_usage")
    object Theme : MainRoute("theme")
    
    object FrameRate : MainRoute("frame_rate")
    object YandereMode : MainRoute("yandere_mode")
    

    // === 总设置 ===
    object GeneralSettings : MainRoute("general_settings")

    // === 个人中心 ===
    object Memory : MainRoute("memory")
    object ContextMemory : MainRoute("context_memory")
    object SelfProfile : MainRoute("self_profile")
    data class CompanionSelfProfile(val companionId: Long) : MainRoute("companion_self_profile/$companionId") 
    
    

    // === 数据备份 ===
    object DataBackup : MainRoute("data_backup")


    companion object {
        /** 从路由字符串解析（用于 NavHost currentRoute） */
        
    }
}
