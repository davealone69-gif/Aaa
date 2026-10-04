package com.aura.avatarstudio.renderer

import android.opengl.Matrix

/**
 * Computes per-joint skinning matrices:
 *   jointMatrix[i] = globalTransform(joint) x inverseBindMatrix(i)
 * (column-major 4x4, exactly what the vertex shader multiplies against
 * the skinning weights).
 *
 * [buildRuntime] caches the inverse-bind matrices once; [update] then
 * recomputes only the global transforms per frame (the animation runtime
 * mutates node TRS in place).
 */
object SkeletonMatrices {

    class Runtime(
        val joints: IntArray,
        val inverseBind: FloatArray
    )

    /** One-shot helper used by the loader at load time. */
    fun compute(
        document: GltfDocument,
        binary: ByteArray
    ): FloatArray {
        val runtime = buildRuntime(document, binary) ?: return FloatArray(0)
        return update(document, runtime)
    }

    fun buildRuntime(
        document: GltfDocument,
        binary: ByteArray
    ): Runtime? {
        val skin = document.skins.firstOrNull() ?: return null
        val joints = skin.joints.toIntArray()
        if (joints.isEmpty()) return null

        val ibm = FloatArray(joints.size * 16) { if (it % 5 == 0) 1f else 0f }

        skin.inverseBindMatrices?.let { ibmIndex ->
            val accessor = document.accessors.getOrNull(ibmIndex)
            if (accessor != null && accessor.componentCount() == 16) {
                val view = accessor.bufferView?.let { document.bufferViews.getOrNull(it) }
                val buffer = document.buffers.getOrNull(view?.buffer ?: 0)
                val source =
                    if (buffer?.uri == null) binary
                    else GltfDataUris.decode(buffer.uri)
                if (view != null && accessor.componentType == 5126) {
                    val base = view.byteOffset + (accessor.byteOffset ?: 0)
                    val stride = view.byteStride ?: 64
                    val bb = java.nio.ByteBuffer
                        .wrap(source)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (j in 0 until minOf(joints.size, accessor.count)) {
                        val off = base + j * stride
                        for (k in 0 until 16) {
                            ibm[j * 16 + k] = bb.getFloat(off + k * 4)
                        }
                    }
                }
            }
        }

        return Runtime(joints, ibm)
    }

    /** Recomputes joint matrices from the current node transforms. */
    fun update(
        document: GltfDocument,
        runtime: Runtime
    ): FloatArray {
        val joints = runtime.joints
        val ibm = runtime.inverseBind
        val output = FloatArray(joints.size * 16)
        val global = HashMap<Int, FloatArray>(document.nodes.size * 2)

        fun local(nodeIndex: Int): FloatArray {
            val node = document.nodes.getOrNull(nodeIndex)
                ?: return Mat4.identity()
            return Mat4.fromTrs(
                node.translation,
                node.rotation,
                node.scale
            )
        }

        fun computeGlobal(nodeIndex: Int): FloatArray {
            global[nodeIndex]?.let { return it }
            val node = document.nodes.getOrNull(nodeIndex)
            if (node == null) {
                val id = Mat4.identity()
                global[nodeIndex] = id
                return id
            }
            val parent = findParent(document, nodeIndex)
            val result =
                if (parent != null) {
                    Mat4.multiply(computeGlobal(parent), local(nodeIndex))
                } else {
                    local(nodeIndex)
                }
            global[nodeIndex] = result
            return result
        }

        for (j in joints.indices) {
            val g = computeGlobal(joints[j])
            val inv = ibm.slice(j * 16 until j * 16 + 16).toFloatArray()
            val m = FloatArray(16)
            Matrix.multiplyMM(m, 0, g, 0, inv, 0)
            System.arraycopy(m, 0, output, j * 16, 16)
        }

        return output
    }

    private fun findParent(
        document: GltfDocument,
        nodeIndex: Int
    ): Int? {
        for ((i, node) in document.nodes.withIndex()) {
            if (i == nodeIndex) continue
            if (node.children.contains(nodeIndex)) return i
        }
        return null
    }
}
