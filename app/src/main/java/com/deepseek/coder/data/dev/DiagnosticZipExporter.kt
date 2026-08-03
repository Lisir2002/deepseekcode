package com.deepseek.coder.data.dev

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.deepseek.coder.data.settings.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断 Zip 导出器：将 8 类诊断信息打包成一个 zip 文件写到指定 Uri。
 * 返回写入的文件条目数（写入失败直接抛异常，由上层 try/catch 展示）。
 */
object DiagnosticZipExporter {

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun export(context: Context, out: Uri): Int {
        val resolver = context.contentResolver
        var count = 0
        resolver.openOutputStream(out, "wt")?.use { rawOut ->
            ZipOutputStream(rawOut.buffered()).use { zip ->
                count += zip.writeTextEntry("settings.json", buildSettingsJson(context))
                count += zip.writeTextEntry("chat_snapshot.json", buildChatSnapshot(context))
                count += zip.writeTextEntry("last_plans.json", buildLastPlans(context))
                count += zip.writeTextEntry("app_logs.txt", buildAppLogs(context))
                count += zip.writeTextEntry("prompt_audit.txt", buildPromptAudit(context))
                count += zip.writeTextEntry("usage_stats.json", buildUsageStats(context))
                count += zip.writeTextEntry("device_info.json", buildDeviceInfo(context))
                zip.finish()
            }
        }
        return count
    }

    private fun ZipOutputStream.writeTextEntry(name: String, content: String): Int {
        putNextEntry(ZipEntry(name))
        OutputStreamWriter(this, Charsets.UTF_8).use { it.write(content) }
        closeEntry()
        return 1
    }

    // ---------- 1. settings.json ----------
    private fun buildSettingsJson(context: Context): String {
        val defaults = AppSettings()
        val file = File(context.filesDir, "datastore/app_settings_snapshot.json")
        val snapshot = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        return prettyJson.encodeToString(
            SettingsSnap(
                exported_at = nowIso(),
                defaults = defaults,
                datastore_snapshot_present = !snapshot.isNullOrBlank()
            )
        )
    }

    @Serializable
    private data class SettingsSnap(
        val exported_at: String,
        val defaults: AppSettings,
        val datastore_snapshot_present: Boolean
    )

    // ---------- 2. chat_snapshot.json ----------
    private fun buildChatSnapshot(context: Context): String {
        val db = context.getDatabasePath("deepcoder.db")
        val sessionsDir = File(context.filesDir, "sessions")
        val recentFiles = sessionsDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(10)
            ?.map { f ->
                mapOf(
                    "file" to f.name,
                    "size_bytes" to f.length().toString(),
                    "last_modified" to Date(f.lastModified()).toString()
                )
            }.orEmpty()
        return prettyJson.encodeToString(
            ChatSnapshot(
                exported_at = nowIso(),
                db_exists = db.exists(),
                db_size_bytes = if (db.exists()) db.length() else 0L,
                recent_session_files = recentFiles,
                sample_messages = readRecentMessages(context, 50)
            )
        )
    }

    @Serializable
    private data class ChatSnapshot(
        val exported_at: String,
        val db_exists: Boolean,
        val db_size_bytes: Long,
        val recent_session_files: List<Map<String, String>>,
        val sample_messages: List<Map<String, String>>
    )

    private fun readRecentMessages(context: Context, limit: Int): List<Map<String, String>> {
        val f = File(context.filesDir, "chat_history.jsonl")
        if (!f.exists()) return emptyList()
        val result = ArrayList<Map<String, String>>(limit)
        f.useLines { lines ->
            lines.toList().takeLast(limit).forEach { line ->
                if (line.isNotBlank()) {
                    result += mapOf("raw" to line.take(500))
                }
            }
        }
        return result
    }

    // ---------- 3. last_plans.json ----------
    private fun buildLastPlans(context: Context): String {
        val planDir = File(context.filesDir, "plans")
        val plans = planDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(5)
            ?.map { f ->
                LastPlan(
                    id = f.nameWithoutExtension,
                    size_bytes = f.length(),
                    last_modified = Date(f.lastModified()).toString(),
                    preview = runCatching { f.readText().take(1200) }.getOrDefault("")
                )
            }.orEmpty()
        return prettyJson.encodeToString(
            LastPlansEnvelope(exported_at = nowIso(), plan_count = plans.size, plans = plans)
        )
    }

    @Serializable
    private data class LastPlan(
        val id: String,
        val size_bytes: Long,
        val last_modified: String,
        val preview: String
    )

    @Serializable
    private data class LastPlansEnvelope(
        val exported_at: String,
        val plan_count: Int,
        val plans: List<LastPlan>
    )

    // ---------- 4. app_logs.txt ----------
    private fun buildAppLogs(context: Context): String = buildString {
        append("===== DeepCoder App Logs @ ").append(nowIso()).append(" =====\n\n")
        append("-- tail -n 500 logcat --\n")
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "-v", "threadtime"))
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { seq ->
                seq.forEach { append(it).append('\n') }
            }
            proc.waitFor()
        }
        append('\n')
        append("-- internal log files under filesDir/logs/ --\n")
        val logDir = File(context.filesDir, "logs")
        if (logDir.exists() && logDir.isDirectory) {
            logDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(5)?.forEach { f ->
                append("--- ").append(f.name).append(" (").append(f.length()).append(" bytes) ---\n")
                runCatching {
                    val txt = f.readText()
                    append(txt.takeLast(8000)).append('\n')
                }
            }
        } else {
            append("(no log dir found)\n")
        }
    }

    // ---------- 5. prompt_audit.txt ----------
    private fun buildPromptAudit(context: Context): String = buildString {
        append("===== DeepCoder Prompt Audit Trail @ ").append(nowIso()).append(" =====\n\n")
        val auditDir = File(context.filesDir, "prompt_audit")
        if (!auditDir.exists() || !auditDir.isDirectory) {
            append("(no prompt_audit dir found; first export or feature gated off)\n")
            return@buildString
        }
        val files = auditDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(20).orEmpty()
        append("Total audit files: ").append(files.size).append(" (showing latest 20)\n\n")
        files.forEachIndexed { i, f ->
            append("--- #").append(i).append(' ').append(f.name)
                .append(' ').append(Date(f.lastModified()))
                .append(" size=").append(f.length()).append(" ---\n")
            runCatching { append(f.readText().take(2000)).append('\n') }
            append('\n')
        }
    }

    // ---------- 7. usage_stats.json ----------
    private fun buildUsageStats(context: Context): String {
        val usageFile = File(context.filesDir, "usage_stats.json")
        val onDisk = if (usageFile.exists()) runCatching { usageFile.readText().take(4000) }.getOrNull() else null
        val prefs = context.getSharedPreferences("deepcoder_usage", Context.MODE_PRIVATE)
        val allEntries = prefs.all.mapKeys { it.key }.mapValues { (it.value as? Any?)?.toString().orEmpty() }
        return prettyJson.encodeToString(
            UsageStats(
                exported_at = nowIso(),
                from_shared_prefs = allEntries,
                from_disk_json = onDisk
            )
        )
    }

    @Serializable
    private data class UsageStats(
        val exported_at: String,
        val from_shared_prefs: Map<String, String>,
        val from_disk_json: String?
    )

    // ---------- 8. device_info.json ----------
    private fun buildDeviceInfo(context: Context): String {
        val displayMetrics = context.resources.displayMetrics
        val ai = context.applicationInfo
        return prettyJson.encodeToString(
            DeviceInfo(
                exported_at = nowIso(),
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                device = Build.DEVICE,
                product = Build.PRODUCT,
                sdk_int = Build.VERSION.SDK_INT,
                android_release = Build.VERSION.RELEASE,
                build_id = Build.DISPLAY,
                fingerprint = Build.FINGERPRINT,
                supported_abis = Build.SUPPORTED_ABIS.toList(),
                screen_density_dpi = displayMetrics.densityDpi,
                screen_width_px = displayMetrics.widthPixels,
                screen_height_px = displayMetrics.heightPixels,
                screen_scaled_density = displayMetrics.scaledDensity,
                app_targetSdk = ai.targetSdkVersion,
                app_minSdk = android.content.pm.ApplicationInfo().minSdkVersion.let { 0 },
                internal_storage_bytes_free = context.filesDir.freeSpace,
                internal_storage_bytes_total = context.filesDir.totalSpace,
                android_id_hex = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull()
            )
        )
    }

    @Serializable
    private data class DeviceInfo(
        val exported_at: String,
        val model: String,
        val manufacturer: String,
        val brand: String,
        val device: String,
        val product: String,
        val sdk_int: Int,
        val android_release: String,
        val build_id: String,
        val fingerprint: String,
        val supported_abis: List<String>,
        val screen_density_dpi: Int,
        val screen_width_px: Int,
        val screen_height_px: Int,
        val screen_scaled_density: Float,
        val app_targetSdk: Int,
        val app_minSdk: Int,
        val internal_storage_bytes_free: Long,
        val internal_storage_bytes_total: Long,
        val android_id_hex: String?
    )

    private fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return fmt.format(Date())
    }
}
