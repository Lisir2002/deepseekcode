package com.deepseek.coder.data.skill

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.deepseek.coder.domain.skill.OutputFormat
import com.deepseek.coder.domain.skill.SkillCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UserSkillRepository 单元测试（Phase 4，SPEC §7）。
 *
 * 覆盖：create/update/delete/byId/snapshot/export/import 循环、
 * 同名导入去重、id 冲突重新生成。
 */
class UserSkillRepositoryTest {

    private fun newRepo() = UserSkillRepository(
        PreferenceDataStoreFactory.create(produceFile = { File.createTempFile("usertest", ".preferences_pb") })
    )

    private fun def(name: String = "S1", id: String = "", prompt: String = "你是助手") = UserSkillDef(
        id = id, name = name, description = "d", icon = "Star",
        category = SkillCategory.QA_ASSIST, systemPrompt = prompt,
        tools = emptyList(), outputFormat = OutputFormat.MARKDOWN, styleHints = "",
        createdAtMs = 0L, updatedAtMs = 0L
    )

    @Test
    fun `create assigns id with user_ prefix and persists`() = runTest {
        val repo = newRepo()
        val id = repo.create(def(name = "我的Skill"))
        assertTrue("id 应带 user_ 前缀", UserSkillDef.isUserSkill(id))
        val snap = repo.snapshot()
        assertEquals(1, snap.size)
        assertEquals("我的Skill", snap[0].name)
        assertTrue(snap[0].createdAtMs > 0)
    }

    @Test
    fun `byId returns created skill and null for unknown`() = runTest {
        val repo = newRepo()
        val id = repo.create(def())
        assertNotNull(repo.byId(id))
        assertNull(repo.byId("user_nonexistent"))
    }

    @Test
    fun `update modifies existing skill and bumps updatedAtMs`() = runTest {
        val repo = newRepo()
        val id = repo.create(def(name = "旧名"))
        val original = repo.byId(id)!!
        repo.update(original.copy(name = "新名", systemPrompt = "新prompt"))
        val updated = repo.byId(id)!!
        assertEquals("新名", updated.name)
        assertEquals("新prompt", updated.systemPrompt)
        assertTrue("updatedAtMs 应刷新", updated.updatedAtMs >= original.updatedAtMs)
    }

    @Test
    fun `delete removes skill`() = runTest {
        val repo = newRepo()
        val id = repo.create(def())
        repo.delete(id)
        assertEquals(0, repo.snapshot().size)
        assertNull(repo.byId(id))
    }

    @Test
    fun `userSkills flow emits changes`() = runTest {
        val repo = newRepo()
        assertEquals(0, repo.userSkills.first().size)
        repo.create(def(name = "A"))
        repo.create(def(name = "B"))
        assertEquals(2, repo.userSkills.first().size)
    }

    @Test
    fun `exportAll returns valid JSON and importFromString restores`() = runTest {
        val repo = newRepo()
        repo.create(def(name = "导出测试", prompt = "P1"))
        repo.create(def(name = "另一个", prompt = "P2"))
        val json = repo.exportAll()
        assertTrue(json.contains("导出测试"))
        assertTrue(json.contains("另一个"))

        // 导入到新 repo
        val repo2 = newRepo()
        val n = repo2.importFromString(json)
        assertEquals(2, n)
        val snap = repo2.snapshot()
        assertTrue(snap.any { it.name == "导出测试" && it.systemPrompt == "P1" })
    }

    @Test
    fun `import skips same-name skills to avoid duplicate`() = runTest {
        val repo = newRepo()
        repo.create(def(name = "重名", prompt = "原"))
        val toImport = listOf(def(name = "重名", prompt = "新"))
        val n = repo.importAll(toImport)
        assertEquals("同名应跳过", 0, n)
        val snap = repo.snapshot()
        assertEquals(1, snap.size)
        assertEquals("原", snap[0].systemPrompt)
    }

    @Test
    fun `import regenerates id when conflict with existing`() = runTest {
        val repo = newRepo()
        repo.create(def(id = "user_fixedid", name = "A"))
        val toImport = listOf(def(id = "user_fixedid", name = "B")) // 同 id 不同名
        val n = repo.importAll(toImport)
        assertEquals("不同名应导入", 1, n)
        val snap = repo.snapshot()
        assertEquals(2, snap.size)
        // 导入项 id 应被重新生成（不与现有 user_fixedid 冲突）
        assertFalse(snap.any { it.id == "user_fixedid" && it.name == "B" })
        assertTrue(snap.any { it.name == "B" && it.id != "user_fixedid" })
    }

    @Test
    fun `importFromString returns 0 on invalid JSON`() = runTest {
        val repo = newRepo()
        val n = repo.importFromString("not a json")
        assertEquals(0, n)
        assertEquals(0, repo.snapshot().size)
    }

    @Test
    fun `isUserSkill detects prefix correctly`() {
        assertTrue(UserSkillDef.isUserSkill("user_abc123"))
        assertFalse(UserSkillDef.isUserSkill("code_review"))
        assertFalse(UserSkillDef.isUserSkill(null))
    }

    @Test
    fun `newId has user_ prefix`() {
        val id = UserSkillDef.newId()
        assertTrue(id.startsWith(UserSkillDef.ID_PREFIX))
        assertTrue(id.length > UserSkillDef.ID_PREFIX.length)
    }
}
