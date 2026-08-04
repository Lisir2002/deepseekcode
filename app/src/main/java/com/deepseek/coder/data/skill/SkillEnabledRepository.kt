package com.deepseek.coder.data.skill

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 启用/禁用状态仓库（SPEC §6.1）。
 *
 * 用 DataStore 存储被用户**禁用**的 skill id 集合（白名单语义：默认全启用，只存禁用项）。
 * 内置 skill 的 enabled 字段是默认值，运行时由本仓库覆盖。
 */
@Singleton
class SkillEnabledRepository @Inject constructor(
    private val store: DataStore<Preferences>
) {
    private val disabledKey = stringSetPreferencesKey("disabled_skill_ids")

    /** 被禁用的 skill id 集合（flow）。 */
    val disabledIds: Flow<Set<String>> = store.data.map { p -> p[disabledKey] ?: emptySet() }

    suspend fun setEnabled(skillId: String, enabled: Boolean) {
        store.edit { p ->
            val current = p[disabledKey] ?: emptySet()
            p[disabledKey] = if (enabled) current - skillId else current + skillId
        }
    }

    suspend fun isDisabled(skillId: String): Boolean = disabledIds.first().contains(skillId)
}
