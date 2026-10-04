package com.aura.avatarstudio.renderer.hd

/**
 * PBR texture slot bundle — the five glTF-standard maps as loaded by
 * [HdTextureManager]. Each slot is optional (null = the shader's
 * uHas*Texture flag goes to 0 and the factor constant is used).
 *
 * Unit layout (shared with the engine's PBR shaders):
 *   0 baseColor | 1 normal | 2 metallicRoughness | 3 occlusion | 4 emissive
 */
data class HdPbrTextures(
    val baseColor: HdTexture? = null,
    val normal: HdTexture? = null,
    val metallicRoughness: HdTexture? = null,
    val occlusion: HdTexture? = null,
    val emissive: HdTexture? = null
)
