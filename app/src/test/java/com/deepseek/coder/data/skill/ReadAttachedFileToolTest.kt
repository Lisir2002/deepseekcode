package com.deepseek.coder.data.skill

import com.deepseek.coder.core.DispatcherProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * ReadAttachedFileTool 单元测试（SPEC-Skill-v1.2 §4.2 / §4.4）。
 *
 * 覆盖：正常读取、二进制拒绝、路径穿越拒绝、截断、空 path。
 */
class ReadAttachedFileToolTest {

    private lateinit var rootDir: java.io.File
    private lateinit var repo: AttachedFileRepository
    private lateinit var tool: ReadAttachedFileTool

    @Before
    fun setup() {
        rootDir = Files.createTempDirectory("read_tool_test").toFile()
        val dispatcher = object : DispatcherProvider {
            override val main = UnconfinedTestDispatcher()
            override val io = UnconfinedTestDispatcher()
            override val default = UnconfinedTestDispatcher()
        }
        repo = AttachedFileRepository(rootDir, dispatcher)
        tool = ReadAttachedFileTool(repo)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun args(path: String): JsonObject = buildJsonObject { put("path", path) }

    @Test
    fun `reads text file content`() = runTest {
        val saved = repo.save("Foo.kt", ByteArrayInputStream("fun foo() = 1".toByteArray()))!!
        val result = tool.execute(args(saved.path))
        assertTrue("expected Success, got $result", result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue(content.contains("fun foo() = 1"))
        assertTrue(content.contains("Foo.kt"))
    }

    @Test
    fun `binary file returns metadata only`() = runTest {
        val saved = repo.save("img.png", ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())))!!
        val result = tool.execute(args(saved.path))
        assertTrue("expected Success", result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue("should mention binary not read", content.contains("二进制文件未读取内容"))
        assertTrue("should include ext", content.contains("png"))
    }

    @Test
    fun `path traversal rejected`() = runTest {
        repo.save("legit.kt", ByteArrayInputStream("x".toByteArray()))
        val result = tool.execute(args("../../etc/passwd"))
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("路径非法"))
    }

    @Test
    fun `nonexistent file returns Failure`() = runTest {
        val result = tool.execute(args("ghost.kt"))
        assertTrue("expected Failure", result is ToolResult.Failure)
    }

    @Test
    fun `blank path returns Failure`() = runTest {
        val result = tool.execute(args(""))
        assertTrue("expected Failure", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("path"))
    }

    @Test
    fun `large file is truncated`() = runTest {
        // 故意调小 MAX_READ_CHARS 通过反射不便，改为构造超过 256KB 的内容验证截断标注
        val big = ByteArray(ReadAttachedFileTool.MAX_READ_CHARS + 100) { 'a'.code.toByte() }
        val saved = repo.save("big.txt", ByteArrayInputStream(big))!!
        val result = tool.execute(args(saved.path))
        assertTrue("expected Success", result is ToolResult.Success)
        val content = (result as ToolResult.Success).content
        assertTrue("should be truncated", content.contains("已截断"))
    }
}
