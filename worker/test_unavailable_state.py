#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys, time, urllib.error, urllib.request

env = os.environ.copy(); env.update({"AVATAR_BACKEND": "triposr", "TRIPOSR_RUN": "/does/not/exist", "BRIDGE_TOKEN": "test", "BRIDGE_PORT": "8099"})
script = pathlib.Path(__file__).with_name("local_bridge.py")
p = subprocess.Popen([sys.executable, str(script)], cwd=str(script.parent), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def call(path, method="GET", payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request("http://127.0.0.1:8099" + path, method=method, data=body, headers={"Authorization": "Bearer test", "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=3))

try:
    health = None
    for _ in range(30):
        try: health = call("/health"); break
        except (urllib.error.URLError, ConnectionRefusedError): time.sleep(0.1)
    assert health is not None and health["ready"] is False and all(p["state"] != "READY" for p in health["providers"]), health
    accepted = call("/v1/generate", "POST", {"requestId": "test-job", "imageBase64": "aGVsbG8=", "photoIsSelfOwned": True, "photoConsentConfirmed": True})
    assert accepted["jobId"] == "test-job", accepted
    job = None
    for _ in range(30):
        job = call("/v1/jobs/test-job")
        if job.get("phase") == "FAILED": break
        time.sleep(0.1)
    assert job and job["phase"] == "FAILED" and job["error"]["code"] == "3D_BACKEND_UNAVAILABLE", job
    adult_blocked = call("/v1/generate", "POST", {"requestId": "adult-blocked", "prompt": "adult nude portrait"})
    assert adult_blocked["jobId"] == "adult-blocked", adult_blocked
    for _ in range(30):
        blocked_job = call("/v1/jobs/adult-blocked")
        if blocked_job.get("phase") == "FAILED": break
        time.sleep(0.1)
    assert blocked_job["error"]["code"] == "ADULT_MODE_REQUIRED", blocked_job
    adult_unavailable = call("/v1/generate", "POST", {"requestId": "adult-unavailable", "prompt": "18+ adult nude portrait", "adultModeEnabled": True, "ageGateConfirmed": True})
    assert adult_unavailable["jobId"] == "adult-unavailable", adult_unavailable
    for _ in range(30):
        unavailable_job = call("/v1/jobs/adult-unavailable")
        if unavailable_job.get("phase") == "FAILED": break
        time.sleep(0.1)
    assert unavailable_job["error"]["code"] == "ADULT_CAPABILITY_UNAVAILABLE", unavailable_job
    print("TRUTHFUL_UNAVAILABLE_STATE_OK")
finally:
    p.terminate(); p.wait(timeout=3)
