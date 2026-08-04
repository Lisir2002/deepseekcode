package com.deepseek.coder.data.skill

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.domain.skill.OutputContract
import com.deepseek.coder.domain.skill.OutputFormat
import com.deepseek.coder.domain.skill.Skill
import com.deepseek.coder.domain.skill.SkillCategory
import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自定义 skill 持久化定义（Phase 4，SPEC §2.3）。
 *
 * 独立于 domain [Skill]，不含运行时态（enabled/builtIn）。
 * enabled 由 [SkillEnabledRepository] 覆盖，builtIn 恒为 false。
 */
@Serializable
data class UserSkillDef(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val category: SkillCategory,
    val systemPrompt: String,
    val tools: List<ToolSpec>,
    val outputFormat: OutputFormat,
    val styleHints: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    /** 转为 domain Skill（enabled 由调用方按 disabledIds 覆盖）。 */
    fun toSkill(enabled: Boolean = true): Skill = Skill(
        id = id,
        name = name,
        description = description,
        icon = icon,
        category = category,
        systemPrompt = systemPrompt,
        tools = tools,
        outputContract = OutputContract(format = outputFormat, styleHints = styleHints),
        enabled = enabled,
        builtIn = false
    )

    companion object {
        /** 自定义 skill id 前缀，避免与内置 skill id 冲突。 */
        const val ID_PREFIX = "user_"

        /** 生成新 id：`user_` + 12 位短码。 */
        fun newId(): String = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)

        /** 判断 id 是否为自定义 skill。 */
        fun isUserSkill(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)
    }
}

/**
 * 用户自定义 skill 仓库（Phase 4，SPEC §2.3 / §7 Phase 4）。
 *
 * 用 DataStore 存储一个 JSON 字符串（[UserSkillDef] 列表序列化）。
 * 提供 flow + CRUD + 导入/导出。
 */
@Singleton
class UserSkillRepository @Inject constructor(
    private val store: DataStore<Preferences>
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("user_skills_json")
    private val serializer = ListSerializer(UserSkillDef.serializer())

    /** 所有自定义 skill（flow）。 */
    val userSkills: Flow<List<UserSkillDef>> = store.data.map { p ->
        runCatching {
            val raw = p[key] ?: return@map emptyList()
            json.decodeFromString(serializer, raw)
        }.getOrElse {
            AppLogger.w(it, "UserSkillRepository: decode failed, fallback to empty")
            emptyList()
        }
    }

    /** 同步获取最新快照（阻塞，供非协程调用方临时使用）。 */
    suspend fun snapshot(): List<UserSkillDef> = userSkills.first()

    /** 新建自定义 skill，返回生成的 id。 */
    suspend fun create(def: UserSkillDef): String {
        val now = System.currentTimeMillis()
        val withId = def.copy(id = def.id.ifBlank { UserSkillDef.newId() }, createdAtMs = now, updatedAtMs = now)
        mutate { current -> current + withId }
        return withId.id
    }

    /** 更新已存在的自定义 skill（按 id 匹配）。不存在则忽略。 */
    suspend fun update(def: UserSkillDef) {
        val now = System.currentTimeMillis()
        mutate { current ->
            current.map { if (it.id == def.id) def.copy(updatedAtMs = now) else it }
        }
    }

    /** 删除自定义 skill。 */
    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    /** 按 id 查找。 */
    suspend fun byId(id: String): UserSkillDef? = snapshot().firstOrNull { it.id == id }

    /**
     * 导入一批自定义 skill（合并去重：同名/同 id 时跳过，重新生成 id 避免冲突）。
     * @return 实际导入条数
     */
    suspend fun importAll(defs: List<UserSkillDef>): Int {
        if (defs.isEmpty()) return 0
        var imported = 0
        mutate { current ->
            val existingIds = current.map { it.id }.toSet()
            val existingNames = current.map { it.name }.toSet()
            val added = defs.mapNotNull { d ->
                if (d.name in existingNames) return@mapNotNull null // 同名跳过
                val newId = if (d.id.isBlank() || d.id in existingIds) UserSkillDef.newId() else d.id
                imported++
                d.copy(id = newId, createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis())
            }
            current + added
        }
        return imported
    }

    /** 导出全部自定义 skill 为 JSON 字符串（用于 SAF 写文件 / 分享）。 */
    suspend fun exportAll(): String {
        val snap = snapshot()
        return json.encodeToString(serializer, snap)
    }

    /** 从 JSON 字符串解析并导入自定义 skill（[importAll] 的字符串入口）。 */
    suspend fun importFromString(jsonStr: String): Int {
        val defs = runCatching { json.decodeFromString(serializer, jsonStr) }
            .getOrElse {
                AppLogger.w(it, "UserSkillRepository: importFromString decode failed")
                return 0
            }
        return importAll(defs)
    }

    private suspend fun mutate(transform: (List<UserSkillDef>) -> List<UserSkillDef>) {
        store.edit { p ->
            val current = runCatching {
                p[key]?.let { json.decodeFromString(serializer, it) } ?: emptyList()
            }.getOrElse { emptyList() }
            val next = transform(current)
            p[key] = json.encodeToString(serializer, next)
        }
    }
}
