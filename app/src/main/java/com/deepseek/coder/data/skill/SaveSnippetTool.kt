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
 * save_snippet 工具实现（SPEC-Skill-v1.2 §4.2）。
 *
 * 把模型生成的代码片段存入 App 片段库（filesDir/snippets/）。
 * SPEC §3.5 决策：写入操作不缓存。
 */
@Singleton
class SaveSnippetTool @Inject constructor(
    private val repo: SnippetRepository
) : ToolImpl {

    override val name: String = "save_snippet"

    suspend override fun execute(args: JsonObject): ToolResult {
        val title = (args["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val language = (args["language"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val code = (args["code"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.Failure("title 不能为空")
        if (code.isBlank()) return ToolResult.Failure("code 不能为空")
        val snippet = repo.save(title, language, code)
            ?: return ToolResult.Failure("片段保存失败")
        return ToolResult.Success(
            "已存档：${snippet.title}（${snippet.language.ifBlank { "txt" }}，" +
                "${code.length} 字符，id=${snippet.id}）。用户可在片段库查阅。"
        )
    }

    companion object {
        val spec = ToolSpec(
            name = "save_snippet",
            description = "把生成的代码片段存入 App 片段库，便于用户后续查阅/复用。" +
                "生成代码类 skill（gen_function/gen_class/scaffold/refactor）应在输出代码后调用本工具存档。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "片段标题，简短描述用途，如「线程安全 LRU 缓存」")
                    })
                    put("language", buildJsonObject {
                        put("type", "string")
                        put("description", "代码语言标识，如 kotlin/python/java")
                    })
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "完整代码内容（不含 markdown 围栏）")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("title"), JsonPrimitive("language"), JsonPrimitive("code")
                )))
            }
        )
    }
}
