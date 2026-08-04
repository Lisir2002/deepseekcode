package com.deepseek.coder.data.skill

import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * render_mermaid 工具实现（SPEC-Skill-v1.2 §4.2，v1.1 决策 6 提前到 Phase 1）。
 *
 * Phase 1 简化实现：验证 mermaid 代码非空，返回确认信息。
 * 模型收到确认后会在最终回复里用 ```mermaid 块输出，UI 层渲染（ChatScreen 代码块 + 后续 WebView 增强）。
 *
 * 不实际渲染成图片文件（Android 端 mermaid 渲染需 WebView + mermaid.js，留待后续增强）。
 * 这样既验证了工具回路，又不过度设计。
 */
@Singleton
class MermaidTool @Inject constructor() : ToolImpl {

    override val name: String = "render_mermaid"

    suspend override fun execute(args: JsonObject): ToolResult {
        val code = (args["code"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (code.isBlank()) {
            return ToolResult.Failure("mermaid 代码不能为空")
        }
        // 基础语法检查：mermaid 图必须以图表类型关键字开头
        val firstLine = code.lineSequence().firstOrNull()?.trim().orEmpty()
        val knownTypes = listOf("graph", "flowchart", "sequenceDiagram", "classDiagram",
            "stateDiagram", "erDiagram", "gantt", "pie", "journey", "mindmap")
        val typeOk = knownTypes.any { firstLine.startsWith(it, ignoreCase = true) }
        if (!typeOk) {
            return ToolResult.Failure("mermaid 代码应以图表类型开头（如 flowchart/sequenceDiagram），实际：${firstLine.take(40)}")
        }
        return ToolResult.Success(
            "mermaid 图已就绪（${code.length} 字符，类型：${firstLine.takeWhile { !it.isWhitespace() }}）。请在最终回复中用 ```mermaid 代码块输出完整代码，UI 会渲染。"
        )
    }

    companion object {
        /** 工具声明（给 Skill 引用）。 */
        val spec = ToolSpec(
            name = "render_mermaid",
            description = "渲染 mermaid 图表代码。当你需要画架构图/流程图/时序图时调用，传入 mermaid 代码。调用后请在最终回复里用 ```mermaid 块输出完整代码。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "mermaid 图表代码，必须以图表类型开头（flowchart/sequenceDiagram/classDiagram 等）")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("code"))))
            }
        )
    }
}
