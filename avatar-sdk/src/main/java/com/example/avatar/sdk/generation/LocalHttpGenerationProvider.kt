package com.example.avatar.sdk.generation

import android.content.ContentResolver
import android.net.Uri
import com.example.avatar.sdk.api.*
import com.example.avatar.sdk.asset.GlbValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Strict adapter for a local worker. The worker must implement /health, POST /v1/generate,
 * GET /v1/jobs/{id}, and GET /v1/assets/{id}. A job is successful only after a GLB is
 * downloaded and validated locally. No percentage is invented when the worker omits it.
 */
class LocalHttpGenerationProvider(
    private val baseUrl: String,
    private val filesDir: File,
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AvatarGenerationProvider {
    override val id = "local-http"
    override val name = "Local HTTP generation worker"
    // The worker currently accepts image-conditioned workflows only. Text is supported only
    // when a pinned text-to-image-to-3D workflow is explicitly installed and exposed later.
    override val capabilities = setOf(GenerationCapability.IMAGE_TO_3D)

    override suspend fun status(): ProviderStatus = try {
        val json = request("GET", "/health").let(::JSONObject)
        val selected = json.optJSONObject("selected") ?: json
        val adult = runCatching { AdultCapability.valueOf(selected.optString("adultCapability", "UNSUPPORTED").uppercase()) }.getOrDefault(AdultCapability.UNSUPPORTED)
        ProviderStatus(id, name, if (json.optBoolean("ready")) ProviderState.READY else ProviderState.NOT_INSTALLED, json.optString("message", "Worker is not ready"), capabilities, adult)
    } catch (t: Throwable) { ProviderStatus(id, name, ProviderState.UNAVAILABLE, "Worker unavailable: ${t.message}", capabilities) }

    override fun generate(request: AvatarRequest): GenerationHandle {
        val jobId = UUID.randomUUID().toString(); val progress = MutableStateFlow(GenerationProgress(GenerationPhase.QUEUED))
        val deferred = scope.async {
            try {
                ContentPolicyValidator.validate(request)
                if (request.policy.adultModeEnabled) {
                    val providerStatus = status()
                    if (providerStatus.adultCapability != AdultCapability.VERIFIED) error("Adult capability is ${providerStatus.adultCapability}; generation was not sent")
                }
                progress.value = GenerationProgress(GenerationPhase.PREPARING)
                val body = JSONObject().put("requestId", jobId).put("kind", request.kind.name).put("quality", request.quality.name)
                    .put("adultModeEnabled", request.policy.adultModeEnabled)
                    .put("ageGateConfirmed", request.policy.ageGateConfirmed)
                    .put("photoIsSelfOwned", request.policy.photoIsSelfOwned)
                    .put("photoConsentConfirmed", request.policy.photoConsentConfirmed)
                request.prompt?.let { body.put("prompt", it) }; request.seed?.let { body.put("seed", it) }
                request.image?.let { uri -> contentResolver.openInputStream(uri)?.use { body.put("imageBase64", android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) } ?: error("Cannot open input image") }
                val accepted = JSONObject(request("POST", "/v1/generate", body.toString()))
                val workerJob = accepted.optString("jobId").takeIf { it.isNotBlank() } ?: error("Worker response has no jobId")
                while (currentCoroutineContext().isActive) {
                    val job = JSONObject(request("GET", "/v1/jobs/$workerJob")); val phase = job.optString("phase", "GENERATING").uppercase()
                    progress.value = GenerationProgress(runCatching { GenerationPhase.valueOf(phase) }.getOrDefault(GenerationPhase.GENERATING), if (job.has("progress")) job.optDouble("progress").toFloat() else null, if (job.has("message")) job.optString("message") else null)
                    when (phase) {
                        "COMPLETED" -> break
                        "FAILED", "CANCELLED" -> error(job.optString("error", "Worker reported $phase"))
                        else -> delay(job.optLong("pollAfterMs", 1000L).coerceIn(250L, 10000L))
                    }
                }
                progress.value = GenerationProgress(GenerationPhase.PROCESSING)
                val assetId = JSONObject(request("GET", "/v1/jobs/$workerJob")).optString("assetId").takeIf { it.isNotBlank() } ?: error("Completed job has no assetId")
                val target = File(filesDir, "provider-cache/$jobId.glb").also { it.parentFile?.mkdirs() }
                download("/v1/assets/$assetId", target); progress.value = GenerationProgress(GenerationPhase.VALIDATING)
                GlbValidator.validate(target); progress.value = GenerationProgress(GenerationPhase.COMPLETED, 1f)
                Result.success(AvatarAsset(AvatarMetadata(name = request.prompt ?: "Generated asset", source = id), target))
            } catch (t: CancellationException) { progress.value = GenerationProgress(GenerationPhase.CANCELLED); throw t
            } catch (t: Throwable) { progress.value = GenerationProgress(GenerationPhase.FAILED, message = t.message); Result.failure(t) }
        }
        return GenerationHandle(jobId, progress, deferred) { deferred.cancel() }
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val c = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5000; readTimeout = 30000; doInput = true }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("Worker HTTP $code: $text"); return text
    }
    private fun download(path: String, target: File) { val c = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection); if (c.responseCode !in 200..299) error("Asset download HTTP ${c.responseCode}"); c.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } } }
}
