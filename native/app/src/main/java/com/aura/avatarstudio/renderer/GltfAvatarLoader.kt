package com.aura.avatarstudio.renderer

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dependency-free GLB 2.0 loader for the core mesh/accessor pipeline.
 *
 * Handles buffers, buffer views, accessors, meshes, PBR material values,
 * skin joints/weights and morph targets. Textures are not decoded here —
 * they are resolved on the GL thread by [GltfTextures] from the retained
 * document ([HdAvatar.gltf] / [HdAvatar.gltfBinary]).
 */
class GltfAvatarLoader(
    private val context: Context
) {

    fun loadFromAssets(
        assetName: String
    ): HdAvatar {
        val bytes = context.assets
            .open(assetName)
            .use { it.readBytes() }

        return loadGlb(bytes)
    }

    fun loadGlb(
        bytes: ByteArray
    ): HdAvatar {

        require(bytes.size >= 20) {
            "Invalid GLB: file too small"
        }

        val header =
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)

        val magic = header.int
        val version = header.int
        val length = header.int

        require(magic == GLB_MAGIC) {
            "Invalid GLB magic"
        }

        require(version == 2) {
            "Only GLB 2.0 is supported"
        }

        require(length <= bytes.size) {
            "Invalid GLB length"
        }

        var json: String? = null
        var binary: ByteArray? = null

        var offset = 12

        while (offset + 8 <= bytes.size) {

            val chunkHeader =
                ByteBuffer
                    .wrap(bytes, offset, 8)
                    .order(ByteOrder.LITTLE_ENDIAN)

            val chunkLength =
                chunkHeader.int

            val chunkType =
                chunkHeader.int

            val dataStart =
                offset + 8

            val dataEnd =
                dataStart + chunkLength

            require(dataEnd <= bytes.size) {
                "Invalid GLB chunk"
            }

            when (chunkType) {

                JSON_CHUNK -> {
                    json = String(
                        bytes,
                        dataStart,
                        chunkLength,
                        Charsets.UTF_8
                    ).trim()
                }

                BIN_CHUNK -> {
                    binary =
                        bytes.copyOfRange(
                            dataStart,
                            dataEnd
                        )
                }
            }

            offset = dataEnd
        }

        require(json != null) {
            "GLB JSON chunk missing"
        }

        require(binary != null) {
            "GLB binary chunk missing"
        }

        return parse(
            GltfDocument.parse(json!!),
            binary!!
        )
    }

    private fun parse(
        document: GltfDocument,
        binary: ByteArray
    ): HdAvatar {

        val avatar =
            HdAvatar()

        avatar.gltf = document
        avatar.gltfBinary = binary

        val skin =
            document.skins.firstOrNull()

        if (skin != null) {

            avatar.skeleton =
                AvatarSkeleton(
                    skin.joints.size
                )

            avatar.jointMatrices =
                SkeletonMatrices.compute(
                    document,
                    binary
                )
        }

        val scene =
            document.scenes
                .getOrNull(
                    document.scene
                )
                ?: document.scenes.firstOrNull()

        val rootNodes =
            scene?.nodes
                ?: document.nodes.indices.toList()

        rootNodes.forEach { nodeIndex ->

            parseNode(
                nodeIndex,
                document,
                binary,
                avatar
            )
        }

        return avatar
    }

    private fun parseNode(
        nodeIndex: Int,
        document: GltfDocument,
        binary: ByteArray,
        avatar: HdAvatar
    ) {

        val node =
            document.nodes
                .getOrNull(nodeIndex)
                ?: return

        node.mesh?.let { meshIndex ->

            val mesh =
                document.meshes
                    .getOrNull(meshIndex)

            val meshWeights =
                mesh?.weights

            mesh?.primitives?.forEach { primitive ->

                val gpuMesh =
                    buildPrimitive(
                        primitive,
                        document,
                        binary
                    )

                // glTF "mesh.weights": default morph weights for this mesh.
                if (
                    meshWeights != null &&
                    meshWeights.isNotEmpty() &&
                    primitive.targets.isNotEmpty()
                ) {
                    gpuMesh.morphWeights =
                        meshWeights.toFloatArray()
                }

                avatar.meshes += gpuMesh
            }
        }

        node.children.forEach { child ->

            parseNode(
                child,
                document,
                binary,
                avatar
            )
        }
    }

    private fun buildPrimitive(
        primitive: GltfPrimitive,
        document: GltfDocument,
        binary: ByteArray
    ): GpuMesh {

        val positions =
            readFloatAttribute(
                primitive.attributes["POSITION"],
                document,
                binary,
                3
            )

        require(positions.isNotEmpty()) {
            "Primitive has no POSITION attribute"
        }

        val rawNormals =
            readFloatAttribute(
                primitive.attributes["NORMAL"],
                document,
                binary,
                3
            )

        var normals =
            if (rawNormals.isEmpty()) {
                FloatArray(positions.size)
                    .also {
                        for (i in it.indices step 3) {
                            it[i + 1] = 1f
                        }
                    }
            } else {
                rawNormals
            }

        val rawUvs =
            readFloatAttribute(
                primitive.attributes["TEXCOORD_0"],
                document,
                binary,
                2
            )

        val uvs =
            if (rawUvs.isEmpty()) {
                FloatArray(
                    positions.size / 3 * 2
                )
            } else {
                rawUvs
            }

        val rawTangents =
            readFloatAttribute(
                primitive.attributes["TANGENT"],
                document,
                binary,
                4
            )

        var tangents: FloatArray? =
            if (rawTangents.isEmpty()) {
                null
            } else {
                rawTangents
            }

        val joints =
            primitive.attributes["JOINTS_0"]
                ?.let {
                    readIntAttribute(
                        it,
                        document,
                        binary,
                        4
                    )
                }

        val weights =
            primitive.attributes["WEIGHTS_0"]
                ?.let {
                    readFloatAttribute(
                        it,
                        document,
                        binary,
                        4
                    )
                }

        val indices =
            primitive.indices?.let {

                readIntAttribute(
                    it,
                    document,
                    binary,
                    1
                )

            } ?: IntArray(
                positions.size / 3
            ) { it }

        // glTF demands NORMAL, but real-world exports often omit it —
        // derive smooth normals from the triangles.
        val hasNormals = primitive.attributes.containsKey("NORMAL")
        if (!hasNormals) {
            normals =
                MeshGeometry.generateNormals(
                    positions,
                    indices
                )
        }

        // TANGENT is optional in glTF (WebGL renderers fall back to
        // surface-level shading); derive it from UVs when present.
        if (tangents == null && uvs.isNotEmpty()) {
            tangents =
                MeshGeometry.generateTangents(
                    positions,
                    normals,
                    uvs,
                    indices
                )
        }

        val material =
            primitive.material?.let {
                document.materials
                    .getOrNull(it)
            }?.toHdPbrMaterial()
                ?: HdPbrMaterial()

        val morphTargets =
            primitive.targets.map { target ->

                MorphTarget(
                    positionDeltas =
                        target["POSITION"]?.let {
                            readFloatAttribute(
                                it,
                                document,
                                binary,
                                3
                            )
                        },

                    normalDeltas =
                        target["NORMAL"]?.let {
                            readFloatAttribute(
                                it,
                                document,
                                binary,
                                3
                            )
                        },

                    tangentDeltas =
                        target["TANGENT"]?.let {
                            readFloatAttribute(
                                it,
                                document,
                                binary,
                                3
                            )
                        }
                )
            }

        return GpuMesh(
            positions = positions,
            normals = normals,
            tangents = tangents,
            uvs = uvs,
            joints = joints,
            weights = weights,
            indices = indices,
            material = material,
            morphTargets = morphTargets
        )
    }

    private fun readFloatAttribute(
        accessorIndex: Int?,
        document: GltfDocument,
        binary: ByteArray,
        expectedComponents: Int
    ): FloatArray {

        if (accessorIndex == null) {
            return FloatArray(0)
        }

        val accessor =
            document.accessors
                .getOrNull(accessorIndex)
                ?: return FloatArray(0)

        val components =
            accessor.componentCount()

        require(
            components == expectedComponents
        ) {
            "Unexpected component count: $components"
        }

        val count =
            accessor.count

        val output =
            FloatArray(
                count * components
            )

        val view =
            accessor.bufferView?.let {
                document.bufferViews
                    .getOrNull(it)
            }

        val bufferIndex =
            view?.buffer ?: 0

        val buffer =
            document.buffers
                .getOrNull(bufferIndex)

        val source =
            if (buffer?.uri == null) {
                binary
            } else {
                decodeDataUri(
                    buffer.uri
                )
            }

        val baseOffset =
            (view?.byteOffset ?: 0) +
                (accessor.byteOffset ?: 0)

        val componentSize =
            accessor.componentTypeSize()

        val packedStride =
            components *
                componentSize

        val stride =
            view?.byteStride
                ?: packedStride

        for (i in 0 until count) {

            val elementOffset =
                baseOffset +
                    i * stride

            for (c in 0 until components) {

                val componentOffset =
                    elementOffset +
                        c * componentSize

                output[
                    i * components + c
                ] = readComponentAsFloat(
                    source,
                    componentOffset,
                    accessor.componentType,
                    accessor.normalized
                )
            }
        }

        return output
    }

    private fun readIntAttribute(
        accessorIndex: Int,
        document: GltfDocument,
        binary: ByteArray,
        expectedComponents: Int
    ): IntArray {

        val accessor =
            document.accessors[accessorIndex]

        val components =
            accessor.componentCount()

        require(
            components == expectedComponents
        )

        val count =
            accessor.count

        val output =
            IntArray(
                count * components
            )

        val view =
            accessor.bufferView?.let {
                document.bufferViews[it]
            }

        val buffer =
            document.buffers[
                view?.buffer ?: 0
            ]

        val source =
            if (buffer.uri == null) {
                binary
            } else {
                decodeDataUri(
                    buffer.uri
                )
            }

        val baseOffset =
            (view?.byteOffset ?: 0) +
                (accessor.byteOffset ?: 0)

        val componentSize =
            accessor.componentTypeSize()

        val packedStride =
            components *
                componentSize

        val stride =
            view?.byteStride
                ?: packedStride

        for (i in 0 until count) {

            val elementOffset =
                baseOffset +
                    i * stride

            for (c in 0 until components) {

                output[
                    i * components + c
                ] = readComponentAsInt(
                    source,
                    elementOffset +
                        c * componentSize,
                    accessor.componentType
                )
            }
        }

        return output
    }

    private fun readComponentAsFloat(
        source: ByteArray,
        offset: Int,
        type: Int,
        normalized: Boolean
    ): Float {

        val buffer =
            ByteBuffer
                .wrap(source)
                .order(ByteOrder.LITTLE_ENDIAN)

        return when (type) {

            COMPONENT_FLOAT ->
                buffer.getFloat(offset)

            COMPONENT_UNSIGNED_BYTE -> {
                val value =
                    source[offset]
                        .toInt() and 0xFF

                if (normalized)
                    value / 255f
                else
                    value.toFloat()
            }

            COMPONENT_BYTE -> {
                val value =
                    source[offset].toInt()

                if (normalized)
                    maxOf(
                        -1f,
                        value / 127f
                    )
                else
                    value.toFloat()
            }

            COMPONENT_UNSIGNED_SHORT -> {
                val value =
                    buffer
                        .getShort(offset)
                        .toInt() and 0xFFFF

                if (normalized)
                    value / 65535f
                else
                    value.toFloat()
            }

            COMPONENT_SHORT -> {
                val value =
                    buffer
                        .getShort(offset)
                        .toInt()

                if (normalized)
                    maxOf(
                        -1f,
                        value / 32767f
                    )
                else
                    value.toFloat()
            }

            else ->
                error(
                    "Unsupported float component type: $type"
                )
        }
    }

    private fun readComponentAsInt(
        source: ByteArray,
        offset: Int,
        type: Int
    ): Int {

        val buffer =
            ByteBuffer
                .wrap(source)
                .order(ByteOrder.LITTLE_ENDIAN)

        return when (type) {

            COMPONENT_UNSIGNED_BYTE ->
                source[offset]
                    .toInt() and 0xFF

            COMPONENT_BYTE ->
                source[offset].toInt()

            COMPONENT_UNSIGNED_SHORT ->
                buffer
                    .getShort(offset)
                    .toInt() and 0xFFFF

            COMPONENT_SHORT ->
                buffer
                    .getShort(offset)
                    .toInt()

            COMPONENT_UNSIGNED_INT ->
                buffer
                    .getInt(offset)

            else ->
                error(
                    "Unsupported integer component type: $type"
                )
        }
    }

    private fun decodeDataUri(
        uri: String
    ): ByteArray =
        GltfDataUris.decode(uri)

    private fun GltfMaterial.toHdPbrMaterial():
        HdPbrMaterial {

        val pbr =
            pbrMetallicRoughness
                ?: GltfPbr()

        return HdPbrMaterial(
            baseColor =
                pbr.baseColorFactor
                    .toFloatArray(),

            metallic =
                pbr.metallicFactor,

            roughness =
                pbr.roughnessFactor,

            emissive =
                emissiveFactor
                    .toFloatArray(),

            normalScale =
                normalTexture?.scale ?: 1f,

            occlusionStrength =
                occlusionTexture
                    ?.strength ?: 1f,

            baseColorTextureIndex =
                pbr.baseColorTexture?.index ?: -1,

            metallicRoughnessTextureIndex =
                pbr.metallicRoughnessTexture?.index ?: -1,

            normalTextureIndex =
                normalTexture?.index ?: -1,

            occlusionTextureIndex =
                occlusionTexture?.index ?: -1,

            emissiveTextureIndex =
                emissiveTexture?.index ?: -1,

            alphaMode = alphaMode,
            doubleSided = doubleSided
        )
    }

    companion object {

        private const val GLB_MAGIC =
            0x46546C67

        private const val JSON_CHUNK =
            0x4E4F534A

        private const val BIN_CHUNK =
            0x004E4942

        private const val COMPONENT_BYTE =
            5120

        private const val COMPONENT_UNSIGNED_BYTE =
            5121

        private const val COMPONENT_SHORT =
            5122

        private const val COMPONENT_UNSIGNED_SHORT =
            5123

        private const val COMPONENT_UNSIGNED_INT =
            5125

        private const val COMPONENT_FLOAT =
            5126
    }
}
