package com.example.avatar.sdk.generation

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Real model registry and downloader; it never marks a file installed before checksum verification. */
enum class ModelInstallState { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, CORRUPT, INCOMPATIBLE, INSUFFICIENT_RESOURCES }
data class ModelDescriptor(
    val id: String,
    val version: String,
    val providerId: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val requiredRamBytes: Long,
    val capabilities: Set<String>
)
data class InstalledModel(val descriptor: ModelDescriptor, val state: ModelInstallState, val path: File?, val message: String? = null)

class ModelManager(private val root: File, private val availableRamBytes: () -> Long) {
    init { root.mkdirs() }
    fun inspect(descriptor: ModelDescriptor): InstalledModel {
        val file = safePath(descriptor)
        if (!file.isFile) return InstalledModel(descriptor, ModelInstallState.NOT_INSTALLED, null)
        if (file.length() != descriptor.sizeBytes || sha256(file) != descriptor.sha256.lowercase()) return InstalledModel(descriptor, ModelInstallState.CORRUPT, file, "Size or SHA-256 mismatch")
        if (availableRamBytes() < descriptor.requiredRamBytes) return InstalledModel(descriptor, ModelInstallState.INSUFFICIENT_RESOURCES, file, "Available RAM is below model requirement")
        return InstalledModel(descriptor, ModelInstallState.INSTALLED, file)
    }
    fun install(descriptor: ModelDescriptor, onBytes: (Long, Long?) -> Unit = { _, _ -> }): InstalledModel {
        require(descriptor.id.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Unsafe model id" }
        if (availableRamBytes() < descriptor.requiredRamBytes) return InstalledModel(descriptor, ModelInstallState.INSUFFICIENT_RESOURCES, null, "Insufficient RAM")
        val target = safePath(descriptor); val partial = File(target.parentFile, target.name + ".part"); target.parentFile?.mkdirs()
        try {
            var existing = if (partial.isFile) partial.length() else 0L
            val connection = URL(descriptor.url).openConnection().apply { connectTimeout = 10000; readTimeout = 60000; if (this is HttpURLConnection && existing > 0) setRequestProperty("Range", "bytes=$existing-") }
            val response = if (connection is HttpURLConnection) connection.responseCode else 200
            if (existing > 0 && response == HttpURLConnection.HTTP_OK) { partial.delete(); existing = 0L }
            if (connection is HttpURLConnection && response !in 200..299 && response != HttpURLConnection.HTTP_PARTIAL) error("Model download HTTP $response")
            connection.inputStream.use { input -> FileOutputStream(partial, existing > 0).use { output -> val buffer = ByteArray(1024 * 1024); var total = existing; var read: Int; while (input.read(buffer).also { read = it } != -1) { output.write(buffer, 0, read); total += read; onBytes(total, descriptor.sizeBytes) } } }
            if (partial.length() != descriptor.sizeBytes || sha256(partial) != descriptor.sha256.lowercase()) { partial.delete(); return InstalledModel(descriptor, ModelInstallState.CORRUPT, null, "Downloaded model failed size/SHA-256 verification") }
            if (!partial.renameTo(target)) error("Unable to atomically install model")
            return InstalledModel(descriptor, ModelInstallState.INSTALLED, target)
        } catch (t: Throwable) { return InstalledModel(descriptor, ModelInstallState.CORRUPT, partial.takeIf { it.isFile }, t.message) }
    }
    fun remove(descriptor: ModelDescriptor) { safePath(descriptor).delete(); File(safePath(descriptor).parentFile, safePath(descriptor).name + ".part").delete() }
    private fun safePath(descriptor: ModelDescriptor): File { val dir = File(root, descriptor.id).canonicalFile; require(dir.path.startsWith(root.canonicalPath + File.separator)); return File(dir, "${descriptor.version}.model") }
    private fun sha256(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); var n: Int; while (input.read(buffer).also { n = it } != -1) digest.update(buffer, 0, n) }; return digest.digest().joinToString("") { "%02x".format(it) } }
}
