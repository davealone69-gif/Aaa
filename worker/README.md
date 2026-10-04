# Local Avatar Bridge Worker

This is an executable stdlib HTTP bridge. It does not contain model weights and never fabricates an asset.

## Run

```bash
cd worker
AVATAR_BACKEND=comfy \
COMFY_URL=http://127.0.0.1:8188 \
COMFY_WORKFLOW=$PWD/../workflows/hunyuan3d_image_to_glb_api.json \
BRIDGE_TOKEN='a-long-random-token' \
python3 local_bridge.py
```

Use `AVATAR_BACKEND=hunyuan` with `HUNYUAN_URL` for a separately installed and version-verified Hunyuan3D server. Use `AVATAR_BACKEND=triposr` with `TRIPOSR_RUN=/absolute/path/to/TripoSR/run.py` for the installed upstream TripoSR script.

## Contract

`GET /health` reports ComfyUI/Hunyuan3D shape/Hunyuan3D texture/TripoSR independently. A provider is `READY` only when its real upstream runtime is reachable and its required workflow/runtime files exist. `POST /v1/generate` creates an asynchronous job. `GET /v1/jobs/{id}` reports the actual upstream phase. `GET /v1/assets/{sha256}` streams only a validated GLB.

The ComfyUI path uploads the input to `/upload/image`, submits a pinned API-format workflow to `/prompt`, inspects `/history/{prompt_id}`, and downloads the selected `.glb` through `/view`. The Hunyuan path calls `/generate` and treats the response as GLB only after local validation. The TripoSR path runs the installed `run.py` subprocess and accepts exactly one real `.glb` output.

## ComfyUI configuration

The bridge does not invent node IDs. Configure `COMFY_IMAGE_NODE`, `COMFY_PROMPT_NODE`, and `COMFY_SEED_NODE` to the IDs in your pinned API workflow. Keep that workflow under administrator control; Android input can change only the whitelisted image, prompt, and seed fields.

## Adult mode

Adult mode is disabled unless the Android app sends `adultModeEnabled=true` together with `ageGateConfirmed=true`. The Worker independently rejects minor/underage terms, ambiguous-age sexual prompts, adult-mode reference photos, missing ordinary-photo ownership/consent, and adult requests unless the selected backend is explicitly configured with `ADULT_CAPABILITY=VERIFIED`. That environment value is a provider deployment assertion, not an identity or age proof; the current sandbox does not set it, so adult generation remains unavailable. Adult prompts and image data are not written to logs.

## Verification

```bash
python3 -m py_compile local_bridge.py
BRIDGE_TOKEN=test AVATAR_BACKEND=triposr TRIPOSR_RUN=/does/not/exist python3 local_bridge.py
curl -H 'Authorization: Bearer test' http://127.0.0.1:8090/health
```

The final command must report `ready: false` and `upstreamReady: false`; this is a correct unavailable-state test. A generation test is valid only when a real model server is installed and the response contains a real GLB that passes `validate_glb`.
