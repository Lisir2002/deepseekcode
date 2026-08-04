package com.deepseek.coder.data.skill

import com.deepseek.coder.di.ApplicationScope
import com.deepseek.coder.domain.skill.Skill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 解析器（SPEC-Skill-v1.2 §2.4 / §2.5 / §2.6）。
 *
 * 职责：
 *  1. 按 id 解析 skill（内置 + 用户自定义合并；v1.2 决策 16：不版本化，按 id 查最新）
 *  2. 解析 @skill_name 临时切换（v1.2 决策 14）
 *  3. 解析 skill 的实际 systemPrompt（v1.2 决策 12：skill 覆盖全局，default_chat 用全局）
 *  4. 加载会话历史时过滤旧 system 消息（v1.1 决策 1）
 *
 * Phase 4：合并内置 skill 与用户自定义 skill。用户自定义 skill 通过 [UserSkillRepository]
 * 异步加载，缓存在 [userSkillsCache] 中供同步 [resolve]/[resolveTemporary] 访问
 * （这两个方法在 ChatViewModel 的非协程段被调用，故需同步可见性）。
 */
@Singleton
class SkillResolver @Inject constructor(
    private val userSkillRepository: UserSkillRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    /** 用户自定义 skill 最新快照（由 appScope 后台 collect 维护）。 */
    private val userSkillsCache = MutableStateFlow<List<UserSkillDef>>(emptyList())
    val userSkills = userSkillsCache.asStateFlow()

    init {
        appScope.launch {
            userSkillRepository.userSkills.collectLatest { defs ->
                userSkillsCache.value = defs
            }
        }
    }

    /** 内置 + 自定义合并后的全部 skill（自定义 enabled 恒 true，由上层按 disabledIds 覆盖）。 */
    fun allMerged(): List<Skill> = BuiltInSkills.all + userSkillsCache.value.map { it.toSkill(true) }

    /** 按 id 解析 skill，找不到返回 default_chat。 */
    fun resolve(skillId: String?): Skill {
        if (skillId != null) {
            BuiltInSkills.byId(skillId)?.let { return it }
            userSkillsCache.value.firstOrNull { it.id == skillId }?.let { return it.toSkill(true) }
        }
        return BuiltInSkills.default
    }

    /**
     * 解析 @skill_name 临时切换（v1.2 决策 14）。
     *
     * @param input 用户输入
     * @param currentSkillId 当前会话 skill id（未临时切换时用）
     * @return (实际 skill, 去除 @skill_name 后的纯文本)
     */
    fun resolveTemporary(input: String, currentSkillId: String?): Pair<Skill, String> {
        val trimmed = input.trimStart()
        val match = Regex("""^@([a-z0-9_]+)\s+(.*)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (match != null) {
            val (name, rest) = match.destructured
            val id = name.lowercase()
            // 先查内置，再查自定义
            BuiltInSkills.byId(id)?.let { return it to rest.trim() }
            userSkillsCache.value.firstOrNull { it.id == id }?.let { return it.toSkill(true) to rest.trim() }
        }
        return resolve(currentSkillId) to input
    }

    /**
     * 解析 skill 的实际 systemPrompt（v1.2 决策 12）。
     *
     * - default_chat → 用户全局 systemPrompt
     * - 其他 skill（含自定义）→ skill.systemPrompt（覆盖全局）
     */
    fun resolveSystemPrompt(skill: Skill, globalSystemPrompt: String): String {
        return if (skill.id == "default_chat" || skill.systemPrompt == BuiltInSkills.DEFAULT_CHAT_PLACEHOLDER) {
            globalSystemPrompt
        } else {
            skill.systemPrompt
        }
    }
}
