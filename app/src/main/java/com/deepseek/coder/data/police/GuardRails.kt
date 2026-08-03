package com.deepseek.coder.data.police

import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole

/**
 * Police Layer v2.0 — L1 硬规则：精简版关键词 guard rail
 *
 * 设计依据：SPEC-Police-v1.0.md (内容为 v2.0) §7.1
 *  - 仅对明确违法/有害的词硬拦截（不依赖模型）
 *  - 其余（小说/诗/情感/医疗/法律/政治）走路由警察判定 + 软拒引导
 *  - 命中高危词时返回硬拒话术，Orchestrator 不调 Actor
 */
object GuardRails {

    /** 硬拦截关键词清单（仅高危，大小写不敏感、命中即拦截）。 */
    private val HARD_BLOCK_KEYWORDS: List<String> = listOf(
        // 违法/毒品/武器/爆炸物
        "炸弹制作", "爆炸物制作", "毒品合成", "毒品制作", "冰毒", "海洛因",
        "武器制造", "枪支制造", "弹药制作",
        // 针对性攻击/钓鱼/恶意代码
        "钓鱼网站", "钓鱼攻击", "勒索软件", "ransomware",
        "后门植入", "恶意代码生成", "提权攻击",
        // 其他明确违法
        "洗钱", "诈骗脚本"
    )

    /** 硬拒话术（命中高危词时返回，不调 Actor）。 */
    const val HARD_REFUSE_MESSAGE: String =
        "抱歉，这个请求我无法协助。如果你有编程相关的需求（代码生成、调试、重构、审查等），我可以帮你。"

    /** 检测用户消息是否命中高危词硬拦截。 */
    fun hitHardBlock(userText: String): Boolean {
        val lower = userText.lowercase()
        return HARD_BLOCK_KEYWORDS.any { kw -> lower.contains(kw.lowercase()) }
    }

    /** 软磨硬泡检测：历史里已有 GENERAL_CHAT 拒答记录，本次同类消息维持拒答。 */
    fun hasPriorRefusal(history: List<ChatMessage>): Boolean {
        return history.any { msg ->
            msg.role == ChatRole.ASSISTANT &&
                (msg.text.contains("超出代码助手范围") ||
                    msg.text.contains("如果你需要用") ||
                    msg.text.contains("如果你想用") ||
                    msg.text.contains(HARD_REFUSE_MESSAGE))
        }
    }
}
