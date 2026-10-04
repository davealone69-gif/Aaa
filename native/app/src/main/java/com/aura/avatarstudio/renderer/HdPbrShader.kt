package com.aura.avatarstudio.renderer

import android.opengl.GLES30
import com.aura.avatarstudio.renderer.hd.HdPbrTextures

/**
 * Single-program PBR shader with uniform-flag texture handling
 * (uHas*Map flags instead of compile-time specialization).
 *
 * Reference implementation for simple-light setups — one point light
 * (position + color + intensity), optional 5-map texture set bound either
 * from an [HdPbrMaterial] or from an [HdPbrTextures] bundle, factor
 * uniforms (base color / metallic / roughness / emissive), skinning via
 * weighted joint-matrix combination. The default pipeline ([PbrPipeline] +
 * [AvatarShaders]) specializes per material variant and adds IBL; keep
 * this class for "fast mode" / very simple lighting scenarios.
 *
 * Attribute layout matches the engine's mesh upload:
 *   0 aPosition, 1 aNormal, 2 aUv, 3 aTangent, 4 aJoints, 5 aWeights
 * (aJoints is uploaded as UBYTE floats, hence the vec4 + int() cast).
 */
class HdPbrShader {

    private var program = 0

    private var uModel = -1
    private var uView = -1
    private var uProjection = -1
    private var uCamera = -1
    private var uLightPosition = -1
    private var uLightColor = -1
    private var uLightIntensity = -1
    private var uEmissiveFactor = -1
    private var uBaseColor = -1
    private var uMetallic = -1
    private var uRoughness = -1
    private var uBaseColorMap = -1
    private var uNormalMap = -1
    private var uMetallicRoughnessMap = -1
    private var uOcclusionMap = -1
    private var uEmissiveMap = -1
    private var uHasBaseColorMap = -1
    private var uHasNormalMap = -1
    private var uHasMetallicRoughnessMap = -1
    private var uHasOcclusionMap = -1
    private var uHasEmissiveMap = -1
    private var uJointMatrices = -1

    fun create() {

        val vertex =
            compile(
                GLES30.GL_VERTEX_SHADER,
                VERTEX
            )

        val fragment =
            compile(
                GLES30.GL_FRAGMENT_SHADER,
                FRAGMENT
            )

        program =
            GLES30.glCreateProgram()

        GLES30.glAttachShader(
            program,
            vertex
        )

        GLES30.glAttachShader(
            program,
            fragment
        )

        GLES30.glBindAttribLocation(
            program,
            0,
            "aPosition"
        )

        GLES30.glBindAttribLocation(
            program,
            1,
            "aNormal"
        )

        GLES30.glBindAttribLocation(
            program,
            2,
            "aUv"
        )

        GLES30.glBindAttribLocation(
            program,
            3,
            "aTangent"
        )

        GLES30.glBindAttribLocation(
            program,
            4,
            "aJoints"
        )

        GLES30.glBindAttribLocation(
            program,
            5,
            "aWeights"
        )

        GLES30.glLinkProgram(
            program
        )

        val status =
            IntArray(1)

        GLES30.glGetProgramiv(
            program,
            GLES30.GL_LINK_STATUS,
            status,
            0
        )

        check(status[0] != 0) {
            GLES30.glGetProgramInfoLog(
                program
            )
        }

        GLES30.glDeleteShader(
            vertex
        )

        GLES30.glDeleteShader(
            fragment
        )

        cache()
    }

    private fun cache() {

        uModel =
            uniform("uModel")

        uView =
            uniform("uView")

        uProjection =
            uniform("uProjection")

        uCamera =
            uniform("uCameraPosition")

        uLightPosition =
            uniform("uLightPosition")

        uLightColor =
            uniform("uLightColor")

        uLightIntensity =
            uniform("uLightIntensity")

        uEmissiveFactor =
            uniform("uEmissiveFactor")

        uBaseColor =
            uniform("uBaseColor")

        uMetallic =
            uniform("uMetallic")

        uRoughness =
            uniform("uRoughness")

        uBaseColorMap =
            uniform("uBaseColorMap")

        uNormalMap =
            uniform("uNormalMap")

        uMetallicRoughnessMap =
            uniform("uMetallicRoughnessMap")

        uOcclusionMap =
            uniform("uOcclusionMap")

        uEmissiveMap =
            uniform("uEmissiveMap")

        uHasBaseColorMap =
            uniform("uHasBaseColorMap")

        uHasNormalMap =
            uniform("uHasNormalMap")

        uHasMetallicRoughnessMap =
            uniform("uHasMetallicRoughnessMap")

        uHasOcclusionMap =
            uniform("uHasOcclusionMap")

        uHasEmissiveMap =
            uniform("uHasEmissiveMap")

        uJointMatrices =
            uniform("uJointMatrices")
    }

    private fun uniform(
        name: String
    ): Int =
        GLES30.glGetUniformLocation(
            program,
            name
        )

    fun bind() {
        GLES30.glUseProgram(
            program
        )
    }

    fun matrices(
        model: FloatArray,
        view: FloatArray,
        projection: FloatArray
    ) {

        GLES30.glUniformMatrix4fv(
            uModel,
            1,
            false,
            model,
            0
        )

        GLES30.glUniformMatrix4fv(
            uView,
            1,
            false,
            view,
            0
        )

        GLES30.glUniformMatrix4fv(
            uProjection,
            1,
            false,
            projection,
            0
        )
    }

    fun camera(
        value: FloatArray
    ) {

        GLES30.glUniform3fv(
            uCamera,
            1,
            value,
            0
        )
    }

    fun light(
        position: FloatArray,
        color: FloatArray,
        intensity: Float = 1f
    ) {

        GLES30.glUniform3fv(
            uLightPosition,
            1,
            position,
            0
        )

        GLES30.glUniform3fv(
            uLightColor,
            1,
            color,
            0
        )

        GLES30.glUniform1f(
            uLightIntensity,
            intensity
        )
    }

    fun material(
        material: HdPbrMaterial
    ) {

        GLES30.glUniform4fv(
            uBaseColor,
            1,
            material.baseColor,
            0
        )

        GLES30.glUniform1f(
            uMetallic,
            material.metallic
        )

        GLES30.glUniform1f(
            uRoughness,
            material.roughness
                .coerceIn(
                    0.04f,
                    1f
                )
        )

        GLES30.glUniform3fv(
            uEmissiveFactor,
            1,
            material.emissive,
            0
        )

        bindTexture(
            unit = 0,
            texture = material.baseColorTexture,
            location = uBaseColorMap,
            hasLocation = uHasBaseColorMap
        )

        bindTexture(
            unit = 1,
            texture = material.normalTexture,
            location = uNormalMap,
            hasLocation = uHasNormalMap
        )

        bindTexture(
            unit = 2,
            texture = material.metallicRoughnessTexture,
            location = uMetallicRoughnessMap,
            hasLocation = uHasMetallicRoughnessMap
        )

        bindTexture(
            unit = 3,
            texture = material.occlusionTexture,
            location = uOcclusionMap,
            hasLocation = uHasOcclusionMap
        )

        bindTexture(
            unit = 4,
            texture = material.emissiveTexture,
            location = uEmissiveMap,
            hasLocation = uHasEmissiveMap
        )
    }

    private fun bindTexture(
        unit: Int,
        texture: Int,
        location: Int,
        hasLocation: Int
    ) {

        GLES30.glActiveTexture(
            GLES30.GL_TEXTURE0 + unit
        )

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            texture
        )

        GLES30.glUniform1i(
            location,
            unit
        )

        GLES30.glUniform1i(
            hasLocation,
            if (texture != 0) 1 else 0
        )
    }

    /**
     * Binds a [HdPbrTextures] slot bundle (manager-loaded textures) onto
     * the standard units 0..4 with the uHas*Map flags, mirroring
     * [material]'s per-material binding path. Slots may be null.
     */
    fun bindTextures(
        textures: HdPbrTextures
    ) {

        bindTexture(
            unit = 0,
            texture = textures.baseColor?.id ?: 0,
            location = uBaseColorMap,
            hasLocation = uHasBaseColorMap
        )

        bindTexture(
            unit = 1,
            texture = textures.normal?.id ?: 0,
            location = uNormalMap,
            hasLocation = uHasNormalMap
        )

        bindTexture(
            unit = 2,
            texture = textures.metallicRoughness?.id ?: 0,
            location = uMetallicRoughnessMap,
            hasLocation = uHasMetallicRoughnessMap
        )

        bindTexture(
            unit = 3,
            texture = textures.occlusion?.id ?: 0,
            location = uOcclusionMap,
            hasLocation = uHasOcclusionMap
        )

        bindTexture(
            unit = 4,
            texture = textures.emissive?.id ?: 0,
            location = uEmissiveMap,
            hasLocation = uHasEmissiveMap
        )
    }

    fun joints(
        matrices: FloatArray
    ) {

        if (uJointMatrices >= 0) {

            GLES30.glUniformMatrix4fv(
                uJointMatrices,
                matrices.size / 16,
                false,
                matrices,
                0
            )
        }
    }

    fun destroy() {

        if (program != 0) {

            GLES30.glDeleteProgram(
                program
            )

            program = 0
        }
    }

    private fun compile(
        type: Int,
        source: String
    ): Int {

        val shader =
            GLES30.glCreateShader(type)

        GLES30.glShaderSource(
            shader,
            source
        )

        GLES30.glCompileShader(
            shader
        )

        val status =
            IntArray(1)

        GLES30.glGetShaderiv(
            shader,
            GLES30.GL_COMPILE_STATUS,
            status,
            0
        )

        check(status[0] != 0) {
            GLES30.glGetShaderInfoLog(
                shader
            )
        }

        return shader
    }

    companion object {

        private const val VERTEX = """
#version 300 es

precision highp float;
precision highp int;

layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
layout(location=3) in vec4 aTangent;
layout(location=4) in vec4 aJoints;
layout(location=5) in vec4 aWeights;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

const int MAX_JOINTS = 256;

uniform mat4 uJointMatrices[MAX_JOINTS];

out vec3 vWorldPosition;
out vec3 vNormal;
out vec2 vUv;
out vec4 vTangent;

void main() {

    float total =
        aWeights.x +
        aWeights.y +
        aWeights.z +
        aWeights.w;

    mat4 skin =
        aWeights.x * uJointMatrices[int(aJoints.x)] +
        aWeights.y * uJointMatrices[int(aJoints.y)] +
        aWeights.z * uJointMatrices[int(aJoints.z)] +
        aWeights.w * uJointMatrices[int(aJoints.w)];

    vec4 localPosition;

    if (total > 0.0001) {
        localPosition =
            skin *
            vec4(aPosition, 1.0);
    } else {
        localPosition =
            vec4(aPosition, 1.0);
    }

    vec4 world =
        uModel *
        localPosition;

    mat3 normalMatrix =
        transpose(
            inverse(
                mat3(uModel)
            )
        );

    vWorldPosition =
        world.xyz;

    vNormal =
        normalize(
            normalMatrix *
            aNormal
        );

    vUv =
        aUv;

    vTangent =
        aTangent;

    gl_Position =
        uProjection *
        uView *
        world;
}
"""

        private const val FRAGMENT = """
#version 300 es

precision highp float;

in vec3 vWorldPosition;
in vec3 vNormal;
in vec2 vUv;
in vec4 vTangent;

uniform vec3 uCameraPosition;

uniform vec3 uLightPosition;
uniform vec3 uLightColor;
uniform float uLightIntensity;
uniform vec3 uEmissiveFactor;

uniform vec4 uBaseColor;

uniform float uMetallic;
uniform float uRoughness;

uniform sampler2D uBaseColorMap;
uniform sampler2D uNormalMap;
uniform sampler2D uMetallicRoughnessMap;
uniform sampler2D uOcclusionMap;
uniform sampler2D uEmissiveMap;

uniform int uHasBaseColorMap;
uniform int uHasNormalMap;
uniform int uHasMetallicRoughnessMap;
uniform int uHasOcclusionMap;
uniform int uHasEmissiveMap;

out vec4 fragColor;

const float PI =
    3.14159265359;

vec3 getNormal() {

    vec3 N =
        normalize(vNormal);

    if (uHasNormalMap == 0) {
        return N;
    }

    vec3 T =
        normalize(vTangent.xyz);

    T =
        normalize(
            T -
            dot(T, N) * N
        );

    vec3 B =
        normalize(
            cross(N, T)
        ) *
        vTangent.w;

    mat3 TBN =
        mat3(T, B, N);

    vec3 normalSample =
        texture(
            uNormalMap,
            vUv
        ).xyz *
        2.0 -
        1.0;

    return normalize(
        TBN *
        normalSample
    );
}

float distributionGGX(
    vec3 N,
    vec3 H,
    float roughness
) {

    float a =
        roughness *
        roughness;

    float a2 =
        a * a;

    float NdotH =
        max(
            dot(N, H),
            0.0
        );

    float d =
        NdotH *
        NdotH *
        (a2 - 1.0) +
        1.0;

    return
        a2 /
        max(
            PI *
            d *
            d,
            0.000001
        );
}

float geometrySchlick(
    float NdotV,
    float roughness
) {

    float r =
        roughness +
        1.0;

    float k =
        (r * r) /
        8.0;

    return
        NdotV /
        (
            NdotV *
            (1.0 - k) +
            k
        );
}

float geometrySmith(
    vec3 N,
    vec3 V,
    vec3 L,
    float roughness
) {

    return
        geometrySchlick(
            max(dot(N,V),0.0),
            roughness
        ) *
        geometrySchlick(
            max(dot(N,L),0.0),
            roughness
        );
}

vec3 fresnel(
    float cosTheta,
    vec3 F0
) {

    return F0 +
        (1.0 - F0) *
        pow(
            1.0 - cosTheta,
            5.0
        );
}

void main() {

    vec4 base =
        uBaseColor;

    if (uHasBaseColorMap != 0) {
        base *=
            texture(
                uBaseColorMap,
                vUv
            );
    }

    vec3 albedo =
        pow(
            base.rgb,
            vec3(2.2)
        );

    float metallic =
        uMetallic;

    float roughness =
        uRoughness;

    if (
        uHasMetallicRoughnessMap != 0
    ) {

        vec4 mr =
            texture(
                uMetallicRoughnessMap,
                vUv
            );

        roughness *= mr.g;
        metallic *= mr.b;
    }

    roughness =
        clamp(
            roughness,
            0.04,
            1.0
        );

    vec3 N =
        getNormal();

    vec3 V =
        normalize(
            uCameraPosition -
            vWorldPosition
        );

    vec3 L =
        normalize(
            uLightPosition -
            vWorldPosition
        );

    float distanceToLight =
        length(
            uLightPosition -
            vWorldPosition
        );

    float attenuation =
        1.0 /
        max(
            distanceToLight *
            distanceToLight,
            0.01
        );

    vec3 H =
        normalize(
            V + L
        );

    vec3 F0 =
        mix(
            vec3(0.04),
            albedo,
            metallic
        );

    vec3 F =
        fresnel(
            max(dot(H,V),0.0),
            F0
        );

    float D =
        distributionGGX(
            N,
            H,
            roughness
        );

    float G =
        geometrySmith(
            N,
            V,
            L,
            roughness
        );

    vec3 numerator =
        D * G * F;

    float denominator =
        max(
            4.0 *
            max(dot(N,V),0.0) *
            max(dot(N,L),0.0),
            0.0001
        );

    vec3 specular =
        numerator /
        denominator;

    vec3 kS =
        F;

    vec3 kD =
        vec3(1.0) -
        kS;

    kD *=
        1.0 -
        metallic;

    float NdotL =
        max(
            dot(N,L),
            0.0
        );

    vec3 diffuse =
        kD *
        albedo /
        PI;

    vec3 radiance =
        uLightColor *
        uLightIntensity *
        attenuation;

    vec3 direct =
        (
            diffuse +
            specular
        ) *
        radiance *
        NdotL;

    float ao = 1.0;

    if (uHasOcclusionMap != 0) {
        ao =
            texture(
                uOcclusionMap,
                vUv
            ).r;
    }

    vec3 emissive =
        uEmissiveFactor;

    if (uHasEmissiveMap != 0) {

        emissive =
            pow(
                texture(
                    uEmissiveMap,
                    vUv
                ).rgb,
                vec3(2.2)
            );
    }

    vec3 ambient =
        albedo *
        0.035 *
        ao;

    vec3 color =
        ambient +
        direct +
        emissive;

    color =
        color /
        (color + vec3(1.0));

    color =
        pow(
            color,
            vec3(1.0 / 2.2)
        );

    fragColor =
        vec4(
            color,
            base.a
        );
}
"""
    }
}
