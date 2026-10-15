# Talking-video engine integration

## Goal

Add real portrait-plus-audio talking-video generation to Avatar Engine without pretending that the existing GLB pipeline or HTTP health check can generate video. Android remains the client. A Linux/CUDA worker runs EchoMimicV3-Flash. Termux remains the local control/bridge and cached playback environment.

## Primary backend: EchoMimicV3-Flash

Upstream: https://github.com/antgroup/echomimic_v3

The upstream inference entry point is infer_flash.py; the official run_flash.sh supplies path arguments for image, audio, config YAML, base model, transformer weights, output directory, and Wav2Vec model. It also accepts prompt, inference steps, seed, video length, resolution, and FPS. Upstream README reports Linux, CUDA >= 12.1, Python 3.10/3.11, and 12 GB VRAM for Flash. Treat these as upstream-reported requirements, not a verified deployment.

The upstream repository declares Apache-2.0 for its models/code. Independently review licenses of every downloaded base model, audio encoder, dependency, and sample asset before redistribution.

## Deployment boundary

- Android: select portrait and audio, submit a job, show honest state, preview/download MP4, retain cache.
- Termux bridge: authenticated routing, job status, local cache, retry, and truthful offline fallback. Do not run diffusion inference on the phone unless a compatible ARM64 runtime is independently demonstrated.
- GPU worker: pinned EchoMimic checkout, exact model weights, Python environment, CUDA, FFmpeg/ffprobe, one generation at a time by default.
- Existing Hunyuan3D/ComfyUI: keep as a separate 3D-asset workflow. It is not the talking-video backend and does not automatically rig or animate a 3D human.

## Required worker configuration

Pin the upstream Git commit and store it in the deployment report. Configure absolute paths for the checkout, config YAML, Wan2.1 base model, Flash transformer weights, and Wav2Vec model. Record SHA-256 for model files and pip freeze. Never download weights at app startup or silently change model revisions.

Suggested environment variables: ECHOMIMIC_ROOT, ECHOMIMIC_PYTHON, ECHOMIMIC_CONFIG, ECHOMIMIC_MODEL, ECHOMIMIC_TRANSFORMER, ECHOMIMIC_WAV2VEC, ECHOMIMIC_CKPT_IDX, VIDEO_JOB_TIMEOUT_SECONDS, BRIDGE_TOKEN.

Health must validate configured files, import dependencies using the selected Python, check torch.cuda.is_available(), and report actual GPU name and VRAM. A responding HTTP port is not sufficient. Do not mark READY without the configured weights and CUDA capability.

## API contract

The bridge's existing POST /v1/video/generate must remain a truthful unavailable response until an actual worker is configured. Once integrated, accept JSON fields requestId, imageBase64, audioBase64, prompt, seed, videoLength, width, and height.

- Return HTTP 202 only after accepting a real queued job.
- GET /v1/video/jobs/{jobId} returns real phases: QUEUED, GENERATING, VALIDATING, COMPLETED, FAILED, CANCELLED.
- GET /v1/video/assets/{sha256} serves only a validated MP4.
- Reject invalid/oversized inputs; keep temporary files private and remove them on retention expiry.
- Never return a demo video, static placeholder, fabricated percentage, or a successful job before output validation.
- Store bounded, redacted logs; never log raw portrait/audio payloads or access tokens.
- Require bearer-token auth and keep the GPU worker private; do not expose ComfyUI or the worker directly to the public internet.

## Output validation

Use ffprobe on the actual generated file. Require exactly one expected MP4 output per job, a decodable video stream, a decodable audio stream, positive duration, non-zero file size, and bounded dimensions/duration. Calculate SHA-256 and retain validation metadata. A successful inference process with no valid MP4 is a FAILED job.

## Acceptance tests

1. No weights or no CUDA: health reports NOT_INSTALLED/INSUFFICIENT_RESOURCES and generation returns a structured unavailable error. No job is falsely marked complete.
2. Invalid portrait, invalid audio, and oversized payloads are rejected before inference.
3. Known consented portrait plus valid WAV: one real MP4 is generated; ffprobe confirms audio/video streams and positive duration.
4. Output hash matches downloaded bytes; a corrupted or missing asset is rejected.
5. Timeout, OOM, missing weights, and non-zero inference exit become FAILED with a useful error and no asset ID.
6. Worker restart does not report in-memory unfinished jobs as completed; persistent job storage or explicit UNKNOWN_AFTER_RESTART semantics are required.
7. Android can play the real MP4, handle retry/failure, and play a cached result in airplane mode.
8. Record wall-clock generation time, GPU name, peak VRAM, input dimensions/duration, output dimensions/duration, model revision, and package lock.
9. Compare at least three consented test portraits and audio samples for identity consistency, visible mouth synchronization, flicker, and audio continuity. Review subjective quality rather than inferring it from HTTP success.

## Current verification status

This document defines the integration target; it does not claim EchoMimic is installed, that a GPU worker exists, or that any talking video has been generated. Existing project verification says no talking-video backend is installed. Update the verification report only after the acceptance tests above run against real output.
