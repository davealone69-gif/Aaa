package com.example.avatar.sdk.generation

import com.example.avatar.sdk.api.*
import com.example.avatar.sdk.storage.GenerationJobRecord
import com.example.avatar.sdk.storage.AvatarRoomStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GenerationManager(private val providers: List<AvatarGenerationProvider>, private val room: AvatarRoomStore? = null) {
    private val heavyJob = Mutex()
    suspend fun statuses() = providers.map { it.status() }
    fun choose(request: AvatarRequest): AvatarGenerationProvider? = providers.firstOrNull { provider ->
        (request.image != null && (GenerationCapability.IMAGE_TO_3D in provider.capabilities || GenerationCapability.IMAGE_TO_IMAGE_3D in provider.capabilities)) ||
            (request.prompt != null && GenerationCapability.TEXT_TO_3D in provider.capabilities)
    }
    suspend fun generate(request: AvatarRequest): Result<AvatarAsset> {
        val provider = choose(request) ?: return Result.failure(IllegalStateException("3D_BACKEND_UNAVAILABLE: no installed provider supports this request"))
        return heavyJob.withLock {
            val handle = provider.generate(request)
            val now = System.currentTimeMillis()
            room?.recordJob(GenerationJobRecord(handle.id, provider.id, null, "QUEUED", null, null, now, now, 2))
            try {
                val result = handle.result.await()
                val finished = System.currentTimeMillis()
                result.onSuccess { asset ->
                    room?.recordGenerated(asset, provider.id, null, handle.id)
                    room?.recordJob(GenerationJobRecord(handle.id, provider.id, null, "COMPLETED", asset.metadata.id, null, now, finished, 2))
                }.onFailure { error ->
                    room?.recordJob(GenerationJobRecord(handle.id, provider.id, null, "FAILED", null, error::class.java.simpleName, now, finished, 2))
                }
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                room?.recordJob(GenerationJobRecord(handle.id, provider.id, null, "CANCELLED", null, "CANCELLED", now, System.currentTimeMillis(), 2))
                throw cancelled
            }
        }
    }
}
