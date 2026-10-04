package com.aura.avatarstudio.renderer

import org.json.JSONArray
import org.json.JSONObject

data class GltfDocument(
    val scene: Int,
    val scenes: List<GltfScene>,
    val nodes: List<GltfNode>,
    val meshes: List<GltfMesh>,
    val accessors: List<GltfAccessor>,
    val bufferViews: List<GltfBufferView>,
    val buffers: List<GltfBuffer>,
    val materials: List<GltfMaterial>,
    val skins: List<GltfSkin>,
    val images: List<GltfImage> = emptyList(),
    val textures: List<GltfTexture> = emptyList(),
    val samplers: List<GltfSampler> = emptyList(),
    /** Retained raw JSON (animations etc.) for the animation runtime. */
    internal val raw: JSONObject? = null
) {

    companion object {

        fun parse(
            source: String
        ): GltfDocument {

            val root =
                JSONObject(source)

            return GltfDocument(
                raw = root,

                scene =
                    root.optInt(
                        "scene",
                        0
                    ),

                scenes =
                    root
                        .optJSONArray("scenes")
                        .toObjects {
                            GltfScene(
                                nodes =
                                    it
                                        .optJSONArray("nodes")
                                        .toIntList()
                            )
                        },

                nodes =
                    root
                        .optJSONArray("nodes")
                        .toObjects {
                            GltfNode(
                                mesh =
                                    if (
                                        it.has("mesh")
                                    )
                                        it.getInt("mesh")
                                    else null,

                                skin =
                                    if (
                                        it.has("skin")
                                    )
                                        it.getInt("skin")
                                    else null,

                                children =
                                    it
                                        .optJSONArray("children")
                                        .toIntList(),

                                translation =
                                    it
                                        .optJSONArray("translation")
                                        ?.toFloatList(
                                            3,
                                            0f
                                        )
                                        ?.toFloatArray()
                                        ?: floatArrayOf(0f, 0f, 0f),

                                rotation =
                                    it
                                        .optJSONArray("rotation")
                                        ?.toFloatList(
                                            4,
                                            0f
                                        )
                                        ?.toFloatArray()
                                        ?: floatArrayOf(0f, 0f, 0f, 1f),

                                scale =
                                    it
                                        .optJSONArray("scale")
                                        ?.toFloatList(
                                            3,
                                            1f
                                        )
                                        ?.toFloatArray()
                                        ?: floatArrayOf(1f, 1f, 1f)
                            )
                        },

                meshes =
                    root
                        .optJSONArray("meshes")
                        .toObjects {
                            GltfMesh(
                                primitives =
                                    it
                                        .optJSONArray(
                                            "primitives"
                                        )
                                        .toObjects {
                                            GltfPrimitive.from(
                                                it
                                            )
                                        },

                                weights =
                                    it
                                        .optJSONArray("weights")
                                        ?.let { array ->
                                            List(array.length()) { i ->
                                                array.getDouble(i).toFloat()
                                            }
                                        }
                            )
                        },

                accessors =
                    root
                        .optJSONArray("accessors")
                        .toObjects {
                            GltfAccessor.from(it)
                        },

                bufferViews =
                    root
                        .optJSONArray("bufferViews")
                        .toObjects {
                            GltfBufferView(
                                buffer =
                                    it.getInt("buffer"),

                                byteOffset =
                                    it.optInt(
                                        "byteOffset",
                                        0
                                    ),

                                byteLength =
                                    it.getInt(
                                        "byteLength"
                                    ),

                                byteStride =
                                    if (
                                        it.has("byteStride")
                                    )
                                        it.getInt(
                                            "byteStride"
                                        )
                                    else null
                            )
                        },

                buffers =
                    root
                        .optJSONArray("buffers")
                        .toObjects {
                            GltfBuffer(
                                uri =
                                    it.optString(
                                        "uri",
                                        null
                                    ),

                                byteLength =
                                    it.getInt(
                                        "byteLength"
                                    )
                            )
                        },

                materials =
                    root
                        .optJSONArray("materials")
                        .toObjects {
                            GltfMaterial.from(it)
                        },

                skins =
                    root
                        .optJSONArray("skins")
                        .toObjects {
                            GltfSkin(
                                joints =
                                    it
                                        .optJSONArray(
                                            "joints"
                                        )
                                        .toIntList(),

                                inverseBindMatrices =
                                    if (
                                        it.has(
                                            "inverseBindMatrices"
                                        )
                                    )
                                        it.getInt(
                                            "inverseBindMatrices"
                                        )
                                    else null
                            )
                        },

                images =
                    root
                        .optJSONArray("images")
                        .toObjects {
                            GltfImage(
                                uri =
                                    it.optString(
                                        "uri",
                                        null
                                    ),

                                bufferView =
                                    if (
                                        it.has("bufferView")
                                    )
                                        it.getInt(
                                            "bufferView"
                                        )
                                    else null,

                                mimeType =
                                    it.optString(
                                        "mimeType",
                                        null
                                    )
                            )
                        },

                textures =
                    root
                        .optJSONArray("textures")
                        .toObjects {
                            GltfTexture(
                                source =
                                    if (
                                        it.has("source")
                                    )
                                        it.getInt("source")
                                    else null,

                                sampler =
                                    if (
                                        it.has("sampler")
                                    )
                                        it.getInt("sampler")
                                    else null
                            )
                        },

                samplers =
                    root
                        .optJSONArray("samplers")
                        .toObjects {
                            GltfSampler(
                                magFilter =
                                    it.optInt(
                                        "magFilter",
                                        9729
                                    ),

                                minFilter =
                                    it.optInt(
                                        "minFilter",
                                        9987
                                    ),

                                wrapS =
                                    it.optInt(
                                        "wrapS",
                                        10497
                                    ),

                                wrapT =
                                    it.optInt(
                                        "wrapT",
                                        10497
                                    )
                            )
                        }
            )
        }
    }
}

data class GltfScene(
    val nodes: List<Int>
)

data class GltfNode(
    val mesh: Int?,
    val skin: Int?,
    val children: List<Int>,
    /** Local TRS; mutated by the animation runtime each frame. */
    var translation: FloatArray = floatArrayOf(0f, 0f, 0f),
    var rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    var scale: FloatArray = floatArrayOf(1f, 1f, 1f)
)

data class GltfMesh(
    val primitives: List<GltfPrimitive>,
    val weights: List<Float>? = null
)

data class GltfPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int?,
    val material: Int?,
    val targets: List<Map<String, Int>>
) {

    companion object {

        fun from(
            json: JSONObject
        ): GltfPrimitive {

            val attributes =
                mutableMapOf<String, Int>()

            val attributeJson =
                json
                    .optJSONObject(
                        "attributes"
                    )

            attributeJson
                ?.keys()
                ?.forEach { key ->
                    attributes[key] =
                        attributeJson
                            .getInt(key)
                }

            val targets =
                json
                    .optJSONArray("targets")
                    .toObjects { target ->

                        val result =
                            mutableMapOf<String, Int>()

                        target
                            .keys()
                            .forEach { key ->
                                result[key] =
                                    target.getInt(
                                        key
                                    )
                            }

                        result
                    }

            return GltfPrimitive(
                attributes =
                    attributes,

                indices =
                    if (
                        json.has("indices")
                    )
                        json.getInt("indices")
                    else null,

                material =
                    if (
                        json.has("material")
                    )
                        json.getInt("material")
                    else null,

                targets =
                    targets
            )
        }
    }
}

data class GltfAccessor(
    val bufferView: Int?,
    val byteOffset: Int?,
    val componentType: Int,
    val count: Int,
    val type: String,
    val normalized: Boolean,
    val sparse: Boolean = false
) {

    fun componentCount(): Int =
        when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else ->
                error(
                    "Unsupported accessor type: $type"
                )
        }

    fun componentTypeSize(): Int =
        when (componentType) {
            5120,
            5121 -> 1

            5122,
            5123 -> 2

            5125,
            5126 -> 4

            else ->
                error(
                    "Unsupported component type"
                )
        }

    companion object {

        fun from(
            json: JSONObject
        ) =
            GltfAccessor(
                bufferView =
                    if (
                        json.has("bufferView")
                    )
                        json.getInt(
                            "bufferView"
                        )
                    else null,

                byteOffset =
                    if (
                        json.has("byteOffset")
                    )
                        json.getInt(
                            "byteOffset"
                        )
                    else null,

                componentType =
                    json.getInt(
                        "componentType"
                    ),

                count =
                    json.getInt("count"),

                type =
                    json.getString("type"),

                normalized =
                    json.optBoolean(
                        "normalized",
                        false
                    ),

                sparse =
                    json.has("sparse")
            )
    }
}

data class GltfBufferView(
    val buffer: Int,
    val byteOffset: Int,
    val byteLength: Int,
    val byteStride: Int?
)

data class GltfBuffer(
    val uri: String?,
    val byteLength: Int
)

data class GltfSkin(
    val joints: List<Int>,
    val inverseBindMatrices: Int?
)

data class GltfMaterial(
    val pbrMetallicRoughness: GltfPbr?,
    val emissiveFactor: List<Float>,
    val normalTexture: GltfTextureInfo?,
    val occlusionTexture: GltfTextureInfo?,
    val emissiveTexture: GltfTextureInfo? = null,
    val alphaMode: String = "OPAQUE",
    val doubleSided: Boolean = false
) {

    companion object {

        fun from(
            json: JSONObject
        ): GltfMaterial {

            val pbrJson =
                json.optJSONObject(
                    "pbrMetallicRoughness"
                )

            val pbr =
                pbrJson?.let {
                    GltfPbr(
                        baseColorFactor =
                            it
                                .optJSONArray(
                                    "baseColorFactor"
                                )
                                .toFloatList(
                                    4,
                                    1f
                                ),

                        metallicFactor =
                            it.optDouble(
                                "metallicFactor",
                                1.0
                            ).toFloat(),

                        roughnessFactor =
                            it.optDouble(
                                "roughnessFactor",
                                1.0
                            ).toFloat(),

                        baseColorTexture =
                            it
                                .optJSONObject(
                                    "baseColorTexture"
                                )
                                ?.let(
                                    GltfTextureInfo::from
                                ),

                        metallicRoughnessTexture =
                            it
                                .optJSONObject(
                                    "metallicRoughnessTexture"
                                )
                                ?.let(
                                    GltfTextureInfo::from
                                )
                    )
                }

            return GltfMaterial(
                pbrMetallicRoughness =
                    pbr,

                emissiveFactor =
                    json
                        .optJSONArray(
                            "emissiveFactor"
                        )
                        .toFloatList(
                            3,
                            0f
                        ),

                normalTexture =
                    json
                        .optJSONObject(
                            "normalTexture"
                        )
                        ?.let(
                            GltfTextureInfo::from
                        ),

                occlusionTexture =
                    json
                        .optJSONObject(
                            "occlusionTexture"
                        )
                        ?.let(
                            GltfTextureInfo::from
                        ),

                emissiveTexture =
                    json
                        .optJSONObject(
                            "emissiveTexture"
                        )
                        ?.let(
                            GltfTextureInfo::from
                        ),

                alphaMode =
                    json.optString(
                        "alphaMode",
                        "OPAQUE"
                    ),

                doubleSided =
                    json.optBoolean(
                        "doubleSided",
                        false
                    )
            )
        }
    }
}

data class GltfPbr(
    val baseColorFactor: List<Float> =
        listOf(1f, 1f, 1f, 1f),

    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
    val baseColorTexture: GltfTextureInfo? = null,
    val metallicRoughnessTexture: GltfTextureInfo? = null
)

data class GltfTextureInfo(
    val index: Int,
    val scale: Float = 1f,
    val strength: Float = 1f
) {

    companion object {

        fun from(
            json: JSONObject
        ) =
            GltfTextureInfo(
                index =
                    json.getInt("index"),

                scale =
                    json.optDouble(
                        "scale",
                        1.0
                    ).toFloat(),

                strength =
                    json.optDouble(
                        "strength",
                        1.0
                    ).toFloat()
            )
    }
}

data class GltfImage(
    val uri: String?,
    val bufferView: Int?,
    val mimeType: String?
)

data class GltfTexture(
    val source: Int?,
    val sampler: Int?
)

data class GltfSampler(
    val magFilter: Int,
    val minFilter: Int,
    val wrapS: Int,
    val wrapT: Int
)

private fun JSONArray?.toIntList():
    List<Int> {

    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (i in 0 until length()) {
            add(getInt(i))
        }
    }
}

private fun JSONArray?.toFloatList(
    size: Int,
    default: Float
): List<Float> {

    if (this == null) {
        return List(size) { default }
    }

    return List(size) { index ->
        if (index < length()) {
            getDouble(index).toFloat()
        } else {
            default
        }
    }
}

private inline fun <T> JSONArray?.toObjects(
    mapper: (JSONObject) -> T
): List<T> {

    if (this == null) {
        return emptyList()
    }

    return buildList {
        for (i in 0 until length()) {
            add(
                mapper(
                    getJSONObject(i)
                )
            )
        }
    }
}
