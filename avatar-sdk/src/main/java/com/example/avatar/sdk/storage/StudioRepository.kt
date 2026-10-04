package com.example.avatar.sdk.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class StudioRepository(private val context: Context) {
    private val db = AvatarDatabase.open(context)
    private val root get() = File(context.filesDir, "studio-references").also { it.mkdirs() }

    fun createProject(name: String, description: String = ""): ProjectRecord {
        val now = System.currentTimeMillis()
        return ProjectRecord(UUID.randomUUID().toString(), name.trim().ifBlank { "Untitled project" }, description, "{}", now, now).also(db.studio()::upsertProject)
    }
    fun listProjects(): List<ProjectRecord> = db.studio().listProjects()
    fun openProject(projectId: String): ProjectRecord? = db.studio().findProject(requireId(projectId))
    fun renameProject(projectId: String, name: String): ProjectRecord {
        val old = requireNotNull(openProject(projectId)) { "Project not found" }
        return old.copy(name = name.trim().ifBlank { old.name }, updatedAt = System.currentTimeMillis()).also(db.studio()::upsertProject)
    }
    fun duplicateProject(projectId: String, name: String? = null): ProjectRecord {
        val source = requireNotNull(openProject(projectId)) { "Project not found" }
        val copy = createProject(name ?: "${source.name} copy", source.description)
        listScenes(source.projectId).forEach { scene -> saveScene(copy.projectId, scene.name, scene.sceneJson) }
        return copy
    }
    fun deleteProject(projectId: String) {
        val id = requireId(projectId)
        listScenes(id).forEach { deleteScene(it.sceneId) }
        listReferences(id).forEach { deleteReference(it.referenceId) }
        db.studio().deleteProject(id)
    }

    fun createScene(projectId: String, name: String = "Scene"): SceneRecord = saveScene(projectId, name, "{}")
    fun saveScene(projectId: String, name: String, sceneJson: String, sceneId: String = UUID.randomUUID().toString()): SceneRecord {
        requireNotNull(openProject(projectId)) { "Project not found" }
        require(sceneJson.trim().startsWith("{") && sceneJson.trim().endsWith("}")) { "Scene data must be a JSON object" }
        val now = System.currentTimeMillis(); val old = db.studio().findScene(sceneId)
        return SceneRecord(sceneId, requireId(projectId), name.trim().ifBlank { "Scene" }, sceneJson, old?.createdAt ?: now, now).also(db.studio()::upsertScene)
    }
    fun listScenes(projectId: String): List<SceneRecord> = db.studio().listScenes(requireId(projectId))
    fun loadScene(sceneId: String): SceneRecord? = db.studio().findScene(requireId(sceneId))
    fun duplicateScene(sceneId: String, name: String? = null): SceneRecord {
        val source = requireNotNull(loadScene(sceneId)) { "Scene not found" }
        return saveScene(source.projectId, name ?: "${source.name} copy", source.sceneJson)
    }
    fun deleteScene(sceneId: String) = db.studio().deleteScene(requireId(sceneId))

    fun importReference(projectId: String, uri: Uri, originalName: String = "reference"): ReferenceRecord {
        requireNotNull(openProject(projectId)) { "Project not found" }
        val safeName = originalName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "reference" }
        val temp = File(root, ".${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L; var lines = 0
        val textLike = isTextLike(safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var n: Int
                while (input.read(buffer).also { n = it } >= 0) {
                    if (n == 0) continue
                    bytes += n; require(bytes <= 512L * 1024 * 1024) { "Reference exceeds 512 MiB limit" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    if (textLike) for (i in 0 until n) if (buffer[i].toInt() == '\n'.code) lines++
                }
            }
        } ?: error("Unable to open reference")
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        val existing = db.studio().findReferenceByChecksum(checksum)
        if (existing != null) { temp.delete(); return existing }
        val id = UUID.randomUUID().toString(); val target = File(root, "$id-$safeName"); require(temp.renameTo(target)) { "Unable to store reference" }
        val record = ReferenceRecord(id, requireId(projectId), safeName, context.contentResolver.getType(uri) ?: mimeFor(safeName), categoryFor(safeName), bytes, checksum, System.currentTimeMillis(), null, "", target.absolutePath, previewStatusFor(safeName), if (textLike) lines else null)
        if (db.studio().insertReference(record) == -1L) {
            target.delete()
            return requireNotNull(db.studio().findReferenceByChecksum(checksum))
        }
        return record
    }
    fun listReferences(projectId: String): List<ReferenceRecord> = db.studio().listReferences(requireId(projectId))
    fun deleteReference(referenceId: String) { val id = requireId(referenceId); db.studio().findReference(id)?.let { File(it.storagePath).delete() }; db.studio().deleteReference(id) }
    fun readText(referenceId: String, maxBytes: Long = 2 * 1024 * 1024): String { val r = db.studio().findReference(requireId(referenceId)) ?: error("Reference not found"); require(r.category == "TEXT" || r.category == "CODE"); require(r.byteSize <= maxBytes) { "Reference too large for preview" }; return File(r.storagePath).readText() }
    private fun requireId(value: String): String { require(Regex("[a-fA-F0-9-]{36}").matches(value)) { "Invalid stable ID" }; return value }
    private fun isTextLike(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts", "html", "css", "gradle", "kts", "properties")
    private fun categoryFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "png", "jpg", "jpeg", "webp", "gif" -> "IMAGE"; "mp4", "webm", "mov", "mkv" -> "VIDEO"; "txt", "md", "json", "xml", "yaml", "yml" -> "TEXT"; else -> if (isTextLike(name)) "CODE" else "DOCUMENT" }
    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "json" -> "application/json"; "kt" -> "text/x-kotlin"; "py" -> "text/x-python"; else -> "application/octet-stream" }
    private fun previewStatusFor(name: String) = when (categoryFor(name)) { "IMAGE" -> "METADATA_ONLY"; "VIDEO" -> "METADATA_ONLY"; "TEXT", "CODE" -> "TEXT_READABLE"; else -> "PREVIEW_UNAVAILABLE" }
}
