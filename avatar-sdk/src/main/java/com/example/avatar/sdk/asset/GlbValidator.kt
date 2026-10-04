package com.example.avatar.sdk.asset

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbValidationException(message: String) : IllegalArgumentException(message)
data class GlbValidation(val jsonLength: Int, val binaryLength: Int, val totalLength: Int)

object GlbValidator {
    private const val MAGIC = 0x46546C67
    private const val JSON = 0x4E4F534A
    private const val BIN = 0x004E4942

    fun validate(file: File): GlbValidation {
        if (!file.isFile || file.length() < 20) fail("GLB is missing or shorter than the 20-byte header")
        file.inputStream().use { input ->
            val header = ByteArray(12); if (input.readFully(header) != 12) fail("Incomplete GLB header")
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (b.int != MAGIC) fail("Invalid GLB magic")
            val version = b.int; if (version != 2) fail("Unsupported glTF version: $version")
            val length = b.int.toLong() and 0xffffffffL
            if (length != file.length()) fail("GLB length field $length does not match file length ${file.length()}")
            var jsonLength = 0; var binaryLength = 0; var jsonText: ByteArray? = null; var read = 12L
            while (read < length) {
                val chunk = ByteArray(8); if (input.readFully(chunk) != 8) fail("Truncated GLB chunk header")
                val c = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN); val size = c.int; val type = c.int
                if (size < 0 || read + 8L + size > length) fail("Invalid GLB chunk size")
                when (type) {
                    JSON -> { if (jsonLength != 0) fail("Multiple JSON chunks"); jsonLength = size; jsonText = input.readNBytes(size) }
                    BIN -> { binaryLength += size; input.skipNBytes(size.toLong()) }
                    else -> input.skipNBytes(size.toLong())
                }
                read += 8L + size
            }
            if (jsonLength == 0 || jsonText == null) fail("GLB has no JSON chunk")
            validateJson(String(jsonText!!, Charsets.UTF_8).trimEnd(' ', '\u0000'), binaryLength)
            return GlbValidation(jsonLength, binaryLength, length.toInt())
        }
    }

    private fun validateJson(text: String, binaryLength: Int) {
        val root = try { JSONObject(text) } catch (t: Throwable) { fail("Invalid glTF JSON: ${t.message}") }
        root.optJSONObject("asset")?.optString("version")?.let { if (it != "2.0") fail("Unsupported glTF asset.version: $it") }
        val buffers = root.optJSONArray("buffers") ?: JSONArray()
        for (i in 0 until buffers.length()) {
            val declared = buffers.optJSONObject(i)?.optInt("byteLength", -1) ?: -1
            if (declared < 0) fail("Buffer $i has invalid byteLength")
            if (i == 0 && declared > binaryLength) fail("Buffer 0 exceeds GLB BIN chunk")
        }
        val views = root.optJSONArray("bufferViews") ?: JSONArray()
        for (i in 0 until views.length()) {
            val view = views.optJSONObject(i) ?: fail("bufferView $i is not an object")
            val buffer = view.optInt("buffer", -1); val offset = view.optLong("byteOffset", 0); val length = view.optLong("byteLength", -1)
            if (buffer < 0 || buffer >= buffers.length() || offset < 0 || length < 0) fail("Invalid bufferView $i")
            val declared = buffers.optJSONObject(buffer)?.optLong("byteLength", -1) ?: -1
            if (offset + length > declared) fail("bufferView $i exceeds its buffer")
        }
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        for (i in 0 until accessors.length()) {
            val accessor = accessors.optJSONObject(i) ?: fail("accessor $i is not an object")
            if (accessor.optInt("count", -1) < 0 || accessor.optString("type").isBlank()) fail("Invalid accessor $i")
            accessor.optInt("bufferView", -1).let { if (it >= views.length()) fail("accessor $i references missing bufferView") }
        }
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        for (i in 0 until meshes.length()) {
            val primitives = meshes.optJSONObject(i)?.optJSONArray("primitives") ?: fail("mesh $i has no primitives")
            for (p in 0 until primitives.length()) {
                val primitive = primitives.optJSONObject(p) ?: fail("mesh $i primitive $p is invalid")
                primitive.optInt("indices", -1).let { if (it >= accessors.length()) fail("primitive references missing indices accessor") }
                val attrs = primitive.optJSONObject("attributes") ?: fail("mesh $i primitive $p has no attributes")
                val position = attrs.optInt("POSITION", -1); if (position >= accessors.length()) fail("primitive references missing POSITION accessor")
                primitive.optInt("material", -1).let { if (it >= (root.optJSONArray("materials")?.length() ?: 0)) fail("primitive references missing material") }
            }
        }
        val materials = root.optJSONArray("materials") ?: JSONArray()
        val textures = root.optJSONArray("textures") ?: JSONArray()
        for (i in 0 until materials.length()) {
            val material = materials.optJSONObject(i) ?: fail("material $i is invalid")
            val pbr = material.optJSONObject("pbrMetallicRoughness") ?: continue
            for (key in listOf("baseColorTexture", "metallicRoughnessTexture")) pbr.optJSONObject(key)?.optInt("index", -1)?.let { if (it >= textures.length()) fail("material $i references missing texture") }
        }
    }

    private fun fail(message: String): Nothing = throw GlbValidationException(message)
    private fun java.io.InputStream.readFully(buffer: ByteArray): Int { var total = 0; while (total < buffer.size) { val n = read(buffer, total, buffer.size - total); if (n < 0) break; total += n }; return total }
}
