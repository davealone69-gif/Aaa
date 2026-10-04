package com.aura.avatarstudio.renderer

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Draws [HdAvatar] meshes with a specialized PBR program per material
 * variant (texture presence, skinning, morphs, alpha mode, IBL flags).
 *
 * Programs are compiled once per variant and cached; mesh geometry is
 * uploaded once per avatar. All methods must run on the GL thread.
 */
object PbrPipeline {

    private data class ProgramKey(
        val jointCount: Int,
        val morphCount: Int,
        val hasBaseTex: Boolean,
        val hasMrTex: Boolean,
        val hasNormalTex: Boolean,
        val hasOcclusionTex: Boolean,
        val hasEmissiveTex: Boolean,
        val hasEmissive: Boolean,
        val hasTangent: Boolean,
        val useIbl: Boolean,
        val usePrefilter: Boolean,
        val alphaMode: String,
        val lightCount: Int
    )

    private class ProgramLocs(program: Int) {
        val uModel = loc(program, "uModel")
        val uView = loc(program, "uView")
        val uProj = loc(program, "uProj")
        val uJointMatrices = loc(program, "uJointMatrices")
        val uMorphWeights = loc(program, "uMorphWeights")
        val uBaseColorFactor = loc(program, "uBaseColorFactor")
        val uMetallicFactor = loc(program, "uMetallicFactor")
        val uRoughnessFactor = loc(program, "uRoughnessFactor")
        val uEmissiveFactor = loc(program, "uEmissiveFactor")
        val uNormalScale = loc(program, "uNormalScale")
        val uOcclusionStrength = loc(program, "uOcclusionStrength")
        val uAlphaCutoff = loc(program, "uAlphaCutoff")
        val uTime = loc(program, "uTime")
        val uBaseColorTex = loc(program, "uBaseColorTex")
        val uMetallicRoughnessTex = loc(program, "uMetallicRoughnessTex")
        val uNormalTex = loc(program, "uNormalTex")
        val uOcclusionTex = loc(program, "uOcclusionTex")
        val uEmissiveTex = loc(program, "uEmissiveTex")
        val uCameraPos = loc(program, "uCameraPos")
        val uLights = loc(program, "uLights")
        val uLightCount = loc(program, "uLightCount")
        val uExposure = loc(program, "uExposure")
        val uIBLIntensity = loc(program, "uIBLIntensity")
        val uIrrMap = loc(program, "uIrrMap")
        val uPrefilterMap = loc(program, "uPrefilterMap")
        val uBrdfLut = loc(program, "uBrdfLut")

        private fun loc(program: Int, name: String): Int =
            GLES30.glGetUniformLocation(program, name)
    }

    private class MeshGpu(
        val program: Int,
        val vao: Int,
        val buffers: IntArray,
        val indexBuffer: Int,
        val indexCount: Int,
        val hasIndices: Boolean,
        val morphCount: Int,
        val skinned: Boolean,
        val alphaMode: String,
        val doubleSided: Boolean,
        val locs: ProgramLocs
    )

    private val programs = HashMap<ProgramKey, Int>()
    private val locs = HashMap<Int, ProgramLocs>()
    private val meshResources = HashMap<GpuMesh, MeshGpu>()

    // fixed attribute locations
    private const val ATTR_POSITION = 0
    private const val ATTR_NORMAL = 1
    private const val ATTR_UV = 2
    private const val ATTR_TANGENT = 3
    private const val ATTR_JOINTS = 4
    private const val ATTR_WEIGHTS = 5
    private const val ATTR_MORPH_BASE = 6
    private const val MAX_MORPH = 8

    fun upload(avatar: HdAvatar) {
        val jointCount = avatar.jointMatrices?.size?.div(16) ?: 0
        for (mesh in avatar.meshes) {
            if (meshResources.containsKey(mesh)) continue
            meshResources[mesh] = buildMeshGpu(mesh, jointCount)
        }
    }

    fun release(avatar: HdAvatar) {
        for (mesh in avatar.meshes) {
            val gpu = meshResources.remove(mesh) ?: continue
            GLES30.glDeleteVertexArrays(1, intArrayOf(gpu.vao), 0)
            if (gpu.buffers.isNotEmpty()) {
                GLES30.glDeleteBuffers(gpu.buffers.size, gpu.buffers, 0)
            }
            if (gpu.indexBuffer != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(gpu.indexBuffer), 0)
            }
        }
    }

    fun draw(
        avatar: HdAvatar,
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        cameraPos: FloatArray,
        lights: FloatArray,
        lightCount: Int,
        exposure: Float,
        iblIntensity: Float,
        timeSeconds: Float
    ) {
        val jointCount = avatar.jointMatrices?.size?.div(16) ?: 0
        for (mesh in avatar.meshes) {
            if (!meshResources.containsKey(mesh)) {
                meshResources[mesh] = buildMeshGpu(mesh, jointCount)
            }
        }

        // opaque pass (sorted front-to-back for early-z), then blend pass
        val opaque = ArrayList<Pair<GpuMesh, MeshGpu>>()
        val blend = ArrayList<Pair<GpuMesh, MeshGpu>>()
        for (mesh in avatar.meshes) {
            val gpu = meshResources[mesh] ?: continue
            if (gpu.alphaMode == "BLEND") blend.add(mesh to gpu) else opaque.add(mesh to gpu)
        }
        val camX = cameraPos[0]; val camY = cameraPos[1]; val camZ = cameraPos[2]
        fun distSq(m: GpuMesh): Float {
            val c = m.positions.size / 3
            if (c == 0) return 0f
            val idx = (c / 2) * 3
            val dx = m.positions[idx] - camX
            val dy = m.positions[idx + 1] - camY
            val dz = m.positions[idx + 2] - camZ
            return dx * dx + dy * dy + dz * dz
        }
        opaque.sortBy { distSq(it.first) }
        blend.sortByDescending { distSq(it.first) }

        for (mesh in opaque) drawMesh(mesh.second, mesh.first, modelMatrix, viewMatrix,
            projMatrix, cameraPos, lights, lightCount, exposure, iblIntensity, timeSeconds,
            jointCount, false)
        for (mesh in blend) drawMesh(mesh.second, mesh.first, modelMatrix, viewMatrix,
            projMatrix, cameraPos, lights, lightCount, exposure, iblIntensity, timeSeconds,
            jointCount, true)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    private fun drawMesh(
        gpu: MeshGpu,
        mesh: GpuMesh,
        model: FloatArray,
        view: FloatArray,
        proj: FloatArray,
        cameraPos: FloatArray,
        lights: FloatArray,
        lightCount: Int,
        exposure: Float,
        iblIntensity: Float,
        timeSeconds: Float,
        jointCount: Int,
        blendPass: Boolean
    ) {
        val program = gpu.program
        GLES30.glUseProgram(program)
        val l = gpu.locs

        GLES30.glUniformMatrix4fv(l.uModel, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(l.uView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(l.uProj, 1, false, proj, 0)
        GLES30.glUniform3f(l.uCameraPos, cameraPos[0], cameraPos[1], cameraPos[2])

        if (gpu.skinned && l.uJointMatrices >= 0 && jointCount > 0) {
            val m = mesh.skinningMatrices
            if (m != null && m.size >= jointCount * 16) {
                GLES30.glUniformMatrix4fv(l.uJointMatrices, jointCount, false, m, 0)
            }
        }

        if (gpu.morphCount > 0 && l.uMorphWeights >= 0) {
            val w = FloatArray(8)
            val mw = mesh.morphWeights
            for (i in 0 until minOf(8, mw.size)) w[i] = mw[i]
            GLES30.glUniform1fv(l.uMorphWeights, 8, w, 0)
        }

        val mat = mesh.material
        GLES30.glUniform4f(l.uBaseColorFactor,
            mat.baseColor.getOrElse(0) { 1f },
            mat.baseColor.getOrElse(1) { 1f },
            mat.baseColor.getOrElse(2) { 1f },
            mat.baseColor.getOrElse(3) { 1f })
        GLES30.glUniform1f(l.uMetallicFactor, mat.metallic)
        GLES30.glUniform1f(l.uRoughnessFactor, mat.roughness)
        GLES30.glUniform3f(l.uEmissiveFactor,
            mat.emissive.getOrElse(0) { 0f },
            mat.emissive.getOrElse(1) { 0f },
            mat.emissive.getOrElse(2) { 0f })
        GLES30.glUniform1f(l.uNormalScale, mat.normalScale)
        GLES30.glUniform1f(l.uOcclusionStrength, mat.occlusionStrength)
        GLES30.glUniform1f(l.uAlphaCutoff, 0.5f)
        GLES30.glUniform1f(l.uTime, timeSeconds)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.baseColorTexture)
        GLES30.glUniform1i(l.uBaseColorTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.metallicRoughnessTexture)
        GLES30.glUniform1i(l.uMetallicRoughnessTex, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.normalTexture)
        GLES30.glUniform1i(l.uNormalTex, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.occlusionTexture)
        GLES30.glUniform1i(l.uOcclusionTex, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mat.emissiveTexture)
        GLES30.glUniform1i(l.uEmissiveTex, 4)

        if (IblEnvironment.isBuilt) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, IblEnvironment.irradianceCube)
            GLES30.glUniform1i(l.uIrrMap, 5)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, IblEnvironment.prefilterCube)
            GLES30.glUniform1i(l.uPrefilterMap, 6)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, IblEnvironment.brdfLut)
            GLES30.glUniform1i(l.uBrdfLut, 7)
        }

        if (lights.isNotEmpty() && lightCount > 0) {
            GLES30.glUniform4fv(l.uLights, lightCount * 2, lights, 0)
            GLES30.glUniform1i(l.uLightCount, lightCount)
        } else {
            GLES30.glUniform1i(l.uLightCount, 0)
        }
        GLES30.glUniform1f(l.uExposure, exposure)
        GLES30.glUniform1f(l.uIBLIntensity, iblIntensity)

        // state
        if (gpu.alphaMode == "BLEND") {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
        }
        if (gpu.doubleSided) {
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        }

        GLES30.glBindVertexArray(gpu.vao)
        if (gpu.hasIndices) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gpu.indexBuffer)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gpu.indexCount,
                GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        } else {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, gpu.indexCount)
        }
        GLES30.glBindVertexArray(0)

        if (gpu.doubleSided) {
            GLES30.glEnable(GLES30.GL_CULL_FACE)
        }
        if (gpu.alphaMode == "BLEND") {
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun buildMeshGpu(
        mesh: GpuMesh,
        jointCount: Int
    ): MeshGpu {
        val morphCount = mesh.morphTargets.size.coerceAtMost(MAX_MORPH)
        val skinned = mesh.joints != null && mesh.weights != null && jointCount > 0
        val mat = mesh.material
        val hasBase = mat.baseColorTexture != 0
        val hasMr = mat.metallicRoughnessTexture != 0
        val hasNormal = mat.normalTexture != 0
        val hasOcc = mat.occlusionTexture != 0
        val hasEmisTex = mat.emissiveTexture != 0
        val hasEmis = hasEmisTex || mat.emissive.any { it > 0f }
        val hasTangent = mesh.tangents != null
        val ibl = IblEnvironment.isBuilt
        val key = ProgramKey(
            jointCount = if (skinned) jointCount else 0,
            morphCount = morphCount,
            hasBaseTex = hasBase,
            hasMrTex = hasMr,
            hasNormalTex = hasNormal,
            hasOcclusionTex = hasOcc,
            hasEmissiveTex = hasEmisTex,
            hasEmissive = hasEmis,
            hasTangent = hasTangent,
            useIbl = ibl,
            usePrefilter = ibl,
            alphaMode = mat.alphaMode,
            lightCount = 4
        )

        val program = programs.getOrPut(key) {
            buildProgram(key)
        }
        val l = locs.getOrPut(program) { ProgramLocs(program) }

        val vao = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        val buffers = mutableListOf<Int>()

        fun uploadFloatAttribute(
            location: Int,
            data: FloatArray,
            components: Int,
            enabled: Boolean
        ) {
            if (!enabled || data.isEmpty()) {
                GLES30.glDisableVertexAttribArray(location)
                return
            }
            val vbo = intArrayOf(0)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                data.size * 4,
                floatBuffer(data),
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, components, GLES30.GL_FLOAT, false, 0, 0)
            buffers += vbo[0]
        }

        uploadFloatAttribute(ATTR_POSITION, mesh.positions, 3, true)
        uploadFloatAttribute(ATTR_NORMAL, mesh.normals, 3, true)
        uploadFloatAttribute(ATTR_UV, mesh.uvs, 2, true)
        uploadFloatAttribute(ATTR_TANGENT, mesh.tangents ?: FloatArray(0), 4, hasTangent)

        if (skinned) {
            // joints as normalized=false unsigned bytes
            val jv = mesh.joints!!
            val jointBytes = ByteArray(jv.size)
            for (i in jv.indices) jointBytes[i] = (jv[i] and 0xFF).toByte()
            val vbo = intArrayOf(0)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                jointBytes.size,
                ByteBuffer.wrap(jointBytes),
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glEnableVertexAttribArray(ATTR_JOINTS)
            GLES30.glVertexAttribPointer(ATTR_JOINTS, 4, GLES30.GL_UNSIGNED_BYTE, false, 0, 0)
            buffers += vbo[0]

            uploadFloatAttribute(ATTR_WEIGHTS, mesh.weights!!, 4, true)
        } else {
            GLES30.glDisableVertexAttribArray(ATTR_JOINTS)
            GLES30.glDisableVertexAttribArray(ATTR_WEIGHTS)
        }

        // morph target attributes (constant (0,0,0) when a delta is absent)
        for (i in 0 until morphCount) {
            val target = mesh.morphTargets[i]
            uploadFloatAttribute(
                ATTR_MORPH_BASE + i,
                target.positionDeltas ?: FloatArray(0),
                3,
                target.positionDeltas != null
            )
            uploadFloatAttribute(
                ATTR_MORPH_BASE + MAX_MORPH + i,
                target.normalDeltas ?: FloatArray(0),
                3,
                target.normalDeltas != null
            )
            uploadFloatAttribute(
                ATTR_MORPH_BASE + MAX_MORPH * 2 + i,
                target.tangentDeltas ?: FloatArray(0),
                3,
                target.tangentDeltas != null
            )
        }

        val indexBuffer: Int
        var indexCount = mesh.positions.size / 3
        val hasIndices = mesh.indices.isNotEmpty() &&
            mesh.indices.size < mesh.positions.size / 3
        if (hasIndices) {
            indexCount = mesh.indices.size
            val ibo = intArrayOf(0)
            GLES30.glGenBuffers(1, ibo, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                mesh.indices.size * 4,
                intBuffer(mesh.indices),
                GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            indexBuffer = ibo[0]
        } else {
            indexBuffer = 0
        }

        GLES30.glBindVertexArray(0)

        return MeshGpu(
            program = program,
            vao = vao[0],
            buffers = buffers.toIntArray(),
            indexBuffer = indexBuffer,
            indexCount = indexCount,
            hasIndices = hasIndices,
            morphCount = morphCount,
            skinned = skinned,
            alphaMode = mat.alphaMode,
            doubleSided = mat.doubleSided,
            locs = l
        )
    }

    private fun buildProgram(key: ProgramKey): Int {
        val vs = AvatarShaders.vertexSource(key.jointCount, key.morphCount)
        val fs = AvatarShaders.fragmentSource(
            lightCount = key.lightCount,
            hasBaseTex = key.hasBaseTex,
            hasMrTex = key.hasMrTex,
            hasNormalTex = key.hasNormalTex,
            hasOcclusionTex = key.hasOcclusionTex,
            hasEmissiveTex = key.hasEmissiveTex,
            hasEmissive = key.hasEmissive,
            hasTangent = key.hasTangent,
            useIbl = key.useIbl,
            usePrefilter = key.usePrefilter,
            alphaMode = key.alphaMode
        )
        val program = GLES30.glCreateProgram()
        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vs)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("PBR program link failed: $log")
        }
        Log.d(TAG, "Compiled PBR variant $key")
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("PBR shader compile failed: $log")
        }
        return shader
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.position(0)  // returns Buffer on the Android stubs — ignore
        return buffer
    }

    private fun intBuffer(data: IntArray): IntBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        buffer.put(data)
        buffer.position(0)  // returns Buffer on the Android stubs — ignore
        return buffer
    }

    private const val TAG = "PbrPipeline"
}
