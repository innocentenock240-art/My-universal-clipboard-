package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.database.dao.ClipboardItemDao
import com.example.data.database.entity.ClipboardItemEntity

@Database(
    entities = [ClipboardItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ClipboardDatabase : RoomDatabase() {

    abstract fun clipboardItemDao(): ClipboardItemDao

    companion object {
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null

        fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClipboardDatabase::class.java,
                    "universal_clipboard.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
