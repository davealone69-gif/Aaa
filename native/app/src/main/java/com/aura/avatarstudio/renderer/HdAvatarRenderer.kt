package com.aura.avatarstudio.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * GLSurfaceView.Renderer for the HD avatar pipeline:
 *
 *  - orbit camera (drag to rotate, pinch to zoom — wired by the view)
 *  - PBR lighting: analytic light rig + runtime-built IBL environment
 *  - ACES tone mapping + exposure
 *  - per-frame animation (glTF channels + morph weights) and skinning
 *  - demo chrome: skybox, ground grid, light billboards
 *
 * All methods run on the GL thread (queueEvent for cross-thread calls).
 */
class HdAvatarRenderer(
    private val context: Context? = null
) : GLSurfaceView.Renderer {

    private var avatar: HdAvatar? = null
    private var skeletonRuntime: SkeletonMatrices.Runtime? = null
    private var animation: GltfAnimation? = null

    // ---- camera ---------------------------------------------------------
    var cameraTarget = floatArrayOf(0f, 0.85f, 0f)
    var cameraDistance = 2.6f
        private set
    var cameraYaw = 0.62f
        private set
    var cameraPitch = 0.34f
        private set
    private val fovYDegrees = 45f
    private var aspect = 1f

    // ---- exposure / lighting --------------------------------------------
    var exposure = 1.15f
    var iblIntensity = 0.9f
    var environmentEnabled = true

    /** 4 lights, 8 floats each: [px,py,pz,pw, r,g,b,intensity]. */
    val lights = FloatArray(4 * 8)
    val lightColors = arrayOf(
        floatArrayOf(1.00f, 0.92f, 0.80f),
        floatArrayOf(0.55f, 0.65f, 1.00f),
        floatArrayOf(0.70f, 0.80f, 1.00f),
        floatArrayOf(1.00f, 0.55f, 0.30f)
    )
    var lightCount = 4
    private val keyDirection = floatArrayOf(-0.55f, 0.85f, 0.40f)
    private val fillDirection = floatArrayOf(0.70f, 0.30f, -0.55f)
    private val rimDirection = floatArrayOf(-0.25f, 0.35f, -0.90f)
    private var pointLightRadius = 2.4f
    private var pointLightHeight = 1.25f

    // ---- model placement -------------------------------------------------
    private var modelMatrix = Mat4.identity()
    private var fitScale = 1f
    private var fitOffset = floatArrayOf(0f, 0f, 0f)

    // ---- demo chrome ------------------------------------------------------
    private var gridVao = 0
    private var gridVbo = 0
    private var gridProgram = 0
    private var gridViewLoc = 0
    private var gridProjLoc = 0
    private var gridSizeLoc = 0
    private var gridFadeLoc = 0

    private var billboardVao = 0
    private var billboardVbo = 0
    private var billboardProgram = 0
    private var bbViewLoc = 0
    private var bbProjLoc = 0
    private var bbModelLoc = 0
    private var bbColorLoc = 0

    private var animationTime = 0f
    private var firstFrame = true

    // ====================================================================
    // public API (call from the GL thread or via queueEvent)
    // ====================================================================

    fun setAvatar(newAvatar: HdAvatar) {
        val old = avatar
        if (old !== newAvatar) {
            old?.let {
                PbrPipeline.release(it)
                GltfTextures.release(it)
            }
            avatar = newAvatar
            fitAvatar(newAvatar)
            PbrPipeline.upload(newAvatar)
            GltfTextures.resolve(newAvatar)
            skeletonRuntime = newAvatar.gltf?.let { doc ->
                newAvatar.gltfBinary?.let { binary ->
                    SkeletonMatrices.buildRuntime(doc, binary)
                }
            }
            animation = GltfAnimations.forAvatar(newAvatar)
            Log.d(TAG, "Avatar loaded: ${newAvatar.meshes.size} mesh(es), " +
                "skinned=${newAvatar.skeleton != null}, " +
                "animated=${animation?.durationSeconds ?: 0f}s")
        }
    }

    fun clearAvatar() {
        avatar?.let {
            PbrPipeline.release(it)
            GltfTextures.release(it)
        }
        avatar = null
        skeletonRuntime = null
        animation = null
    }

    /** Drag deltas in pixels; converts to yaw/pitch. */
    fun rotateCamera(dx: Float, dy: Float, sensitivity: Float = 0.008f) {
        cameraYaw -= dx * sensitivity
        cameraPitch = (cameraPitch - dy * sensitivity)
            .coerceIn(-1.45f, 1.45f)
    }

    /** Multiplicative zoom (pinch gesture scale factor). */
    fun zoomCamera(factor: Float) {
        cameraDistance = (cameraDistance / factor)
            .coerceIn(0.6f, 8f)
    }

    fun pointLightEnabled(enabled: Boolean) {
        lightCount = if (enabled) 4 else 3
    }

    // ====================================================================
    // GLSurfaceView.Renderer
    // ====================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.02f, 0.02f, 0.03f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        if (environmentEnabled) {
            IblEnvironment.build()
        }
        buildGrid()
        buildBillboard()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        if (firstFrame) {
            firstFrame = false
            if (environmentEnabled) IblEnvironment.build()
        }

        val now = System.nanoTime() / 1e9f
        val dt = 1f / 60f

        // ---- animate -----------------------------------------------------
        val av = avatar
        if (av != null) {
            animationTime += dt
            animation?.apply(animationTime, av)
            skeletonRuntime?.let { runtime ->
                av.gltf?.let { doc ->
                    av.jointMatrices = SkeletonMatrices.update(doc, runtime)
                    for (mesh in av.meshes) {
                        if (mesh.joints != null) {
                            mesh.skinningMatrices = av.jointMatrices
                        }
                    }
                }
            }
        }

        // ---- camera & lights ----------------------------------------------
        val eye = cameraEye()
        val view = Mat4.lookAt(eye, cameraTarget, floatArrayOf(0f, 1f, 0f))
        val proj = Mat4.perspective(fovYDegrees, aspect, 0.05f, 60f)
        updateLights(now)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // skybox
        if (environmentEnabled && IblEnvironment.isBuilt) {
            IblEnvironment.drawSkybox(view, proj, exposure)
        }

        // avatar
        if (av != null) {
            updateModelMatrix()
            PbrPipeline.draw(
                avatar = av,
                modelMatrix = modelMatrix,
                viewMatrix = view,
                projMatrix = proj,
                cameraPos = eye,
                lights = lights,
                lightCount = lightCount,
                exposure = exposure,
                iblIntensity = if (environmentEnabled) iblIntensity else 0f,
                timeSeconds = now
            )
        }

        // demo chrome
        drawGrid(view, proj)
        if (lightCount >= 4) drawLightBillboards(view, proj, now)
    }

    // ====================================================================
    // internals
    // ====================================================================

    private fun cameraEye(): FloatArray {
        val cp = cos(cameraPitch)
        return floatArrayOf(
            cameraTarget[0] + cameraDistance * cp * sin(cameraYaw),
            cameraTarget[1] + cameraDistance * sin(cameraPitch),
            cameraTarget[2] + cameraDistance * cp * cos(cameraYaw)
        )
    }

    private fun updateLights(time: Float) {
        fun setLight(i: Int, px: Float, py: Float, pz: Float, pw: Float,
                       r: Float, g: Float, b: Float, intensity: Float) {
            val o = i * 8
            lights[o] = px; lights[o + 1] = py; lights[o + 2] = pz; lights[o + 3] = pw
            lights[o + 4] = r; lights[o + 5] = g; lights[o + 6] = b; lights[o + 7] = intensity
        }

        // key (warm directional)
        setLight(0,
            keyDirection[0], keyDirection[1], keyDirection[2], 0f,
            lightColors[0][0], lightColors[0][1], lightColors[0][2], 2.6f)

        // fill (cool directional)
        setLight(1,
            fillDirection[0], fillDirection[1], fillDirection[2], 0f,
            lightColors[1][0], lightColors[1][1], lightColors[1][2], 0.7f)

        // rim
        setLight(2,
            rimDirection[0], rimDirection[1], rimDirection[2], 0f,
            lightColors[2][0], lightColors[2][1], lightColors[2][2], 1.1f)

        // orbiting point light
        val angle = time * 0.55f
        setLight(3,
            cos(angle) * pointLightRadius, pointLightHeight, sin(angle) * pointLightRadius, 1f,
            lightColors[3][0], lightColors[3][1], lightColors[3][2], 7.0f)
    }

    /** Normalize scale/position: feet at y=0, height ~1.65, centered on XZ. */
    private fun fitAvatar(av: HdAvatar) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (mesh in av.meshes) {
            val p = mesh.positions
            for (i in 0 until p.size step 3) {
                if (p[i] < minX) minX = p[i]
                if (p[i] > maxX) maxX = p[i]
                if (p[i + 1] < minY) minY = p[i + 1]
                if (p[i + 1] > maxY) maxY = p[i + 1]
                if (p[i + 2] < minZ) minZ = p[i + 2]
                if (p[i + 2] > maxZ) maxZ = p[i + 2]
            }
        }
        val height = (maxY - minY).coerceAtLeast(1e-3f)
        fitScale = 1.65f / height
        val cx = (minX + maxX) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        fitOffset = floatArrayOf(-cx, -minY, -cz)
        cameraTarget = floatArrayOf(0f, 0.85f * fitScale, 0f)
        cameraDistance = 2.6f
    }

    private fun updateModelMatrix() {
        // T * S
        modelMatrix = floatArrayOf(
            fitScale, 0f, 0f, 0f,
            0f, fitScale, 0f, 0f,
            0f, 0f, fitScale, 0f,
            fitOffset[0], fitOffset[1], fitOffset[2], 1f
        )
    }

    // ------------------------------------------------------------------
    // demo chrome
    // ------------------------------------------------------------------

    private fun buildGrid() {
        gridProgram = ShaderUtil.buildProgram(
            DemoShaders.GRID_VERTEX, DemoShaders.GRID_FRAGMENT, "grid"
        )
        gridViewLoc = GLES30.glGetUniformLocation(gridProgram, "uView")
        gridProjLoc = GLES30.glGetUniformLocation(gridProgram, "uProj")
        gridSizeLoc = GLES30.glGetUniformLocation(gridProgram, "uGridSize")
        gridFadeLoc = GLES30.glGetUniformLocation(gridProgram, "uFadeStart")

        val verts = ArrayList<Float>()
        val extent = 5f
        val step = 0.5f
        var x = -extent
        while (x <= extent) {
            verts.add(x); verts.add(-0.02f); verts.add(-extent)
            verts.add(x); verts.add(-0.02f); verts.add(extent)
            x += step
        }
        var z = -extent
        while (z <= extent) {
            verts.add(-extent); verts.add(-0.02f); verts.add(z)
            verts.add(extent); verts.add(-0.02f); verts.add(z)
            z += step
        }
        val data = verts.toFloatArray()
        val vao = intArrayOf(0); val vbo = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4,
            ShaderUtil.floatBuffer(data), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
        gridVao = vao[0]; gridVbo = vbo[0]
    }

    private fun drawGrid(view: FloatArray, proj: FloatArray) {
        if (gridProgram == 0) return
        GLES30.glUseProgram(gridProgram)
        GLES30.glUniformMatrix4fv(gridViewLoc, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(gridProjLoc, 1, false, proj, 0)
        GLES30.glUniform1f(gridSizeLoc, 5f)
        GLES30.glUniform1f(gridFadeLoc, 4f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(gridVao)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 84)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun buildBillboard() {
        billboardProgram = ShaderUtil.buildProgram(
            DemoShaders.LIGHT_VERTEX, DemoShaders.LIGHT_FRAGMENT, "billboard"
        )
        bbViewLoc = GLES30.glGetUniformLocation(billboardProgram, "uView")
        bbProjLoc = GLES30.glGetUniformLocation(billboardProgram, "uProj")
        bbModelLoc = GLES30.glGetUniformLocation(billboardProgram, "uModel")
        bbColorLoc = GLES30.glGetUniformLocation(billboardProgram, "uColor")

        // octahedron, radius 1
        val v = floatArrayOf(
            1f, 0f, 0f,  -1f, 0f, 0f,  0f, 1f, 0f,
            1f, 0f, 0f,   0f, 1f, 0f,  0f, 0f, 1f,
            1f, 0f, 0f,   0f, 0f, 1f,  0f, -1f, 0f,
            1f, 0f, 0f,   0f, -1f, 0f, 0f, 0f, -1f,
            -1f, 0f, 0f,  0f, 0f, -1f, 0f, -1f, 0f,
            -1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, -1f,
            -1f, 0f, 0f,  0f, -1f, 0f, 0f, 1f, 0f,
            -1f, 0f, 0f,  0f, 0f, 1f,  0f, 1f, 0f
        )
        val vao = intArrayOf(0); val vbo = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4,
            ShaderUtil.floatBuffer(v), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
        billboardVao = vao[0]; billboardVbo = vbo[0]
    }

    private fun drawLightBillboards(view: FloatArray, proj: FloatArray, time: Float) {
        if (billboardProgram == 0) return
        GLES30.glUseProgram(billboardProgram)
        GLES30.glUniformMatrix4fv(bbViewLoc, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(bbProjLoc, 1, false, proj, 0)
        GLES30.glBindVertexArray(billboardVao)

        val angle = time * 0.55f
        val pos = floatArrayOf(
            cos(angle) * pointLightRadius, pointLightHeight, sin(angle) * pointLightRadius
        )
        val m = Mat4.translation(pos[0], pos[1], pos[2])
        val s = floatArrayOf(0.05f, 0.05f, 0.05f)
        // scale
        for (i in 0 until 3) {
            m[i * 4] *= s[i]
            m[i * 4 + 1] *= s[i]
            m[i * 4 + 2] *= s[i]
        }
        GLES30.glUniformMatrix4fv(bbModelLoc, 1, false, m, 0)
        GLES30.glUniform3f(bbColorLoc, lightColors[3][0], lightColors[3][1], lightColors[3][2])
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 24)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        private const val TAG = "HdAvatarRenderer"
    }
}
