package com.deepseek.coder.data.skill

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.di.AttachedRootDir
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附加文件沙箱仓库（SPEC-Skill-v1.2 §4.2 / §4.4）。
 *
 * 用户通过文件选择器导入的文件被复制到 App 沙箱 `filesDir/attached/` 目录，
 * 工具只能读取该目录下的文件；路径穿越（`../`、绝对路径）一律拒绝。
 *
 * 文件名规则：保留原名，重名时追加短 UUID 后缀避免覆盖。
 *
 * 构造函数注入 [rootDirFile]（由 DI 提供 `filesDir/attached`），便于单元测试替换为临时目录。
 */
@Singleton
class AttachedFileRepository @Inject constructor(
    @AttachedRootDir private val rootDirFile: File,
    private val dispatchers: DispatcherProvider
) {

    private val rootDir: File by lazy { rootDirFile.apply { if (!exists()) mkdirs() } }

    /** 沙箱根目录（绝对路径，用于路径穿越校验前缀比对）。 */
    fun rootDirCanonical(): File = rootDir.canonicalFile

    /**
     * 把输入流落盘到沙箱目录，返回最终的 [AttachedFile] 描述。
     *
     * @param originalName 用户选中的原始文件名（用于显示与扩展名保留）
     * @param stream 文件内容输入流（来自 SAF）
     * @param maxBytes 最大允许字节数，超出拒绝（避免内存爆炸）
     */
    suspend fun save(originalName: String, stream: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): AttachedFile? =
        withContext(dispatchers.io) {
            val safeName = sanitizeName(originalName) ?: return@withContext null
            val target = uniqueTarget(safeName)
            var oversize = false
            runCatching {
                stream.use { input ->
                    var written = 0L
                    target.outputStream().use { out ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > maxBytes) {
                                oversize = true
                                return@use
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }.getOrElse {
                AppLogger.w(it, "AttachedFile: save failed for %s", safeName)
                target.delete()
                return@withContext null
            }
            if (oversize) {
                target.delete()
                AppLogger.w(null, "AttachedFile: %s exceeds %d bytes, rejected", safeName, maxBytes)
                return@withContext null
            }
            AttachedFile(
                name = target.name,
                displayName = safeName,
                sizeBytes = target.length(),
                path = target.name // 只暴露相对名，避免 UI 拿到绝对路径乱传
            )
        }

    /** 列出当前沙箱内所有附加文件。 */
    suspend fun list(): List<AttachedFile> = withContext(dispatchers.io) {
        rootDir.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { AttachedFile(it.name, it.name, it.length(), it.name) }
            ?: emptyList()
    }

    /**
     * 按相对名读取文件，做路径穿越防护（§4.4）。
     *
     * @param relativeName 沙箱内相对名（如 "Foo.kt"）
     * @return 文件 [File]（已校验在沙箱内）；非法返回 null
     */
    suspend fun resolve(relativeName: String): File? = withContext(dispatchers.io) {
        if (relativeName.isBlank()) return@withContext null
        // 拒绝绝对路径、穿越符号
        if (relativeName.startsWith("/")) return@withContext null
        if (relativeName.contains("..")) return@withContext null
        if (relativeName.contains(File.separator)) return@withContext null // 只允许单层
        val candidate = File(rootDir, relativeName).canonicalFile
        val root = rootDir.canonicalFile
        // 二次校验：canonical 路径必须以 root 开头
        if (!candidate.path.startsWith(root.path + File.separator) && candidate.path != root.path) {
            AppLogger.w(null, "AttachedFile: path traversal blocked for %s", relativeName)
            return@withContext null
        }
        if (!candidate.exists() || !candidate.isFile) return@withContext null
        candidate
    }

    /** 删除指定附加文件。 */
    suspend fun delete(relativeName: String): Boolean = withContext(dispatchers.io) {
        resolve(relativeName)?.delete() ?: false
    }

    /** 清空所有附加文件（清空会话时调用）。 */
    suspend fun clear() = withContext(dispatchers.io) {
        rootDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun sanitizeName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        // 仅保留字母数字点下划线连字符，禁止路径分隔符
        val cleaned = trimmed.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
        return cleaned
    }

    private fun uniqueTarget(safeName: String): File {
        var target = File(rootDir, safeName)
        if (!target.exists()) return target
        // 重名：追加短 UUID
        val dot = safeName.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) safeName.substring(0, dot) to safeName.substring(dot) else safeName to ""
        target = File(rootDir, "${stem}_${UUID.randomUUID().toString().take(6)}$ext")
        return target
    }

    companion object {
        const val DIR_NAME = "attached"
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 // 2MB
    }
}

/** 沙箱内附加文件描述（UI 展示 + 工具入参用 path 字段）。 */
data class AttachedFile(
    val name: String,        // 沙箱内实际文件名（可能带 UUID 后缀）
    val displayName: String, // 显示名（原文件名清洗后）
    val sizeBytes: Long,
    val path: String         // 等于 name，工具入参用（仅相对名）
)
