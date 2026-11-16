package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.common.AppLog

/**
 * 聊天流程的诊断日志，统一汇入 [AppLog]（设置 → 实时日志）。
 *
 * 🔒 只有 debug 包会记录：这些日志里带有聊天内容片段，release 包不记录。
 */
object ChatDebugLog {
    fun log(msg: String) {
        AppLog.d("Chat", msg)
    }
}
