package com.example.avatar.sdk.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "avatar_assets")
data class AvatarRecord(
    @PrimaryKey val id: String,
    val name: String,
    val source: String,
    val providerId: String?,
    val modelId: String?,
    val generationJobId: String?,
    val state: String,
    val validationStatus: String,
    val localAssetPath: String,
    val createdAt: String,
    val schemaVersion: Int
)

@Entity(tableName = "generation_jobs")
data class GenerationJobRecord(
    @PrimaryKey val jobId: String,
    val providerId: String,
    val modelId: String?,
    val state: String,
    val assetId: String?,
    val errorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int
)
