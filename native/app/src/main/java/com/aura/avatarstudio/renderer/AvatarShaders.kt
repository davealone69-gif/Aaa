package com.aura.avatarstudio.renderer

/**
 * GLSL ES 3.00 shader sources. No external dependencies, no includes —
 * everything is one self-contained program (mesh) plus a skybox program.
 */
object AvatarShaders {

    private const val VERTEX_PREAMBLE = """#version 300 es
precision highp float;
precision highp int;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;

in vec3 aPosition;
in vec3 aNormal;
in vec2 aUv;
in vec4 aTangent;
in vec4 aJoints;
in vec4 aWeights;
"""

    private const val VERTEX_COMMON = """
uniform mat4 uJointMatrices[%JOINT_COUNT%];
uniform float uMorphWeights[8];

out vec3 vWorldPos;
out vec3 vWorldNormal;
out vec2 vUv;
out vec3 vTangent;
out float vTangentSign;
out vec4 vColor;
"""

    private const val VERTEX_MAIN = """
void main() {

    vec4 pos = vec4(aPosition, 1.0);
    vec3 nrm = aNormal;
    vec3 tan = aTangent.xyz;
    float sign = aTangent.w;

    // ---- morph targets -------------------------------------------------
%MORPH_CODE%

    // ---- skinning -------------------------------------------------------
    %SKIN_CODE%

    vec4 worldPos = uModel * pos;
    vWorldPos = worldPos.xyz;
    vWorldNormal = normalize(mat3(uModel) * nrm);
    vUv = aUv;
    if (sign == 0.0) { sign = 1.0; }
    vTangent = normalize(mat3(uModel) * tan);
    vTangentSign = sign;
    vColor = vec4(1.0);
    gl_Position = uProj * uView * worldPos;
}
"""

    /** Injected when the mesh is skinned. */
    private const val SKIN_CODE = """
    vec4 skinned = vec4(0.0);
    skinned += aWeights.x * (uJointMatrices[int(aJoints.x)] * pos);
    skinned += aWeights.y * (uJointMatrices[int(aJoints.y)] * pos);
    skinned += aWeights.z * (uJointMatrices[int(aJoints.z)] * pos);
    skinned += aWeights.w * (uJointMatrices[int(aJoints.w)] * pos);
    pos = skinned;
    nrm = mat3(uJointMatrices[int(aJoints.x)]) * nrm * aWeights.x
        + mat3(uJointMatrices[int(aJoints.y)]) * nrm * aWeights.y
        + mat3(uJointMatrices[int(aJoints.z)]) * nrm * aWeights.z
        + mat3(uJointMatrices[int(aJoints.w)]) * nrm * aWeights.w;
    tan = mat3(uJointMatrices[int(aJoints.x)]) * tan * aWeights.x
        + mat3(uJointMatrices[int(aJoints.y)]) * tan * aWeights.y
        + mat3(uJointMatrices[int(aJoints.z)]) * tan * aWeights.z
        + mat3(uJointMatrices[int(aJoints.w)]) * tan * aWeights.w;
"""

    /** Injected when the mesh has no skin (identity 4x4). */
    private const val SKIN_CODE_NONE = """
    /* unskinned */
"""

    private const val FRAGMENT_PREAMBLE = """#version 300 es
precision highp float;
precision highp int;

#define PI 3.14159265359
#define MAX_LIGHTS %MAX_LIGHTS%
#define USE_TEX_BASE %USE_TEX_BASE%
#define USE_TEX_MR %USE_TEX_MR%
#define USE_TEX_NORMAL %USE_TEX_NORMAL%
#define USE_TEX_OCCLUSION %USE_TEX_OCCLUSION%
#define USE_TEX_EMISSIVE %USE_TEX_EMISSIVE%
#define USE_IBL %USE_IBL%
#define USE_IBL_PREFILTER %USE_IBL_PREFILTER%
#define HAS_EMISSIVE %HAS_EMISSIVE%
#define USE_ALPHA_BLEND %USE_ALPHA_BLEND%
#define USE_ALPHA_MASK %USE_ALPHA_MASK%
#define HAS_TANGENT %HAS_TANGENT%

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec2 vUv;
in vec3 vTangent;
in float vTangentSign;
in vec4 vColor;

layout(location = 0) out vec4 outColor;
"""

    private const val FRAGMENT_UNIFORMS = """
uniform vec4 uBaseColorFactor;
uniform float uMetallicFactor;
uniform float uRoughnessFactor;
uniform vec3 uEmissiveFactor;
uniform float uNormalScale;
uniform float uOcclusionStrength;
uniform float uAlphaCutoff;
uniform float uTime;

uniform sampler2D uBaseColorTex;
uniform sampler2D uMetallicRoughnessTex;
uniform sampler2D uNormalTex;
uniform sampler2D uOcclusionTex;
uniform sampler2D uEmissiveTex;

uniform vec3 uCameraPos;

struct Light {
    vec4 position;   // xyz, w=0 directional / w=1 point
    vec3 color;
    float intensity;
};
uniform Light uLights[MAX_LIGHTS];
uniform int uLightCount;
uniform float uExposure;
uniform float uIBLIntensity;

uniform samplerCube uIrrMap;
uniform samplerCube uPrefilterMap;
uniform sampler2D uBrdfLut;
"""

    private const val FRAGMENT_FUNCTIONS = """
// ---- BRDF helpers -----------------------------------------------------
float distributionGGX(vec3 n, vec3 h, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float nDotH = max(dot(n, h), 0.0);
    float nDotH2 = nDotH * nDotH;
    float denom = nDotH2 * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denom * denom, 1e-6);
}

float geometrySchlickGGX(float nDotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return nDotV / max(nDotV * (1.0 - k) + k, 1e-6);
}

float geometrySmith(vec3 n, vec3 v, vec3 l, float roughness) {
    float nDotV = max(dot(n, v), 0.0);
    float nDotL = max(dot(n, l), 0.0);
    return geometrySchlickGGX(nDotV, roughness) *
           geometrySchlickGGX(nDotL, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 fresnelSchlickRoughness(float cosTheta, vec3 f0, float roughness) {
    return f0 + (max(vec3(1.0 - roughness), f0) - f0) *
                pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// ---- textures ---------------------------------------------------------
vec4 textureBaseColor(vec2 uv) {
#if USE_TEX_BASE == 1
    return texture(uBaseColorTex, uv) * uBaseColorFactor;
#else
    return uBaseColorFactor;
#endif
}

void textureMetallicRoughness(vec2 uv, out float metallic, out float roughness) {
#if USE_TEX_MR == 1
    vec4 mr = texture(uMetallicRoughnessTex, uv);
    metallic = mr.b * uMetallicFactor;
    roughness = mr.g * uRoughnessFactor;
#else
    metallic = uMetallicFactor;
    roughness = uRoughnessFactor;
#endif
}

vec3 textureNormal(vec2 uv, vec3 n, vec3 t, float sign) {
#if USE_TEX_NORMAL == 1
    vec3 tn = texture(uNormalTex, uv).xyz * 2.0 - 1.0;
    tn.xy *= uNormalScale;
    tn.z = sqrt(max(1.0 - dot(tn.xy, tn.xy), 0.0));
    t = normalize(t - n * dot(n, t));
    vec3 b = cross(n, t) * sign;
    return normalize(t * tn.x + b * tn.y + n * tn.z);
#else
    return n;
#endif
}

float textureOcclusion(vec2 uv, vec2 aoUv) {
#if USE_TEX_OCCLUSION == 1
    float ao = texture(uOcclusionTex, aoUv).r;
    return mix(1.0, ao, uOcclusionStrength);
#else
    return 1.0;
#endif
}

vec3 textureEmissive(vec2 uv) {
#if USE_TEX_EMISSIVE == 1
    return texture(uEmissiveTex, uv).rgb * uEmissiveFactor;
#else
    return uEmissiveFactor;
#endif
}

// ---- IBL --------------------------------------------------------------
vec3 iblDiffuse(vec3 n) {
#if USE_IBL == 1
    return texture(uIrrMap, n).rgb;
#else
    return vec3(0.0);
#endif
}

vec3 iblSpecular(vec3 r, float roughness) {
#if USE_IBL_PREFILTER == 1
    return textureLod(uPrefilterMap, r, roughness * 8.0).rgb;
#else
    return vec3(0.0);
#endif
}

vec2 brdfLut(vec3 n, vec3 v, float roughness) {
    return texture(uBrdfLut, vec2(max(dot(n, v), 0.0), roughness)).rg;
}

// ---- tone mapping -----------------------------------------------------
vec3 reinhard(vec3 c) {
    return c / (c + vec3(1.0));
}

vec3 acesApprox(vec3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}
"""

    private const val FRAGMENT_MAIN = """
void main() {

    vec2 uv = vUv;
    vec3 n = normalize(vWorldNormal);
    vec3 v = normalize(uCameraPos - vWorldPos);

#if HAS_TANGENT == 1
    vec3 tangent = normalize(vTangent);
    n = textureNormal(uv, n, tangent, vTangentSign);
#else
    n = normalize(n);
#endif

    vec4 baseColor = textureBaseColor(uv);
    float metallic;
    float roughness;
    textureMetallicRoughness(uv, metallic, roughness);

#if USE_ALPHA_MASK == 1
    if (baseColor.a < uAlphaCutoff) discard;
#endif

    vec3 f0 = mix(vec3(0.04), baseColor.rgb, metallic);
    vec3 diffuseColor = baseColor.rgb * (1.0 - metallic);
    float ao = textureOcclusion(uv, uv);

    vec3 lo = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= uLightCount) break;
        Light light = uLights[i];
        vec3 l;
        float attenuation = 1.0;
        if (light.position.w == 0.0) {
            l = normalize(light.position.xyz);
        } else {
            vec3 toLight = light.position.xyz - vWorldPos;
            float dist = length(toLight);
            l = toLight / max(dist, 1e-4);
            attenuation = 1.0 / (dist * dist);
        }
        vec3 h = normalize(v + l);
        float nDotL = max(dot(n, l), 0.0);
        if (nDotL <= 0.0) continue;

        vec3 radiance = light.color * light.intensity * attenuation;

        float ndf = distributionGGX(n, h, roughness);
        float geo = geometrySmith(n, v, l, roughness);
        vec3 fresnel = fresnelSchlick(max(dot(h, v), 0.0), f0);

        vec3 numerator = ndf * geo * fresnel;
        float denominator = 4.0 * max(dot(n, v), 0.0) * nDotL + 1e-4;
        vec3 specular = numerator / denominator;

        vec3 kd = (1.0 - fresnel) * (1.0 - metallic);
        lo += (kd * diffuseColor / PI + specular) * radiance * nDotL;
    }

    // ---- image-based lighting ------------------------------------------
    vec3 ibl = vec3(0.0);
#if USE_IBL == 1
    vec3 f = fresnelSchlickRoughness(max(dot(n, v), 0.0), f0, roughness);
    vec3 kd = (1.0 - f) * (1.0 - metallic);
    vec3 irradiance = iblDiffuse(n);
    vec3 diffuse = irradiance * diffuseColor;
    vec3 r = reflect(-v, n);
    vec3 spec = iblSpecular(r, roughness) *
                (f * brdfLut(n, v, roughness).x +
                 brdfLut(n, v, roughness).y);
    ibl = (kd * diffuse + spec) * uIBLIntensity;
#endif

    vec3 emissive = vec3(0.0);
#if HAS_EMISSIVE == 1
    emissive = textureEmissive(uv);
#endif

    vec3 color = lo + ibl + emissive;
    color *= ao;

    // ---- exposure + tone mapping + gamma --------------------------------
    color *= uExposure;
    color = acesApprox(color);
    color = pow(color, vec3(1.0 / 2.2));

#if USE_ALPHA_BLEND == 1
    outColor = vec4(color, baseColor.a);
#else
    outColor = vec4(color, 1.0);
#endif
}
"""

    /** Mesh program, compiled per-mesh with baked specialization flags. */
    fun vertexSource(
        jointCount: Int,
        morphCount: Int
    ): String {
        val skinned = jointCount > 0
        val count = minOf(morphCount, 8)
        val sb = StringBuilder()
        for (i in 0 until count) {
            sb.append("in vec3 aMorph").append(i).append(";\n")
            sb.append("in vec3 aMorphN").append(i).append(";\n")
            sb.append("in vec3 aMorphT").append(i).append(";\n")
        }
        val morphCode = if (count == 0) {
            "    /* no morph targets */\n"
        } else {
            buildString {
                append("    pos.xyz += ")
                for (i in 0 until count) {
                    if (i > 0) append(" + ")
                    append("uMorphWeights[$i] * aMorph$i")
                }
                append(";\n    nrm += ")
                for (i in 0 until count) {
                    if (i > 0) append(" + ")
                    append("uMorphWeights[$i] * aMorphN$i")
                }
                append(";\n    tan += ")
                for (i in 0 until count) {
                    if (i > 0) append(" + ")
                    append("uMorphWeights[$i] * aMorphT$i")
                }
                append(";\n")
            }
        }
        return VERTEX_PREAMBLE +
            // GLSL forbids zero-sized arrays; [1] is fine since the
            // unskinned variant never indexes into the uniform.
            VERTEX_COMMON.replace("%JOINT_COUNT%", maxOf(1, jointCount).toString()) +
            sb.toString() +
            VERTEX_MAIN
                .replace("%MORPH_CODE%", morphCode)
                .replace("%SKIN_CODE%", if (skinned) SKIN_CODE else SKIN_CODE_NONE)
    }

    fun fragmentSource(
        lightCount: Int,
        hasBaseTex: Boolean,
        hasMrTex: Boolean,
        hasNormalTex: Boolean,
        hasOcclusionTex: Boolean,
        hasEmissiveTex: Boolean,
        hasEmissive: Boolean,
        hasTangent: Boolean,
        useIbl: Boolean,
        usePrefilter: Boolean,
        alphaMode: String
    ): String {
        return FRAGMENT_PREAMBLE
            // GLSL forbids zero-sized arrays; [1] is fine since the
            // zero-light variant never loops past uLightCount.
            .replace("%MAX_LIGHTS%", maxOf(1, lightCount).toString())
            .replace("%USE_TEX_BASE%", flag(hasBaseTex))
            .replace("%USE_TEX_MR%", flag(hasMrTex))
            .replace("%USE_TEX_NORMAL%", flag(hasNormalTex))
            .replace("%USE_TEX_OCCLUSION%", flag(hasOcclusionTex))
            .replace("%USE_TEX_EMISSIVE%", flag(hasEmissiveTex))
            .replace("%USE_IBL%", flag(useIbl))
            .replace("%USE_IBL_PREFILTER%", flag(useIbl && usePrefilter))
            .replace("%HAS_EMISSIVE%", flag(hasEmissive))
            .replace("%HAS_TANGENT%", flag(hasTangent))
            .replace(
                "%USE_ALPHA_BLEND%",
                flag(alphaMode == "BLEND")
            )
            .replace(
                "%USE_ALPHA_MASK%",
                flag(alphaMode == "MASK")
            ) +
            FRAGMENT_UNIFORMS +
            FRAGMENT_FUNCTIONS +
            FRAGMENT_MAIN
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
