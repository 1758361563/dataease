#!/usr/bin/env python3
"""Functional smoke helper for a running Company BI stack (used by tests/local-e2e.sh).

  smoke.py login  <base_url> <user> <password> [--save FILE]   -> prints OK + token length; exit 1 on failure
  smoke.py authed <base_url> --token-file FILE                  -> calls an authenticated API with the saved token
  smoke.py anon   <base_url>                                    -> the same API without a token must be rejected

Needs: python3 + `cryptography`. Talks only to the base_url you pass. Never prints secrets.
"""
import base64
import json
import sys
import urllib.error
import urllib.request

SEP = base64.urlsafe_b64encode(b"-pk_separator-").decode()
CTX = "/de2api"
AUTHED_PATH = CTX + "/user/personInfo"  # needs a valid token; verified against the running stack


def http(method, url, body=None, headers=None, timeout=20):
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def public_key(base):
    status, text = http("GET", base + CTX + "/dekey")
    if status != 200:
        raise SystemExit(f"/dekey -> HTTP {status}")
    try:
        text = json.loads(text)["data"]  # {"code":0,"data":"<aes(pub)>-sep-<aesKey>"}
    except (ValueError, KeyError, TypeError):
        text = text.strip().strip('"')
    cipher_b64, aes_key = text.split(SEP)
    from cryptography.hazmat.primitives import padding as sympad
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    dec = Cipher(algorithms.AES(aes_key.encode()), modes.CBC(b"0" * 16)).decryptor()
    raw = dec.update(base64.b64decode(cipher_b64)) + dec.finalize()
    un = sympad.PKCS7(128).unpadder()
    der_b64 = un.update(raw) + un.finalize()
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    return load_der_public_key(base64.b64decode(der_b64))


def rsa(pub, text):
    from cryptography.hazmat.primitives.asymmetric import padding

    return base64.b64encode(pub.encrypt(text.encode(), padding.PKCS1v15())).decode()


def login(base, user, pwd):
    pub = public_key(base)
    body = json.dumps({"name": rsa(pub, user), "pwd": rsa(pub, pwd)}).encode()
    status, text = http("POST", base + CTX + "/login/localLogin", body, {"Content-Type": "application/json"})
    if status != 200:
        return None, f"HTTP {status}"
    try:
        data = json.loads(text)
    except ValueError:
        return None, "non-JSON response"
    # DataEase wraps results as {"code":0,"data":{...}} on some endpoints; TokenVO may also come bare
    payload = data.get("data", data) if isinstance(data, dict) else {}
    token = payload.get("token") if isinstance(payload, dict) else None
    if not token:
        return None, "no token in response (code=%s)" % (data.get("code") if isinstance(data, dict) else "?")
    return token, ""


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    cmd, base = argv[1], argv[2].rstrip("/")
    if cmd == "login":
        user, pwd = argv[3], argv[4]
        token, err = login(base, user, pwd)
        if not token:
            print("LOGIN FAIL:", err)
            return 1
        if "--save" in argv:
            path = argv[argv.index("--save") + 1]
            with open(path, "w") as f:
                f.write(token)
            import os

            os.chmod(path, 0o600)
        print("LOGIN OK (token length %d)" % len(token))
        return 0
    if cmd == "authed":
        token = open(argv[argv.index("--token-file") + 1]).read().strip()
        status, text = http("GET", base + AUTHED_PATH, headers={"X-DE-TOKEN": token})
        ok = status == 200 and '"code":0' in text.replace(" ", "")
        print("AUTHED %s (HTTP %d)" % ("OK" if ok else "FAIL", status))
        return 0 if ok else 1
    if cmd == "call":
        # smoke.py call BASE --method M --path /de2api/... [--token-file F] [--body JSON]
        # prints "HTTP <status>" and then the raw response body; exit 0 unless the request itself failed
        method = argv[argv.index("--method") + 1]
        path = argv[argv.index("--path") + 1]
        hdr = {"Content-Type": "application/json"}
        if "--token-file" in argv:
            hdr["X-DE-TOKEN"] = open(argv[argv.index("--token-file") + 1]).read().strip()
        data = argv[argv.index("--body") + 1].encode() if "--body" in argv else None
        status, text = http(method, base + path, data, hdr)
        print("HTTP %d" % status)
        print(text)
        return 0
    if cmd in ("dash-create", "dash-get"):
        token = open(argv[argv.index("--token-file") + 1]).read().strip()
        hdr = {"X-DE-TOKEN": token, "Content-Type": "application/json"}
        if cmd == "dash-create":
            name = argv[argv.index("--name") + 1]
            body = {
                "name": name, "pid": 0, "nodeType": "leaf", "type": "dashboard", "level": 0,
                "canvasStyleData": json.dumps({"width": 1600, "height": 900, "scale": 100}),
                "componentData": "[]", "mobileLayout": False, "status": 1, "version": 3,
                "contentId": "e2e", "checkVersion": "e2e", "canvasViewInfo": {},
            }
            # new ids are generated by the client in the real UI; use a time-based one
            import time
            body["id"] = str(int(time.time() * 1000))
            status, text = http("POST", base + CTX + "/dataVisualization/saveCanvas", json.dumps(body).encode(), hdr)
            try:
                data = json.loads(text)
            except ValueError:
                data = {}
            if status == 200 and data.get("code") == 0:
                print("DASH CREATED id=%s" % body["id"])
                return 0
            print("DASH CREATE FAIL (HTTP %d): %s" % (status, text[:300]))
            return 1
        did = argv[argv.index("--id") + 1]
        want = argv[argv.index("--name") + 1]
        status, text = http("POST", base + CTX + "/dataVisualization/findById",
                            json.dumps({"id": did, "busiFlag": "dashboard", "source": "main", "taskId": None}).encode(), hdr)
        try:
            data = json.loads(text)
        except ValueError:
            data = {}
        got = (data.get("data") or {}).get("name") if isinstance(data, dict) else None
        ok = status == 200 and data.get("code") == 0 and got == want
        print("DASH %s (HTTP %d, name=%r)" % ("FOUND" if ok else "MISSING", status, got))
        return 0 if ok else 1
    if cmd == "anon":
        status, text = http("GET", base + AUTHED_PATH)
        rejected = not (status == 200 and '"code":0' in text.replace(" ", ""))
        print("ANON %s (HTTP %d)" % ("REJECTED" if rejected else "ACCEPTED!!", status))
        return 0 if rejected else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
