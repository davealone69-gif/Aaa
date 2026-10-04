package com.example.avatar.sdk.api

import java.io.File
import java.time.Instant
import java.util.UUID

enum class MediaKind { IMAGE, IMAGE_EDIT, UPSCALE, VIDEO, AUDIO, TTS, LIP_SYNC, RENDER, EXPORT }
enum class MediaJobState { QUEUED, STARTING, RUNNING, CANCELLING, COMPLETE, FAILED, CANCELLED, BACKEND_UNAVAILABLE, MODEL_NOT_INSTALLED, INSUFFICIENT_RESOURCES }
enum class MediaCapability { TEXT_TO_IMAGE, IMAGE_TO_IMAGE, IMAGE_EDIT, INPAINTING, OUTPAINTING, BACKGROUND, UPSCALE, TEXT_TO_VIDEO, IMAGE_TO_VIDEO, AUDIO_TO_VIDEO, TEXT_TO_SPEECH, SOUND_EFFECT, MUSIC, LIP_SYNC, ADULT_18_PLUS }

data class MediaGenerationRequest(
    val kind: MediaKind,
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val inputFiles: List<File> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val durationSeconds: Float? = null,
    val seed: Long? = null,
    val modelId: String? = null,
    val adultModeEnabled: Boolean = false
)

data class MediaOutput(val file: File, val mimeType: String, val width: Int? = null, val height: Int? = null, val durationSeconds: Float? = null)
data class MediaJob(val id: String = UUID.randomUUID().toString(), val kind: MediaKind, val providerId: String, val modelId: String?, val state: MediaJobState, val progress: Float? = null, val output: MediaOutput? = null, val errorCode: String? = null, val errorMessage: String? = null, val createdAt: String = Instant.now().toString())
data class MediaProviderStatus(val id: String, val name: String, val state: ProviderState, val capabilities: Set<MediaCapability>, val message: String, val adultCapability: AdultCapability = AdultCapability.UNSUPPORTED)
