#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys, time, urllib.error, urllib.request

env = os.environ.copy(); env.update({"AVATAR_BACKEND": "triposr", "TRIPOSR_RUN": "/does/not/exist", "BRIDGE_TOKEN": "test", "BRIDGE_PORT": "8101"})
script = pathlib.Path(__file__).with_name("local_bridge.py")
p = subprocess.Popen([sys.executable, str(script)], cwd=str(script.parent), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def call(path):
    req = urllib.request.Request("http://127.0.0.1:8101" + path, method="POST", data=b"{}", headers={"Authorization": "Bearer test", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

try:
    for _ in range(30):
        try:
            image_status, image = call("/v1/image/generate")
            break
        except (urllib.error.URLError, ConnectionRefusedError):
            time.sleep(0.1)
    audio_status, audio = call("/v1/audio/generate")
    assert image_status == 503 and image["error"]["code"] == "IMAGE_BACKEND_UNAVAILABLE", (image_status, image)
    assert audio_status == 503 and audio["error"]["code"] == "AUDIO_BACKEND_UNAVAILABLE", (audio_status, audio)
    print("TRUTHFUL_MEDIA_UNAVAILABLE_OK")
finally:
    p.terminate(); p.wait(timeout=3)
