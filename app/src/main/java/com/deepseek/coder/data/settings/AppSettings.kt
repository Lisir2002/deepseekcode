package com.deepseek.coder.data.settings

/**
 * Immutable domain settings model.
 * Default values tuned for code-writing use-case per DeepSeek docs recommendations.
 */
data class AppSettings(
    val model: DeepSeekModel = DeepSeekModel.V4_FLASH,
    val temperature: Float = 0.2f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 4096,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val thinkingEnabled: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val editorFontSizeSp: Float = 14f,
    val fimEnabled: Boolean = true,
    val fimDebounceMs: Long = 700L,
    val baseUrl: String = DEFAULT_BASE_URL,
    val betaBaseUrl: String = DEFAULT_BETA_BASE_URL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cumulativeTokens: Long = 0L,
    // ---- Orchestrator + LoRA settings (v1.1) ----
    val orchestratorEnabled: Boolean = true,
    val customFineTuneModelId: String? = null,
    val fineTuneDataCollectionEnabled: Boolean = false,
    val selfCheckMaxRetry: Int = 1,
    val clarificationsAutoAsk: Boolean = true,
    // ---- Planner granularity + scope (v1.2) ----
    val granularity: Granularity = Granularity.MEDIUM,
    val rerankEnabled: Boolean = true,
    val scopeTag: String? = null,
    val scopeHints: List<String> = emptyList()
) {
    enum class DeepSeekModel(val id: String, val display: String) {
        V4_FLASH("deepseek-v4-flash", "V4 Flash（高并发 / 快速响应）"),
        V4_PRO("deepseek-v4-pro", "V4 Pro（更强推理 / 思考模式）");

        companion object {
            fun fromId(id: String?): DeepSeekModel = entries.firstOrNull { it.id == id } ?: V4_FLASH
        }
    }

    enum class Granularity(val display: String) {
        COARSE("粗粒度（里程碑级）"),
        MEDIUM("中粒度（子任务级）"),
        FINE("细粒度（步骤级）");

        companion object {
            fun fromGranularity(v: String?): Granularity =
                entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: MEDIUM
        }
    }

    /** Effective model ID used for API calls.  If the user configured a custom fine-tune
     *  model id (obtained e.g. from LoRA training jobs), we use it directly instead of the
     *  built-in enum.  This allows LoRA-trained models to plug into any workflow without
     *  touching model enum entries. */
    val effectiveModelId: String
        get() = customFineTuneModelId?.takeIf { it.isNotBlank() } ?: model.id

    enum class ReasoningEffort(val value: String) {
        LOW("low"), MEDIUM("medium"), HIGH("high"), DISABLED("");

        fun enabled() = this != DISABLED
        companion object {
            fun fromValue(v: String?): ReasoningEffort = entries.firstOrNull { it.value == v } ?: MEDIUM
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

        const val SCOPE_ANDROID_KOTLIN = "ANDROID_KOTLIN"
        const val SCOPE_WEB_FRONTEND = "WEB_FRONTEND"
        const val SCOPE_GENERAL = "GENERAL"

        fun fromScope(v: String?): String? = when (v?.uppercase()) {
            SCOPE_ANDROID_KOTLIN -> SCOPE_ANDROID_KOTLIN
            SCOPE_WEB_FRONTEND -> SCOPE_WEB_FRONTEND
            SCOPE_GENERAL -> SCOPE_GENERAL
            else -> null
        }
    }
}
