import com.aura.avatarstudio.renderer.*
import com.aura.avatarstudio.renderer.hd.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless end-to-end test: loads the generated test GLB through the real
 * GltfAvatarLoader, then verifies geometry, materials, morphs, skinning
 * matrices, animation sampling and the PBR draw pipeline (against a
 * no-op GL stub).
 */
fun main() {
    var passed = 0
    var failed = 0

    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
            println("  PASS  $name")
        } else {
            failed++
            println("  FAIL  $name  $detail")
        }
    }

    fun approx(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) <= eps

    // ------------------------------------------------------------ load
    val glbPath = "native/app/src/main/assets/avatars/my_avatar.glb"
    val bytes = File(glbPath).readBytes()
    check("GLB exists", bytes.size > 1000, "size=${bytes.size}")

    val loader = GltfAvatarLoader(android.content.Context())
    val avatar = loader.loadGlb(bytes)

    check("4 meshes loaded", avatar.meshes.size == 4,
        "got ${avatar.meshes.size}")
    check("skeleton present", avatar.skeleton?.jointCount == 4)
    check("joint matrices present", avatar.jointMatrices?.size == 64)

    val body = avatar.meshes[0]
    val head = avatar.meshes[1]
    val visor = avatar.meshes[2]
    val eyes = avatar.meshes[3]

    // ------------------------------------------------------------ body
    check("body vertices", body.positions.size == 432)
    check("body indices (UINT32)", body.indices.size == 216)
    check("body has tangents", body.tangents?.size == 576)
    check("body skinning", body.joints?.size == 576 && body.weights?.size == 576)
    check("body joints (1,0,0,0)",
        body.joints != null && body.joints.indices.all { i ->
            if (i % 4 == 0) body.joints[i] == 1 else body.joints[i] == 0
        })
    check("body weights (1,0,0,0)",
        body.weights != null && body.weights.indices.all { i ->
            if (i % 4 == 0) body.weights[i] == 1f else body.weights[i] == 0f
        })

    // tangent sanity (unit length, w = +/-1)
    var tangentsOk = true
    for (i in 0 until 144) {
        val t = body.tangents!!
        val len = sqrt(t[i * 4] * t[i * 4] + t[i * 4 + 1] * t[i * 4 + 1] + t[i * 4 + 2] * t[i * 4 + 2])
        if (abs(len - 1f) > 1e-3f || abs(abs(t[i * 4 + 3]) - 1f) > 1e-3f) tangentsOk = false
    }
    check("body tangents unit", tangentsOk)

    // ------------------------------------------------------------ materials
    val outfit = body.material
    check("outfit baseColorTex", outfit.baseColorTextureIndex == 0)
    check("outfit mrTex", outfit.metallicRoughnessTextureIndex == 2)
    check("outfit normalTex", outfit.normalTextureIndex == 1)
    check("outfit occlusionTex", outfit.occlusionTextureIndex == 3)
    check("outfit no emissive", outfit.emissiveTextureIndex == -1 &&
        outfit.emissive.all { it == 0f })
    check("outfit occlusion strength", approx(outfit.occlusionStrength, 0.85f))
    check("outfit alpha OPAQUE", outfit.alphaMode == "OPAQUE")

    check("visor MASK + doubleSided", visor.material.alphaMode == "MASK" &&
        visor.material.doubleSided)
    check("visor emissive tex", visor.material.emissiveTextureIndex == 4)
    check("visor emissive factor",
        approx(visor.material.emissive[0], 0.5f) &&
        approx(visor.material.emissive[1], 0.16f) &&
        approx(visor.material.emissive[2], 0.05f))

    check("head normalScale", approx(head.material.normalScale, 0.8f))
    check("eyes factor-only material", eyes.material.baseColorTextureIndex == -1 &&
        eyes.material.baseColor[0] < 0.05f)

    // ------------------------------------------------------------ morphs
    check("head has 1 morph target", head.morphTargets.size == 1)
    val smile = head.morphTargets[0].positionDeltas
    check("smile deltas size", smile?.size == 84)
    check("smile 4 nonzero", smile?.count { it != 0f } == 4)
    check("smile max dy ~0.012",
        smile != null && abs(smile.max() - 0.012f) < 1e-5f)
    check("head default morph weight 0", head.morphWeights.size == 1 &&
        head.morphWeights[0] == 0f)

    check("eyes has blink morph", eyes.morphTargets.size == 1)
    val blink = eyes.morphTargets[0].positionDeltas
    check("blink 4 nonzero", blink?.count { it != 0f } == 4)
    check("blink min dy ~-0.03", blink != null && abs(blink.min() + 0.03f) < 1e-5f)

    // ------------------------------------------------------------ generated normals/tangents (eyes has neither in GLB)
    var eyesNormalsOk = true
    for (i in 0 until 8) {
        val nz = eyes.normals[i * 3 + 2]
        val len = sqrt(eyes.normals[i * 3] * eyes.normals[i * 3] +
            eyes.normals[i * 3 + 1] * eyes.normals[i * 3 + 1] + nz * nz)
        if (abs(nz - 1f) > 1e-3f || abs(len - 1f) > 1e-3f) eyesNormalsOk = false
    }
    check("eyes normals generated (+Z)", eyesNormalsOk)
    check("eyes tangents generated", eyes.tangents?.size == 32 &&
        abs(sqrt(eyes.tangents!![0] * eyes.tangents[0] +
            eyes.tangents[1] * eyes.tangents[1] +
            eyes.tangents[2] * eyes.tangents[2]) - 1f) < 1e-3f)

    // ------------------------------------------------------------ byteStride path (eyes are interleaved, stride 32)
    check("eyes position[0] via stride", eyes.positions.size == 24 &&
        approx(eyes.positions[0], -0.1f) && approx(eyes.positions[1], 0.1775f))
    check("eyes uv via stride", approx(eyes.uvs[0], 0f) && approx(eyes.uvs[1], 0f))
    check("eyes joints (3,0,0,0)",
        eyes.joints != null && eyes.joints.indices.all { i ->
            if (i % 4 == 0) eyes.joints[i] == 3 else eyes.joints[i] == 0
        })
    check("eyes UINT16 indices", eyes.indices.size == 12 && eyes.indices[0] == 0)

    // ------------------------------------------------------------ skinning at rest
    val jm = avatar.jointMatrices!!
    var restOk = true
    for (j in 0 until 4) {
        for (k in 0 until 16) {
            val expected = if (k % 5 == 0) 1f else 0f
            if (abs(jm[j * 16 + k] - expected) > 1e-4f) restOk = false
        }
    }
    check("joint matrices identity at rest", restOk)

    // ------------------------------------------------------------ animation
    val anim = GltfAnimations.forAvatar(avatar)
    check("animation found", anim != null)
    check("animation duration ~3s", anim != null && abs(anim.durationSeconds - 3f) < 0.01f)

    anim!!.apply(1.5f, avatar)
    val doc = avatar.gltf!!
    check("torso swayed at t=1.5", abs(doc.nodes[1].rotation[2]) > 1e-3f)
    check("head swayed at t=1.5", abs(doc.nodes[3].rotation[2]) > 1e-3f)
    check("blink weight 1.0 at t=1.5", eyes.morphWeights.size == 1 &&
        approx(eyes.morphWeights[0], 1.0f))
    check("smile weight 0.375 at t=1.5", head.morphWeights.size == 1 &&
        approx(head.morphWeights[0], 0.375f, 1e-3f))

    // STEP interpolation: value holds until the NEXT keyframe
    anim.apply(0.3f, avatar)
    check("blink weight 0 at t=0.3", eyes.morphWeights.size == 1 &&
        eyes.morphWeights[0] == 0f)
    anim.apply(2.9f, avatar)
    check("blink weight 1.0 at t=2.9 (STEP holds)",
        eyes.morphWeights.size == 1 && approx(eyes.morphWeights[0], 1.0f))
    check("smile weight 0.585 at t=2.9", head.morphWeights.size == 1 &&
        approx(head.morphWeights[0], 0.585f, 1e-3f))
    anim.apply(3.0f, avatar)  // wraps to t=0 -> blink off
    check("blink weight 0 at loop wrap", eyes.morphWeights[0] == 0f)

    // ------------------------------------------------------------ skinning after animation
    anim.apply(1.5f, avatar)
    val runtime = SkeletonMatrices.buildRuntime(doc, avatar.gltfBinary!!)
    check("runtime rebuilt", runtime != null)
    val jm2 = SkeletonMatrices.update(doc, runtime!!)
    // torso joint (index 1) now carries Rz(0.07): m00 = cos(0.07) ~ 0.9976
    // (joint 0 = root never moves, stays identity)
    check("root joint still identity", abs(jm2[0] - 1f) < 1e-4f)
    check("torso matrix rotated after anim",
        abs(jm2[16] - 1f) > 1e-3f && abs(abs(jm2[16]) - 1f) < 1e-2f,
        "m00=${jm2[16]}")
    // Head inherits the torso sway (-0.07) plus its own counter-sway (+0.05):
    // joint matrix carries the combined Rz(-0.02) -> m00 = cos(0.02).
    check("head joint inherits parent rotation",
        abs(jm2[48] - kotlin.math.cos(0.02f)) < 1e-4f,
        "m00=${jm2[48]}")

    // ------------------------------------------------------------ data URI
    val emissiveImage = doc.images[4]
    check("emissive image is data URI", emissiveImage.uri?.startsWith("data:image/png") == true)
    val decoded = GltfDataUris.decode(emissiveImage.uri!!)
    check("data URI decodes to PNG",
        decoded.size > 8 &&
        decoded[0] == 0x89.toByte() &&
        decoded[1] == 'P'.code.toByte() &&
        decoded[2] == 'N'.code.toByte() &&
        decoded[3] == 'G'.code.toByte())

    // embedded image bufferView readable (PNG signature at view offset)
    val img0 = doc.images[0]
    val view0 = doc.bufferViews[img0.bufferView!!]
    val bin = avatar.gltfBinary!!
    check("embedded image PNG signature",
        bin[view0.byteOffset] == 0x89.toByte() &&
        bin[view0.byteOffset + 1] == 'P'.code.toByte())

    // ------------------------------------------------------------ GL smoke test (stubbed GL)
    try {
        IblEnvironment.build()
        check("IBL built (stub)", IblEnvironment.isBuilt)

        // texture resolve control flow (stub BitmapFactory returns null,
        // but data-URI + bufferView slicing + sampler lookup all run)
        GltfTextures.resolve(avatar)
        check("GltfTextures.resolve control flow", true)
        GltfTextures.release(avatar)

        // HdPbrShader — single-program PBR reference (stub GL)
        try {
            val shader = HdPbrShader()
            shader.create()
            shader.bind()
            shader.matrices(Mat4.identity(), Mat4.identity(), Mat4.identity())
            shader.camera(floatArrayOf(0f, 0f, 3f))
            shader.light(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 1f, 1f), 25f)
            shader.material(avatar.meshes[0].material)
            shader.bindTextures(HdPbrTextures())
            shader.joints(FloatArray(16) { i -> if (i % 5 == 0) 1f else 0f })
            shader.destroy()
            check("HdPbrShader API smoke test", true)
        } catch (e: Throwable) {
            check("HdPbrShader API smoke test", false, e.toString())
        }

        // HdTextureManager / HdTexture / HdPbrTextures — texture manager
        // layer (stub decode returns null -> clean null paths; missing
        // assets throw FileNotFoundException like real Android)
        try {
            val manager = HdTextureManager(android.content.Context())
            var threw = false
            try {
                manager.load("avatars/missing.png")
            } catch (e: java.io.FileNotFoundException) {
                threw = true
            }
            check("HdTextureManager.load missing asset throws FileNotFound", threw)
            check("HdTextureManager.loadResource missing returns null",
                manager.loadResource(0x7f010001) == null)
            manager.clear()
            check("HdTextureManager.clear after empty", true)
            manager.close()
            check("HdTextureManager.close", true)

            HdTexture(0, 512, 512, false).close()
            check("HdTexture id=0 close is a no-op", true)

            val slots = HdPbrTextures(
                baseColor = null,
                normal = null,
                metallicRoughness = null,
                occlusion = null,
                emissive = null
            )
            check("HdPbrTextures defaults", slots.baseColor == null && slots.emissive == null)
        } catch (e: Throwable) {
            check("HdTextureManager layer smoke test", false, e.toString())
        }

        // PbrTexture — asset texture utility (stub GL)
        try {
            val textures = PbrTexture(android.content.Context())
            textures.destroy(0)
            check("PbrTexture API smoke test", true)
        } catch (e: Throwable) {
            check("PbrTexture API smoke test", false, e.toString())
        }

        PbrPipeline.upload(avatar)
        PbrPipeline.draw(
            avatar = avatar,
            modelMatrix = Mat4.identity(),
            viewMatrix = Mat4.lookAt(
                floatArrayOf(0f, 1.2f, 3f),
                floatArrayOf(0f, 0.9f, 0f),
                floatArrayOf(0f, 1f, 0f)),
            projMatrix = Mat4.perspective(45f, 0.75f, 0.05f, 60f),
            cameraPos = floatArrayOf(0f, 1.2f, 3f),
            lights = FloatArray(32) { i -> if (i % 8 == 7) 1f else if (i % 8 == 3) 0f else 0.5f },
            lightCount = 4,
            exposure = 1.15f,
            iblIntensity = 0.9f,
            timeSeconds = 1.5f
        )
        check("PbrPipeline.draw smoke test", true)
    } catch (e: Throwable) {
        check("PbrPipeline.draw smoke test", false, e.toString())
    }

    // ------------------------------------------------------------ summary
    println()
    println("=============================================")
    println("  $passed passed, $failed failed")
    println("=============================================")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
