package com.deepseek.coder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.skill.BuiltInSkills
import com.deepseek.coder.data.skill.SkillEnabledRepository
import com.deepseek.coder.data.skill.UserSkillDef
import com.deepseek.coder.data.skill.UserSkillRepository
import com.deepseek.coder.domain.skill.Skill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Skill 管理页 ViewModel（SPEC §6.1 / Phase 4）。
 *
 * 暴露按分类分组的 skill 列表（内置 + 用户自定义合并，含启用状态），
 * 处理启用/禁用切换、删除自定义 skill、导入/导出 JSON。
 */
@HiltViewModel
class SkillManagementViewModel @Inject constructor(
    private val enabledRepo: SkillEnabledRepository,
    private val userSkillRepository: UserSkillRepository
) : ViewModel() {

    data class UiState(
        val skills: List<Skill> = emptyList()
    ) {
        val userSkills: List<Skill> get() = skills.filterNot { it.builtIn }
    }

    val state: StateFlow<UiState> = combine(
        enabledRepo.disabledIds,
        userSkillRepository.userSkills
    ) { disabled, userDefs ->
        val userSkills = userDefs.map { it.toSkill(true) }
        UiState(skills = BuiltInSkills.mergedWith(userSkills, disabled))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState(BuiltInSkills.all)
    )

    fun setEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch { enabledRepo.setEnabled(skillId, enabled) }
    }

    /** 删除自定义 skill（内置 skill 不可删）。 */
    fun delete(skillId: String) {
        if (!UserSkillDef.isUserSkill(skillId)) return
        viewModelScope.launch {
            userSkillRepository.delete(skillId)
            // 同时清理禁用态，避免残留
            enabledRepo.setEnabled(skillId, true)
            _toast.value = "已删除自定义 Skill"
        }
    }

    /** 导出全部自定义 skill 为 JSON 字符串。 */
    fun export(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val json = runCatching { userSkillRepository.exportAll() }
                .onFailure { AppLogger.w(it, "export failed") }
                .getOrNull()
            onResult(json)
        }
    }

    /** 从 JSON 字符串导入自定义 skill。 */
    fun import(json: String) {
        viewModelScope.launch {
            val result = runCatching { userSkillRepository.importFromString(json) }
                .onFailure { AppLogger.w(it, "import failed") }
            _toast.value = result.fold(
                onSuccess = { n -> if (n > 0) "已导入 $n 个 Skill" else "未导入（无新增或同名跳过）" },
                onFailure = { "导入失败：格式错误" }
            )
        }
    }

    fun consumeToast() { _toast.value = null }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
}
