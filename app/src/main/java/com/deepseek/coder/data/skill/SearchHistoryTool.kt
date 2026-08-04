package com.deepseek.coder.data.skill

import com.deepseek.coder.data.SessionRepository
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * search_history 工具实现（SPEC-Skill-v1.2 §4.2）。
 *
 * 跨所有历史会话 LIKE 搜索消息，返回匹配片段列表（带角色 + 会话 id 前缀）。
 * 用于 qa_assist/error_explain 等 skill 复用过往对话上下文。
 */
@Singleton
class SearchHistoryTool @Inject constructor(
    private val sessionRepository: SessionRepository
) : ToolImpl {

    override val name: String = "search_history"

    suspend override fun execute(args: JsonObject): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (query.isBlank()) return ToolResult.Failure("query 不能为空")
        val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?.coerceIn(1, 20) ?: 5
        val results = sessionRepository.searchMessages(query, limit)
        if (results.isEmpty()) {
            return ToolResult.Success("未找到匹配「$query」的历史消息。")
        }
        val formatted = results.joinToString("\n\n") { msg ->
            val role = if (msg.role == ChatRole.USER) "用户" else "助手"
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(msg.timestampMs))
            val snippet = msg.text.take(300).replace("\n", " ")
            "[$time|$role] $snippet"
        }
        return ToolResult.Success("找到 ${results.size} 条匹配「$query」的历史消息：\n\n$formatted")
    }

    companion object {
        val spec = ToolSpec(
            name = "search_history",
            description = "跨所有历史会话搜索消息（LIKE 模糊匹配），返回匹配片段列表。" +
                "用于复用过往对话上下文，如报错解读时查找是否曾遇到类似问题。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "搜索关键词")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "返回条数上限，1-20，默认 5")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
        )
    }
}
