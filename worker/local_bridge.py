#!/usr/bin/env python3
"""Strict local bridge for Avatar Engine.

Modes:
  comfy   POSTs a pinned API workflow to ComfyUI, polls history, downloads a GLB.
  hunyuan POSTs the image to a specific Hunyuan3D API server and validates its GLB.
  triposr runs the installed upstream run.py and accepts only a real GLB output.

No endpoint returns COMPLETED until a real GLB exists and passes the container validator.
This worker intentionally uses Python stdlib for the HTTP boundary; model runtimes remain
external, version-pinned installations.
"""
from __future__ import annotations
import base64, hashlib, json, mimetypes, os, re, shutil, subprocess, tempfile, threading, time, urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("AVATAR_BRIDGE_ROOT", Path.cwd() / "bridge-data")).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
MODE = os.environ.get("AVATAR_BACKEND", "comfy").lower()
COMFY_URL = os.environ.get("COMFY_URL", "http://127.0.0.1:8188").rstrip("/")
HUNYUAN_URL = os.environ.get("HUNYUAN_URL", "http://127.0.0.1:8080").rstrip("/")
COMFY_WORKFLOW = Path(os.environ.get("COMFY_WORKFLOW", "workflows/hunyuan3d_image_to_glb_api.json")).resolve()
TRIPOSR_RUN = Path(os.environ.get("TRIPOSR_RUN", "run.py")).resolve()
ECHOMIMIC_WORKER_URL = os.environ.get("ECHOMIMIC_WORKER_URL", "").rstrip("/")
VIDEO_ROOT = ROOT / "video-jobs"
VIDEO_ASSETS = ROOT / "video-assets"
VIDEO_ROOT.mkdir(parents=True, exist_ok=True)
VIDEO_ASSETS.mkdir(parents=True, exist_ok=True)
VIDEO_JOBS: dict[str, dict[str, Any]] = {}
HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1")
PORT = int(os.environ.get("BRIDGE_PORT", "8090"))
MAX_IMAGE_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(20 * 1024 * 1024)))
MAX_ASSET_BYTES = int(os.environ.get("MAX_ASSET_BYTES", str(512 * 1024 * 1024)))
TOKEN = os.environ.get("BRIDGE_TOKEN", "")
ADULT_CAPABILITY = os.environ.get("ADULT_CAPABILITY", "UNSUPPORTED").upper()
JOBS: dict[str, dict[str, Any]] = {}
CANCELLED_JOBS: set[str] = set()
LOCK = threading.RLock()

MINOR_WORDS = re.compile(r"\b(child|children|minor|underage|preteen|teen|teenage|teenager|adolescent|school[- ]?age|schoolgirl|schoolboy|loli|shota)\b", re.I)
UNDER_18 = re.compile(r"\b(?:age\s*)?(?:[0-9]|1[0-7])\s*(?:year|years|yo|y/o)\b", re.I)
SEXUAL_WORDS = re.compile(r"\b(nsfw|sexual|sexually|explicit|nude|nudity|erotic|pornographic|porn|fetish|intercourse|masturbat(?:e|ion)|genitals?|breasts?|topless|blowjob|anal)\b", re.I)
AMBIGUOUS_AGE = re.compile(r"\b(girl|boy|student|fresh[- ]?faced|barely[- ]?legal|young[- ]?looking|youthful)\b", re.I)
EXPLICIT_ADULT = re.compile(r"\b(adult|18\s*\+|18[- ]?year[- ]?old|mature)\b", re.I)

def validate_request_policy(request: dict[str, Any]) -> None:
    prompt = str(request.get("prompt", ""))
    adult_mode = bool(request.get("adultModeEnabled", False))
    sexual = bool(SEXUAL_WORDS.search(prompt))
    if MINOR_WORDS.search(prompt) or UNDER_18.search(prompt):
        raise BridgeError("MINOR_OR_UNDERAGE_REQUEST", "The request suggests a person under 18")
    if sexual and not adult_mode:
        raise BridgeError("ADULT_MODE_REQUIRED", "Sexual content requires explicitly enabled adult mode")
    if adult_mode and not bool(request.get("ageGateConfirmed", False)):
        raise BridgeError("AGE_GATE_REQUIRED", "Adult mode requires explicit 18+ confirmation")
    if adult_mode and sexual and (not EXPLICIT_ADULT.search(prompt) or AMBIGUOUS_AGE.search(prompt)):
        raise BridgeError("AMBIGUOUS_ADULT_AGE", "Sexual adult-mode requests must explicitly describe an adult subject")
    if adult_mode and request.get("imageBase64"):
        raise BridgeError("PHOTO_DISABLED_IN_ADULT_MODE", "Reference photos are disabled in adult mode")
    if request.get("imageBase64") and not (request.get("photoIsSelfOwned") and request.get("photoConsentConfirmed")):
        raise BridgeError("PHOTO_CONSENT_REQUIRED", "Reference photos require ownership and consent confirmation")
    if adult_mode and ADULT_CAPABILITY != "VERIFIED":
        raise BridgeError("ADULT_CAPABILITY_UNAVAILABLE", f"Selected backend adult capability is {ADULT_CAPABILITY}", "Use a provider with verified adult capability")

class BridgeError(Exception):
    def __init__(self, code: str, message: str, recovery: str = ""):
        super().__init__(message); self.code = code; self.recovery = recovery

def json_bytes(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()

def http_json(url: str, method: str = "GET", value: Any | None = None, timeout: int = 30) -> Any:
    data = None if value is None else json_bytes(value)
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read())
    except urllib.error.HTTPError as e: raise BridgeError("UPSTREAM_HTTP", f"{e.code}: {e.read().decode(errors='replace')}", "Check the upstream server and workflow")
    except (urllib.error.URLError, TimeoutError) as e: raise BridgeError("UPSTREAM_UNAVAILABLE", str(e), "Start the configured local model server")

def http_bytes(url: str, method: str = "GET", value: Any | None = None, timeout: int = 300) -> bytes:
    data = None if value is None else json_bytes(value)
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(MAX_ASSET_BYTES + 1)
            if len(body) > MAX_ASSET_BYTES: raise BridgeError("ASSET_TOO_LARGE", "Asset exceeds configured limit")
            return body
    except urllib.error.HTTPError as e: raise BridgeError("UPSTREAM_HTTP", f"{e.code}: {e.read().decode(errors='replace')}")
    except urllib.error.URLError as e: raise BridgeError("UPSTREAM_UNAVAILABLE", str(e), "Start the configured local model server")

def validate_glb(path: Path) -> dict[str, int]:
    if not path.is_file() or path.stat().st_size < 20: raise BridgeError("INVALID_GLB", "GLB is missing or shorter than 20 bytes")
    with path.open("rb") as f:
        header = f.read(12)
        magic, version, declared = __import__("struct").unpack("<III", header)
        if magic != 0x46546C67: raise BridgeError("INVALID_GLB", "Invalid GLB magic")
        if version != 2: raise BridgeError("INVALID_GLB", f"Unsupported glTF version {version}")
        if declared != path.stat().st_size: raise BridgeError("INVALID_GLB", "GLB length does not match file")
        offset = 12; json_len = 0; bin_len = 0
        while offset < declared:
            chunk = f.read(8)
            if len(chunk) != 8: raise BridgeError("INVALID_GLB", "Truncated chunk header")
            size, kind = __import__("struct").unpack("<II", chunk)
            if size < 0 or offset + 8 + size > declared: raise BridgeError("INVALID_GLB", "Invalid chunk size")
            if kind == 0x4E4F534A: json_len = size if not json_len else json_len
            elif kind == 0x004E4942: bin_len += size
            f.seek(size, 1); offset += 8 + size
        if not json_len: raise BridgeError("INVALID_GLB", "GLB has no JSON chunk")
        return {"bytes": declared, "jsonBytes": json_len, "binaryBytes": bin_len}

def put_job(job_id: str, **values: Any) -> None:
    with LOCK: JOBS.setdefault(job_id, {}).update(values)

def ensure_not_cancelled(job_id: str) -> None:
    with LOCK:
        if job_id in CANCELLED_JOBS: raise BridgeError("CANCELLED", "Job was cancelled by the client")

def upload_comfy_image(data: bytes, filename: str) -> str:
    boundary = "----AvatarBridge" + hashlib.sha256(os.urandom(16)).hexdigest()[:16]
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{filename}\"\r\nContent-Type: image/png\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(COMFY_URL + "/upload/image", data=body, method="POST", headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r: result = json.loads(r.read())
    except Exception as e: raise BridgeError("COMFY_UPLOAD_FAILED", str(e), "Check ComfyUI /upload/image and input permissions")
    name = result.get("name")
    if not isinstance(name, str) or not name or Path(name).name != name: raise BridgeError("COMFY_BAD_UPLOAD", "ComfyUI returned an unsafe image name")
    return name

def set_workflow_input(workflow: dict[str, Any], image_name: str | None, prompt: str | None, seed: int | None) -> dict[str, Any]:
    # Only mutate well-known input fields; node IDs are configured per pinned workflow.
    out = json.loads(json.dumps(workflow))
    image_node = os.environ.get("COMFY_IMAGE_NODE", "")
    prompt_node = os.environ.get("COMFY_PROMPT_NODE", "")
    seed_node = os.environ.get("COMFY_SEED_NODE", "")
    if image_name and image_node:
        out[image_node]["inputs"]["image"] = image_name
    if prompt and prompt_node:
        out[prompt_node]["inputs"][os.environ.get("COMFY_PROMPT_INPUT", "text")] = prompt
    if seed is not None and seed_node:
        out[seed_node]["inputs"][os.environ.get("COMFY_SEED_INPUT", "seed")] = seed
    return out

def run_comfy(job_id: str, request: dict[str, Any], work: Path) -> Path:
    if not COMFY_WORKFLOW.is_file(): raise BridgeError("WORKFLOW_NOT_INSTALLED", f"Missing workflow: {COMFY_WORKFLOW}", "Install a pinned API-format ComfyUI workflow")
    workflow = json.loads(COMFY_WORKFLOW.read_text())
    image_name = None
    if request.get("imageBase64"):
        raw = base64.b64decode(request["imageBase64"], validate=True)
        if len(raw) > MAX_IMAGE_BYTES: raise BridgeError("IMAGE_TOO_LARGE", "Input image exceeds limit")
        image_name = upload_comfy_image(raw, f"avatar-{job_id}.png")
    prompt = set_workflow_input(workflow, image_name, request.get("prompt"), request.get("seed"))
    put_job(job_id, phase="PREPARING", message="Submitting pinned workflow to ComfyUI")
    accepted = http_json(COMFY_URL + "/prompt", "POST", {"prompt": prompt, "client_id": job_id})
    comfy_id = accepted.get("prompt_id")
    if not comfy_id: raise BridgeError("COMFY_QUEUE_FAILED", "ComfyUI returned no prompt_id")
    put_job(job_id, phase="QUEUED", upstreamId=comfy_id, message="Workflow queued")
    deadline = time.time() + int(os.environ.get("JOB_TIMEOUT_SECONDS", "3600"))
    while time.time() < deadline:
        ensure_not_cancelled(job_id)
        history = http_json(COMFY_URL + "/history/" + urllib.parse.quote(comfy_id, safe=""))
        item = history.get(comfy_id) if isinstance(history, dict) else None
        if item:
            if item.get("status", {}).get("status_str") == "error" or item.get("status", {}).get("completed") is False and item.get("status", {}).get("messages"):
                raise BridgeError("COMFY_FAILED", json.dumps(item.get("status", {})), "Inspect ComfyUI console and required nodes/models")
            outputs = item.get("outputs", {})
            files = []
            for node in outputs.values():
                for key in ("gltf", "glb", "mesh", "files", "models"):
                    for entry in node.get(key, []) if isinstance(node, dict) else []:
                        if isinstance(entry, dict) and str(entry.get("filename", "")).lower().endswith(".glb"): files.append(entry)
            if files:
                entry = files[0]
                query = urllib.parse.urlencode({"filename": entry["filename"], "subfolder": entry.get("subfolder", ""), "type": entry.get("type", "output")})
                put_job(job_id, phase="PROCESSING", message="Downloading ComfyUI GLB")
                body = http_bytes(COMFY_URL + "/view?" + query)
                result = work / "result.glb"; result.write_bytes(body); validate_glb(result); return result
            if item.get("status", {}).get("completed") is True: raise BridgeError("NO_GLB_OUTPUT", "ComfyUI completed but emitted no GLB output", "Configure a GLB output node")
        put_job(job_id, phase="GENERATING", message="Waiting for ComfyUI history")
        time.sleep(1)
    raise BridgeError("JOB_TIMEOUT", "ComfyUI job timed out", "Inspect upstream queue and reduce quality or timeout")

def run_hunyuan(job_id: str, request: dict[str, Any], work: Path) -> Path:
    image = request.get("imageBase64")
    if not image: raise BridgeError("IMAGE_REQUIRED", "The configured Hunyuan3D API path requires an input image")
    raw = base64.b64decode(image, validate=True)
    if len(raw) > MAX_IMAGE_BYTES: raise BridgeError("IMAGE_TOO_LARGE", "Input image exceeds limit")
    put_job(job_id, phase="GENERATING", message="Calling the configured Hunyuan3D API server")
    body = http_bytes(HUNYUAN_URL + "/generate", "POST", {"image": image}, timeout=1800)
    result = work / "result.glb"; result.write_bytes(body); validate_glb(result); return result

def run_triposr(job_id: str, request: dict[str, Any], work: Path) -> Path:
    image = request.get("imageBase64")
    if not image: raise BridgeError("IMAGE_REQUIRED", "TripoSR requires an input image")
    if not TRIPOSR_RUN.is_file(): raise BridgeError("MODEL_NOT_INSTALLED", f"Missing TripoSR run.py: {TRIPOSR_RUN}")
    input_file = work / "input.png"; input_file.write_bytes(base64.b64decode(image, validate=True))
    output_dir = work / "output"; output_dir.mkdir()
    put_job(job_id, phase="GENERATING", message="Running installed TripoSR run.py")
    proc = subprocess.run([os.environ.get("PYTHON", "python3"), str(TRIPOSR_RUN), str(input_file), "--output-dir", str(output_dir), "--bake-texture"], text=True, capture_output=True, timeout=1800)
    if proc.returncode != 0: raise BridgeError("TRIPOSR_FAILED", proc.stderr[-4000:], "Check PyTorch/CUDA and TripoSR dependencies")
    candidates = list(output_dir.rglob("*.glb"))
    if len(candidates) != 1: raise BridgeError("NO_GLB_OUTPUT", f"TripoSR produced {len(candidates)} GLB files; exactly one is required")
    result = work / "result.glb"; shutil.copyfile(candidates[0], result); validate_glb(result); return result

def _worker_url(path_or_url: str, base_url: str) -> str:
    """Resolve a worker URL and reject credentials or unsupported schemes."""
    url = urllib.parse.urljoin(base_url.rstrip("/") + "/", path_or_url)
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise BridgeError("VIDEO_WORKER_BAD_URL", "Worker returned an unsafe URL")
    return url

def _worker_json(url: str, timeout: int, authenticated: bool = True) -> tuple[dict[str, Any], str]:
    headers = {"Accept": "application/json"}
    if authenticated:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
            raw = response.read(2 * 1024 * 1024)
            if content_type != "application/json":
                raise BridgeError("VIDEO_WORKER_BAD_STATUS", "Worker job status endpoint did not return JSON")
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise BridgeError("VIDEO_WORKER_BAD_STATUS", "Worker status must be a JSON object")
            return payload, response.geturl()
    except urllib.error.HTTPError as exc:
        raise BridgeError("VIDEO_WORKER_STATUS_HTTP", f"Worker status HTTP {exc.code}: {exc.read().decode(errors='replace')[-1000:]}")
    except (urllib.error.URLError, TimeoutError) as exc:
        raise BridgeError("VIDEO_WORKER_STATUS_UNAVAILABLE", str(exc), "Check worker health, network reachability, and job retention")

def _download_worker_video(url: str, destination: Path, timeout: int) -> tuple[bytes, str]:
    headers = {"Accept": "video/mp4,application/octet-stream"}
    # Never forward the private worker token to a different host (for example signed object storage).
    if urllib.parse.urlsplit(url).hostname == urllib.parse.urlsplit(ECHOMIMIC_WORKER_URL).hostname:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
            body = response.read(MAX_ASSET_BYTES + 1)
            if len(body) > MAX_ASSET_BYTES:
                raise BridgeError("VIDEO_OUTPUT_TOO_LARGE", "Downloaded MP4 exceeds configured size limit")
            if not body:
                raise BridgeError("VIDEO_OUTPUT_EMPTY", "Worker returned an empty video download")
            if content_type not in ("video/mp4", "application/octet-stream", "binary/octet-stream"):
                raise BridgeError("VIDEO_OUTPUT_INVALID", f"Worker download content type is {content_type or 'missing'}, not MP4")
            destination.write_bytes(body)
            return body, response.headers.get("X-Video-SHA256", "")
    except urllib.error.HTTPError as exc:
        raise BridgeError("VIDEO_DOWNLOAD_FAILED", f"Video download HTTP {exc.code}: {exc.read().decode(errors='replace')[-1000:]}")
    except (urllib.error.URLError, TimeoutError) as exc:
        raise BridgeError("VIDEO_DOWNLOAD_FAILED", str(exc), "Retry the download or inspect the worker's output URL expiry")

def execute_remote_video(job_id: str, request: dict[str, Any]) -> None:
    """Submit a real async worker job, poll its status, download its artifact, and validate it."""
    work = VIDEO_ROOT / job_id
    work.mkdir(parents=True, exist_ok=True)
    try:
        if not ECHOMIMIC_WORKER_URL:
            raise BridgeError("VIDEO_BACKEND_UNAVAILABLE", "ECHOMIMIC_WORKER_URL is not configured", "Deploy a real EchoMimic-compatible GPU worker and configure its URL")
        if not request.get("imageBase64") or not request.get("audioBase64"):
            raise BridgeError("INPUT_REQUIRED", "imageBase64 and audioBase64 are required")
        max_audio_bytes = int(os.environ.get("MAX_AUDIO_BYTES", str(50 * 1024 * 1024)))
        image = base64.b64decode(request["imageBase64"], validate=True)
        audio = base64.b64decode(request["audioBase64"], validate=True)
        if len(image) > MAX_IMAGE_BYTES or len(audio) > max_audio_bytes:
            raise BridgeError("INPUT_TOO_LARGE", "Image or audio exceeds configured size limit")
        timeout = int(os.environ.get("VIDEO_JOB_TIMEOUT_SECONDS", "3600"))
        poll_seconds = max(1, int(os.environ.get("VIDEO_POLL_INTERVAL_SECONDS", "3")))
        deadline = time.monotonic() + timeout
        with LOCK:
            VIDEO_JOBS[job_id].update({"phase": "SUBMITTING", "message": "Submitting image and audio to the configured GPU worker"})
        submit_url = _worker_url(os.environ.get("VIDEO_WORKER_SUBMIT_PATH", "/generate"), ECHOMIMIC_WORKER_URL)
        payload = dict(request)
        # The bridge owns its job ID so duplicate client IDs cannot overwrite active jobs.
        payload["requestId"] = job_id
        req = urllib.request.Request(
            submit_url, data=json_bytes(payload), method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json", "Authorization": "Bearer " + TOKEN}
        )
        try:
            with urllib.request.urlopen(req, timeout=min(60, timeout)) as response:
                content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
                raw = response.read(2 * 1024 * 1024)
                if response.status not in (200, 201, 202):
                    raise BridgeError("VIDEO_WORKER_REJECTED", f"Worker returned HTTP {response.status}")
                if content_type != "application/json":
                    raise BridgeError("VIDEO_WORKER_CONTRACT", "Worker must return a JSON job receipt, not a synchronous video body")
                receipt = json.loads(raw)
        except urllib.error.HTTPError as exc:
            raise BridgeError("VIDEO_WORKER_REJECTED", f"Worker submit HTTP {exc.code}: {exc.read().decode(errors='replace')[-2000:]}", "Check worker API contract, model weights, and GPU capacity")
        except (urllib.error.URLError, TimeoutError) as exc:
            raise BridgeError("VIDEO_WORKER_UNAVAILABLE", str(exc), "Check worker health and network reachability")
        if not isinstance(receipt, dict):
            raise BridgeError("VIDEO_WORKER_CONTRACT", "Worker submit response is not a JSON object")
        remote_id = receipt.get("jobId") or receipt.get("job_id") or receipt.get("id")
        status_ref = receipt.get("statusUrl") or receipt.get("status_url")
        if not status_ref and remote_id:
            status_ref = os.environ.get("VIDEO_WORKER_STATUS_PATH", "/jobs/{job_id}").replace("{job_id}", urllib.parse.quote(str(remote_id), safe=""))
        if not status_ref:
            raise BridgeError("VIDEO_WORKER_CONTRACT", "Worker receipt must include jobId and/or statusUrl; refusing to claim success without polling")
        status_url = _worker_url(str(status_ref), ECHOMIMIC_WORKER_URL)
        allowed_worker_host = urllib.parse.urlsplit(ECHOMIMIC_WORKER_URL).hostname
        if urllib.parse.urlsplit(status_url).hostname != allowed_worker_host:
            raise BridgeError("VIDEO_WORKER_BAD_URL", "Worker status URL must remain on the configured worker host")
        with LOCK:
            VIDEO_JOBS[job_id].update({"phase": "QUEUED", "upstreamId": str(remote_id or ""), "message": "Worker accepted the job; polling actual job status"})
        terminal_success = {"COMPLETED", "SUCCEEDED", "SUCCESS", "DONE"}
        terminal_failure = {"FAILED", "ERROR", "CANCELLED", "CANCELED", "REJECTED"}
        while time.monotonic() < deadline:
            status, _ = _worker_json(status_url, timeout=min(30, max(5, int(deadline - time.monotonic()))))
            raw_state = status.get("phase") or status.get("status") or status.get("state")
            state = str(raw_state or "").upper()
            with LOCK:
                VIDEO_JOBS[job_id].update({"phase": "WORKER_" + (state or "UNKNOWN"), "workerStatus": status, "message": "Polling upstream generation job"})
            if state in terminal_failure:
                error_value = status.get("error") or status.get("message") or state
                raise BridgeError("VIDEO_WORKER_JOB_FAILED", str(error_value)[:2000], "Inspect the GPU worker logs and model runtime")
            if state in terminal_success:
                output = status.get("video") or status.get("output") or status.get("result")
                if isinstance(output, dict):
                    output = output.get("url") or output.get("downloadUrl") or output.get("download_url")
                output_url = status.get("downloadUrl") or status.get("download_url") or status.get("outputUrl") or status.get("output_url") or output
                if not output_url and status.get("assetUrl"):
                    output_url = status["assetUrl"]
                if not output_url:
                    raise BridgeError("VIDEO_WORKER_CONTRACT", "Worker reported success without a downloadable video URL")
                video_url = _worker_url(str(output_url), ECHOMIMIC_WORKER_URL)
                with LOCK:
                    VIDEO_JOBS[job_id].update({"phase": "DOWNLOADING", "message": "Worker completed; downloading the actual video artifact"})
                result = work / "result.mp4"
                data, digest_header = _download_worker_video(video_url, result, timeout=min(300, timeout))
                digest = hashlib.sha256(data).hexdigest()
                if digest_header and digest_header.lower().removeprefix("sha256:") != digest:
                    raise BridgeError("VIDEO_HASH_MISMATCH", "Worker-provided SHA-256 does not match downloaded bytes")
                with LOCK:
                    VIDEO_JOBS[job_id].update({"phase": "VALIDATING", "message": "Checking MP4 container and decoding streams"})
                probe = subprocess.run(
                    [os.environ.get("FFPROBE", "ffprobe"), "-v", "error", "-show_entries",
                     "format=format_name,duration,size:stream=codec_type,codec_name,width,height,duration",
                     "-of", "json", str(result)],
                    capture_output=True, text=True, timeout=45
                )
                if probe.returncode:
                    raise BridgeError("VIDEO_VALIDATION_FAILED", "Downloaded artifact failed ffprobe validation: " + probe.stderr[-1000:])
                metadata = json.loads(probe.stdout)
                fmt = metadata.get("format", {})
                streams = metadata.get("streams", [])
                duration = float(fmt.get("duration") or 0)
                format_names = str(fmt.get("format_name", "")).split(",")
                has_video = any(x.get("codec_type") == "video" and int(x.get("width") or 0) > 0 and int(x.get("height") or 0) > 0 for x in streams)
                has_audio = any(x.get("codec_type") == "audio" for x in streams)
                if "mp4" not in format_names and "mov" not in format_names:
                    raise BridgeError("VIDEO_VALIDATION_FAILED", "Downloaded file is not recognized as an MP4/MOV container")
                if duration <= 0 or not has_video or not has_audio or result.stat().st_size < 1024:
                    raise BridgeError("VIDEO_VALIDATION_FAILED", "MP4 must be non-empty, have positive duration, and contain decodable video and audio streams")
                # Decode the entire file, not just its header, before publishing the asset.
                decode = subprocess.run(
                    [os.environ.get("FFMPEG", "ffmpeg"), "-v", "error", "-i", str(result), "-f", "null", "-"],
                    capture_output=True, text=True, timeout=min(300, timeout)
                )
                if decode.returncode:
                    raise BridgeError("VIDEO_VALIDATION_FAILED", "Full video/audio decode failed: " + decode.stderr[-1000:])
                final = VIDEO_ASSETS / (digest + ".mp4")
                temporary = final.with_suffix(".mp4.tmp")
                temporary.write_bytes(data)
                temporary.replace(final)
                with LOCK:
                    VIDEO_JOBS[job_id].update({
                        "phase": "COMPLETED", "assetId": digest,
                        "downloadUrl": "/v1/video/assets/" + digest,
                        "validation": {"bytes": len(data), "sha256": digest, "durationSeconds": duration,
                                       "container": format_names, "streams": streams, "fullDecodePassed": True},
                        "message": "Real worker job completed; MP4 downloaded, fully decoded, and validated"
                    })
                return
            time.sleep(min(poll_seconds, max(0, deadline - time.monotonic())))
        raise BridgeError("VIDEO_JOB_TIMEOUT", "Worker job did not reach a terminal state before the deadline", "Inspect the remote job and increase VIDEO_JOB_TIMEOUT_SECONDS if appropriate")
    except Exception as exc:
        error = {"code": exc.code, "message": str(exc), "recovery": exc.recovery} if isinstance(exc, BridgeError) else {"code": "INTERNAL", "message": str(exc)}
        with LOCK:
            VIDEO_JOBS[job_id].update({"phase": "FAILED", "error": error, "message": "Video generation did not produce a validated MP4"})

def execute(job_id: str, request: dict[str, Any]) -> None:
    work = ROOT / "jobs" / job_id; work.mkdir(parents=True, exist_ok=True)
    try:
        validate_request_policy(request)
        put_job(job_id, phase="PREPARING")
        if MODE == "comfy": result = run_comfy(job_id, request, work)
        elif MODE == "hunyuan": result = run_hunyuan(job_id, request, work)
        elif MODE == "triposr": result = run_triposr(job_id, request, work)
        else: raise BridgeError("UNSUPPORTED_BACKEND", f"Unknown AVATAR_BACKEND={MODE}")
        ensure_not_cancelled(job_id)
        asset_id = hashlib.sha256(result.read_bytes()).hexdigest()
        final = ROOT / "assets" / f"{asset_id}.glb"; final.parent.mkdir(exist_ok=True); shutil.copyfile(result, final); info = validate_glb(final)
        put_job(job_id, phase="COMPLETED", assetId=asset_id, validation=info, message="GLB validated")
    except Exception as e:
        if isinstance(e, BridgeError) and e.code == "CANCELLED": put_job(job_id, phase="CANCELLED", error={"code": e.code, "message": str(e), "recovery": e.recovery})
        elif isinstance(e, BridgeError): put_job(job_id, phase="FAILED", error={"code": e.code, "message": str(e), "recovery": e.recovery})
        else: put_job(job_id, phase="FAILED", error={"code": "INTERNAL", "message": str(e)})

def _http_ready(url: str) -> tuple[bool, str | None]:
    try:
        http_json(url, timeout=5)
        return True, None
    except Exception as exc:
        return False, str(exc)

def health() -> dict[str, Any]:
    """Report every configured provider independently; readiness requires real runtime checks."""
    providers: list[dict[str, Any]] = []
    comfy_up, comfy_error = _http_ready(COMFY_URL + "/system_stats")
    comfy_workflow = COMFY_WORKFLOW.is_file()
    providers.append({
        "id": "comfyui-hunyuan3d",
        "state": "READY" if comfy_up and comfy_workflow else ("NOT_INSTALLED" if not comfy_workflow else "UNAVAILABLE"),
        "capabilities": ["IMAGE_TO_3D"],
        "workflowInstalled": comfy_workflow,
        **({"error": comfy_error} if comfy_error else {}),
    })

    hunyuan_up, hunyuan_error = _http_ready(HUNYUAN_URL + "/health")
    shape_installed = hunyuan_up
    texture_url = os.environ.get("HUNYUAN_TEXTURE_URL", "").rstrip("/")
    texture_up, texture_error = _http_ready(texture_url + "/health") if texture_url else (False, "HUNYUAN_TEXTURE_URL is not configured")
    providers.append({
        "id": "hunyuan3d-shape",
        "state": "READY" if shape_installed else "UNAVAILABLE",
        "capabilities": ["IMAGE_TO_3D", "HUMAN", "GENERAL"],
        **({"error": hunyuan_error} if hunyuan_error else {}),
    })
    providers.append({
        "id": "hunyuan3d-texture",
        "state": "READY" if texture_up else "NOT_INSTALLED",
        "capabilities": ["PBR_TEXTURE"],
        **({"error": texture_error} if texture_error else {}),
    })

    triposr_installed = TRIPOSR_RUN.is_file()
    providers.append({
        "id": "triposr",
        "state": "READY" if triposr_installed else "NOT_INSTALLED",
        "capabilities": ["IMAGE_TO_3D", "OBJECT", "CAR", "GENERAL"],
        "run": str(TRIPOSR_RUN) if triposr_installed else None,
    })
    selected_id = "comfyui-hunyuan3d" if MODE == "comfy" else ("hunyuan3d-shape" if MODE == "hunyuan" else "triposr")
    selected = next((p for p in providers if p["id"] == selected_id), None)
    for provider in providers:
        provider["adultCapability"] = ADULT_CAPABILITY if provider is selected else "UNSUPPORTED"
    video_providers = [
        {"id": "wan-2.2", "state": "NOT_INSTALLED", "capabilities": ["TEXT_TO_VIDEO", "IMAGE_TO_VIDEO"], "message": "Wan 2.2 runtime and weights are not installed"},
        {"id": "ltx-2", "state": "NOT_INSTALLED", "capabilities": ["TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "AUDIO_VIDEO"], "message": "LTX-2 runtime and weights are not installed"},
    ]
    return {"ready": any(p["state"] == "READY" for p in providers), "mode": MODE, "providers": providers, "videoProviders": video_providers, "selected": selected}

class Handler(BaseHTTPRequestHandler):
    def _auth(self) -> bool:
        return not TOKEN or self.headers.get("Authorization", "") == "Bearer " + TOKEN
    def _send(self, code: int, body: Any, content_type: str = "application/json"):
        raw = body if isinstance(body, bytes) else json_bytes(body); self.send_response(code); self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if not self._auth(): return self._send(401, {"error": "unauthorized"})
        path = urllib.parse.urlparse(self.path).path
        if path == "/health":
            status = health()
            if ECHOMIMIC_WORKER_URL:
                ok, error = _http_ready(ECHOMIMIC_WORKER_URL + "/health")
                status["videoWorker"] = {"configured": True, "reachable": ok, **({"error": error} if error else {})}
            else:
                status["videoWorker"] = {"configured": False, "reachable": False, "state": "NOT_CONFIGURED"}
            return self._send(200, status)
        vm = re.fullmatch(r"/v1/video/jobs/([A-Za-z0-9-]{1,64})", path)
        if vm:
            with LOCK: job = dict(VIDEO_JOBS.get(vm.group(1), {}))
            return self._send(200 if job else 404, job or {"error": "job_not_found"})
        vm = re.fullmatch(r"/v1/video/assets/([a-f0-9]{64})", path)
        if vm:
            asset = VIDEO_ASSETS / (vm.group(1) + ".mp4")
            return self._send(200, asset.read_bytes(), "video/mp4") if asset.is_file() else self._send(404, {"error": "asset_not_found"})
        m = re.fullmatch(r"/v1/jobs/([A-Za-z0-9-]{1,64})", path)
        if m:
            with LOCK: job = dict(JOBS.get(m.group(1), {}))
            return self._send(200 if job else 404, job or {"error": "job_not_found"})
        m = re.fullmatch(r"/v1/assets/([a-f0-9]{64})", path)
        if m:
            asset = ROOT / "assets" / (m.group(1) + ".glb")
            return self._send(200, asset.read_bytes(), "model/gltf-binary") if asset.is_file() else self._send(404, {"error": "asset_not_found"})
        self._send(404, {"error": "not_found"})
    def do_POST(self):
        if not self._auth(): return self._send(401, {"error": "unauthorized"})
        if self.path == "/v1/video/generate":
            try:
                size = int(self.headers.get("Content-Length", "0"))
                max_audio_bytes = int(os.environ.get("MAX_AUDIO_BYTES", str(50 * 1024 * 1024)))
                if size <= 0 or size > (MAX_IMAGE_BYTES * 2) + (max_audio_bytes * 2) + (1024 * 1024):
                    raise BridgeError("REQUEST_TOO_LARGE", "Video request is empty or exceeds configured limit")
                request = json.loads(self.rfile.read(size))
                if not ECHOMIMIC_WORKER_URL:
                    return self._send(503, {"error": {"code": "VIDEO_BACKEND_UNAVAILABLE", "message": "No EchoMimic GPU worker is configured", "recovery": "Configure ECHOMIMIC_WORKER_URL and the private worker token"}})
                job_id = str(request.get("requestId", ""))
                if not re.fullmatch(r"[A-Za-z0-9-]{1,64}", job_id):
                    job_id = hashlib.sha256(os.urandom(32)).hexdigest()[:32]
                with LOCK:
                    VIDEO_JOBS[job_id] = {"jobId": job_id, "phase": "QUEUED", "createdAt": time.time(), "provider": "echomimicv3-flash"}
                threading.Thread(target=execute_remote_video, args=(job_id, request), daemon=True).start()
                return self._send(202, {"jobId": job_id, "statusUrl": "/v1/video/jobs/" + job_id})
            except BridgeError as error:
                return self._send(400, {"error": {"code": error.code, "message": str(error), "recovery": error.recovery}})
            except Exception as error:
                return self._send(400, {"error": {"code": "INVALID_REQUEST", "message": str(error)}})
        if self.path == "/v1/image/generate":
            return self._send(503, {"error": {"code": "IMAGE_BACKEND_UNAVAILABLE", "message": "No local image-generation workflow is installed", "recovery": "Install and configure a supported local image backend before submitting image jobs"}})
        if self.path == "/v1/audio/generate":
            return self._send(503, {"error": {"code": "AUDIO_BACKEND_UNAVAILABLE", "message": "No local audio-generation backend is installed", "recovery": "Install and configure a supported local audio backend before submitting audio jobs"}})
        if self.path != "/v1/generate": return self._send(404, {"error": "not_found"})
        try:
            size = int(self.headers.get("Content-Length", "0"));
            if size > MAX_IMAGE_BYTES * 2: raise BridgeError("REQUEST_TOO_LARGE", "Request exceeds limit")
            request = json.loads(self.rfile.read(size)); requested_id = str(request.get("requestId", ""))
            job_id = requested_id if re.fullmatch(r"[A-Za-z0-9-]{1,64}", requested_id) else hashlib.sha256(os.urandom(32)).hexdigest()[:32]
            put_job(job_id, phase="QUEUED", createdAt=time.time())
            try:
                validate_request_policy(request)
            except BridgeError as error:
                put_job(job_id, phase="FAILED", error={"code": error.code, "message": str(error), "recovery": error.recovery})
                return self._send(202, {"jobId": job_id})
            if not request.get("adultModeEnabled"):
                selected = health().get("selected")
                if not selected or selected.get("state") != "READY":
                    put_job(job_id, phase="FAILED", error={"code": "3D_BACKEND_UNAVAILABLE", "message": "No configured 3D backend is ready", "recovery": "Install and start a supported backend, then verify /health"})
                    return self._send(202, {"jobId": job_id})
            threading.Thread(target=execute, args=(job_id, request), daemon=True).start()
            self._send(202, {"jobId": job_id})
        except Exception as e: self._send(400, {"error": str(e)})
    def do_DELETE(self):
        if not self._auth(): return self._send(401, {"error": "unauthorized"})
        path = urllib.parse.urlparse(self.path).path
        m = re.fullmatch(r"/v1/jobs/([A-Za-z0-9-]{1,64})", path)
        if not m: return self._send(404, {"error": "not_found"})
        job_id = m.group(1)
        with LOCK:
            if job_id not in JOBS: return self._send(404, {"error": "job_not_found"})
            CANCELLED_JOBS.add(job_id)
            JOBS[job_id].update({"phase": "CANCELLED", "message": "Cancelled by client"})
        self._send(200, {"jobId": job_id, "phase": "CANCELLED"})
    def log_message(self, *_): pass

if __name__ == "__main__":
    print(f"Avatar bridge listening on http://{HOST}:{PORT} backend={MODE}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
