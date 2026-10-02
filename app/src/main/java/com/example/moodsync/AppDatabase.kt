package com.example.moodsync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(entities = [HistoryItem::class, PlaylistItem::class, PlaylistMetadata::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistMetadataDao(): PlaylistMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the new playlist_metadata table
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_metadata` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                
                // 2. Add playlistId column to existing playlist table
                db.execSQL("ALTER TABLE `playlist` ADD COLUMN `playlistId` INTEGER NOT NULL DEFAULT 0")
                
                // 3. Insert default "My Favorites" playlist with ID 1
                // We'll use 1 as the default ID for My Favorites
                db.execSQL("INSERT INTO `playlist_metadata` (id, name, createdAt) VALUES (1, 'My Favorites', ${System.currentTimeMillis()})")
                
                // 4. Update existing songs to belong to the "My Favorites" playlist (ID 1)
                db.execSQL("UPDATE `playlist` SET `playlistId` = 1")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moodsync_database"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
