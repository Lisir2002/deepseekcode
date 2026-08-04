package com.deepseek.coder.data.skill

import android.content.Context
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.core.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置文档快照仓库（SPEC-Skill-v1.2 §4.2 决策 7）。
 *
 * 文档以 Markdown 形式打包进 `assets/docs/deepseek/`，随 App 版本更新。
 * fetch_doc 工具按 topic 关键词匹配返回相关片段，离线可用、无网络依赖。
 *
 * 匹配策略（轻量，避免引入全文检索依赖）：
 * 1. 文件名命中 topic → 整文件返回（最高优先级）
 * 2. 文件内容包含 topic 关键词 → 返回命中的段落（## 标题分块）
 * 3. 都未命中 → 返回所有文档的标题索引 + 提示
 */
@Singleton
class DocRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {

    private data class DocEntry(val path: String, val title: String, val content: String)

    /** 懒加载 assets 文档（首次调用读盘，后续内存缓存）。 */
    private val docs: List<DocEntry> by lazy { loadDocs() }

    private fun loadDocs(): List<DocEntry> = runCatching {
        val assetManager = context.assets
        val files = assetManager.list(DOC_DIR) ?: emptyArray()
        files.filter { it.endsWith(".md") }.mapNotNull { fileName ->
            runCatching {
                val content = assetManager.open("${DOC_DIR}/$fileName").bufferedReader().use { it.readText() }
                val title = fileName.removeSuffix(".md")
                DocEntry(fileName, title, content)
            }.getOrNull()
        }
    }.getOrElse {
        AppLogger.w(it, "DocRepository: loadDocs failed")
        emptyList()
    }

    /** 按 topic 关键词匹配文档片段。 */
    suspend fun fetch(topic: String): String = withContext(dispatchers.io) {
        if (topic.isBlank()) return@withContext "topic 不能为空"
        val keyword = topic.trim().lowercase()

        // 1. 文件名命中
        val nameHit = docs.firstOrNull { it.title.lowercase() == keyword || it.title.lowercase().contains(keyword) }
        if (nameHit != null) {
            return@withContext formatHit(nameHit)
        }

        // 2. 内容命中段落（按 ## 标题分块）
        val sectionHits = mutableListOf<String>()
        docs.forEach { doc ->
            val sections = splitBySection(doc.content)
            sections.forEach { section ->
                if (section.lowercase().contains(keyword)) {
                    sectionHits.add("## ${doc.title} ／ ${section.take(80)}…")
                    sectionHits.add(section.take(MAX_SECTION_CHARS))
                    sectionHits.add("")
                }
            }
        }
        if (sectionHits.isNotEmpty()) {
            return@withContext "找到 ${sectionHits.size / 3} 段与「$topic」相关的文档片段：\n\n" +
                sectionHits.joinToString("\n")
        }

        // 3. 未命中 → 返回索引
        val index = docs.joinToString("\n") { d -> "- ${d.title}" }
        "未找到与「$topic」直接相关的文档。可用主题索引：\n$index\n\n建议用上述主题词重试。"
    }

    private fun formatHit(doc: DocEntry): String =
        "文档：${doc.title}\n\n${doc.content.take(MAX_FULL_DOC_CHARS)}" +
            if (doc.content.length > MAX_FULL_DOC_CHARS) "\n\n…[文档较长，已截断]" else ""

    /** 按 markdown 二级标题分块。 */
    private fun splitBySection(content: String): List<String> {
        val lines = content.lines()
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (line.startsWith("## ")) {
                if (current.isNotEmpty()) {
                    sections.add(current.toString())
                    current.clear()
                }
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) sections.add(current.toString())
        return sections
    }

    companion object {
        const val DOC_DIR = "docs/deepseek"
        const val MAX_SECTION_CHARS = 800
        const val MAX_FULL_DOC_CHARS = 4000
    }
}
