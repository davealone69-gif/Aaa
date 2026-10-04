package com.aura.avatarstudio.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.opengl.GLES30
import android.util.Log

/**
 * Decodes glTF images (data-URI or embedded bufferView, PNG/JPEG) into
 * GL_TEXTURE_2D objects on the GL thread and binds them onto each mesh's
 * PBR material (*_Tex fields). Idempotent per avatar.
 *
 * Handles:
 *  - sampler wrap modes (REPEAT / CLAMP / MIRROR)
 *  - min/mag filters + mipmap generation
 *  - glTF UV convention (flips image V so v=0 is the texture bottom)
 *  - texture sharing between materials (dedup by image index)
 */
object GltfTextures {

    private val TAG = "GltfTextures"

    fun resolve(avatar: HdAvatar) {
        val document = avatar.gltf ?: return
        val binary = avatar.gltfBinary

        if (document.images.isEmpty() || document.textures.isEmpty()) {
            return
        }

        // image index -> GL texture id
        val uploaded = HashMap<Int, Int>()
        val owned = mutableListOf<Int>()

        fun imagePixels(image: GltfImage): Bitmap? {
            var bytes: ByteArray? = null

            if (image.uri != null) {
                if (!image.uri.startsWith("data:")) {
                    Log.w(TAG, "External image URIs not supported: ${image.uri}")
                    return null
                }
                bytes = GltfDataUris.decode(image.uri)
            } else if (image.bufferView != null) {
                val view = document.bufferViews.getOrNull(image.bufferView) ?: return null
                val buffer = document.buffers.getOrNull(view.buffer) ?: return null
                val source =
                    if (buffer.uri == null) binary
                    else GltfDataUris.decode(buffer.uri)
                if (source == null) return null
                val start = view.byteOffset
                val end = minOf(source.size, start + view.byteLength)
                if (end <= start) return null
                bytes = source.copyOfRange(start, end)
            }

            if (bytes == null) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        fun uploadImage(
            imageIndex: Int,
            samplerIndex: Int?,
            srgb: Boolean
        ): Int {
            uploaded[imageIndex]?.let { return it }
            val image = document.images.getOrNull(imageIndex) ?: return 0

            var bitmap = imagePixels(image) ?: return 0

            // glTF UV origin is top-left; GL expects bottom-left -> flip V.
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val flipped = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flipped)
            val matrix = Matrix()
            matrix.postScale(1f, -1f)
            matrix.postTranslate(0f, bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, matrix, null)

            val tex = intArrayOf(0)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])

            val pixels = java.nio.ByteBuffer
                .allocateDirect(flipped.width * flipped.height * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            flipped.copyPixelsToBuffer(pixels)
            pixels.position(0)

            // sRGB storage for base-color/emissive maps: the GPU decodes
            // to linear light at sample time (correct PBR input).
            val internalFormat =
                if (srgb) GLES30.GL_SRGB8_ALPHA8
                else GLES30.GL_RGBA8

            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                internalFormat,
                flipped.width,
                flipped.height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                pixels
            )

            // Sampler state (defaults per glTF spec); a texture may be
            // shared by multiple images/samplers, and texture indices are
            // independent of image indices.
            val sampler = samplerIndex?.let { document.samplers.getOrNull(it) }
            val minFilter = sampler?.minFilter ?: 9987 // LINEAR_MIPMAP_LINEAR
            val magFilter = sampler?.magFilter ?: 9729
            val wrapS = sampler?.wrapS ?: 10497
            val wrapT = sampler?.wrapT ?: 10497

            val wantMips = minFilter != 9728 && minFilter != 9729
            val glMin = if (wantMips) minFilter else {
                if (minFilter == 9728) 9728 else 9729
            }

            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, glMin)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)

            if (wantMips) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            }

            flipped.recycle()
            bitmap.recycle()

            uploaded[imageIndex] = tex[0]
            owned += tex[0]
            return tex[0]
        }

        // Textures sampled as color (base-color/emissive) are sRGB;
        // data maps (normal/metallic-roughness/occlusion) stay linear.
        val srgbTextures = HashSet<Int>()
        for (mesh in avatar.meshes) {
            val m = mesh.material
            if (m.baseColorTextureIndex >= 0) srgbTextures += m.baseColorTextureIndex
            if (m.emissiveTextureIndex >= 0) srgbTextures += m.emissiveTextureIndex
        }

        // Resolve texture.sources (with their samplers), then bind onto
        // materials. Dedup by image so shared images upload once.
        val texByTexture = HashMap<Int, Int>()
        for ((textureIndex, texture) in document.textures.withIndex()) {
            val source = texture.source ?: continue
            texByTexture[textureIndex] =
                uploadImage(
                    source,
                    texture.sampler,
                    textureIndex in srgbTextures
                )
        }

        for (mesh in avatar.meshes) {
            val m = mesh.material
            m.baseColorTexture =
                if (m.baseColorTextureIndex >= 0) texByTexture[m.baseColorTextureIndex] ?: 0 else 0
            m.metallicRoughnessTexture =
                if (m.metallicRoughnessTextureIndex >= 0) texByTexture[m.metallicRoughnessTextureIndex] ?: 0 else 0
            m.normalTexture =
                if (m.normalTextureIndex >= 0) texByTexture[m.normalTextureIndex] ?: 0 else 0
            m.occlusionTexture =
                if (m.occlusionTextureIndex >= 0) texByTexture[m.occlusionTextureIndex] ?: 0 else 0
            m.emissiveTexture =
                if (m.emissiveTextureIndex >= 0) texByTexture[m.emissiveTextureIndex] ?: 0 else 0
        }

        Log.d(TAG, "Uploaded ${owned.size} texture(s) for avatar")
    }

    /** Deletes all textures referenced by the avatar (GL thread). */
    fun release(avatar: HdAvatar) {
        val ids = HashSet<Int>()
        for (mesh in avatar.meshes) {
            val m = mesh.material
            if (m.baseColorTexture != 0) ids += m.baseColorTexture
            if (m.metallicRoughnessTexture != 0) ids += m.metallicRoughnessTexture
            if (m.normalTexture != 0) ids += m.normalTexture
            if (m.occlusionTexture != 0) ids += m.occlusionTexture
            if (m.emissiveTexture != 0) ids += m.emissiveTexture
            m.baseColorTexture = 0
            m.metallicRoughnessTexture = 0
            m.normalTexture = 0
            m.occlusionTexture = 0
            m.emissiveTexture = 0
        }
        if (ids.isNotEmpty()) {
            GLES30.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        }
    }
}
