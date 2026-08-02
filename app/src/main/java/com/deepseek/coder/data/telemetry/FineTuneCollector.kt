package com.deepseek.coder.data.telemetry

import android.content.Context
import com.deepseek.coder.data.settings.AppSettings
import com.deepseek.coder.data.settings.SettingsRepository
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.models.ChatSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local on-device JSONL collector for LoRA training data.
 *
 * IMPORTANT: data never leaves the user's device unless they explicitly
 * share/export the file from storage (the UI does not expose a network upload).
 * We default to disabled and explicitly surface a Switch in the settings page
 * to obtain user consent before any recording starts.
 *
 * The JSONL schema intentionally matches DeepSeek chat completions fine-tune
 * format so the file can be sent verbatim for enterprise LoRA jobs:
 *   {"messages":[{"role":"system","content":"..."}, {"role":"user","content":"..."}, {"role":"assistant","content":"..."}]}
 */
@Singleton
class FineTuneCollector @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = false }

    private val targetFile: File by lazy {
        File(ctx.filesDir, "deepcoder_ft_samples.jsonl").apply {
            if (!exists()) createNewFile()
        }
    }

    val absolutePath: String get() = targetFile.absolutePath

    suspend fun recordConversation(
        session: ChatSession,
        messages: List<ChatMessage>,
        finalAssistant: ChatMessage?
    ) {
        val s: AppSettings = settings.settings.first()
        if (!s.fineTuneDataCollectionEnabled) return
        val system = AppSettings.DEFAULT_SYSTEM_PROMPT
        val trainableMessages = buildList<FtMessage> {
            messages.filter {
                it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT
            }.forEach { if (it.text.isNotBlank()) add(FtMessage(role = it.role.value, content = it.text.take(6000))) }
            if (finalAssistant != null && finalAssistant.text.isNotBlank()) {
                add(FtMessage(role = finalAssistant.role.value, content = finalAssistant.text.take(6000)))
            }
        }.takeLast(40) // keep training examples short to match LoRA context budget

        if (trainableMessages.isEmpty()) return
        val record = FtRecord(
            session_title = session.title.takeIf { it.isNotBlank() } ?: "session-${session.id}",
            messages = buildList {
                add(FtMessage(role = "system", content = system.take(1500)))
                addAll(trainableMessages)
            }
        )
        mutex.withLock {
            targetFile.appendText(json.encodeToString(record) + "\n")
        }
    }

    suspend fun countRecords(): Int = mutex.withLock {
        if (!targetFile.exists()) return 0
        targetFile.useLines { seq -> seq.count { it.isNotBlank() } }
    }

    @Serializable
    data class FtRecord(
        val session_title: String,
        val messages: List<FtMessage>
    )

    @Serializable
    data class FtMessage(val role: String, val content: String)
}
