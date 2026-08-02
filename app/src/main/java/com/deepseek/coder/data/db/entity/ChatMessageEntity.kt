package com.deepseek.coder.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val role: String,
    val text: String,
    val reasoning: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val pending: Boolean = false,
    val sortOrder: Int = 0
)
