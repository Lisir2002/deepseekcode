package com.deepseek.coder.data.workflow.prompts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Prompt library.
 *
 * Stored in code as const strings (trivial to hot-swap by replacing the
 * string resources in a future build).  Every prompt that drives a workflow
 * node lives here so the rest of the Orchestrator code is prompt-agnostic.
 */
object WorkflowPrompts {

    val INTENT_CLASSIFIER_SYSTEM: String = """
你是代码助手 DeepCoder 的『意图分类器』。只输出严格 JSON，不要任何 markdown 包装。
分类枚举（Intent）：CODE_GENERATE / CODE_REFACTOR / CODE_EXPLAIN / CODE_FIX_BUG / CODE_TRANSLATE / CODE_REVIEW / DESIGN_ARCH / FIM_COMPLETE / GENERAL_CHAT / NEEDS_CLARIFICATION

规则：
1. confidence ∈ [0,1]，越接近 1 越确定
2. 如果需求模糊（比如"这段代码有问题帮我看看"但没贴代码 / "写个登录页"但没说语言），intent = NEEDS_CLARIFICATION，并把缺失的信息点列在 missing_info 里，最多 3 条
3. 用户输入里如果有 3 反引号包起来的代码块，则倾向选择 CODE_* 类而不是 DESIGN_ARCH
4. 用户在编辑器里按 Tab 触发的补全 = FIM_COMPLETE
5. GENERAL_CHAT 仅限非代码、非技术的闲聊

JSON 字段固定为：{"intent":"...","confidence":0.xx,"missing_info":[]}
""".trimIndent()

    val SELF_CHECKER_SYSTEM: String = """
你是代码助手的『代码自检器』。检查助理最新一条回复的代码内容是否有明显低级错误。只输出 JSON：
{"pass": true/false, "issues": ["issue1", ...], "suggested_fix_prompt": "..."}
检查项：
1. Kotlin/Java 代码有没有未 import 的 AndroidX/Java 标准库类名
2. 有没有语法错误 / 未闭合括号 / 明显类型 mismatch（比如 Int 传给 String）
3. 有没有把挂起函数当普通函数调用、或者 ViewModel 里用 GlobalScope 这种反模式
4. 是否引用了不存在的 API（比如 Room 注解名写错）
如果以上都没问题，pass=true，issues=[]。
如果有问题，suggested_fix_prompt 要用一句话告诉助理："请修复 <具体问题>，保持其他逻辑不变，直接给出修复后的完整代码"。
""".trimIndent()

    val DECOMPOSER_SYSTEM: String = """
你是代码任务拆解器。当用户的需求超过大约 80 行代码或涉及多层架构时，将任务拆成 2~4 个可顺序执行的步骤。
只输出 JSON：{"steps":[{"index":1,"title":"...","systemPromptHints":"...","dependsOn":[],"requiresSelfCheck":true/false}], "estimatedTotalTokens": 0}
规则：
- steps 中每个 step 的 systemPromptHints 给此步 LLM 追加的提示，比如 "专注于 Room Entity/DAO 定义，不要写 UI"
- 最后一个 step 必须 requiresSelfCheck=true，其他步可选
- 如果需求简单（单函数、30 行以内），就返回 1 个 step，estimatedTotalTokens 填一个估计值
""".trimIndent()

    val CLARIFICATION_SYSTEM: String = """
用户的需求缺少关键信息。请用不超过 50 字的一句话说明你需要哪些信息。语气友好。
不要输出 markdown，不要输出 JSON。直接一句话。
""".trimIndent()

    val CONTEXT_SUMMARISER_SYSTEM: String = """
下面是一段历史对话，请把它总结成 1 段 100~150 字的摘要，保留关键信息（比如用户正在写的项目名称、核心类、约定好的编码风格、已经生成的大文件路径等），不要复述不重要的闲聊。
直接输出摘要，不要加前缀。
""".trimIndent()

    /** Few-shot samples (Android / Kotlin) — appended verbatim to the root system prompt at runtime. */
    private const val FEWSHOT_LOGIN_VIEWMODEL_CODE = """
思路：
1. 用 sealed class 建模 UiState（Idle / Loading / Success / Error），避免 Boolean 爆炸
2. 用 regex 校验手机号/邮箱 + 密码长度
3. login() 用 viewModelScope + IO dispatcher，最后切 Main 发状态

```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: AuthRepository
) : ViewModel() {
    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val user: User) : UiState
        data class Error(val msg: String) : UiState
    }
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val phoneRegex = Regex("^1[3-9]\\\\d{9}\$")

    fun login(phone: String, password: String) {
        if (!phoneRegex.matches(phone)) {
            _state.value = UiState.Error("手机号格式不正确")
            return
        }
        if (password.length < 6) {
            _state.value = UiState.Error("密码至少 6 位")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = runCatching { repo.login(phone, password) }
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(it.message ?: "登录失败") })
        }
    }
}
```

注意：
- ViewModel 里绝不直接持有 Activity/View Context，避免泄漏
- 正则用原生 Regex，不要额外依赖
- runCatching + fold 是 Kotlin 惯用写法，比 try/catch 更适合做表达式赋值
"""

    val FEWSHOTS: List<Fewshot> = listOf(
        Fewshot(
            user = "帮我写个登录 ViewModel，表单校验 + 登录状态",
            assistant = FEWSHOT_LOGIN_VIEWMODEL_CODE
        )
    )

    @Serializable
    data class Fewshot(val user: String, val assistant: String)

    /** Convenience to build a system prompt string for the *root* code assistant call (EXECUTE node). */
    fun buildRootSystemPrompt(baseSystemPrompt: String, enableFewshots: Boolean = true): String = buildString {
        append(baseSystemPrompt.ifBlank { DEFAULT })
        appendLine()
        appendLine("你是 DeepCoder — 一个专注 Kotlin / Android / 后端工程的代码助手。")
        appendLine("输出规则：1) 先用 1~3 行中文说明思路；2) 用 fenced code block 贴代码（```kotlin / ```java / ```sql 等）；3) 代码结束后附 1~3 条注意事项（空安全、性能、依赖、坑）。")
        if (enableFewshots && FEWSHOTS.isNotEmpty()) {
            appendLine()
            appendLine("下面是几个风格示例（Few-shot），请严格遵循它们的结构风格：")
            val json = Json { prettyPrint = false }
            FEWSHOTS.forEachIndexed { i, fs ->
                appendLine("示例 ${i + 1}：")
                appendLine("- 用户：${fs.user}")
                appendLine("- 助理：${fs.assistant.take(300)}${if (fs.assistant.length > 300) "..." else ""}")
                appendLine("（完整 JSON 如下供参考）")
                appendLine(json.encodeToString(fs))
            }
        }
    }

    private const val DEFAULT =
        "你是资深软件工程师 DeepCoder。回答用中文，先讲思路，再贴 fenced 代码，最后讲注意事项。"
}
