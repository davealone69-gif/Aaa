# Headless verification harness

The renderer is pure Kotlin + `android.*`/`org.json` (the JSON classes ship
inside the Android framework), so the whole package can be compiled and
**run on a plain JVM** against tiny stubs of the Android surface it touches.

## What it verifies

`TestMain.kt` loads `app/src/main/assets/avatars/my_avatar.glb` through the
real `GltfAvatarLoader` and asserts (57 checks):

- chunk parsing, accessor/bufferView decoding (FLOAT / UBYTE / USHORT / UINT,
  packed and interleaved-with-`byteStride` layouts)
- geometry, indices, joints/weights, generated normals & tangents
- PBR material factor/texture-slot mapping, alpha modes, double-sided
- morph target deltas and `mesh.weights` defaults
- skinning matrices at rest (identity) and after animation
  (parent rotations correctly inherited through the joint hierarchy)
- glTF animation: LINEAR rotation channels, STEP morph weights, looping wrap
- data-URI and embedded-image decoding
- `IblEnvironment.build()` + `PbrPipeline.upload/draw` smoke test (stubbed GL)

`ShaderDump.kt` emits every shader variant the pipeline generates (21 files)
so they can be syntax-checked with glslang (`glslang -S vert -e main x.vert`).

## Running

```bash
KOTLINC=/path/to/kotlinc/bin/kotlinc \
JSON_JAR=/path/to/org.json.jar \
tools/headless-test/run.sh
```

kotlinc: https://github.com/JetBrains/kotlin/releases
org.json: https://repo1.maven.org/maven2/org/json/json/

The stubs live in `stubs/` and intentionally mirror only the API surface the
renderer uses; the GLES30 stub is no-op (constant values are real), the
`android.opengl.Matrix` stub is the real AOSP math so skin tests are
meaningful.

## GLSL validation (optional)

```bash
# dump generated shaders (needs kotlinc + org.json, see run.sh)
KOTLINC=... JSON_JAR=... kotlinc -classpath $JSON_JAR -d /tmp/shd \
  stubs app/.../renderer/*.kt tools/headless-test/ShaderDump.kt
java -cp /tmp/shd:$JSON_JAR:$KOTLINC_DIR/lib/kotlin-stdlib.jar ShaderDumpKt
# validate (glslang-tools package, or Khronos glslang release)
for f in /tmp/shaders/*.vert; do glslang -S vert -e main "$f"; done
for f in /tmp/shaders/*.frag; do glslang -S frag -e main "$f"; done
```
