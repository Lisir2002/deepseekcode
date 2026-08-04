package com.deepseek.coder.data.skill

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.deepseek.coder.domain.skill.OutputFormat
import com.deepseek.coder.domain.skill.SkillCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SkillResolver 单元测试（SPEC-Skill-v1.2 §2.4 / §2.5 / §2.6 + Phase 4 自定义合并）。
 *
 * 覆盖：按 id 解析（内置 + 自定义）、@skill 临时切换（决策 14）、
 * systemPrompt 覆盖规则（决策 12）、自定义 skill 解析。
 */
class SkillResolverTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun newResolver(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File.createTempFile("test", ".preferences_pb") })
    ): Pair<SkillResolver, UserSkillRepository> {
        val repo = UserSkillRepository(store)
        val resolver = SkillResolver(repo, CoroutineScope(dispatcher))
        return resolver to repo
    }

    @Test
    fun `resolve by known id returns that skill`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val skill = resolver.resolve("explain_code")
        assertEquals("explain_code", skill.id)
        assertEquals(SkillCategory.CODE_UNDERSTAND, skill.category)
    }

    @Test
    fun `resolve unknown id falls back to default_chat`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val skill = resolver.resolve("nonexistent_skill")
        assertEquals("default_chat", skill.id)
    }

    @Test
    fun `resolve null falls back to default_chat`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val skill = resolver.resolve(null)
        assertEquals("default_chat", skill.id)
    }

    @Test
    fun `resolveTemporary with valid at-skill switches and strips prefix`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val (skill, text) = resolver.resolveTemporary("@explain_code 解释这段代码", "default_chat")
        assertEquals("explain_code", skill.id)
        assertEquals("解释这段代码", text)
    }

    @Test
    fun `resolveTemporary with at-skill uppercase is case-insensitive`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val (skill, text) = resolver.resolveTemporary("@Explain_Code hello", "default_chat")
        assertEquals("explain_code", skill.id)
        assertEquals("hello", text)
    }

    @Test
    fun `resolveTemporary with unknown at-skill falls back to current skill and keeps text`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val (skill, text) = resolver.resolveTemporary("@unknown_skill do something", "default_chat")
        assertEquals("default_chat", skill.id)
        assertEquals("@unknown_skill do something", text)
    }

    @Test
    fun `resolveTemporary without at-prefix uses current skill`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val (skill, text) = resolver.resolveTemporary("普通消息", "explain_code")
        assertEquals("explain_code", skill.id)
        assertEquals("普通消息", text)
    }

    @Test
    fun `resolveTemporary does not change current skill - only one message scope`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val currentSkillId = "default_chat"
        val (effective, _) = resolver.resolveTemporary("@explain_code test", currentSkillId)
        assertNotEquals(currentSkillId, effective.id)
        assertEquals("explain_code", effective.id)
    }

    @Test
    fun `resolveSystemPrompt default_chat uses global prompt - decision 12 exception`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val skill = BuiltInSkills.default
        val globalPrompt = "你是用户的自定义助手"
        val result = resolver.resolveSystemPrompt(skill, globalPrompt)
        assertEquals(globalPrompt, result)
    }

    @Test
    fun `resolveSystemPrompt non-default skill overrides global prompt - decision 12`() = runTest(dispatcher) {
        val (resolver, _) = newResolver()
        val skill = BuiltInSkills.byId("explain_code")!!
        val globalPrompt = "你是用户的自定义助手"
        val result = resolver.resolveSystemPrompt(skill, globalPrompt)
        assertEquals(skill.systemPrompt, result)
        assertNotEquals(globalPrompt, result)
        assertTrue(result.contains("代码解释专家"))
    }

    @Test
    fun `BuiltInSkills has default_chat explain_code gen_function for Phase 1`() {
        val ids = BuiltInSkills.all.map { it.id }
        assertTrue("default_chat", "default_chat" in ids)
        assertTrue("explain_code", "explain_code" in ids)
        assertTrue("gen_function", "gen_function" in ids)
    }

    @Test
    fun `explain_code skill declares render_mermaid tool`() {
        val skill = BuiltInSkills.byId("explain_code")!!
        assertEquals(1, skill.tools.size)
        assertEquals("render_mermaid", skill.tools[0].name)
    }

    @Test
    fun `default_chat and gen_function have no tools`() {
        assertTrue(BuiltInSkills.default.tools.isEmpty())
        assertTrue(BuiltInSkills.byId("gen_function")!!.tools.isEmpty())
    }

    // ---- Phase 4：自定义 skill 解析 ----

    @Test
    fun `resolve user-defined skill by id returns it with builtIn false`() = runTest(dispatcher) {
        val (resolver, repo) = newResolver()
        val id = repo.create(sampleDef(name = "我的助手"))
        // 等待 resolver 后台 collect 刷新缓存
        resolver.userSkills.first { list -> list.any { it.id == id } }
        val skill = resolver.resolve(id)
        assertEquals(id, skill.id)
        assertEquals("我的助手", skill.name)
        assertTrue("自定义 skill builtIn 应为 false", !skill.builtIn)
    }

    @Test
    fun `resolveTemporary can switch to user-defined skill via at-prefix`() = runTest(dispatcher) {
        val (resolver, repo) = newResolver()
        val id = repo.create(sampleDef(name = "测试自定义"))
        resolver.userSkills.first { list -> list.any { it.id == id } }
        // user_ 前缀 id 含数字/下划线，@正则支持 [a-z0-9_]
        val (skill, text) = resolver.resolveTemporary("@$id 跑一下", "default_chat")
        assertEquals(id, skill.id)
        assertEquals("跑一下", text)
    }

    @Test
    fun `allMerged includes built-in and user skills`() = runTest(dispatcher) {
        val (resolver, repo) = newResolver()
        val before = resolver.allMerged().size
        repo.create(sampleDef(name = "合并测试"))
        resolver.userSkills.first { list -> list.any { it.name == "合并测试" } }
        val after = resolver.allMerged()
        assertEquals(before + 1, after.size)
        assertTrue(after.any { it.name == "合并测试" && !it.builtIn })
    }

    @Test
    fun `resolveSystemPrompt user skill overrides global prompt`() = runTest(dispatcher) {
        val (resolver, repo) = newResolver()
        val id = repo.create(sampleDef(name = "P", systemPrompt = "你是专属助手"))
        resolver.userSkills.first { list -> list.any { it.id == id } }
        val skill = resolver.resolve(id)
        assertEquals("你是专属助手", resolver.resolveSystemPrompt(skill, "全局prompt"))
    }

    private fun sampleDef(
        name: String = "测试",
        systemPrompt: String = "你是测试助手",
        category: SkillCategory = SkillCategory.QA_ASSIST,
        outputFormat: OutputFormat = OutputFormat.MARKDOWN
    ): UserSkillDef = UserSkillDef(
        id = "", name = name, description = "desc", icon = "Star",
        category = category, systemPrompt = systemPrompt,
        tools = emptyList(), outputFormat = outputFormat, styleHints = "",
        createdAtMs = 0L, updatedAtMs = 0L
    )
}
