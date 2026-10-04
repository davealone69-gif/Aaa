package com.aura.avatarstudio.renderer.hd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * GL texture handle. `id` is the GL texture id; `srgb` records the storage
 * format so callers know whether the GPU decodes to linear light at sample
 * time (base-color/emissive maps) or not (normal/metallic-roughness/AO maps).
 */
data class HdTexture(
    val id: Int,
    val width: Int,
    val height: Int,
    val srgb: Boolean
) : Closeable {

    override fun close() {
        if (id != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        }
    }
}

/**
 * GPU texture manager: loads Bitmaps from assets or app resources, uploads
 * them as mipmapped GL textures (optional sRGB storage + Y flip), and caches
 * by key so repeated loads reuse one texture id.
 *
 * This is the manager layer for textures that live OUTSIDE the GLB
 * (environment maps, UI overlays, generated maps). Textures embedded in the
 * GLB are uploaded by [com.aura.avatarstudio.renderer.GltfTextures]; the
 * single-shot asset helper is [com.aura.avatarstudio.renderer.PbrTexture].
 *
 * ES 3.0: GLES32 calls in the reference spec map 1:1 onto GLES30.
 * Anisotropic filtering is an optional extension — applied when present,
 * skipped otherwise (no GL error on devices without it).
 */
class HdTextureManager(
    private val context: Context
) : Closeable {

    private val cache = ConcurrentHashMap<String, HdTexture>()

    /** GL_TEXTURE_MAX_ANISOTROPY_EXT — not in the ES 3.0 core constants. */
    private val anisotropySupported: Boolean by lazy {
        GLES30.glGetString(GLES30.GL_EXTENSIONS)
            ?.contains("EXT_texture_filter_anisotropic") == true
    }

    fun load(
        assetPath: String,
        srgb: Boolean = false,
        flipY: Boolean = false
    ): HdTexture? {
        cache[cacheKey(assetPath, srgb)]?.let { return it }

        val bitmap = context.assets.open(assetPath).use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val source = if (flipY) flipBitmap(bitmap) else bitmap

        val texture = createTexture(
            source,
            srgb
        )

        if (source !== bitmap) {
            source.recycle()
        }

        bitmap.recycle()

        if (texture != null) {
            cache[cacheKey(assetPath, srgb)] = texture
        }

        return texture
    }

    fun loadResource(
        resourceId: Int,
        srgb: Boolean = false
    ): HdTexture? {
        val key = "res:$resourceId:$srgb"

        cache[key]?.let { return it }

        val bitmap = BitmapFactory.decodeResource(
            context.resources,
            resourceId
        ) ?: return null

        val texture = createTexture(bitmap, srgb)

        bitmap.recycle()

        if (texture != null) {
            cache[key] = texture
        }

        return texture
    }

    private fun createTexture(
        bitmap: Bitmap,
        srgb: Boolean
    ): HdTexture? {

        val ids = IntArray(1)

        GLES30.glGenTextures(1, ids, 0)

        if (ids[0] == 0) {
            return null
        }

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            ids[0]
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR_MIPMAP_LINEAR
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_REPEAT
        )

        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_REPEAT
        )

        if (anisotropySupported) {
            GLES30.glTexParameterf(
                GLES30.GL_TEXTURE_2D,
                GL_TEXTURE_MAX_ANISOTROPY_EXT,
                8.0f
            )
        }

        val internalFormat =
            if (srgb) GLES30.GL_SRGB8_ALPHA8
            else GLES30.GL_RGBA8

        GLUtils.texImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            bitmap,
            0
        )

        GLES30.glGenerateMipmap(
            GLES30.GL_TEXTURE_2D
        )

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            0
        )

        checkGlError("createTexture")

        return HdTexture(
            id = ids[0],
            width = bitmap.width,
            height = bitmap.height,
            srgb = srgb
        )
    }

    private fun flipBitmap(
        source: Bitmap
    ): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postScale(1f, -1f)
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    private fun cacheKey(
        path: String,
        srgb: Boolean
    ): String {
        return "$path:$srgb"
    }

    private fun checkGlError(
        operation: String
    ) {
        var error = GLES30.glGetError()

        while (error != GLES30.GL_NO_ERROR) {
            android.util.Log.e(
                "HdTextureManager",
                "$operation GL error: 0x${error.toString(16)}"
            )

            error = GLES30.glGetError()
        }
    }

    fun clear() {
        cache.values.forEach {
            it.close()
        }

        cache.clear()
    }

    override fun close() {
        clear()
    }

    companion object {
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    }
}
