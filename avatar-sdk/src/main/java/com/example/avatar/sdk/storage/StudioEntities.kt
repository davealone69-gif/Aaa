package com.example.avatar.sdk.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects", indices = [Index(value = ["updatedAt"])])
data class ProjectRecord(
    @PrimaryKey val projectId: String,
    val name: String,
    val description: String,
    val settingsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = 3
)

@Entity(tableName = "scenes", indices = [Index(value = ["projectId"]), Index(value = ["updatedAt"])])
data class SceneRecord(
    @PrimaryKey val sceneId: String,
    val projectId: String,
    val name: String,
    val sceneJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = 3
)

@Entity(tableName = "reference_assets", indices = [Index(value = ["projectId"]), Index(value = ["checksum"], unique = true)])
data class ReferenceRecord(
    @PrimaryKey val referenceId: String,
    val projectId: String,
    val originalName: String,
    val mimeType: String,
    val category: String,
    val byteSize: Long,
    val checksum: String,
    val importedAt: Long,
    val description: String?,
    val tags: String,
    val storagePath: String,
    val previewStatus: String,
    val lineCount: Int?,
    val schemaVersion: Int = 3
)
