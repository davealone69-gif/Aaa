#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys, time, urllib.error, urllib.request

env = os.environ.copy(); env.update({"AVATAR_BACKEND": "triposr", "TRIPOSR_RUN": "/does/not/exist", "BRIDGE_TOKEN": "test", "BRIDGE_PORT": "8100"})
script = pathlib.Path(__file__).with_name("local_bridge.py")
p = subprocess.Popen([sys.executable, str(script)], cwd=str(script.parent), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def call(path, method="GET", payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request("http://127.0.0.1:8100" + path, method=method, data=body, headers={"Authorization": "Bearer test", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

try:
    for _ in range(30):
        try:
            status, health = call("/health")
            break
        except (urllib.error.URLError, ConnectionRefusedError):
            time.sleep(0.1)
    assert health["videoProviders"][0]["state"] == "NOT_INSTALLED", health
    status, body = call("/v1/video/generate", "POST", {"requestId": "video-test", "prompt": "test"})
    assert status == 503 and body["error"]["code"] == "VIDEO_BACKEND_UNAVAILABLE", (status, body)
    print("TRUTHFUL_VIDEO_UNAVAILABLE_OK")
finally:
    p.terminate(); p.wait(timeout=3)
