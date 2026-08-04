package com.deepseek.coder.data.skill

import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path
import kotlin.io.path.extension

/**
 * read_attached_file 工具实现（SPEC-Skill-v1.2 §4.2 / §4.4）。
 *
 * 读取用户导入到 App 沙箱 `filesDir/attached/` 目录下的文件内容，回传给模型。
 *
 * 安全：
 *  - 路径穿越防护委托给 [AttachedFileRepository.resolve]（拒绝 `../`、绝对路径、子目录）
 *  - 二进制文件不读内容，只返回元信息（避免把二进制塞进上下文）
 *  - 文件大小硬上限（单次读取 256KB），超出截断并标注
 */
@Singleton
class ReadAttachedFileTool @Inject constructor(
    private val repo: AttachedFileRepository
) : ToolImpl {

    override val name: String = "read_attached_file"

    suspend override fun execute(args: JsonObject): ToolResult {
        val path = (args["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (path.isBlank()) {
            return ToolResult.Failure("path 参数不能为空")
        }
        val file = repo.resolve(path) ?: return ToolResult.Failure(
            "文件不存在或路径非法：$path（仅可读取已附加到沙箱的文件，禁止路径穿越）"
        )

        val ext = Path(file.name).extension.lowercase()
        if (ext in BINARY_EXTENSIONS) {
            return ToolResult.Success(
                "[二进制文件未读取内容] 文件名：${file.name}，大小：${file.length()} 字节，类型：$ext。" +
                    "如需分析，请用户提供文本版或源码片段。"
            )
        }

        val raw = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return ToolResult.Failure("读取失败：${it.message}") }

        val truncated = if (raw.length > MAX_READ_CHARS) {
            raw.take(MAX_READ_CHARS) + "\n\n…[已截断，原始长度 ${raw.length} 字符，仅展示前 $MAX_READ_CHARS]"
        } else raw

        return ToolResult.Success(
            "文件：${file.name}（${file.length()} 字节）\n```\n$truncated\n```"
        )
    }

    companion object {
        /** 单次读取字符上限（约 256KB，避免撑爆上下文窗口）。 */
        const val MAX_READ_CHARS = 256 * 1024

        /** 已知二进制扩展名（不读内容，只返回元信息）。 */
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
            "mp3", "mp4", "wav", "avi", "mov", "flv",
            "zip", "gz", "tar", "rar", "7z", "jar", "aar", "apk",
            "class", "dex", "so", "dll", "exe", "bin", "dat",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
        )

        /** 工具声明（给 Skill 引用）。 */
        val spec = ToolSpec(
            name = "read_attached_file",
            description = "读取用户附加到沙箱的文件内容（仅文本类源码/配置/文档）。" +
                "入参 path 为附加文件名（不含路径分隔符），可通过列表查询或从用户上下文获取。" +
                "二进制文件不会返回内容。单次最多读取 256KB 文本。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "附加文件名，如 Foo.kt（不含目录分隔符，禁止 ../）")
                    })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
            }
        )
    }
}
