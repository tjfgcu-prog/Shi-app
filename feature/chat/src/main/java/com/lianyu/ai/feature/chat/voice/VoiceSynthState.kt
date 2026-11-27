package com.lianyu.ai.feature.chat.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一条消息的语音合成状态。没有记录 = 不在合成、也没失败，按消息本身的样子显示。 */
enum class VoiceSynthStatus {
    /** 正在合成：语音条位置显示"正在说话……" */
    SYNTHESIZING,

    /** 合成失败或超时（只用于刚生成的回复，消息仍是纯文字）：语音条位置显示"对方还在思考" */
    FAILED
}

/**
 * 进程级的语音合成状态表（消息 id → 状态），只放在内存里。
 *
 * 合成发生在进程级作用域里（离开聊天页也会继续），所以状态也放在 ViewModel 之外；
 * 聊天气泡订阅这张表，决定语音条位置显示什么。
 */
object VoiceSynthState {
    private val _states = MutableStateFlow<Map<Long, VoiceSynthStatus>>(emptyMap())
    val states: StateFlow<Map<Long, VoiceSynthStatus>> = _states.asStateFlow()

    fun start(messageId: Long) = _states.update { it + (messageId to VoiceSynthStatus.SYNTHESIZING) }

    fun markFailed(messageId: Long) = _states.update { it + (messageId to VoiceSynthStatus.FAILED) }

    fun clear(messageId: Long) = _states.update { it - messageId }
}
