package com.example.avatar.sdk.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface AvatarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(record: AvatarRecord)
    @Query("SELECT * FROM avatar_assets ORDER BY createdAt DESC") fun list(): List<AvatarRecord>
    @Query("SELECT * FROM avatar_assets WHERE id = :id LIMIT 1") fun find(id: String): AvatarRecord?
    @Query("DELETE FROM avatar_assets WHERE id = :id") fun delete(id: String)
}

@Dao
interface GenerationJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(record: GenerationJobRecord)
    @Query("SELECT * FROM generation_jobs WHERE jobId = :jobId LIMIT 1") fun find(jobId: String): GenerationJobRecord?
    @Query("SELECT * FROM generation_jobs ORDER BY updatedAt DESC") fun list(): List<GenerationJobRecord>
}

@Database(entities = [AvatarRecord::class, GenerationJobRecord::class], version = 2, exportSchema = false)
abstract class AvatarDatabase : RoomDatabase() {
    abstract fun avatars(): AvatarDao
    abstract fun jobs(): GenerationJobDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN providerId TEXT")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN generationJobId TEXT")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN state TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN validationStatus TEXT NOT NULL DEFAULT 'VALID'")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN localAssetPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE avatar_assets ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 2")
                db.execSQL("CREATE TABLE IF NOT EXISTS generation_jobs (jobId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, modelId TEXT, state TEXT NOT NULL, assetId TEXT, errorCode TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, schemaVersion INTEGER NOT NULL)")
            }
        }

        fun open(context: Context): AvatarDatabase = Room.databaseBuilder(context.applicationContext, AvatarDatabase::class.java, "avatar-engine.db")
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
    }
}
