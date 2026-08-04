package com.deepseek.coder.data.skill

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.di.SnippetRootDir
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代码片段存档仓库（SPEC-Skill-v1.2 §4.2 save_snippet）。
 *
 * 片段以 JSON 元数据 + 原始代码文件形式存于 App 沙箱 `filesDir/snippets/`，
 * 便于用户后续在"片段库"页面查阅/复制（v1.0 仅做存档，列表 UI 留待后续迭代）。
 */
@Singleton
class SnippetRepository @Inject constructor(
    @SnippetRootDir private val rootDir: File,
    private val dispatchers: DispatcherProvider
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class Snippet(
        val id: String,
        val title: String,
        val language: String,
        val codeFile: String, // 相对文件名
        val createdAtMs: Long
    )

    suspend fun save(title: String, language: String, code: String): Snippet? = withContext(dispatchers.io) {
        if (title.isBlank() || code.isBlank()) return@withContext null
        if (!rootDir.exists()) rootDir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val safeTitle = title.trim().take(80).replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val safeLang = language.trim().take(20).replace(Regex("[^A-Za-z0-9_\\-]"), "")
        val codeFile = "${id}_${safeTitle}.${safeLang.ifBlank { "txt" }}"
        val codeTarget = File(rootDir, codeFile)
        val metaTarget = File(rootDir, "$id.meta.json")
        runCatching {
            codeTarget.writeText(code, Charsets.UTF_8)
            val snippet = Snippet(id, title.trim(), safeLang, codeFile, System.currentTimeMillis())
            metaTarget.writeText(json.encodeToString(Snippet.serializer(), snippet), Charsets.UTF_8)
            snippet
        }.getOrElse {
            AppLogger.w(it, "SnippetRepository: save failed for %s", title)
            codeTarget.delete(); metaTarget.delete()
            null
        }
    }

    suspend fun list(): List<Snippet> = withContext(dispatchers.io) {
        rootDir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(Snippet.serializer(), f.readText()) }.getOrNull()
            }?.sortedByDescending { it.createdAtMs } ?: emptyList()
    }

    suspend fun readCode(snippet: Snippet): String? = withContext(dispatchers.io) {
        File(rootDir, snippet.codeFile).takeIf { it.exists() }?.readText()
    }

    companion object {
        const val DIR_NAME = "snippets"
    }
}
