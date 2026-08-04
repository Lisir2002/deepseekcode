package com.deepseek.coder.data.skill

import com.deepseek.coder.data.remote.dto.FunctionToolDto
import com.deepseek.coder.data.remote.dto.ToolDto
import com.deepseek.coder.domain.skill.Skill
import com.deepseek.coder.domain.skill.ToolSpec

/**
 * 把 Skill.tools（领域 ToolSpec）转成请求 DTO（ToolDto）。
 */
object ToolRequestBuilder {

    fun buildTools(skill: Skill): List<ToolDto>? {
        if (skill.tools.isEmpty()) return null
        return skill.tools.map { it.toDto() }
    }

    private fun ToolSpec.toDto(): ToolDto = ToolDto(
        type = "function",
        function = FunctionToolDto(
            name = name,
            description = description,
            parameters = parameters
        )
    )
}
