package com.example.avatar.sdk.api

import android.net.Uri
import java.io.File
import java.time.Instant
import java.util.UUID

enum class AvatarQuality { ULTRA, HIGH, BALANCED, PERFORMANCE, LOW_MEMORY }
enum class ModelKind { HUMAN, CAR, OBJECT, GENERAL }
enum class GenerationCapability { IMPORT_GLB, IMAGE_TO_3D, TEXT_TO_3D, IMAGE_TO_IMAGE_3D }
enum class ProviderState { READY, NOT_INSTALLED, INITIALIZING, GENERATING, UNSUPPORTED, INSUFFICIENT_RESOURCES, ERROR, UNAVAILABLE }
enum class AdultCapability { VERIFIED, FILTERED, UNSUPPORTED, UNAVAILABLE, UNVERIFIED }
enum class GenerationPhase { QUEUED, PREPARING, LOADING_MODEL, GENERATING, PROCESSING, VALIDATING, SAVING, COMPLETED, FAILED, CANCELLED }

data class ContentPolicy(
    val adultModeEnabled: Boolean = false,
    val ageGateConfirmed: Boolean = false,
    val photoIsSelfOwned: Boolean = false,
    val photoConsentConfirmed: Boolean = false
)

data class AvatarRequest(
    val prompt: String? = null,
    val image: Uri? = null,
    val kind: ModelKind = ModelKind.HUMAN,
    val quality: AvatarQuality = AvatarQuality.BALANCED,
    val seed: Long? = null,
    val policy: ContentPolicy = ContentPolicy()
) { init { require(prompt != null || image != null) { "At least a prompt or image is required" } } }

data class AvatarMetadata(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val createdAt: String = Instant.now().toString(), val source: String,
    val validationStatus: String = "VALID", val version: Int = 1
)
data class AvatarAsset(val metadata: AvatarMetadata, val glb: File)
data class ProviderStatus(val id: String, val name: String, val state: ProviderState, val message: String, val capabilities: Set<GenerationCapability>, val adultCapability: AdultCapability = AdultCapability.UNSUPPORTED)
data class GenerationProgress(val phase: GenerationPhase, val fraction: Float? = null, val message: String? = null)
data class GenerationHandle(val id: String, val progress: kotlinx.coroutines.flow.StateFlow<GenerationProgress>, val result: kotlinx.coroutines.Deferred<Result<AvatarAsset>>, val cancel: () -> Unit)

interface AvatarGenerationProvider {
    val id: String
    val name: String
    val capabilities: Set<GenerationCapability>
    suspend fun status(): ProviderStatus
    fun generate(request: AvatarRequest): GenerationHandle
}
