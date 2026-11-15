package com.lianyu.ai.feature.chat.roleplay

/**
 * 角色扮演模式的提示词片段（纯函数）。
 */
object RoleplayPromptBuilder {

    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 2   // 1 = 标准（默认）

    fun identityLine(name: String): String =
        "你是$name，正在和用户进行一场沉浸式的角色扮演。始终以${name}的身份说话，不要出戏，不要提到自己是AI、模型或程序。"

    fun lengthText(level: Int): String = when (level) {
        LENGTH_SHORT -> "40 到 80 字"
        LENGTH_LONG -> "150 到 300 字"
        else -> "80 到 150 字"
    }

    /** 滑块文字说明：0 = 纯剧情，10 = 纯恋爱 */
    fun ratioLabel(ratio: Int): String = when {
        ratio >= 9 -> "几乎只有感情互动"
        ratio >= 7 -> "恋爱为主，剧情为辅"
        ratio >= 6 -> "感情稍多一些，剧情并行"
        ratio == 5 -> "恋爱与剧情各占一半"
        ratio >= 3 -> "剧情稍多一些，感情并行"
        ratio >= 1 -> "剧情为主，感情为辅"
        else -> "几乎只有剧情推进"
    }

    /**
     * @param ratio 0..10：0 = 纯剧情，10 = 纯恋爱，5 = 各占一半
     * @param lengthLevel 回复长度档位 [LENGTH_SHORT]/[LENGTH_STANDARD]/[LENGTH_LONG]
     */
    fun rules(ratio: Int, lengthLevel: Int): String {
        val r = ratio.coerceIn(0, 10)
        return buildString {
            appendLine("回复规则（角色扮演）：")
            appendLine("1. 每次回复约 ${lengthText(lengthLevel)}，不要只有一两个字，也不要写成长篇大论。")
            appendLine("2. 动作、神态、场景描写用（）括起来；角色说的话直接写，并用“”括起来；不要使用【】和其他括号。")
            appendLine("3. 不要替用户说话、不要替用户做决定，也不要一次把剧情讲完，把选择留给用户。")
            appendLine("4. 严格遵守【世界设定】和【当前状态】里的内容；设定里没有的细节宁可含糊带过，不要胡编。")
            appendLine("5. 保持身份、语气和情绪连贯，不要突然换情绪，不要重复上一句的内容。")
            when {
                r >= 8 -> {
                    appendLine("6. 以感情互动为主：角色的话占大部分，旁白最多一小句；多用亲昵、关心、吃醋、害羞等细腻的情绪，剧情只当作背景，不要主动引入大事件。")
                }
                r >= 3 -> {
                    appendLine("6. 感情与剧情并行：每次回复先用（）写一小句场景或动作，再写角色的话，话里要带着对用户的感情。")
                    appendLine("7. 每隔两三轮，自然地带出一个剧情钩子（线索、危险、陌生人、传闻或抉择），让用户有可以接话的方向；遇到危险时，角色要用行动体现对用户的在意。")
                    if (r >= 6) appendLine("8. 感情的比重稍多一些：多写角色对用户的关心与情绪。")
                    if (r <= 4) appendLine("8. 剧情的比重稍多一些：多写场景和事件的发展。")
                }
                else -> {
                    appendLine("6. 以剧情推进为主：多写场景和事件，让环境、其他人物和冲突登场；每次回复都给用户留下可以行动的线索或选择。感情只作底色，用一两句体现即可。")
                }
            }
            appendLine()
            append("记住：始终保持角色，直接输出角色的回复内容，不要输出思考过程。")
        }
    }
}
