package com.deepseek.coder.data.settings

import kotlinx.serialization.Serializable

/**
 * Immutable domain settings model.
 * Default values tuned for code-writing use-case per DeepSeek docs recommendations.
 */
@Serializable
data class AppSettings(
    val model: DeepSeekModel = DeepSeekModel.V4_FLASH,
    val temperature: Float = 0.2f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 4096,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val thinkingEnabled: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val editorFontSizeSp: Float = 14f,
    val fimEnabled: Boolean = true,
    val fimDebounceMs: Long = 700L,
    val baseUrl: String = DEFAULT_BASE_URL,
    val betaBaseUrl: String = DEFAULT_BETA_BASE_URL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cumulativeTokens: Long = 0L
) {
    enum class DeepSeekModel(val id: String, val display: String) {
        V4_FLASH("deepseek-v4-flash", "V4 Flash（高并发 / 快速响应）"),
        V4_PRO("deepseek-v4-pro", "V4 Pro（更强推理 / 思考模式）");

        companion object {
            fun fromId(id: String?): DeepSeekModel = entries.firstOrNull { it.id == id } ?: V4_FLASH
        }
    }

    enum class ReasoningEffort(val value: String) {
        HIGH("high"), MAX("max"), DISABLED("");

        fun enabled() = this != DISABLED
        companion object {
            fun fromValue(v: String?): ReasoningEffort = entries.firstOrNull { it.value == v } ?: HIGH
        }
    }

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/"
        const val DEFAULT_BETA_BASE_URL = "https://api.deepseek.com/beta/"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个资深软件工程师助手 DeepCoder，专注于编写、审查、重构代码。" +
                    "输出代码前请先说明思路。代码需要附带注释并遵循语言惯用风格。" +
                    "涉及危险操作（rm -rf、生产数据库写操作等）必须给出醒目警告。"
    }
}
