package com.lianyu.ai.domain

/**
 * 角色扮演模式需要拼进系统提示词的三段内容。
 *
 * @property identityLine 替换"你是 X，用户的女朋友"的身份行
 * @property contextText  世界设定（世界书里被触发的条目）+ 当前状态卡；可能为空
 * @property rulesText    替换默认"恋爱陪伴"回复规则的角色扮演规则
 */
data class RoleplayPrompt(
    val identityLine: String,
    val contextText: String,
    val rulesText: String
)

/**
 * 角色扮演提供者。通过 [ServiceRegistry] 注册，聊天（本地/云端）拼提示词时按角色编号查询。
 * 方法都是同步的、不做耗时 IO（数据在内存里），可以放心在提示词拼装时调用。
 */
interface RoleplayProvider {
    /** 该角色是否开启了角色扮演模式 */
    fun isRoleplay(companionId: Long): Boolean

    /**
     * @param recentText 最近几条对话的文字，用来触发世界书条目
     * @return 未开启角色扮演模式时返回 null（调用方沿用原来的提示词）
     */
    fun getPrompt(companionId: Long, name: String, recentText: String): RoleplayPrompt?
}
