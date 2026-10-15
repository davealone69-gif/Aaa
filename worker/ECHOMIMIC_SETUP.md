# EchoMimicV3-Flash GPU worker setup

This worker is separate from Termux. Run it on a private Linux host with a supported NVIDIA CUDA GPU. The phone/Termux bridge forwards jobs; it does not run diffusion inference itself.

Upstream: https://github.com/antgroup/echomimic_v3

## Requirements

- Linux with NVIDIA CUDA support. Upstream documents CUDA >= 12.1 and Python 3.10/3.11.
- EchoMimicV3-Flash upstream says approximately 12 GB VRAM is required. This is checked at runtime; it is not a guarantee that every GPU or model combination will work.
- FFmpeg/ffprobe available on PATH.
- Downloaded, version-pinned EchoMimic code, Wan2.1-Fun-V1.1-1.3B-InP base model, Flash transformer weights, and Chinese Wav2Vec audio encoder.
- An identical strong random BRIDGE_TOKEN on this worker and the bridge.

## Configure the GPU worker

Clone the official upstream repository and install its pinned requirements in a dedicated Python 3.10 or 3.11 environment, following upstream instructions. Download model files from the official model links in the upstream README. Do not commit model weights or credentials into this repository.

Set absolute paths. Example names below are placeholders and must be replaced with actual installed paths:

```sh
export ECHOMIMIC_ROOT=/opt/echomimic_v3
export ECHOMIMIC_PYTHON=/opt/echomimic-env/bin/python
export ECHOMIMIC_CONFIG=/opt/echomimic_v3/config/config.yaml
export ECHOMIMIC_MODEL=/models/Wan2.1-Fun-V1.1-1.3B-InP
export ECHOMIMIC_TRANSFORMER=/models/flash/transformer/diffusion_pytorch_model.safetensors
export ECHOMIMIC_WAV2VEC=/models/flash/chinese-wav2vec2-base
export ECHOMIMIC_CKPT_IDX=50000
export BRIDGE_TOKEN='replace-with-a-long-random-secret'
export VIDEO_WORKER_HOST=0.0.0.0
export VIDEO_WORKER_PORT=8091
python worker/echomimic_video_worker.py
```

Bind to a private interface/firewall only. Do not expose port 8091 publicly. For traffic across an untrusted network, put the service behind authenticated HTTPS. Health is public on the private interface; generation requires the bearer token.

Check `GET /health`. `ready: true` means configured files exist and PyTorch reports a CUDA device with at least 12 GiB VRAM. It does not prove a full inference will succeed. Run one real portrait plus WAV generation and validate the returned MP4 before marking the deployment verified.

## Configure the Termux/local bridge

Set the same token and the private worker URL in the environment used to launch `worker/local_bridge.py`:

```sh
export BRIDGE_TOKEN='the-same-long-random-secret'
export ECHOMIMIC_WORKER_URL='http://192.168.1.50:8091'
python worker/local_bridge.py
```

Replace the address with the actual private GPU-host address. The bridge reports whether the worker URL is configured/reachable, accepts `POST /v1/video/generate`, reports status at `GET /v1/video/jobs/{jobId}`, and serves only validated cached output at `GET /v1/video/assets/{sha256}`. The request JSON must include base64-encoded `imageBase64` and `audioBase64`; audio input must be a decodable WAV file. The bridge validates the returned MP4 with ffprobe and checks the worker's SHA-256 header.

## Failure behavior

- Missing weights, dependencies, CUDA, or enough VRAM: worker health reports unavailable/insufficient resources.
- Worker not configured: bridge returns `VIDEO_BACKEND_UNAVAILABLE`; it does not invent a job or video.
- Invalid input, inference failure, timeout, missing output, corrupt MP4, or missing audio/video stream: request fails; no asset ID is published.
- The GPU worker's current inference route is synchronous, so the bridge's background thread remains occupied until generation completes. Keep worker concurrency limited; add a persistent queue before multi-user production deployment.

## Verification status

The worker code and contract test are committed in this branch, but no GPU host/model weights are available to this repository integration session. Therefore no generated video, CUDA inference, quality result, or end-to-end Android runtime is claimed as verified. CI checks syntax and the truthful no-model state only.
