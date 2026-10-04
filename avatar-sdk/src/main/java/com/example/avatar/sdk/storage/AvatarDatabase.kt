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

@Dao
interface StudioDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertProject(record: ProjectRecord)
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun listProjects(): List<ProjectRecord>
    @Query("SELECT * FROM projects WHERE projectId = :id LIMIT 1") fun findProject(id: String): ProjectRecord?
    @Query("DELETE FROM projects WHERE projectId = :id") fun deleteProject(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertScene(record: SceneRecord)
    @Query("SELECT * FROM scenes WHERE projectId = :projectId ORDER BY updatedAt DESC") fun listScenes(projectId: String): List<SceneRecord>
    @Query("SELECT * FROM scenes WHERE sceneId = :id LIMIT 1") fun findScene(id: String): SceneRecord?
    @Query("DELETE FROM scenes WHERE sceneId = :id") fun deleteScene(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertReference(record: ReferenceRecord): Long
    @Query("SELECT * FROM reference_assets WHERE projectId = :projectId ORDER BY importedAt DESC") fun listReferences(projectId: String): List<ReferenceRecord>
    @Query("SELECT * FROM reference_assets WHERE referenceId = :id LIMIT 1") fun findReference(id: String): ReferenceRecord?
    @Query("SELECT * FROM reference_assets WHERE checksum = :checksum LIMIT 1") fun findReferenceByChecksum(checksum: String): ReferenceRecord?
    @Query("DELETE FROM reference_assets WHERE referenceId = :id") fun deleteReference(id: String)
}

@Database(entities = [AvatarRecord::class, GenerationJobRecord::class, ProjectRecord::class, SceneRecord::class, ReferenceRecord::class], version = 3, exportSchema = false)
abstract class AvatarDatabase : RoomDatabase() {
    abstract fun avatars(): AvatarDao
    abstract fun jobs(): GenerationJobDao
    abstract fun studio(): StudioDao

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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS projects (projectId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, settingsJson TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, schemaVersion INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_updatedAt ON projects(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS scenes (sceneId TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, name TEXT NOT NULL, sceneJson TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, schemaVersion INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scenes_projectId ON scenes(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scenes_updatedAt ON scenes(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reference_assets (referenceId TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, originalName TEXT NOT NULL, mimeType TEXT NOT NULL, category TEXT NOT NULL, byteSize INTEGER NOT NULL, checksum TEXT NOT NULL, importedAt INTEGER NOT NULL, description TEXT, tags TEXT NOT NULL, storagePath TEXT NOT NULL, previewStatus TEXT NOT NULL, lineCount INTEGER, schemaVersion INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_assets_projectId ON reference_assets(projectId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reference_assets_checksum ON reference_assets(checksum)")
            }
        }

        fun open(context: Context): AvatarDatabase = Room.databaseBuilder(context.applicationContext, AvatarDatabase::class.java, "avatar-engine.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
    }
}
