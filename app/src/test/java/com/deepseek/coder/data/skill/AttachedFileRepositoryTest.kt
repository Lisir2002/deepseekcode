package com.deepseek.coder.data.skill

import com.deepseek.coder.core.DispatcherProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * AttachedFileRepository 单元测试（SPEC-Skill-v1.2 §4.4 路径穿越防护）。
 *
 * 用 JDK 临时目录模拟 App 沙箱 `filesDir/attached/`，避免依赖 Android Context。
 */
class AttachedFileRepositoryTest {

    private lateinit var rootDir: java.io.File
    private lateinit var repo: AttachedFileRepository

    @Before
    fun setup() {
        rootDir = Files.createTempDirectory("attached_test").toFile()
        val dispatcher = object : DispatcherProvider {
            override val main = UnconfinedTestDispatcher()
            override val io = UnconfinedTestDispatcher()
            override val default = UnconfinedTestDispatcher()
        }
        repo = AttachedFileRepository(rootDir, dispatcher)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `save and resolve roundtrip`() = runTest {
        val content = "fun main() { println(1) }"
        val saved = repo.save("Main.kt", ByteArrayInputStream(content.toByteArray()))
        assertNotNull(saved)
        val file = repo.resolve(saved!!.path)
        assertNotNull(file)
        assertEquals(content, file!!.readText())
    }

    // ===== §4.4 路径穿越防护 =====

    @Test
    fun `path traversal with dotdot is rejected`() = runTest {
        repo.save("legit.kt", ByteArrayInputStream("x".toByteArray()))
        val resolved = repo.resolve("../../etc/passwd")
        assertNull("../../../etc/passwd must be rejected", resolved)
    }

    @Test
    fun `absolute path is rejected`() = runTest {
        repo.save("legit.kt", ByteArrayInputStream("x".toByteArray()))
        val resolved = repo.resolve("/etc/passwd")
        assertNull("absolute path must be rejected", resolved)
    }

    @Test
    fun `subdirectory path is rejected`() = runTest {
        repo.save("legit.kt", ByteArrayInputStream("x".toByteArray()))
        val resolved = repo.resolve("subdir/legit.kt")
        assertNull("subdirectory path must be rejected (only single-level allowed)", resolved)
    }

    @Test
    fun `blank path is rejected`() = runTest {
        val resolved = repo.resolve("")
        assertNull(resolved)
    }

    @Test
    fun `nonexistent file returns null`() = runTest {
        val resolved = repo.resolve("does_not_exist.kt")
        assertNull(resolved)
    }

    // ===== 文件名清洗 =====

    @Test
    fun `filename with special chars is sanitized`() = runTest {
        val saved = repo.save("我的 文件 (1).kt", ByteArrayInputStream("x".toByteArray()))
        assertNotNull(saved)
        assertFalse("display name should be sanitized", saved!!.displayName.contains(" "))
        assertFalse("name should not contain parens", saved.name.contains("("))
    }

    @Test
    fun `duplicate filename gets uuid suffix`() = runTest {
        val a = repo.save("Dup.kt", ByteArrayInputStream("a".toByteArray()))!!
        val b = repo.save("Dup.kt", ByteArrayInputStream("b".toByteArray()))!!
        assertTrue("duplicate should get different names", a.name != b.name)
        assertEquals("both readable", "a", repo.resolve(a.name)!!.readText())
        assertEquals("both readable", "b", repo.resolve(b.name)!!.readText())
    }

    // ===== 大小限制 =====

    @Test
    fun `file exceeding max bytes is rejected`() = runTest {
        val big = ByteArray(100) { 'x'.code.toByte() }
        val saved = repo.save("big.txt", ByteArrayInputStream(big), maxBytes = 50)
        assertNull("file over maxBytes should be rejected", saved)
    }

    // ===== list / delete =====

    @Test
    fun `list returns all saved files`() = runTest {
        repo.save("a.kt", ByteArrayInputStream("a".toByteArray()))
        repo.save("b.kt", ByteArrayInputStream("bb".toByteArray()))
        val list = repo.list()
        assertEquals(2, list.size)
    }

    @Test
    fun `delete removes file`() = runTest {
        val saved = repo.save("del.kt", ByteArrayInputStream("x".toByteArray()))!!
        assertTrue(repo.delete(saved.path))
        assertNull(repo.resolve(saved.path))
    }

    @Test
    fun `clear removes all files`() = runTest {
        repo.save("a.kt", ByteArrayInputStream("a".toByteArray()))
        repo.save("b.kt", ByteArrayInputStream("b".toByteArray()))
        repo.clear()
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `delete with traversal path returns false`() = runTest {
        assertFalse(repo.delete("../../etc/passwd"))
    }
}
