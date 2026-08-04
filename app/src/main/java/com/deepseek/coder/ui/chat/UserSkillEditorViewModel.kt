package com.deepseek.coder.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.skill.BuiltInToolSpecs
import com.deepseek.coder.data.skill.UserSkillDef
import com.deepseek.coder.data.skill.UserSkillRepository
import com.deepseek.coder.domain.skill.OutputFormat
import com.deepseek.coder.domain.skill.SkillCategory
import com.deepseek.coder.domain.skill.ToolSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 用户自定义 Skill 编辑器 ViewModel（Phase 4，SPEC §7）。
 *
 * 支持创建/编辑/删除。新建模式 skillId="new"；编辑模式 skillId=user_xxx。
 * 表单字段：name/description/icon/category/systemPrompt/工具勾选/输出格式/风格提示。
 */
@HiltViewModel
class UserSkillEditorViewModel @Inject constructor(
    private val userSkillRepository: UserSkillRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class FormState(
        val editingId: String? = null,    // null=新建模式
        val name: String = "",
        val description: String = "",
        val icon: String = "Star",
        val category: SkillCategory = SkillCategory.QA_ASSIST,
        val systemPrompt: String = "",
        val selectedTools: Set<String> = emptySet(),
        val outputFormat: OutputFormat = OutputFormat.MARKDOWN,
        val styleHints: String = "",
        val loading: Boolean = true,
        val saving: Boolean = false,
        val error: String? = null,
        val saved: Boolean = false,
        val deleted: Boolean = false
    ) {
        val isEditing: Boolean get() = editingId != null
        val canSave: Boolean get() = name.isNotBlank() && systemPrompt.isNotBlank() && !saving
    }

    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    /** 所有可勾选的内置工具（UI 用）。 */
    val availableTools: List<ToolSpec> get() = BuiltInToolSpecs.all

    init {
        val skillId: String? = savedStateHandle["skillId"]
        if (skillId == null || skillId == "new") {
            _state.update { it.copy(loading = false, editingId = null) }
        } else {
            load(skillId)
        }
    }

    private fun load(skillId: String) {
        viewModelScope.launch {
            val def = runCatching { userSkillRepository.byId(skillId) }
                .onFailure { AppLogger.w(it, "UserSkillEditor: load failed") }
                .getOrNull()
            if (def == null) {
                _state.update { it.copy(loading = false, error = "Skill 不存在或已被删除") }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    editingId = def.id,
                    name = def.name,
                    description = def.description,
                    icon = def.icon.ifBlank { "Star" },
                    category = def.category,
                    systemPrompt = def.systemPrompt,
                    selectedTools = def.tools.map { t -> t.name }.toSet(),
                    outputFormat = def.outputFormat,
                    styleHints = def.styleHints
                )
            }
        }
    }

    fun onNameChange(v: String) = _state.update { it.copy(name = v) }
    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v) }
    fun onIconChange(v: String) = _state.update { it.copy(icon = v) }
    fun onCategoryChange(v: SkillCategory) = _state.update { it.copy(category = v) }
    fun onSystemPromptChange(v: String) = _state.update { it.copy(systemPrompt = v) }
    fun onStyleHintsChange(v: String) = _state.update { it.copy(styleHints = v) }
    fun onOutputFormatChange(v: OutputFormat) = _state.update { it.copy(outputFormat = v) }

    fun toggleTool(name: String) {
        _state.update { s ->
            val next = if (name in s.selectedTools) s.selectedTools - name else s.selectedTools + name
            s.copy(selectedTools = next)
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val tools = s.selectedTools.mapNotNull { BuiltInToolSpecs.byName(it) }
            val def = UserSkillDef(
                id = s.editingId ?: UserSkillDef.newId(),
                name = s.name.trim(),
                description = s.description.trim(),
                icon = s.icon.trim().ifBlank { "Star" },
                category = s.category,
                systemPrompt = s.systemPrompt.trim(),
                tools = tools,
                outputFormat = s.outputFormat,
                styleHints = s.styleHints.trim(),
                createdAtMs = 0L,
                updatedAtMs = 0L
            )
            val ok = runCatching {
                if (s.isEditing) userSkillRepository.update(def)
                else userSkillRepository.create(def)
                true
            }.onFailure { e ->
                AppLogger.w(e, "UserSkillEditor: save failed")
                _state.update { it.copy(saving = false, error = "保存失败：${e.message}") }
            }.isSuccess
            if (ok) _state.update { it.copy(saving = false, saved = true) }
        }
    }

    fun delete() {
        val id = _state.value.editingId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { userSkillRepository.delete(id) }
                .onFailure { e ->
                    AppLogger.w(e, "UserSkillEditor: delete failed")
                    _state.update { it.copy(saving = false, error = "删除失败：${e.message}") }
                    return@launch
                }
            _state.update { it.copy(saving = false, deleted = true) }
        }
    }
}
