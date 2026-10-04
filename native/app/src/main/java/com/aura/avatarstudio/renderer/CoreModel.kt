package com.aura.avatarstudio.renderer

import kotlin.math.sqrt

/**
 * Core runtime types shared by the loader and the render pipeline.
 *
 * [GltfAvatarLoader] fills [HdAvatar] with [GpuMesh]es; [PbrPipeline] consumes
 * them on the GL thread.
 */
class HdAvatar {

    val meshes = mutableListOf<GpuMesh>()

    var skeleton: AvatarSkeleton? = null

    /** Source document retained so textures can be resolved on the GL thread. */
    var gltf: GltfDocument? = null

    /** Raw BIN chunk retained for texture/image decoding. */
    var gltfBinary: ByteArray? = null

    /** Per-joint skinning matrices (joint global transform x inverse bind),
     *  column-major, computed by [SkeletonMatrices]. Null when unskinned. */
    var jointMatrices: FloatArray? = null
}

class AvatarSkeleton(val jointCount: Int)

data class MorphTarget(
    val positionDeltas: FloatArray?,
    val normalDeltas: FloatArray?,
    val tangentDeltas: FloatArray?
)

data class GpuMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val tangents: FloatArray?,
    val uvs: FloatArray,
    val joints: IntArray?,
    val weights: FloatArray?,
    val indices: IntArray,
    val material: HdPbrMaterial,
    val morphTargets: List<MorphTarget>,
    /** Default morph weights from the glTF mesh (0.0 when unset). Animatable. */
    var morphWeights: FloatArray = FloatArray(0),
    /** Per-mesh joint skinning matrices, recomputed per frame by the renderer. */
    var skinningMatrices: FloatArray? = null
)

/**
 * PBR material — GPU-texture based (see [PbrTexture]).
 *
 * Factor fields come from the glTF material; the *_TextureIndex fields are
 * the loader-side glTF texture references (-1 = none); the *_Texture fields
 * hold GL texture ids (0 = none) filled on the GL thread by [GltfTextures].
 */
class HdPbrMaterial(
    var baseColor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    var metallic: Float = 0f,
    var roughness: Float = 0.5f,
    var emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    var normalScale: Float = 1f,
    var occlusionStrength: Float = 1f,
    var alphaMode: String = "OPAQUE",
    var doubleSided: Boolean = false,
    // loader-side glTF texture references
    var baseColorTextureIndex: Int = -1,
    var metallicRoughnessTextureIndex: Int = -1,
    var normalTextureIndex: Int = -1,
    var occlusionTextureIndex: Int = -1,
    var emissiveTextureIndex: Int = -1,
    // GL-side texture ids (filled on the GL thread)
    var baseColorTexture: Int = 0,
    var metallicRoughnessTexture: Int = 0,
    var normalTexture: Int = 0,
    var occlusionTexture: Int = 0,
    var emissiveTexture: Int = 0
)

/** Base64 data-URI helper shared by the loader and the texture loader. */
object GltfDataUris {

    fun decode(uri: String): ByteArray {
        val marker = uri.indexOf("base64,")
        require(uri.startsWith("data:") && marker >= 0) {
            "Only base64 data URIs are supported"
        }
        return android.util.Base64.decode(
            uri.substring(marker + "base64,".length),
            android.util.Base64.DEFAULT
        )
    }
}

/** CPU mesh geometry helpers (smooth normals + tangents when the asset omits them). */
object MeshGeometry {

    fun generateNormals(
        positions: FloatArray,
        indices: IntArray?
    ): FloatArray {
        val vertexCount = positions.size / 3
        val normals = FloatArray(vertexCount * 3)
        val triangles = indices ?: IntArray(vertexCount) { it }

        for (t in triangles.indices step 3) {
            val i0 = triangles[t]
            val i1 = triangles[t + 1]
            val i2 = triangles[t + 2]
            val ax = positions[i1 * 3] - positions[i0 * 3]
            val ay = positions[i1 * 3 + 1] - positions[i0 * 3 + 1]
            val az = positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            val bx = positions[i2 * 3] - positions[i0 * 3]
            val by = positions[i2 * 3 + 1] - positions[i0 * 3 + 1]
            val bz = positions[i2 * 3 + 2] - positions[i0 * 3 + 2]
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx
            normals[i0 * 3] += nx; normals[i0 * 3 + 1] += ny; normals[i0 * 3 + 2] += nz
            normals[i1 * 3] += nx; normals[i1 * 3 + 1] += ny; normals[i1 * 3 + 2] += nz
            normals[i2 * 3] += nx; normals[i2 * 3 + 1] += ny; normals[i2 * 3 + 2] += nz
        }

        for (i in 0 until vertexCount) {
            val nx = normals[i * 3]
            val ny = normals[i * 3 + 1]
            val nz = normals[i * 3 + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) {
                normals[i * 3] = nx / len
                normals[i * 3 + 1] = ny / len
                normals[i * 3 + 2] = nz / len
            } else {
                normals[i * 3 + 1] = 1f
            }
        }
        return normals
    }

    /**
     * Averaged per-vertex tangents (MikkTSpace-lite, no seams handled).
     * Output is a FloatArray of vec4: xyz tangent, w = bitangent sign.
     */
    fun generateTangents(
        positions: FloatArray,
        normals: FloatArray,
        uvs: FloatArray,
        indices: IntArray?
    ): FloatArray {
        val vertexCount = positions.size / 3
        val tangents = FloatArray(vertexCount * 4)
        if (uvs.size < vertexCount * 2) {
            // No usable UVs: emit a degenerate tangent that disables normal mapping.
            for (i in 0 until vertexCount) tangents[i * 4 + 3] = 1f
            return tangents
        }
        val bitangents = FloatArray(vertexCount * 3)
        val triangles = indices ?: IntArray(vertexCount) { it }

        for (t in triangles.indices step 3) {
            val i0 = triangles[t]
            val i1 = triangles[t + 1]
            val i2 = triangles[t + 2]
            val e1x = positions[i1 * 3] - positions[i0 * 3]
            val e1y = positions[i1 * 3 + 1] - positions[i0 * 3 + 1]
            val e1z = positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            val e2x = positions[i2 * 3] - positions[i0 * 3]
            val e2y = positions[i2 * 3 + 1] - positions[i0 * 3 + 1]
            val e2z = positions[i2 * 3 + 2] - positions[i0 * 3 + 2]
            val du1 = uvs[i1 * 2] - uvs[i0 * 2]
            val dv1 = uvs[i1 * 2 + 1] - uvs[i0 * 2 + 1]
            val du2 = uvs[i2 * 2] - uvs[i0 * 2]
            val dv2 = uvs[i2 * 2 + 1] - uvs[i0 * 2 + 1]
            val det = du1 * dv2 - dv1 * du2
            if (kotlin.math.abs(det) < 1e-12f) continue
            val r = 1f / det
            val tx = (e1x * dv2 - e2x * dv1) * r
            val ty = (e1y * dv2 - e2y * dv1) * r
            val tz = (e1z * dv2 - e2z * dv1) * r
            val bx = (e2x * du1 - e1x * du2) * r
            val by = (e2y * du1 - e1y * du2) * r
            val bz = (e2z * du1 - e1z * du2) * r
            for (i in intArrayOf(i0, i1, i2)) {
                tangents[i * 4] += tx; tangents[i * 4 + 1] += ty; tangents[i * 4 + 2] += tz
                bitangents[i * 3] += bx; bitangents[i * 3 + 1] += by; bitangents[i * 3 + 2] += bz
            }
        }

        for (i in 0 until vertexCount) {
            val nx = normals[i * 3]
            val ny = normals[i * 3 + 1]
            val nz = normals[i * 3 + 2]
            var tx = tangents[i * 4]
            var ty = tangents[i * 4 + 1]
            var tz = tangents[i * 4 + 2]
            val nDotT = nx * tx + ny * ty + nz * tz
            tx -= nx * nDotT
            ty -= ny * nDotT
            tz -= nz * nDotT
            val len = sqrt(tx * tx + ty * ty + tz * tz)
            if (len > 1e-12f) {
                tangents[i * 4] = tx / len
                tangents[i * 4 + 1] = ty / len
                tangents[i * 4 + 2] = tz / len
            }
            // w = handedness
            val w = nx * (ty * bitangents[i * 3 + 2] - tz * bitangents[i * 3 + 1]) +
                    ny * (tz * bitangents[i * 3] - tx * bitangents[i * 3 + 2]) +
                    nz * (tx * bitangents[i * 3 + 1] - ty * bitangents[i * 3])
            tangents[i * 4 + 3] = if (w < 0f) -1f else 1f
        }
        return tangents
    }
}

/** Minimal column-major 4x4 float matrix helpers (GL convention). */
object Mat4 {

    fun identity(): FloatArray =
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

    /** result = a * b (column-major). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) {
                    s += a[k * 4 + row] * b[c * 4 + k]
                }
                r[c * 4 + row] = s
            }
        }
        return r
    }

    fun lookAt(eye: FloatArray, center: FloatArray, up: FloatArray): FloatArray {
        val f = normalize(sub(center, eye))
        val s = normalize(cross(f, up))
        val u = cross(s, f)
        return floatArrayOf(
            s[0], u[0], -f[0], 0f,
            s[1], u[1], -f[1], 0f,
            s[2], u[2], -f[2], 0f,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1f
        )
    }

    fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovYDegrees / 2.0).toFloat())
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f
        )
    }

    fun translation(x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            x, y, z, 1f
        )

    /** T * R * S from glTF node TRS (quat x,y,z,w). */
    fun fromTrs(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val x = r[0]; val y = r[1]; val z = r[2]; val w = r[3]
        val xx = 2f * x * x; val yy = 2f * y * y; val zz = 2f * z * z
        val xy = 2f * x * y; val xz = 2f * x * z; val yz = 2f * y * z
        val wx = 2f * w * x; val wy = 2f * w * y; val wz = 2f * w * z
        val m00 = 1f - yy - zz; val m01 = xy + wz; val m02 = xz - wy
        val m10 = xy - wz; val m11 = 1f - xx - zz; val m12 = yz + wx
        val m20 = xz + wy; val m21 = yz - wx; val m22 = 1f - xx - yy
        return floatArrayOf(
            m00 * s[0], m10 * s[0], m20 * s[0], 0f,
            m01 * s[1], m11 * s[1], m21 * s[1], 0f,
            m02 * s[2], m12 * s[2], m22 * s[2], 0f,
            t[0], t[1], t[2], 1f
        )
    }

    /** View matrix with translation stripped, for skyboxes. */
    fun rotationOnly(view: FloatArray): FloatArray =
        floatArrayOf(
            view[0], view[1], view[2], 0f,
            view[4], view[5], view[6], 0f,
            view[8], view[9], view[10], 0f,
            0f, 0f, 0f, 1f
        )

    private fun sub(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )

    private fun dot(a: FloatArray, b: FloatArray) =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 1e-12f) {
            floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }
    }
}
