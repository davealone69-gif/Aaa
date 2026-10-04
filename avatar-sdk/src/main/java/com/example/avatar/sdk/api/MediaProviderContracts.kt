package com.example.avatar.sdk.api

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

interface ImageGenerationProvider {
    val id: String
    suspend fun status(): MediaProviderStatus
    fun generate(request: MediaGenerationRequest): MediaJobHandle
}

interface AudioGenerationProvider {
    val id: String
    suspend fun status(): MediaProviderStatus
    fun generate(request: MediaGenerationRequest): MediaJobHandle
}

interface TalkingAvatarProvider {
    val id: String
    suspend fun status(): MediaProviderStatus
    fun generate(request: MediaGenerationRequest): MediaJobHandle
}

data class MediaJobHandle(val jobId: String, val state: StateFlow<MediaJob>, val result: Deferred<Result<MediaJob>> , val cancel: () -> Unit)
