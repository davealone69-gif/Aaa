# LocalHttpGenerationProvider bridge design

## 1. Why an adapter process is required

The Android SDK deliberately exposes one stable contract:

```text
Android -> LocalHttpGenerationProvider -> /v1/* bridge -> real model runtime
```

ComfyUI and Hunyuan3D do not expose the same job protocol. ComfyUI queues a workflow with `POST /prompt`, reports execution over WebSocket and/or `GET /history/{prompt_id}`, and serves generated files with `GET /view`. Hunyuan3D's official `api_server.py` can accept an image and return a GLB response directly. Keeping those details in a local Python bridge prevents the Android SDK from depending on a particular model, node pack, or output directory layout.

The bridge is an adapter, not a generator. It must call a real installed model and must return an error if the model, workflow, GPU, disk, or output is unavailable.

## 2. End-to-end sequence

```text
Android app
  POST /v1/generate {prompt, kind, quality, imageBase64, policy}
        |
        v
Local bridge (127.0.0.1 on Termux or LAN workstation)
  validate request and adult/consent policy
  choose pinned workflow and model profile
  upload input image if needed
  queue real ComfyUI workflow OR call Hunyuan3D API server
  poll actual job state / receive actual WebSocket events
  locate actual output GLB
  run glTF/GLB + texture + mesh validation
  retain asset in a private job directory
        |
        v
Android polls /v1/jobs/{id}
Android downloads /v1/assets/{assetId}
Android validates again and persists the asset
Filament loads the validated GLB
```

The Android side must not mark a request complete from a queue acknowledgement. `COMPLETED` means that a real GLB has been downloaded and passed validation on both sides.

## 3. ComfyUI path

### 3.1 Workflow preparation

Export a ComfyUI workflow in API format, not only the UI graph. Pin the workflow revision and record the required custom nodes and model files. For Hunyuan3D 2, the workflow is normally geometry first and texture/material second. The official ComfyUI documentation describes Hunyuan3D 2 single-view and multi-view workflows; generated GLB files are written to the ComfyUI mesh output directory. The exact node IDs must be treated as configuration because custom-node updates can change them.

The bridge keeps a template such as `workflows/hunyuan3d_image_to_glb_api.json`. At request time it changes only whitelisted inputs: input image filename, text prompt node if supported by the workflow, seed, quality profile, and output prefix. It must reject unknown workflow mutation paths rather than allowing arbitrary code or node execution from the phone.

### 3.2 Input upload

For an image request, the bridge sends the image to ComfyUI's upload route (`POST /upload/image`) and obtains the server-side filename. The filename is inserted into the pinned workflow's image loader node. It should use a random per-job name, restrict extensions and size, and delete temporary files after the job.

### 3.3 Queue and progress

The bridge submits the API-format prompt to ComfyUI's `POST /prompt`. The response contains a `prompt_id`. The bridge maps that ID to its own opaque SDK job ID and immediately returns:

```json
{"jobId":"sdk-job-id"}
```

For progress, the bridge prefers ComfyUI's WebSocket endpoint (`/ws?clientId=...`) and translates actual execution events into the SDK phases. If WebSocket is not available, it polls `GET /history/{prompt_id}`. It must not manufacture a fraction: node-count-based progress is only allowed if the bridge explicitly labels it as an estimate; otherwise `progress` is omitted.

The bridge treats a ComfyUI error, missing node, missing checkpoint, out-of-memory message, cancelled prompt, or empty history as a failed job with a recovery message.

### 3.4 Output retrieval

After history reports completion, the bridge inspects the output records and selects only an allowed 3D output extension (`.glb` or a configured conversion path from `.obj`/`.ply`). It downloads the result using ComfyUI's `/view` route or reads it from a configured private output directory. It never scans arbitrary paths supplied by the client.

If the workflow emits OBJ/PLY rather than GLB, the bridge must run a real deterministic conversion step, such as Blender in background mode or a glTF converter, then validate the resulting GLB. It must fail if textures, materials, UVs, normals, or the conversion log are invalid.

## 4. Hunyuan3D direct path

Hunyuan3D 2's official repository includes `api_server.py`. The documented local server accepts an image payload and can return a GLB. A bridge can expose the SDK contract as follows:

```text
/v1/generate
  create local job
  call Hunyuan3D /generate with the image
  write the binary response to a private job file
  validate GLB
  mark job completed with assetId

/v1/jobs/{id}
  return PREPARING, GENERATING, VALIDATING, COMPLETED or FAILED

/v1/assets/{assetId}
  stream the validated GLB only
```

The direct Hunyuan3D path is primarily image-to-3D. Text-to-3D requires a real upstream text-to-image stage or a ComfyUI workflow that supplies the appropriate conditioning; it must not be described as direct text-to-3D unless the selected model/workflow genuinely supports it.

Hunyuan3D 2 separates shape generation from texture generation. A production high-quality profile should run both stages, then validate the textured GLB, PBR texture references, UVs, and material channels. A low-memory profile may return geometry without texture only if the UI explicitly labels the result as untextured and the request permits it.

## 5. TripoSR path

TripoSR is a practical MIT-licensed single-image reconstruction worker. Its upstream README reports approximately 6 GB VRAM for the default single-image inference and supports optional texture baking. It is a useful fast fallback for objects, cars, and props, but it is not a photorealistic human-avatar rigging system and does not by itself produce ARKit 52 blendshapes or a full-body skeleton.

The bridge can run TripoSR for `IMAGE_TO_IMAGE_3D` and then pass its mesh through a real post-processing pipeline: texture baking if requested, UV validation, material creation, optional Blender retopology/rigging, GLB export, and final validation. If those post-processing stages are not installed, the provider must report the missing capability rather than claim a rigged avatar.

## 6. Termux deployment

Termux is a useful local control plane, file staging area, and bridge host. It is not automatically a viable inference device for Hunyuan3D or TripoSR. The bridge performs a startup health check that reports Python version, model installation, RAM, free storage, accelerator availability, and estimated requirements.

Two supported layouts are:

### Same Android device

```text
Android app -> http://127.0.0.1:<port> -> Termux bridge -> installed lightweight runtime
```

Use this only for a model that has been genuinely ported and tested on that ARM64 device. Heavy desktop PyTorch/CUDA models must return `INSUFFICIENT_RESOURCES` rather than silently falling back.

### Termux controller + workstation worker

```text
Android app -> Termux bridge -> http://<LAN-host>:<port> -> ComfyUI/Hunyuan3D
```

The bridge can forward jobs, but must preserve the same health, progress, cancellation, and asset-validation contract. Keep the service on a private LAN, require an authentication token, use HTTPS when crossing an untrusted network, and never expose an unauthenticated model server to the public Internet.

## 7. Adult-mode and consent enforcement

Policy checks happen before a request reaches ComfyUI or a model server. Adult mode is off by default and requires an explicit local age-gate state. The request model should carry a policy object with `adultModeEnabled`, `sourcePhotoConsent`, and `isSelfOwnedPhoto`.

The bridge rejects prompts or metadata that request a minor, underage appearance, school-age styling, or age ambiguity. The minimum requested apparent age is 18. In adult mode, photo-to-avatar is disabled; outside adult mode, a reference photo requires a local consent/self-ownership assertion. The bridge must not attempt face recognition or real-person identity matching. Adult prompts, images, job metadata, and outputs remain on the device or the user's own server.

These checks are defense-in-depth. The Android UI, SDK, bridge, and workflow input layer should all reject disallowed requests, and the rejection should be logged locally with a recovery message.

## 8. Security and validation boundary

The bridge uses random job IDs, private per-job directories, path allow-lists, maximum input/output sizes, checksums, timeouts, cancellation, and cleanup. It never executes a workflow or Python file supplied by the Android client. Only administrator-installed and pinned workflows/models can run.

The bridge validates:

- GLB magic, version, declared length, JSON and BIN chunks;
- valid glTF JSON and buffer ranges;
- image decode and referenced texture existence;
- UV ranges, normals, tangents and material references;
- triangle and texture limits for the selected Android quality profile;
- optional skeleton, skin, morph-target and animation invariants.

## 9. Model capability truth table

| Path | Real capability | Honest limitation |
|---|---|---|
| Hunyuan3D 2/2mini local worker | image-to-shape; texture/PBR stage when configured | generally workstation-class; not a complete human rigging system |
| ComfyUI + Hunyuan3D workflow | workflow-controlled image/multi-view-to-3D; GLB output | node packs, model files, and VRAM must be installed and pinned |
| TripoSR local worker | fast single-image reconstruction | mainly objects/props; no automatic ARKit 52 or cinematic human rig |
| Pure Android ARM64 | renderer, storage, validation, parametric assets where implemented | heavy diffusion 3D generation is not assumed feasible |

## References

- Hunyuan3D-2 repository: https://github.com/Tencent-Hunyuan/Hunyuan3D-2
- TripoSR repository: https://github.com/VAST-AI-Research/TripoSR
- ComfyUI server/API documentation: https://docs.comfy.org/development/comfyui-server/comms_routes
- ComfyUI Hunyuan3D-2 tutorial: https://docs.comfy.org/tutorials/3d/hunyuan3D-2
