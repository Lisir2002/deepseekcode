package com.deepseek.coder.domain.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Skill 系统数据模型（SPEC-Skill-v1.2 §2）。
 *
 * 一个 Skill = 能力声明（system prompt + tools 契约 + 输出契约）+ 工具执行回路。
 * 不引入编排状态机；一个 skill = 一次"带工具箱的直通流式"。
 *
 * 注：本模型带 @Serializable 以支持 Phase 4 用户自定义 skill 的 DataStore JSON 持久化
 * 与导入/导出。`enabled`/`builtIn` 为运行时态，持久化时由 [UserSkillDef] 单独建模。
 */
data class Skill(
    val id: String,                     // 唯一标识，如 "code_review"
    val name: String,                   // 显示名，如 "代码审查"
    val description: String,            // 一句话描述
    val icon: String,                   // 图标 key（Material Icon 名）
    val category: SkillCategory,        // 分类
    val systemPrompt: String,           // 角色 system prompt
    val tools: List<ToolSpec>,          // 声明可用工具（空列表=纯 prompt skill）
    val outputContract: OutputContract, // 输出格式约束
    val enabled: Boolean = true,        // UI 开关
    val builtIn: Boolean = true         // 内置不可删（用户自定义可删）
)

@Serializable
enum class SkillCategory {
    CODE_MODIFY,     // 代码改动类
    CODE_UNDERSTAND, // 理解产出类
    CODE_GENERATE,   // 新生成类
    QA_ASSIST        // 问答辅助类
}

@Serializable
data class ToolSpec(
    val name: String,           // 工具名，如 "render_mermaid"
    val description: String,    // 工具描述（给模型看）
    val parameters: JsonObject  // 参数 JSON Schema
)

@Serializable
data class OutputContract(
    val format: OutputFormat,
    val requiredFields: List<String> = emptyList(),
    val styleHints: String = ""
)

@Serializable
enum class OutputFormat { MARKDOWN, DIFF, JSON, MERMAID, PLAIN }
