package com.deepseek.coder.data.skill

import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * fetch_doc 工具实现（SPEC-Skill-v1.2 §4.2 决策 7）。
 *
 * 基于内置文档快照（assets/docs/deepseek/）按 topic 关键词匹配返回文档片段。
 * 离线可用、无网络依赖、无反爬风险。
 */
@Singleton
class FetchDocTool @Inject constructor(
    private val repo: DocRepository
) : ToolImpl {

    override val name: String = "fetch_doc"

    suspend override fun execute(args: JsonObject): ToolResult {
        val topic = (args["topic"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (topic.isBlank()) return ToolResult.Failure("topic 不能为空")
        val content = repo.fetch(topic)
        return ToolResult.Success(content)
    }

    companion object {
        val spec = ToolSpec(
            name = "fetch_doc",
            description = "从内置 DeepSeek API 文档快照中按主题关键词检索片段（离线可用）。" +
                "api_qa/dep_advice 等 skill 在回答 API 相关问题前应先调用本工具获取准确文档。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("topic", buildJsonObject {
                        put("type", "string")
                        put("description", "文档主题关键词，如「chat/completions」「reasoning」「function_calling」「temperature」")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("topic"))))
            }
        )
    }
}
