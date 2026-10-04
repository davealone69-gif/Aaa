import com.aura.avatarstudio.renderer.AvatarShaders
import com.aura.avatarstudio.renderer.DemoShaders
import com.aura.avatarstudio.renderer.IblEnvironment
import com.aura.avatarstudio.renderer.SkyboxShaders
import java.io.File

/** Dumps every shader variant the pipeline can generate, for glslang. */
fun main() {
    val dir = File("/tmp/shaders").apply { mkdirs() }

    fun write(name: String, source: String) {
        File(dir, name).writeText(source)
    }

    // vertex variants
    write("v_skinned_morph.vert", AvatarShaders.vertexSource(4, 1))     // head (skinned, 1 morph)
    write("v_skinned.vert", AvatarShaders.vertexSource(4, 0))           // body/visor (skinned)
    write("v_plain.vert", AvatarShaders.vertexSource(0, 0))             // unskinned
    write("v_morph8.vert", AvatarShaders.vertexSource(0, 8))            // max morphs, no skin

    // fragment variants
    write("f_full.frag", AvatarShaders.fragmentSource(
        lightCount = 4,
        hasBaseTex = true, hasMrTex = true, hasNormalTex = true,
        hasOcclusionTex = true, hasEmissiveTex = true, hasEmissive = true,
        hasTangent = true, useIbl = true, usePrefilter = true,
        alphaMode = "OPAQUE"))
    write("f_visor.frag", AvatarShaders.fragmentSource(
        lightCount = 4,
        hasBaseTex = false, hasMrTex = false, hasNormalTex = false,
        hasOcclusionTex = false, hasEmissiveTex = true, hasEmissive = true,
        hasTangent = true, useIbl = true, usePrefilter = true,
        alphaMode = "MASK"))
    write("f_eyes.frag", AvatarShaders.fragmentSource(
        lightCount = 4,
        hasBaseTex = false, hasMrTex = false, hasNormalTex = false,
        hasOcclusionTex = false, hasEmissiveTex = false, hasEmissive = false,
        hasTangent = true, useIbl = true, usePrefilter = true,
        alphaMode = "OPAQUE"))
    write("f_min.frag", AvatarShaders.fragmentSource(
        lightCount = 0,
        hasBaseTex = false, hasMrTex = false, hasNormalTex = false,
        hasOcclusionTex = false, hasEmissiveTex = false, hasEmissive = false,
        hasTangent = false, useIbl = false, usePrefilter = false,
        alphaMode = "OPAQUE"))
    write("f_blend.frag", AvatarShaders.fragmentSource(
        lightCount = 1,
        hasBaseTex = true, hasMrTex = false, hasNormalTex = false,
        hasOcclusionTex = false, hasEmissiveTex = false, hasEmissive = false,
        hasTangent = false, useIbl = false, usePrefilter = false,
        alphaMode = "BLEND"))

    // IBL passes
    write("ibl_cube_pass.vert", IblEnvironment.CUBE_PASS_VERTEX)
    write("ibl_quad.vert", IblEnvironment.QUAD_VERTEX)
    write("ibl_sky_radiance.frag", IblEnvironment.SKY_RADIANCE_FRAGMENT)
    write("ibl_irradiance.frag", IblEnvironment.IRRADIANCE_FRAGMENT)
    write("ibl_prefilter.frag", IblEnvironment.PREFILTER_FRAGMENT)
    write("ibl_brdf.frag", IblEnvironment.BRDF_FRAGMENT)

    // skybox + demo
    write("skybox.vert", SkyboxShaders.VERTEX)
    write("skybox.frag", SkyboxShaders.FRAGMENT)
    write("grid.vert", DemoShaders.GRID_VERTEX)
    write("grid.frag", DemoShaders.GRID_FRAGMENT)
    write("light.vert", DemoShaders.LIGHT_VERTEX)
    write("light.frag", DemoShaders.LIGHT_FRAGMENT)

    println("dumped " + (dir.list()?.size ?: 0) + " shaders")
}
