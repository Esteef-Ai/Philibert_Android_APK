"""A stand-in for the Android app: pairs through the phone page's address, answers actions, and
runs one phone chat, to test Philibert without a phone.
python fake_phone.py <phone address .../phone/> "<message>" [model]"""
import json
import sys
import threading
import time
import urllib.request

BASE = sys.argv[1].rstrip("/") + "/api/"
CAPS = [
    {"name": "set_alarm", "params": ["hour", "minute", "label"], "required": ["hour", "minute"], "description": "Set an alarm (24-hour clock)"},
    {"name": "open_app", "params": ["name"], "required": ["name"], "description": "Open an app by its name"},
    {"name": "read_screen", "params": [], "required": [], "description": "What is on the screen now, numbered"},
    {"name": "tap", "params": ["id"], "required": ["id"], "description": "Tap an element number from read_screen"},
    {"name": "flashlight", "params": ["on"], "required": ["on"], "description": "Torch on or off"},
]
calls = []


def post(op, body, timeout=70):
    req = urllib.request.Request(BASE + op, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def phone():
    did = post("device_register", {"device_id": "pixel9pro-test", "name": "Pixel 9 Pro", "platform": "android", "capabilities": CAPS})["device_id"]
    while True:
        c = post("device_poll", {"device_id": did, "wait": 20}).get("call")
        if not c:
            continue
        calls.append(c)
        print("PHONE GOT:", c["tool"], c["args"], flush=True)
        res = {"set_alarm": "Alarm set for %s:%s." % (c["args"].get("hour"), c["args"].get("minute")),
               "flashlight": "Flashlight is now " + ("on" if c["args"].get("on") else "off") + ".",
               "open_app": "Opened " + str(c["args"].get("name")) + ".",
               }.get(c["tool"], "ok")
        post("device_result", {"device_id": did, "call_id": c["call_id"], "result": res})


threading.Thread(target=phone, daemon=True).start()
time.sleep(1.5)
print("hello:", post("hello", {}))
msg = sys.argv[2]
model = sys.argv[3] if len(sys.argv) > 3 else ""
j = post("chat", {"messages": [{"role": "user", "content": msg}], "model": model})
print("chat:", j)
t0 = time.time()
while time.time() - t0 < 300:
    r = post("poll", {"job": j["job"]})
    if r.get("status") in ("done", "error"):
        print("REPLY:", r.get("reply") or r.get("error"))
        break
    time.sleep(1)
print("last:", {k: r.get(k) for k in ("status","stage","detail","error")}); print("took", round(time.time()-t0))
print("calls:", [(c["tool"], c["args"]) for c in calls])
