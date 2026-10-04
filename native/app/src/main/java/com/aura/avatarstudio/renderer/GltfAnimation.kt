package com.aura.avatarstudio.renderer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal dependency-free glTF 2.0 animation runtime.
 *
 * Supports linear + step + (basic) cubic-spline interpolation on
 * translation/rotation/scale channels, plus morph-target weight channels.
 * Sparse accessors are skipped (loader limitation).
 */
class GltfAnimation(
    private val document: GltfDocument,
    private val binary: ByteArray
) {

    val durationSeconds: Float

    private data class Channel(
        val nodeIndex: Int,
        val path: String,
        val sampler: Sampler
    )

    private data class Sampler(
        val times: FloatArray,
        val output: FloatArray,
        val interpolation: String
    )

    private val channels = mutableListOf<Channel>()

    init {
        val animations: JSONArray? =
            document.raw?.optJSONArray("animations")

        if (animations == null) {
            durationSeconds = 0f
        } else {
            durationSeconds = parseAnimations(animations)
        }
    }

    private fun parseAnimations(animations: JSONArray): Float {
        var maxTime = 0f

        for (a in 0 until animations.length()) {
            val anim = animations.getJSONObject(a)
            val samplers = anim.optJSONArray("samplers")
            val channelsJson = anim.optJSONArray("channels")
            if (samplers == null || channelsJson == null) continue

            val parsedSamplers = HashMap<Int, Sampler>()

            for (s in 0 until samplers.length()) {
                val samplerJson = samplers.getJSONObject(s)
                val inputIndex = samplerJson.optInt("input", -1)
                val outputIndex = samplerJson.optInt("output", -1)
                if (inputIndex < 0 || outputIndex < 0) continue

                val inputAccessor = document.accessors.getOrNull(inputIndex) ?: continue
                val outputAccessor = document.accessors.getOrNull(outputIndex) ?: continue
                if (inputAccessor.sparse) continue // sparse accessors unsupported

                val times = readFloatAccessor(inputAccessor, document, binary, 1)
                if (times.isEmpty()) continue

                val outComponents = outputAccessor.componentCount()
                val output = readFloatAccessor(outputAccessor, document, binary, outComponents)
                if (output.isEmpty()) continue

                parsedSamplers[s] = Sampler(
                    times = times,
                    output = output,
                    interpolation =
                        samplerJson.optString(
                            "interpolation",
                            "LINEAR"
                        )
                )
                maxTime = maxOf(maxTime, times[times.size - 1])
            }

            for (c in 0 until channelsJson.length()) {
                val channelJson = channelsJson.getJSONObject(c)
                val sampler = parsedSamplers[channelJson.optInt("sampler", -1)] ?: continue

                val target = channelJson.optJSONObject("target") ?: continue
                val nodeIndex = target.optInt("node", -1)
                if (nodeIndex < 0) continue

                channels += Channel(
                    nodeIndex = nodeIndex,
                    path = target.optString("path", ""),
                    sampler = sampler
                )
            }
        }

        return maxTime
    }

    /**
     * Applies the animation at [timeSeconds] (looping) to node local
     * transforms in [document] and morph weights in [avatar].
     * Returns true when at least one channel applied.
     */
    fun apply(
        timeSeconds: Float,
        avatar: HdAvatar?
    ): Boolean {

        var applied = false
        val t = if (durationSeconds > 0f) {
            timeSeconds % durationSeconds
        } else {
            timeSeconds
        }

        val morphByNode = HashMap<Int, FloatArray>()

        for (channel in channels) {
            val sampler = channel.sampler
            val times = sampler.times
            if (times.isEmpty()) continue

            val index = findKeyframe(times, t)
            val t0 = times[index]
            val t1 = if (index + 1 < times.size) times[index + 1] else t0
            val span = t1 - t0
            val local = if (span > 1e-6f) (t - t0) / span else 0f
            val eased = if (sampler.interpolation == "STEP") 0f else local

            when (channel.path) {
                "translation" -> {
                    val node = document.nodes.getOrNull(channel.nodeIndex) ?: continue
                    node.translation =
                        sampleVec(sampler.output, index, 3, eased, sampler.interpolation)
                    applied = true
                }

                "rotation" -> {
                    val node = document.nodes.getOrNull(channel.nodeIndex) ?: continue
                    val q = sampleVec(sampler.output, index, 4, eased, sampler.interpolation)
                    normalizeQuat(q)
                    node.rotation = q
                    applied = true
                }

                "scale" -> {
                    val node = document.nodes.getOrNull(channel.nodeIndex) ?: continue
                    node.scale =
                        sampleVec(sampler.output, index, 3, eased, sampler.interpolation)
                    applied = true
                }

                "weights" -> {
                    if (avatar == null) continue
                    val count = sampler.output.size / sampler.times.size
                    if (count == 0) continue
                    val values = FloatArray(count) { k ->
                        lerp(
                            sampler.output.getOrElse(index * count + k) { 0f },
                            sampler.output.getOrElse((index + 1) * count + k) { 0f },
                            eased
                        )
                    }
                    morphByNode[channel.nodeIndex] = values
                    applied = true
                }
            }
        }

        if (applied && avatar != null && morphByNode.isNotEmpty()) {
            // The loader flattens meshes per primitive in scene order
            // (depth-first over root nodes); walk the same order.
            var meshSlot = 0
            fun visit(nodeIndex: Int) {
                val node = document.nodes.getOrNull(nodeIndex) ?: return
                if (node.mesh != null) {
                    val mesh = document.meshes.getOrNull(node.mesh)
                    val weights = morphByNode[nodeIndex]
                    mesh?.primitives?.forEach { prim ->
                        if (meshSlot < avatar.meshes.size) {
                            if (weights != null && prim.targets.isNotEmpty()) {
                                avatar.meshes[meshSlot].morphWeights =
                                    weights.copyOf()
                            }
                            meshSlot++
                        }
                    }
                }
                node.children.forEach { visit(it) }
            }
            val scene = document.scenes
                .getOrNull(document.scene)
                ?: document.scenes.firstOrNull()
            (scene?.nodes ?: document.nodes.indices.toList())
                .forEach { visit(it) }
        }

        return applied
    }

    private fun readFloatAccessor(
        accessor: GltfAccessor,
        document: GltfDocument,
        binary: ByteArray,
        components: Int
    ): FloatArray {

        val count = accessor.count
        val output = FloatArray(count * components)
        val view = accessor.bufferView?.let { document.bufferViews.getOrNull(it) }
        val buffer = document.buffers.getOrNull(view?.buffer ?: 0)
        val source =
            if (buffer?.uri == null) binary
            else GltfDataUris.decode(buffer.uri)

        val base = (view?.byteOffset ?: 0) + (accessor.byteOffset ?: 0)
        val componentSize = accessor.componentTypeSize()
        val packed = components * componentSize
        val stride = view?.byteStride ?: packed
        val bb = java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until count) {
            val element = base + i * stride
            for (c in 0 until components) {
                output[i * components + c] =
                    when (accessor.componentType) {
                        5126 -> bb.getFloat(element + c * 4)
                        5123 -> (bb.getShort(element + c * 2).toInt() and 0xFFFF).toFloat()
                        5121 -> (source[element + c].toInt() and 0xFF).toFloat()
                        else -> 0f
                    }
            }
        }
        return output
    }

    private fun sampleVec(
        output: FloatArray,
        index: Int,
        components: Int,
        eased: Float,
        interpolation: String
    ): FloatArray {
        if (interpolation == "CUBICSPLINE") {
            // glTF cubic spline: per keyframe (tangentIn, tangentOut, value)
            val stride = components * 3
            return FloatArray(components) { k ->
                lerp(
                    output.getOrElse(index * stride + stride - components + k) { 0f },
                    output.getOrElse((index + 1) * stride + stride - components + k) { 0f },
                    eased
                )
            }
        }
        return FloatArray(components) { k ->
            lerp(
                output.getOrElse(index * components + k) { 0f },
                output.getOrElse((index + 1) * components + k) { 0f },
                eased
            )
        }
    }

    private fun findKeyframe(times: FloatArray, t: Float): Int {
        for (i in 0 until times.size - 1) {
            if (t < times[i + 1]) return i
        }
        return maxOf(0, times.size - 2)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun normalizeQuat(q: FloatArray) {
        val len = kotlin.math.sqrt(
            q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]
        )
        if (len > 1e-8f) {
            for (i in 0 until 4) q[i] /= len
        } else {
            q[3] = 1f
        }
    }
}

/** Cache so animations parse once per avatar document. */
object GltfAnimations {

    private val cache = HashMap<Int, GltfAnimation>()

    fun forAvatar(
        avatar: HdAvatar
    ): GltfAnimation? {
        val document = avatar.gltf ?: return null
        val binary = avatar.gltfBinary ?: return null
        val id = System.identityHashCode(document)
        return cache.getOrPut(id) {
            GltfAnimation(document, binary)
        }
    }
}
