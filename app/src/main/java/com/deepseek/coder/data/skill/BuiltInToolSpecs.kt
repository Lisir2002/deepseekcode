package com.deepseek.coder.data.skill

import com.deepseek.coder.domain.skill.ToolSpec

/**
 * 内置工具规格清单（Phase 4：用户自定义 skill 编辑器勾选用）。
 *
 * 用户自定义 skill 只能从已注册的内置工具中勾选，不允许自定义工具的 JSON Schema
 * （避免用户写出无法执行的工具定义）。新增工具只需在此登记。
 */
object BuiltInToolSpecs {

    /** 所有已注册内置工具的 spec（按工具名排序，UI 稳定展示）。 */
    val all: List<ToolSpec> = listOf(
        ReadAttachedFileTool.spec,
        SaveSnippetTool.spec,
        SearchHistoryTool.spec,
        FetchDocTool.spec,
        MermaidTool.spec
    ).sortedBy { it.name }

    /** 按工具名查 spec。 */
    fun byName(name: String): ToolSpec? = all.firstOrNull { it.name == name }
}
