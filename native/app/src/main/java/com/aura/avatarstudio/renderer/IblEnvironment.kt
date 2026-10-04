package com.aura.avatarstudio.renderer

import android.opengl.GLES30
import android.util.Log

/**
 * Runtime IBL environment — zero assets, zero dependencies.
 *
 * Builds on the GPU, on the GL thread:
 *  1. radiance      : procedural analytic HDR sky, RGBA16F cubemap
 *  2. irradiance    : diffuse convolution (32^2 cubemap)
 *  3. prefiltered   : GGX prefiltered specular, 128^2 x 8 mips
 *  4. BRDF LUT      : split-sum integration, 256x256 RG16F
 *
 * [PbrPipeline] samples these directly; the skybox draw uses the
 * prefiltered cube as background.
 */
object IblEnvironment {

    var radianceCube: Int = 0
        private set
    var irradianceCube: Int = 0
        private set
    var prefilterCube: Int = 0
        private set
    var brdfLut: Int = 0
        private set

    var isBuilt: Boolean = false
        private set

    // Sky parameters (HDR-ish colors; ACES tone maps later).
    var sunDirection = floatArrayOf(0.28f, 0.86f, 0.42f)
        private set
    private var sunColor = floatArrayOf(1.0f, 0.86f, 0.62f)
    private var zenithColor = floatArrayOf(0.05f, 0.10f, 0.26f)
    private var horizonColor = floatArrayOf(0.42f, 0.48f, 0.62f)
    private var groundColor = floatArrayOf(0.10f, 0.10f, 0.12f)

    private const val RADIANCE_SIZE = 128
    private const val IRRADIANCE_SIZE = 32
    private const val PREFILTER_SIZE = 128
    private const val PREFILTER_MIPS = 8
    private const val BRDF_SIZE = 256
    private const val SAMPLE_COUNT = 1024

    private var skyRadianceProgram = 0
    private var irradianceProgram = 0
    private var prefilterProgram = 0
    private var brdfProgram = 0
    private var skyboxProgram = 0

    private var quadVao = 0
    private var quadVbo = 0
    private var cubeVao = 0
    private var cubeVbo = 0

    private val sunLoc = HashMap<Int, Int>()
    private val faceBasisLoc = HashMap<Int, Int>()
    private val radianceCubeLoc = HashMap<Int, Int>()
    private val roughnessLoc = HashMap<Int, Int>()
    private val viewRotLoc = HashMap<Int, Int>()
    private val projLoc = HashMap<Int, Int>()
    private val exposureLoc = HashMap<Int, Int>()
    private val skyboxLoc = HashMap<Int, Int>()
    private val sunDirLoc = HashMap<Int, Int>()
    private val zenithLoc = HashMap<Int, Int>()
    private val horizonLoc = HashMap<Int, Int>()
    private val groundLoc = HashMap<Int, Int>()

    fun configure(
        sunDir: FloatArray,
        sun: FloatArray = floatArrayOf(1f, 0.86f, 0.62f),
        zenith: FloatArray = floatArrayOf(0.05f, 0.10f, 0.26f),
        horizon: FloatArray = floatArrayOf(0.42f, 0.48f, 0.62f),
        ground: FloatArray = floatArrayOf(0.10f, 0.10f, 0.12f)
    ) {
        sunDirection = sunDir.copyOf()
        sunColor = sun.copyOf()
        zenithColor = zenith.copyOf()
        horizonColor = horizon.copyOf()
        groundColor = ground.copyOf()
    }

    /** Must run on the GL thread with a current context. Idempotent. */
    fun build() {
        if (isBuilt) return
        val t0 = System.nanoTime()

        skyRadianceProgram = buildProgram(
            SKY_RADIANCE_VERTEX, SKY_RADIANCE_FRAGMENT, "ibl_sky_radiance"
        )
        irradianceProgram = buildProgram(
            CUBE_PASS_VERTEX, IRRADIANCE_FRAGMENT, "ibl_irradiance"
        )
        prefilterProgram = buildProgram(
            CUBE_PASS_VERTEX, PREFILTER_FRAGMENT, "ibl_prefilter"
        )
        brdfProgram = buildProgram(
            QUAD_VERTEX, BRDF_FRAGMENT, "ibl_brdf"
        )
        skyboxProgram = buildProgram(
            SkyboxShaders.VERTEX, SkyboxShaders.FRAGMENT, "ibl_skybox"
        )

        quadVao = makeQuad()
        cubeVao = makeCube()

        // 1. HDR radiance cubemap
        radianceCube = createCubeTexture(RADIANCE_SIZE)
        renderCubePass(
            program = skyRadianceProgram,
            cubeTex = radianceCube,
            size = RADIANCE_SIZE,
            mip = 0
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, radianceCube)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_CUBE_MAP)

        // 2. Irradiance (diffuse convolution)
        irradianceCube = createCubeTexture(IRRADIANCE_SIZE)
        renderCubePass(
            program = irradianceProgram,
            cubeTex = irradianceCube,
            size = IRRADIANCE_SIZE,
            mip = 0
        )

        // 3. Prefiltered specular, mip chain
        prefilterCube = createCubeTexture(PREFILTER_SIZE, mipLevels = PREFILTER_MIPS)
        for (mip in 0 until PREFILTER_MIPS) {
            val roughness = mip.toFloat() / (PREFILTER_MIPS - 1)
            GLES30.glUseProgram(prefilterProgram)
            GLES30.glUniform1f(roughnessLoc[prefilterProgram] ?: -1, roughness)
            renderCubePass(
                program = prefilterProgram,
                cubeTex = prefilterCube,
                size = (PREFILTER_SIZE shr mip).coerceAtLeast(1),
                mip = mip
            )
        }

        // 4. BRDF LUT
        brdfLut = create2dTexture(BRDF_SIZE, BRDF_SIZE, GLES30.GL_RG16F, GLES30.GL_RG)
        renderBrdfLut()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, 0)

        isBuilt = true
        Log.d(
            TAG,
            "IBL built in %.1f ms".format((System.nanoTime() - t0) / 1e6f)
        )
    }

    /** Draws the environment as a skybox background. */
    fun drawSkybox(
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        exposure: Float
    ) {
        if (!isBuilt) return
        GLES30.glUseProgram(skyboxProgram)
        GLES30.glUniformMatrix4fv(
            viewRotLoc[skyboxProgram] ?: -1, 1, false,
            Mat4.rotationOnly(viewMatrix), 0
        )
        GLES30.glUniformMatrix4fv(
            projLoc[skyboxProgram] ?: -1, 1, false, projMatrix, 0
        )
        GLES30.glUniform1f(exposureLoc[skyboxProgram] ?: -1, exposure)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, prefilterCube)
        GLES30.glUniform1i(skyboxLoc[skyboxProgram] ?: -1, 0)

        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(cubeVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun renderCubePass(
        program: Int,
        cubeTex: Int,
        size: Int,
        mip: Int
    ) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, radianceCube)
        GLES30.glUniform1i(radianceCubeLoc[program] ?: -1, 0)

        val fbo = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, size, size)

        for (face in 0 until 6) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
                cubeTex,
                mip
            )
            GLES30.glUniformMatrix3fv(
                faceBasisLoc[program] ?: -1, 1, false,
                faceBasis(face), 0
            )
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun renderBrdfLut() {
        GLES30.glUseProgram(brdfProgram)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val fbo = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, BRDF_SIZE, BRDF_SIZE)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            brdfLut,
            0
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun createCubeTexture(size: Int, mipLevels: Int = 1): Int {
        val tex = intArrayOf(0)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, tex[0])
        GLES30.glTexStorage2D(
            GLES30.GL_TEXTURE_CUBE_MAP,
            mipLevels,
            GLES30.GL_RGBA16F,
            size,
            size
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_CUBE_MAP,
            GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipLevels > 1) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun create2dTexture(w: Int, h: Int, internalFormat: Int, format: Int): Int {
        val tex = intArrayOf(0)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            w,
            h,
            0,
            format,
            GLES30.GL_HALF_FLOAT,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun makeQuad(): Int {
        // Fullscreen NDC quad: position.xy = NDC, position.z = 1 (for cube passes)
        val verts = floatArrayOf(
            -1f, -1f, 1f,
             1f, -1f, 1f,
             1f,  1f, 1f,
            -1f, -1f, 1f,
             1f,  1f, 1f,
            -1f,  1f, 1f
        )
        return makeVao(verts, 3)
    }

    private fun makeCube(): Int {
        val verts = FloatArray(36 * 3)
        var v = 0
        fun push(x: Float, y: Float, z: Float) {
            verts[v++] = x; verts[v++] = y; verts[v++] = z
        }
        // 6 faces, 2 triangles each, outward normals (position == direction)
        val faces = arrayOf(
            floatArrayOf(-1f, -1f, -1f,  1f, -1f, -1f,  1f,  1f, -1f,  -1f, -1f, -1f,  1f,  1f, -1f, -1f,  1f, -1f), // -Z
            floatArrayOf(-1f, -1f,  1f, -1f,  1f,  1f,  1f,  1f,  1f,  -1f, -1f,  1f,  1f,  1f,  1f,  1f, -1f,  1f), // +Z
            floatArrayOf(-1f,  1f, -1f, -1f,  1f,  1f,  1f,  1f,  1f,  -1f,  1f, -1f,  1f,  1f,  1f,  1f,  1f, -1f), // +Y
            floatArrayOf(-1f, -1f, -1f,  1f, -1f, -1f,  1f, -1f,  1f,  -1f, -1f, -1f,  1f, -1f,  1f, -1f, -1f,  1f), // -Y
            floatArrayOf( 1f, -1f, -1f,  1f,  1f, -1f,  1f,  1f,  1f,   1f, -1f, -1f,  1f,  1f,  1f,  1f, -1f,  1f), // +X
            floatArrayOf(-1f, -1f, -1f, -1f, -1f,  1f, -1f,  1f,  1f,  -1f, -1f, -1f, -1f,  1f,  1f, -1f,  1f, -1f)  // -X
        )
        for (face in faces) {
            for (i in face.indices step 3) push(face[i], face[i + 1], face[i + 2])
        }
        return makeVao(verts, 3)
    }

    private fun makeVao(verts: FloatArray, components: Int): Int {
        val vao = intArrayOf(0)
        val vbo = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            verts.size * 4,
            java.nio.ByteBuffer
                .allocateDirect(verts.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(verts)
                .position(0),
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, components, GLES30.GL_FLOAT, false, components * 4, 0)
        GLES30.glBindVertexArray(0)
        return vao[0]
    }

    /** Column-major 3x3 tangent basis for each cube face. */
    private fun faceBasis(face: Int): FloatArray {
        val normals = arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0f, 0f, -1f)
        )
        val ups = arrayOf(
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, -1f, 0f)
        )
        val n = normals[face]
        val up = ups[face]
        val t = cross(up, n)
        val b = cross(n, t)
        // columns: t, b, n
        return floatArrayOf(
            t[0], t[1], t[2],
            b[0], b[1], b[2],
            n[0], n[1], n[2]
        )
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )

    private fun buildProgram(vertex: String, fragment: String, tag: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex, tag)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment, tag)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("$tag link failed: $log")
        }
        return program
    }

    private fun compile(type: Int, source: String, tag: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("$tag compile failed: $log")
        }
        return shader
    }

    // ------------------------------------------------------------------
    // shaders
    // ------------------------------------------------------------------

    internal const val QUAD_VERTEX = """#version 300 es
precision highp float;
in vec3 aPosition;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPosition.xy, 0.999, 1.0);
    vUv = aPosition.xy * 0.5 + 0.5;
}
"""

    internal const val CUBE_PASS_VERTEX = """#version 300 es
precision highp float;
uniform mat3 uFaceBasis;
in vec3 aPosition;
out vec3 vDir;
void main() {
    gl_Position = vec4(aPosition.xy, 0.999, 1.0);
    vDir = uFaceBasis * aPosition;
}
"""

    internal const val SKY_RADIANCE_VERTEX = CUBE_PASS_VERTEX

    internal const val SKY_RADIANCE_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vDir;
out vec4 fragColor;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uGround;

void main() {
    vec3 dir = normalize(vDir);
    float sunAmt = max(dot(dir, uSunDir), 0.0);
    float disk = pow(sunAmt, 4000.0);
    float glow = pow(sunAmt, 64.0);

    vec3 sky;
    if (dir.y < 0.0) {
        sky = uGround;
    } else {
        float h = pow(dir.y, 0.55);
        sky = mix(uHorizon, uZenith, h);
    }
    sky += disk * uSunColor * 12.0 + glow * uSunColor * 0.14;
    fragColor = vec4(sky, 1.0);
}
"""

    internal const val IRRADIANCE_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vDir;
out vec4 fragColor;
uniform samplerCube uRadiance;

const int SAMPLES = 64;

void main() {
    vec3 n = normalize(vDir);
    // tangent basis around n
    vec3 up = abs(n.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(up, n));
    vec3 b = cross(n, t);

    vec3 irr = vec3(0.0);
    for (int i = 0; i < SAMPLES; i++) {
        // golden spiral over hemisphere, uniform solid angle
        float fi = float(i) + 0.5;
        float phi = 2.399963 * float(i);
        float cosTheta = 1.0 - fi / float(SAMPLES);
        float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
        vec3 s = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
        vec3 worldDir = t * s.x + b * s.y + n * s.z;
        irr += texture(uRadiance, worldDir).rgb * cosTheta;
    }
    irr *= 6.2831853 / float(SAMPLES);
    fragColor = vec4(irr, 1.0);
}
"""

    internal const val PREFILTER_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vDir;
out vec4 fragColor;
uniform samplerCube uRadiance;
uniform float uRoughness;

const float PI = 3.14159265359;
const int SAMPLES = 1024;

float radicalInverseVdC(uint bits) {
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

vec2 hammersley(uint i) {
    return vec2(float(i) / float(SAMPLES), radicalInverseVdC(i));
}

vec3 importanceSampleGGX(vec2 xi, vec3 n, float roughness) {
    float a = roughness * roughness;
    float phi = 2.0 * PI * xi.x;
    float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
    float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
    vec3 h = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
    vec3 up = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(up, n));
    vec3 b = cross(n, t);
    return normalize(t * h.x + b * h.y + n * h.z);
}

void main() {
    vec3 n = normalize(vDir);
    vec3 v = n;
    float roughness = uRoughness;

    vec3 prefiltered = vec3(0.0);
    float totalWeight = 0.0;
    for (uint i = 0u; i < uint(SAMPLES); i++) {
        vec2 xi = hammersley(i);
        vec3 h = importanceSampleGGX(xi, n, roughness);
        vec3 l = normalize(2.0 * dot(v, h) * h - v);
        float nDotL = max(dot(n, l), 0.0);
        if (nDotL > 0.0) {
            prefiltered += textureLod(uRadiance, l, 0.0).rgb * nDotL;
            totalWeight += nDotL;
        }
    }
    prefiltered /= max(totalWeight, 1e-4);
    fragColor = vec4(prefiltered, 1.0);
}
"""

    internal const val BRDF_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

const float PI = 3.14159265359;
const int SAMPLES = 1024;

float radicalInverseVdC(uint bits) {
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

vec2 hammersley(uint i) {
    return vec2(float(i) / float(SAMPLES), radicalInverseVdC(i));
}

vec3 importanceSampleGGX(vec2 xi, vec3 n, float roughness) {
    float a = roughness * roughness;
    float phi = 2.0 * PI * xi.x;
    float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
    float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
    vec3 h = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
    vec3 up = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(up, n));
    vec3 b = cross(n, t);
    return normalize(t * h.x + b * h.y + n * h.z);
}

float geometrySchlickGGX(float nDotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return nDotV / max(nDotV * (1.0 - k) + k, 1e-4);
}

float geometrySmith(vec3 n, vec3 v, vec3 l, float roughness) {
    float nDotV = max(dot(n, v), 0.0);
    float nDotL = max(dot(n, l), 0.0);
    return geometrySchlickGGX(nDotV, roughness) * geometrySchlickGGX(nDotL, roughness);
}

vec2 integrateBrdf(float nDotV, float roughness) {
    vec3 v = vec3(sqrt(max(1.0 - nDotV * nDotV, 0.0)), 0.0, nDotV);
    vec3 n = vec3(0.0, 0.0, 1.0);
    float A = 0.0;
    float B = 0.0;
    for (uint i = 0u; i < uint(SAMPLES); i++) {
        vec2 xi = hammersley(i);
        vec3 h = importanceSampleGGX(xi, n, roughness);
        vec3 l = normalize(2.0 * dot(v, h) * h - v);
        float nDotL = max(l.z, 0.0);
        if (nDotL > 0.0) {
            float nDotH = max(h.z, 0.0);
            float vDotH = max(dot(v, h), 0.0);
            float g = geometrySmith(n, v, l, roughness);
            float gVis = (g * vDotH) / max(nDotH * nDotV, 1e-4);
            float fc = pow(1.0 - vDotH, 5.0);
            A += (1.0 - fc) * gVis;
            B += fc * gVis;
        }
    }
    return vec2(A, B) / float(SAMPLES);
}

void main() {
    vec2 lut = integrateBrdf(vUv.x, vUv.y);
    fragColor = vec4(lut, 0.0, 1.0);
}
"""

    private const val TAG = "IblEnvironment"
}
