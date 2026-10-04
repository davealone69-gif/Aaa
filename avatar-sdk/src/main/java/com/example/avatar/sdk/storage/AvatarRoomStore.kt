package com.example.avatar.sdk.storage

import android.content.Context
import com.example.avatar.sdk.api.AvatarAsset
import com.example.avatar.sdk.api.AvatarMetadata
import java.io.File

class AvatarRoomStore(context: Context) {
    private val db = AvatarDatabase.open(context)

    fun recordImported(asset: AvatarAsset) {
        db.avatars().upsert(AvatarRecord(asset.metadata.id, asset.metadata.name, asset.metadata.source, null, null, null, "COMPLETED", asset.metadata.validationStatus, asset.glb.absolutePath, asset.metadata.createdAt, asset.metadata.version))
    }

    fun recordGenerated(asset: AvatarAsset, providerId: String, modelId: String?, jobId: String?) {
        db.avatars().upsert(AvatarRecord(asset.metadata.id, asset.metadata.name, asset.metadata.source, providerId, modelId, jobId, "COMPLETED", asset.metadata.validationStatus, asset.glb.absolutePath, asset.metadata.createdAt, asset.metadata.version))
    }

    fun list(): List<AvatarRecord> = db.avatars().list()
    fun find(id: String): AvatarRecord? = db.avatars().find(id)
    fun delete(id: String) = db.avatars().delete(id)
    fun recordJob(job: GenerationJobRecord) = db.jobs().upsert(job)
    fun findJob(id: String): GenerationJobRecord? = db.jobs().find(id)
    fun close() = db.close()
}
