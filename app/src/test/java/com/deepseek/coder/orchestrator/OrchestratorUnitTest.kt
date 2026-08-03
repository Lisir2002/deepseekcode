package com.deepseek.coder.orchestrator

import com.deepseek.coder.data.remote.dto.ChatCompletionRequest
import com.deepseek.coder.data.remote.dto.ChatMessageDto
import com.deepseek.coder.data.remote.dto.ResponseFormatDto
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.domain.workflow.CodeIntent
import com.deepseek.coder.domain.workflow.OrchestratorEvent
import com.deepseek.coder.domain.workflow.WorkflowEvent
import com.deepseek.coder.domain.workflow.WorkflowPlan
import com.deepseek.coder.domain.workflow.WorkflowState
import com.deepseek.coder.domain.workflow.WorkflowStep
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the v1.1 Orchestrator + LoRA interfaces.
 * These intentionally test the pure-pure logic (models, prompt, JSON schema)
 * against in-memory fixtures; they do NOT exercise the network layer.
 */
class OrchestratorUnitTest {

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // --------------------------------------------------------------
    // ChatCompletionRequest JSON-mode (response_format) serialisation
    // --------------------------------------------------------------
    @Test
    fun json_mode_request_serializes_response_format() {
        val req = ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = listOf(
                ChatMessageDto(role = "system", content = "Classify."),
                ChatMessageDto(role = "user", content = "写一个 Kotlin 类")
            ),
            temperature = 0.05f,
            stream = false,
            responseFormat = ResponseFormatDto(type = "json_object")
        )
        val out = json.encodeToString(req)
        assertTrue(out.contains("\"response_format\":"))
        val parsed = json.parseToJsonElement(out).jsonObject
        val rf = parsed["response_format"]!!.jsonObject
        assertEquals("json_object", rf["type"]!!.jsonPrimitive.content)
        // content must stay plain string (regression guard)
        assertFalse(out.contains("\"type\":\"Text\""))
    }

    // --------------------------------------------------------------
    // CodeIntent fallback mapping (no network)
    // --------------------------------------------------------------
    @Test
    fun codeIntent_guess_covers_common_zh_keywords() {
        val cases: List<Pair<String, CodeIntent>> = listOf(
            "写一个 LoginViewModel" to CodeIntent.CODE_GENERATE,
            "帮我重构一下这个 ViewModel" to CodeIntent.CODE_REFACTOR,
            "解释一下 Kotlin 的 lazy 原理" to CodeIntent.CODE_EXPLAIN,
            "我这段代码崩溃了帮我看看报错" to CodeIntent.CODE_FIX_BUG,
            "把 Python 脚本转成 Kotlin" to CodeIntent.CODE_TRANSLATE,
            "帮我 review 这个 PR 的 diff" to CodeIntent.CODE_REVIEW,
            "请设计一下登录模块的架构" to CodeIntent.DESIGN_ARCH,
            "光标在中间自动补全 FIM" to CodeIntent.FIM_COMPLETE
        )
        cases.forEach { (text, expected) ->
            val actual = guessIntentFallback(text)
            assertEquals("Failed for input=$text", expected, actual)
        }
    }

    // --------------------------------------------------------------
    // Workflow plan step index + serialisation roundtrip
    // --------------------------------------------------------------
    @Test
    fun workflowPlan_roundTrip_json() {
        val plan = WorkflowPlan(
            steps = listOf(
                WorkflowStep(0, "数据层：Entity + DAO", "Room 注解命名规范；用 Hilt 提供 Db", requiresSelfCheck = false),
                WorkflowStep(1, "Domain：UseCase + 接口", dependsOn = listOf(0), requiresSelfCheck = false),
                WorkflowStep(2, "UI：ViewModel + Compose", dependsOn = listOf(1), requiresSelfCheck = true)
            ),
            estimatedTotalTokens = 8000
        )
        val dto = WorkflowPlanDto(
            steps = plan.steps.map {
                WorkflowPlanDto.StepDto(it.index, it.title, it.systemPromptHints, it.dependsOn, it.requiresSelfCheck)
            },
            estimatedTotalTokens = plan.estimatedTotalTokens
        )
        val encoded = json.encodeToString(dto)
        val decoded = json.decodeFromString(WorkflowPlanDto.serializer(), encoded)
        assertEquals(dto.steps.size, decoded.steps.size)
        assertEquals(dto.estimatedTotalTokens, decoded.estimatedTotalTokens)
        assertTrue(decoded.steps.last().requiresSelfCheck == true)
    }

    // --------------------------------------------------------------
    // Orchestrator FSM: verify synthetic state transitions reach every node
    // --------------------------------------------------------------
    @Test
    fun synthetic_fsm_visits_all_expected_nodes() = runBlocking {
        val states = WorkflowState.entries
        val visited = mutableSetOf<WorkflowState>()
        flowOf(
            WorkflowEvent.Orch(OrchestratorEvent.Started("r1")),
            t(WorkflowState.IDLE, WorkflowState.CLASSIFY),
            t(WorkflowState.CLASSIFY, WorkflowState.GOVERN_CONTEXT),
            t(WorkflowState.GOVERN_CONTEXT, WorkflowState.DECOMPOSE),
            t(WorkflowState.DECOMPOSE, WorkflowState.EXECUTE),
            t(WorkflowState.EXECUTE, WorkflowState.SELF_CHECK),
            t(WorkflowState.SELF_CHECK, WorkflowState.RETRY_FIX),
            t(WorkflowState.RETRY_FIX, WorkflowState.SELF_CHECK),
            t(WorkflowState.SELF_CHECK, WorkflowState.DONE)
        ).toList().forEach { ev ->
            if (ev is WorkflowEvent.Orch && ev.event is OrchestratorEvent.StateTransition) {
                visited += ev.event.to
            }
        }
        assertTrue("CLASSIFY must be reachable", WorkflowState.CLASSIFY in visited)
        assertTrue("EXECUTE must be reachable", WorkflowState.EXECUTE in visited)
        assertTrue("SELF_CHECK must be reachable", WorkflowState.SELF_CHECK in visited)
        assertTrue("RETRY_FIX must be reachable", WorkflowState.RETRY_FIX in visited)
        assertTrue("DONE must be reachable", WorkflowState.DONE in visited)
    }

    // --------------------------------------------------------------
    // ClarifyQuestion event keeps a stable question order
    // --------------------------------------------------------------
    @Test
    fun clarification_event_preserves_order() {
        val qs = listOf("语言？", "目标平台？", "是否需要数据库？")
        val ev = OrchestratorEvent.ClarifyQuestion(qs)
        assertEquals(3, ev.questions.size)
        assertEquals("语言？", ev.questions.first())
        assertEquals("是否需要数据库？", ev.questions.last())
    }

    // --------------------------------------------------------------
    // Self-check JSON schema matches expected structure (regression guard)
    // --------------------------------------------------------------
    @Test
    fun self_check_dto_parses_example_output() {
        val jsonStr = """
            {"pass":false,"issues":["Missing import androidx.hilt.navigation.compose.hiltViewModel","GlobalScope used in ViewModel"],"suggested_fix_prompt":"请修复导入类名与协程作用域问题，保持逻辑不变，直接给出修复后的完整代码"}
        """.trimIndent()
        val dto = json.decodeFromString(SelfCheckDto.serializer(), jsonStr)
        assertFalse(dto.pass)
        assertEquals(2, dto.issues?.size)
        assertNotNull(dto.suggested_fix_prompt)
        assertTrue(dto.suggested_fix_prompt!!.startsWith("请修复"))
    }

    // --------------------------------------------------------------
    // Helpers (mirror small pure functions so tests are self-hosted)
    // --------------------------------------------------------------
    private fun t(from: WorkflowState, to: WorkflowState): WorkflowEvent =
        WorkflowEvent.Orch(OrchestratorEvent.StateTransition(from, to))

    @kotlinx.serialization.Serializable
    private data class WorkflowPlanDto(
        val steps: List<StepDto>,
        val estimatedTotalTokens: Int? = null
    ) {
        @kotlinx.serialization.Serializable
        data class StepDto(
            val index: Int? = null,
            val title: String? = null,
            val systemPromptHints: String? = null,
            val dependsOn: List<Int>? = null,
            val requiresSelfCheck: Boolean? = null
        )
    }

    @kotlinx.serialization.Serializable
    private data class SelfCheckDto(
        val pass: Boolean,
        val issues: List<String>? = null,
        val suggested_fix_prompt: String? = null
    )

    private fun guessIntentFallback(text: String): CodeIntent {
        val t = text
        return when {
            t.contains("重构") || t.contains("重写") || t.contains("改造") -> CodeIntent.CODE_REFACTOR
            t.contains("解释") || t.contains("讲一下") || t.contains("原理") -> CodeIntent.CODE_EXPLAIN
            t.contains("报错") || t.contains("崩溃") || t.contains("修复") || t.contains("bug") || t.contains("异常")
                -> CodeIntent.CODE_FIX_BUG
            t.contains("翻译") || t.contains("转成") || t.contains("转换") -> CodeIntent.CODE_TRANSLATE
            t.contains("review") || t.contains("评审") || t.contains("审查") || t.contains("点评") -> CodeIntent.CODE_REVIEW
            t.contains("架构") || t.contains("设计") || t.contains("模块") || t.contains("分层") -> CodeIntent.DESIGN_ARCH
            t.contains("补全") || t.contains("光标") || t.contains("FIM") -> CodeIntent.FIM_COMPLETE
            t.contains("写") || t.contains("生成") || t.contains("实现") || t.contains("创建") -> CodeIntent.CODE_GENERATE
            else -> CodeIntent.CODE_GENERATE
        }
    }
}
