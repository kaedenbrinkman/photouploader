package com.photopipeline.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ShootEntity::class, FileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shootDao(): ShootDao
    abstract fun fileDao(): FileDao

    companion object {
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE files ADD COLUMN uploadPartsJson TEXT NOT NULL DEFAULT '[]'",
                    )
                    db.execSQL(
                        "ALTER TABLE files ADD COLUMN manifestUploaded INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "photopipeline.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
