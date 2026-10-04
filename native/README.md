# Aura Avatar Studio — dependency-free GLB avatar renderer (Android / OpenGL ES 3.0)

A zero-dependency HD avatar pipeline: GLB 2.0 loading, PBR materials,
skinning, morph targets, animation, runtime-generated IBL environment
lighting, and ACES tone mapping — all `android.*` + `org.json`, no third-party
libraries, no NDK, no assets to ship (the environment is computed on the GPU
at startup).

> **Relationship to the web app:** this is the **native Android counterpart**
> of the in-app viewport. `src/renderer/` in the repo root mirrors this
> engine in TypeScript/WebGL2 (`HdAvatarRenderer.ts` ↔ `HdAvatarRenderer.kt`).
> Keep the two in sync when changing the shader pipeline or mesh layout.
> Texture-side mirror: `src/renderer/ProceduralSkinTextures.ts` ↔
> `HdTextureManager.kt`/`PbrTexture.kt` (upload semantics; sRGB base color,
> R-channel roughness, tangent-space normal). The web avatar fragment
> follows the native `renderer.hd` PBR spec — factor uniforms, point light
> with intensity, derivative TBN, Reinhard + gamma — while the wired native
> path stays `PbrPipeline` + IBL. Web textures are procedural until
> GLB/texture parity lands. The web eye system (`EyeTextures.ts`,
> `EyeMaterial.ts`, `EyeShader.ts` — sclera/iris/pupil/limbal ring, cornea
> Fresnel wet layer, PBR specular) mirrors the native eye-renderer plan
> and precedes a dedicated corneal shell (next refinement). The web hair
> path (`HairTextures.ts`, `HairMaterial.ts`, `HairShader.ts` — strand
> color/roughness/direction/density maps, anisotropic dual-lobe specular,
> root darkening, alpha cutoff) is milestone 2. Shadow mapping
> (`ShadowMap.ts`, `ShadowShader.ts` — 2048² 32F depth pass, 3×3 PCF,
> slope-scaled bias, strength) is milestone 3. HDR + IBL
> (`IblPipeline.ts` — RGBA16F studio cubemaps, irradiance/prefiltered/
> BRDF LUT, exposure + ACES) is milestone 4; production path is real HDR
> equirectangular import + GPU convolution. Advanced skin
> (`AdvancedSkinMaterial.ts` — multi-channel scatter, pores, oil layer,
> skin-tuned IBL) is milestone 5; the face/expression system
> (`avatar/MorphTargets.ts`, `avatar/FaceControls.ts`, `avatar/Correctives.ts`,
> `MorphShader.ts` — morph deltas, corrective shapes, wrinkle masks, blink)
> is milestone 6. Cinematic output (`CinematicPipeline.ts` — HDR scene,
> bloom, DOF, TAA, exposure, ACES) is milestone 7; GLB parity is
> milestone 8: `avatar/GltfTypes.ts`, `GlbLoader.ts`, `GltfAccessor.ts`,
> `GltfMesh.ts`, `GltfImages.ts`, `GltfMaterial.ts`, `GltfMorphs.ts`,
> `GltfSkeleton.ts`, `GltfAvatar.ts` — GLB assets render through the
> existing skin program + shadow + cinematic passes (uSkinned + GPU
> morph uniforms), no second rendering path.
>
> Render-target rule: `src/renderer/RenderTarget.ts` — never trust
> EXT_color_buffer_float just because it is advertised; every render
> target is created via `createRenderTarget({ width, height, color,
> depth })` — per-format capability table probed once per GL context
> (RGBA16F -> RGBA32F -> RGBA8 chain, depth 32F -> 24 -> 16), the
> requested format starts the fallback chain, and the actual selected
> format is reported back (colorInternal/depthInternal).
> Sampling-only float textures stay float. IBL pipeline: all four
> intermediate targets of the production path (equirect->cubemap,
> irradiance, prefiltered mips, BRDF LUT) must allocate via
> `createRenderTarget()`.

## Wired into the Capacitor app

The engine is compiled directly into the app (`android/` module) via
`sourceSets` — **no file copies**, the renderer package lives here only:

- `android/app/build.gradle` pulls `native/app/src/main/java/.../renderer`
  into the app build (Kotlin plugin 1.9.24, jvmTarget 17)
- `ai.grokgirls.studio.NativeAvatarActivity` — fullscreen GL viewport:
  drag to orbit, pinch to zoom, loads `avatars/my_avatar.glb` from assets
  (override via the `avatar` intent extra)
- `ai.grokgirls.studio.AvatarStudioPlugin` — Capacitor bridge so the web
  app can launch the native viewport:

```js
await Capacitor.Plugins.AvatarStudio.openViewport({ avatar: 'avatars/my_avatar.glb' });
```

- `android/app/src/main/assets/avatars/my_avatar.glb` — the test avatar
  (regenerate with `tools/make_test_glb.py`, copy into both asset dirs)

```
app/src/main/assets/avatars/my_avatar.glb   test avatar (generated, 28 KB)
```

## Architecture

| File | Role |
|---|---|
| `renderer/GltfAvatarLoader.kt` | GLB 2.0 chunk parser → `HdAvatar` (buffers, views, accessors, meshes, PBR factors, skin joints/weights, morph deltas) |
| `renderer/GltfModel.kt` | `org.json`-based glTF document model (+ images/textures/samplers/animations) |
| `renderer/CoreModel.kt` | runtime types: `HdAvatar`, `GpuMesh`, `PbrMaterial`, `MorphTarget`, mesh math (normals/tangents), `Mat4` |
| `renderer/SkeletonMatrices.kt` | joint matrices = global joint transform × inverse bind |
| `renderer/GltfAnimation.kt` | glTF animation runtime: TRS + morph-weight channels, LINEAR/STEP, looping |
| `renderer/GltfTextures.kt` | decodes glTF images (embedded + data URI) → GL textures, sampler state, UV flip |
| `renderer/AvatarShaders.kt` | GLSL ES 3.00 sources; per-material-variant specialization |
| `renderer/PbrPipeline.kt` | program cache, mesh upload (VBO/IBO/VAO), PBR draw (GGX, IBL split-sum, ACES) |
| `renderer/IblEnvironment.kt` | runtime IBL: analytic HDR sky cubemap, irradiance, prefiltered specular, BRDF LUT |
| `renderer/HdAvatarRenderer.kt` | `GLSurfaceView.Renderer`: orbit camera, light rig, per-frame skin/animation |
| `GltfAvatarView.kt`, `MainActivity.kt` | demo app (drag to orbit, pinch to zoom) |

## Pipeline

```
GLB ──► GltfAvatarLoader ──► HdAvatar (CPU arrays)
                                │
        GL thread ◄─────────────┤  (queueEvent)
        │
        ├─► GltfTextures.resolve()   textures → GL
        ├─► PbrPipeline.upload()     VBO/IBO/VAO per mesh
        ├─► IblEnvironment.build()   sky → irradiance → prefilter → BRDF LUT (GPU)
        │
   per frame:
        ├─► GltfAnimation.apply()    node TRS + morph weights
        ├─► SkeletonMatrices.update() joint matrices
        └─► PbrPipeline.draw()       GGX specular + IBL + emissive + AO
                                     → ACES tone map → gamma
```

Shader specialization: each material variant (texture set, skinning, morphs,
alpha mode, IBL on/off) compiles once into its own program; uniforms drive
the rest. Faces with no NORMAL/TANGENT get them generated on the CPU
(smooth normals, UV-aligned tangents).

## Feature coverage (all verified headless)

- GLB chunk structure; FLOAT/UBYTE/USHORT/UINT accessors; packed and
  interleaved (`byteStride`) layouts; data-URI buffers & images
- PBR metallic-roughness: base color, MR, normal, AO, emissive maps +
  factors, `alphaMode` OPAQUE/MASK/BLEND, `doubleSided`
- Skinning (4-weight, inverse bind matrices), morph targets (up to 8,
  position/normal/tangent deltas) with `mesh.weights` defaults
- Animation: translation/rotation/scale + morph weights, LINEAR/STEP,
  looping
- IBL: runtime-convolved irradiance + GGX-prefiltered specular + BRDF LUT,
  procedural HDR sky; exposure + ACES tonemapping

## Test avatar

`tools/make_test_glb.py` generates a rigged test avatar (28 KB GLB) that
exercises every path above: 4 meshes, 4 joints, morph targets (blink +
smile), a 3s idle animation (sway + counter-sway + blink + smile), the full
5-texture PBR set, interleaved attributes, both index types, a data-URI
image, and MASK/double-sided materials. Regenerate with:

```bash
python3 tools/make_test_glb.py
```

## Verification

The package compiles and runs headless on a JVM (Android stubs):

```bash
KOTLINC=/path/to/kotlinc/bin/kotlinc \
JSON_JAR=/path/to/org.json.jar \
tools/headless-test/run.sh        # 57 assertions, loader→draw
```

All 21 generated GLSL ES 3.00 shader variants also validate with
`glslangValidator` (see `tools/headless-test/README.md`).

## Build

```bash
./gradlew :app:assembleDebug   # plain Android project, no dependencies
```

Drop any rigged GLB at `app/src/main/assets/avatars/my_avatar.glb` and it
renders with skinning, morphs and animation out of the box.

## Next steps (roadmap)

- KHR_lights_punctual + spot lights, shadow mapping
- HDR environment capture (real .hdr files, cube-to-cube prefilter)
- Animation blending / retargeting, bone-attached props (hair, clothing)
- SSAO / SSR / cloth sim on the morph pipeline
