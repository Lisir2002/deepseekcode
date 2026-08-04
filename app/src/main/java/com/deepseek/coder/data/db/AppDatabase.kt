package com.deepseek.coder.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.deepseek.coder.data.db.dao.ChatDao
import com.deepseek.coder.data.db.entity.ChatMessageEntity
import com.deepseek.coder.data.db.entity.ChatSessionEntity

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val DB_NAME = "deepcoder_db"
    }
}
