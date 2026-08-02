package com.deepseek.coder.data

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.data.db.dao.ChatDao
import com.deepseek.coder.data.db.toDomain
import com.deepseek.coder.data.db.toEntity
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val chatDao: ChatDao
) {
    fun observeSessions(): Flow<List<ChatSession>> =
        chatDao.observeSessions().map { list -> list.map { it.toDomain() } }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessages(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getAllSessions(): List<ChatSession> =
        chatDao.getAllSessions().map { it.toDomain() }

    suspend fun getMessages(sessionId: String): List<ChatMessage> =
        chatDao.getMessages(sessionId).map { it.toDomain() }

    suspend fun getSession(sessionId: String): ChatSession? =
        chatDao.getSession(sessionId)?.toDomain()

    suspend fun createSession(title: String? = null, systemPrompt: String? = null): ChatSession {
        val id = "s_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = id,
            title = title?.ifBlank { null } ?: "新会话",
            systemPrompt = systemPrompt,
            createdAtMs = now,
            updatedAtMs = now,
            messageCount = 0,
            cumulativeTokens = 0L
        )
        chatDao.insertSession(session.toEntity())
        return session
    }

    suspend fun saveSnapshot(session: ChatSession, messages: List<ChatMessage>) {
        runCatching {
            val msgEntities = messages.mapIndexed { i, m -> m.toEntity(session.id, i) }
            val now = System.currentTimeMillis()
            val count = messages.size
            val title = deriveTitle(messages)
            val updated = session.copy(
                title = title,
                updatedAtMs = now,
                messageCount = count
            )
            chatDao.upsertSessionWithMessages(updated.toEntity(), msgEntities)
        }.onFailure {
            AppLogger.w(it, "saveSnapshot failed for session %s", session.id)
        }
    }

    suspend fun touchAndAddTokens(sessionId: String, deltaTokens: Long) {
        val session = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(
            session.copy(
                updatedAtMs = System.currentTimeMillis(),
                cumulativeTokens = session.cumulativeTokens + deltaTokens.coerceAtLeast(0L)
            )
        )
    }

    suspend fun deleteSession(sessionId: String) {
        runCatching { chatDao.deleteSession(sessionId) }
            .onFailure { AppLogger.w(it, "deleteSession failed") }
    }

    suspend fun deleteAllSessions() {
        runCatching { chatDao.deleteAllSessions() }
            .onFailure { AppLogger.w(it, "deleteAllSessions failed") }
    }

    private fun deriveTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == com.deepseek.coder.domain.models.ChatRole.USER }
        val raw = firstUser?.text?.trim().orEmpty()
        return if (raw.isBlank()) "新会话" else raw.take(40).let {
            if (it.length < raw.length) "$it…" else it
        }
    }
}
