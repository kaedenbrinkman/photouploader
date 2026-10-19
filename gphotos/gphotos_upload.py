#!/usr/bin/env python3
"""Minimal Google Photos Library API uploader (NOT run against a real account in this investigation).

Flow (per https://developers.google.com/photos/library/guides/upload-media, checked 2026-10-18):
  1. OAuth 2.0 installed-app flow, scope photoslibrary.appendonly (+ readonly.appcreateddata for idempotent resume).
  2. albums.create  (app-created album; API cannot add to user-created albums)
  3. POST /v1/uploads (raw bytes) -> uploadToken (valid 1 day). Parallel OK.
  4. mediaItems.batchCreate with albumId, <=50 items/call, serial per user.
  5. 429 -> wait >=30 s, exponential backoff; 5xx -> backoff. 207 -> per-item status.
State is kept in a JSON ledger so a rerun skips files already created (idempotent resume).

Usage:
  python gphotos_upload.py --client-secret client_secret.json --token token.json \
      --album "Shoot 2026-10-18" --ledger ledger.json out/*.jpg
"""
import argparse
import hashlib
import json
import os
import random
import sys
import time

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = [
    "https://www.googleapis.com/auth/photoslibrary.appendonly",
    "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
]
API = "https://photoslibrary.googleapis.com/v1"


def creds(client_secret, token_path):
    c = (
        Credentials.from_authorized_user_file(token_path, SCOPES)
        if os.path.exists(token_path)
        else None
    )
    if c and c.expired and c.refresh_token:
        try:
            c.refresh(Request())
        except Exception as e:  # invalid_grant: revoked, or 7-day expiry in Testing mode
            sys.exit(
                f"REAUTH_REQUIRED: refresh failed ({type(e).__name__}); rerun interactively"
            )
    if not c or not c.valid:
        c = InstalledAppFlow.from_client_secrets_file(client_secret, SCOPES).run_local_server(
            port=0
        )
    fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(c.to_json())
    return c


def call(c, method, url, **kw):
    r = None
    for attempt in range(8):
        if not c.valid:
            c.refresh(Request())
        h = kw.pop("headers", {})
        h["Authorization"] = f"Bearer {c.token}"
        r = requests.request(method, url, headers=h, timeout=300, **kw)
        kw["headers"] = h
        if r.status_code == 429:
            time.sleep(max(30, 2**attempt * 5) + random.random())
            continue
        if r.status_code >= 500:
            time.sleep(2**attempt + random.random())
            continue
        if r.status_code == 401:
            c.refresh(Request())
            continue
        r.raise_for_status()
        return r
    raise RuntimeError(f"giving up on {method} {url}: {r.status_code if r else '?'}")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def upload_album(
    credentials: Credentials,
    album_title: str,
    file_paths: list[str],
    ledger: dict,
    *,
    save_ledger,
) -> dict:
    """Upload JPEGs to a Google Photos app-created album; mutates ledger in place."""
    if not ledger.get("album_id"):
        led_album = call(
            credentials,
            "POST",
            f"{API}/albums",
            json={"album": {"title": album_title[:500]}},
        ).json()
        ledger["album_id"] = led_album["id"]
        if led_album.get("productUrl"):
            ledger["album_product_url"] = led_album["productUrl"]
        save_ledger()

    pending: list[str] = []
    for p in file_paths:
        k = sha256_file(p)
        it = ledger.setdefault("items", {}).setdefault(k, {"path": p, "state": "PENDING"})
        if it["state"] == "CREATED":
            continue
        if it["state"] != "UPLOADED" or time.time() - it.get("token_at", 0) > 20 * 3600:
            with open(p, "rb") as f:
                r = call(
                    credentials,
                    "POST",
                    f"{API}/uploads",
                    data=f,
                    headers={
                        "Content-type": "application/octet-stream",
                        "X-Goog-Upload-Content-Type": "image/jpeg",
                        "X-Goog-Upload-Protocol": "raw",
                    },
                )
            it.update(state="UPLOADED", upload_token=r.text, token_at=time.time())
            save_ledger()
        pending.append(k)

    for i in range(0, len(pending), 50):
        batch = pending[i : i + 50]
        body = {
            "albumId": ledger["album_id"],
            "newMediaItems": [
                {
                    "simpleMediaItem": {
                        "fileName": os.path.basename(ledger["items"][k]["path"]),
                        "uploadToken": ledger["items"][k]["upload_token"],
                    }
                }
                for k in batch
            ],
        }
        res = call(
            credentials, "POST", f"{API}/mediaItems:batchCreate", json=body
        ).json().get("newMediaItemResults", [])
        for k, item_res in zip(batch, res):
            st = item_res.get("status", {})
            if item_res.get("mediaItem") and st.get("code", 0) == 0:
                ledger["items"][k].update(
                    state="CREATED", media_item_id=item_res["mediaItem"]["id"]
                )
                ledger["items"][k].pop("upload_token", None)
            else:
                ledger["items"][k].update(state="FAILED_CREATE", error=st)
        save_ledger()

    n = sum(1 for v in ledger.get("items", {}).values() if v["state"] == "CREATED")
    return {
        "album_id": ledger["album_id"],
        "album_product_url": ledger.get("album_product_url"),
        "created": n,
        "total": len(ledger.get("items", {})),
    }


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--client-secret", required=True)
    a.add_argument("--token", required=True)
    a.add_argument("--album", required=True)
    a.add_argument("--ledger", required=True)
    a.add_argument("files", nargs="+")
    args = a.parse_args()
    led = json.load(open(args.ledger)) if os.path.exists(args.ledger) else {"album_id": None, "items": {}}

    def save():
        json.dump(led, open(args.ledger + ".tmp", "w"), indent=1)
        os.replace(args.ledger + ".tmp", args.ledger)

    c = creds(args.client_secret, args.token)
    result = upload_album(c, args.album, args.files, led, save_ledger=save)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
