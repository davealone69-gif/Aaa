package com.example.avatar.sdk.storage

import android.content.Context
import android.net.Uri
import com.example.avatar.sdk.api.AvatarAsset
import com.example.avatar.sdk.api.AvatarMetadata
import com.example.avatar.sdk.asset.GlbValidator
import java.io.File

class AvatarLibrary(private val context: Context) {
    private val root get() = File(context.filesDir, "avatars").also { it.mkdirs() }
    private val room = AvatarRoomStore(context)
    fun importGlb(uri: Uri, name: String = "Imported avatar"): AvatarAsset {
        val id = java.util.UUID.randomUUID().toString(); val dir = File(root, id).also { it.mkdirs() }
        val target = File(dir, "avatar.glb")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            ?: error("Unable to open $uri")
        GlbValidator.validate(target)
        val metadata = AvatarMetadata(id = id, name = name, source = "GLB_IMPORT")
        File(dir, "metadata.properties").writeText(metadata.toProperties())
        return AvatarAsset(metadata, target).also(room::recordImported)
    }
    fun list(): List<AvatarMetadata> = root.listFiles().orEmpty().mapNotNull { dir ->
        val file = File(dir, "metadata.properties"); if (!file.isFile) null else {
            val values = file.readLines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            AvatarMetadata(id = dir.name, name = values["name"] ?: dir.name, source = values["source"] ?: "UNKNOWN")
        }
    }
    fun load(id: String): AvatarAsset { require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid avatar id" }; val dir = File(root, id); val glb = File(dir, "avatar.glb"); GlbValidator.validate(glb); return AvatarAsset(AvatarMetadata(id, id, source = "LIBRARY"), glb) }
    fun delete(id: String) { require(id.matches(Regex("[a-fA-F0-9-]{36}"))); File(root, id).deleteRecursively(); room.delete(id) }
    fun export(id: String, destination: Uri) { val asset = load(id); context.contentResolver.openOutputStream(destination)?.use { output -> asset.glb.inputStream().use { input -> input.copyTo(output) } } ?: error("Unable to open destination") }
    private fun AvatarMetadata.toProperties() = "id=$id\nname=$name\nsource=$source\ncreatedAt=$createdAt\nversion=$version\n"
}
