#!/usr/bin/env python3
"""Authenticated EchoMimicV3-Flash worker. Run only on a private Linux/CUDA GPU host."""
from __future__ import annotations
import base64, hashlib, json, os, subprocess, tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT_RAW = os.environ.get('ECHOMIMIC_ROOT', '').strip()
ROOT = Path(ROOT_RAW).resolve() if ROOT_RAW else None
PYTHON = os.environ.get('ECHOMIMIC_PYTHON', 'python3')
CONFIG = os.environ.get('ECHOMIMIC_CONFIG', '')
MODEL = os.environ.get('ECHOMIMIC_MODEL', '')
TRANSFORMER = os.environ.get('ECHOMIMIC_TRANSFORMER', '')
WAV2VEC = os.environ.get('ECHOMIMIC_WAV2VEC', '')
CKPT = os.environ.get('ECHOMIMIC_CKPT_IDX', '50000')
TOKEN = os.environ.get('BRIDGE_TOKEN', '')
HOST = os.environ.get('VIDEO_WORKER_HOST', '127.0.0.1')
PORT = int(os.environ.get('VIDEO_WORKER_PORT', '8091'))
FFPROBE = os.environ.get('FFPROBE', 'ffprobe')
MAX_IMAGE = int(os.environ.get('MAX_IMAGE_BYTES', str(20 * 1024 * 1024)))
MAX_AUDIO = int(os.environ.get('MAX_AUDIO_BYTES', str(50 * 1024 * 1024)))
MAX_OUTPUT = int(os.environ.get('MAX_VIDEO_BYTES', str(512 * 1024 * 1024)))


def health():
    required = {'checkout': ROOT / 'infer_flash.py' if ROOT else None,
                'config': Path(CONFIG) if CONFIG else None,
                'base_model': Path(MODEL) if MODEL else None,
                'transformer': Path(TRANSFORMER) if TRANSFORMER else None,
                'wav2vec': Path(WAV2VEC) if WAV2VEC else None}
    missing = [k for k, v in required.items() if v is None or not v.exists()]
    if missing:
        return {'ready': False, 'state': 'NOT_INSTALLED', 'missing': missing, 'provider': 'echomimicv3-flash'}
    try:
        p = subprocess.run([PYTHON, '-c', "import torch, diffusers; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else ''); print(torch.cuda.get_device_properties(0).total_memory if torch.cuda.is_available() else 0)"], cwd=str(ROOT), capture_output=True, text=True, timeout=15)
        if p.returncode:
            return {'ready': False, 'state': 'UNAVAILABLE', 'error': p.stderr[-1200:], 'provider': 'echomimicv3-flash'}
        lines = p.stdout.splitlines()
        cuda = bool(lines and lines[0].strip() == 'True')
        gpu = lines[1].strip() if len(lines) > 1 else ''
        vram = int(lines[2]) if len(lines) > 2 and lines[2].isdigit() else 0
        ready = cuda and vram >= 12 * 1024**3
        return {'ready': ready, 'state': 'READY' if ready else 'INSUFFICIENT_RESOURCES', 'provider': 'echomimicv3-flash', 'cuda': cuda, 'gpu': gpu, 'vramBytes': vram, 'minimumVramBytes': 12 * 1024**3}
    except Exception as e:
        return {'ready': False, 'state': 'UNAVAILABLE', 'error': str(e), 'provider': 'echomimicv3-flash'}


class Handler(BaseHTTPRequestHandler):
    def send_json(self, status, value):
        data = json.dumps(value, separators=(',', ':')).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

    def authorized(self):
        return bool(TOKEN) and self.headers.get('Authorization', '') == 'Bearer ' + TOKEN

    def do_GET(self):
        if self.path != '/health':
            return self.send_json(404, {'error': 'not_found'})
        return self.send_json(200, health())

    def do_POST(self):
        if self.path != '/generate':
            return self.send_json(404, {'error': 'not_found'})
        if not self.authorized():
            return self.send_json(401, {'error': 'unauthorized'})
        status = health()
        if not status.get('ready'):
            return self.send_json(503, {'error': {'code': 'VIDEO_BACKEND_UNAVAILABLE', 'provider': status}})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length <= 0 or length > MAX_IMAGE + MAX_AUDIO + 1024 * 1024:
                return self.send_json(413, {'error': {'code': 'REQUEST_TOO_LARGE'}})
            req = json.loads(self.rfile.read(length))
            image = base64.b64decode(req.get('imageBase64', ''), validate=True)
            audio = base64.b64decode(req.get('audioBase64', ''), validate=True)
            if not image or len(image) > MAX_IMAGE or not audio or len(audio) > MAX_AUDIO:
                return self.send_json(400, {'error': {'code': 'INVALID_INPUT', 'message': 'Portrait or audio is empty or over size limit'}})
            with tempfile.TemporaryDirectory(prefix='echomimic-job-') as temp:
                work = Path(temp); image_path = work / 'portrait.png'; audio_path = work / 'speech.wav'; out = work / 'output'; out.mkdir()
                image_path.write_bytes(image); audio_path.write_bytes(audio)
                for path in (image_path, audio_path):
                    p = subprocess.run([FFPROBE, '-v', 'error', str(path)], capture_output=True, timeout=15)
                    if p.returncode:
                        return self.send_json(400, {'error': {'code': 'INVALID_MEDIA', 'message': 'Input media failed ffprobe validation'}})
                steps = max(5, min(int(req.get('steps', 8)), 25)); length = max(17, min(int(req.get('videoLength', 81)), 138))
                width = max(256, min(int(req.get('width', 768)), 768)); height = max(256, min(int(req.get('height', 768)), 768))
                command = [PYTHON, str(ROOT / 'infer_flash.py'), '--image_path', str(image_path), '--audio_path', str(audio_path), '--prompt', str(req.get('prompt') or 'A person is speaking naturally.'), '--num_inference_steps', str(steps), '--config_path', CONFIG, '--model_name', MODEL, '--ckpt_idx', CKPT, '--transformer_path', TRANSFORMER, '--save_path', str(out), '--wav2vec_model_dir', WAV2VEC, '--sampler_name', 'Flow_Unipc', '--video_length', str(length), '--sample_size', str(width), str(height), '--fps', '25', '--seed', str(int(req.get('seed', 43))), '--enable_teacache']
                timeout = max(60, min(int(os.environ.get('VIDEO_JOB_TIMEOUT_SECONDS', '3600')), 14400))
                run = subprocess.run(command, cwd=str(ROOT), capture_output=True, text=True, timeout=timeout)
                if run.returncode:
                    return self.send_json(502, {'error': {'code': 'INFERENCE_FAILED', 'message': run.stderr[-2500:]}})
                files = [p for p in out.rglob('*.mp4') if p.is_file() and p.stat().st_size > 1024]
                if len(files) != 1:
                    return self.send_json(502, {'error': {'code': 'INVALID_OUTPUT', 'message': 'Expected exactly one non-empty MP4'}})
                video = files[0]
                if video.stat().st_size > MAX_OUTPUT:
                    return self.send_json(413, {'error': {'code': 'OUTPUT_TOO_LARGE'}})
                probe = subprocess.run([FFPROBE, '-v', 'error', '-show_entries', 'format=duration:stream=codec_type,width,height', '-of', 'json', str(video)], capture_output=True, text=True, timeout=30)
                if probe.returncode:
                    return self.send_json(502, {'error': {'code': 'OUTPUT_VALIDATION_FAILED'}})
                metadata = json.loads(probe.stdout); streams = metadata.get('streams', [])
                if not any(x.get('codec_type') == 'video' for x in streams) or not any(x.get('codec_type') == 'audio' for x in streams) or float(metadata.get('format', {}).get('duration', 0)) <= 0:
                    return self.send_json(502, {'error': {'code': 'OUTPUT_VALIDATION_FAILED', 'message': 'Output must contain valid audio and video streams'}})
                data = video.read_bytes(); digest = hashlib.sha256(data).hexdigest()
                self.send_response(200); self.send_header('Content-Type', 'video/mp4'); self.send_header('Content-Length', str(len(data))); self.send_header('X-Video-SHA256', digest); self.send_header('X-Video-Duration', str(metadata['format']['duration'])); self.end_headers(); self.wfile.write(data)
        except subprocess.TimeoutExpired:
            self.send_json(504, {'error': {'code': 'VIDEO_TIMEOUT'}})
        except (ValueError, json.JSONDecodeError) as e:
            self.send_json(400, {'error': {'code': 'INVALID_REQUEST', 'message': str(e)}})
        except Exception as e:
            self.send_json(500, {'error': {'code': 'WORKER_ERROR', 'message': str(e)}})

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    print(json.dumps({'event': 'startup', 'host': HOST, 'port': PORT, 'health': health()}), flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
