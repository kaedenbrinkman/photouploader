#!/usr/bin/env python3
"""Google Photos Library API uploader with resumable uploads for large video files."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
import threading
import time
from contextlib import nullcontext
from dataclasses import dataclass
from typing import Callable, Iterator

import logging

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow

from gphotos.google_http import photos_api_call

log = logging.getLogger("gphotos.upload")

SCOPES = [
    "https://www.googleapis.com/auth/photoslibrary.appendonly",
    "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
]
API = "https://photoslibrary.googleapis.com/v1"
CHUNK_SIZE = 8 * 1024 * 1024


@dataclass
class MediaUploadItem:
    ledger_key: str
    filename: str
    content_type: str
    size_bytes: int
    local_path: str | None = None
    s3_bucket: str | None = None
    s3_key: str | None = None


def creds(client_secret, token_path):
    c = (
        Credentials.from_authorized_user_file(token_path, SCOPES)
        if os.path.exists(token_path)
        else None
    )
    if c and c.expired and c.refresh_token:
        try:
            c.refresh(Request())
        except Exception as e:
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


def call(c, method, url, session=None, **kw):
    """Photos Library API call with bounded retries and HTTP timeouts."""
    return photos_api_call(c, method, url, session=session, **kw)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def _byte_source(item: MediaUploadItem, s3_client) -> tuple[Callable[[], Iterator[bytes]], int]:
    if item.local_path:
        size = item.size_bytes or os.path.getsize(item.local_path)

        def open_iter() -> Iterator[bytes]:
            with open(item.local_path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    yield chunk

        return open_iter, size
    if item.s3_bucket and item.s3_key and s3_client is not None:
        head = s3_client.head_object(Bucket=item.s3_bucket, Key=item.s3_key)
        size = int(head["ContentLength"])

        def open_iter() -> Iterator[bytes]:
            obj = s3_client.get_object(Bucket=item.s3_bucket, Key=item.s3_key)
            body = obj["Body"]
            while True:
                chunk = body.read(CHUNK_SIZE)
                if not chunk:
                    break
                yield chunk

        return open_iter, size
    raise ValueError(f"MediaUploadItem {item.ledger_key} has no local_path or s3 location")


def _upload_bytes_resumable(
    credentials: Credentials,
    content_type: str,
    size: int,
    chunks: Iterator[bytes],
) -> str:
    start = call(
        credentials,
        "POST",
        f"{API}/uploads",
        headers={
            "Content-Type": "application/octet-stream",
            "X-Goog-Upload-Protocol": "resumable",
            "X-Goog-Upload-Command": "start",
            "X-Goog-Upload-Content-Type": content_type,
            "X-Goog-Upload-Content-Length": str(size),
        },
    )
    upload_url = start.headers.get("X-Goog-Upload-URL")
    if not upload_url:
        raise RuntimeError("missing X-Goog-Upload-URL from Google Photos upload start")
    offset = 0
    for chunk in chunks:
        end = offset + len(chunk) - 1
        call(
            credentials,
            "PUT",
            upload_url,
            data=chunk,
            headers={
                "Content-Length": str(len(chunk)),
                "Content-Range": f"bytes {offset}-{end}/{size}",
            },
        )
        offset += len(chunk)
    if offset != size:
        raise RuntimeError(f"upload size mismatch: sent {offset} expected {size}")
    finalize = call(
        credentials,
        "PUT",
        upload_url,
        headers={
            "Content-Length": "0",
            "X-Goog-Upload-Command": "upload, finalize",
        },
    )
    return finalize.text


def ensure_album(
    credentials: Credentials,
    album_title: str,
    ledger: dict,
    *,
    save_ledger: Callable[[], None],
    ledger_lock: threading.Lock | None = None,
    http_session=None,
    album_file_lock=None,
) -> str:
    """Create the app album once; safe under concurrent worker threads."""
    ctx = ledger_lock if ledger_lock is not None else nullcontext()
    file_lock_ctx = album_file_lock() if album_file_lock is not None else nullcontext()

    with file_lock_ctx:
        with ctx:
            existing = ledger.get("album_id")
        if existing:
            log.info("gphotos: using existing album_id=%s", existing)
            return existing
        log.info("gphotos: creating Google Photos album title=%r", album_title[:80])
        led_album = call(
            credentials,
            "POST",
            f"{API}/albums",
            json={"album": {"title": album_title[:500]}},
            session=http_session,
        ).json()
        with ctx:
            if ledger.get("album_id"):
                return ledger["album_id"]
            ledger["album_id"] = led_album["id"]
            if led_album.get("productUrl"):
                ledger["album_product_url"] = led_album["productUrl"]
            save_ledger()
        log.info("gphotos: album created id=%s", ledger["album_id"])
        return ledger["album_id"]


def _upload_item(
    credentials: Credentials,
    item: MediaUploadItem,
    ledger_item: dict,
    save_ledger: Callable[[], None],
    s3_client,
    ledger_lock: threading.Lock | None = None,
    http_session=None,
) -> None:
    ctx = ledger_lock if ledger_lock is not None else nullcontext()
    with ctx:
        if ledger_item.get("state") == "CREATED":
            return
        need_upload = ledger_item.get("state") != "UPLOADED" or time.time() - ledger_item.get(
            "token_at", 0
        ) > 20 * 3600
        if not need_upload and ledger_item.get("upload_token"):
            return

    if need_upload:
        chunk_iter_fn, size = _byte_source(item, s3_client)
        if size > 32 * 1024 * 1024 or (item.content_type or "").startswith("video/"):
            token = _upload_bytes_resumable(
                credentials,
                item.content_type,
                size,
                chunk_iter_fn(),
            )
        else:
            if not item.local_path:
                raise ValueError(f"MediaUploadItem {item.ledger_key} missing local_path for raw upload")
            with open(item.local_path, "rb") as f:
                r = call(
                    credentials,
                    "POST",
                    f"{API}/uploads",
                    data=f,
                    headers={
                        "Content-type": "application/octet-stream",
                        "X-Goog-Upload-Content-Type": item.content_type,
                        "X-Goog-Upload-Protocol": "raw",
                    },
                    session=http_session,
                )
            token = r.text
        with ctx:
            if ledger_item.get("state") == "CREATED":
                return
            ledger_item.update(state="UPLOADED", upload_token=token, token_at=time.time())
            save_ledger()


def upload_album(
    credentials: Credentials,
    album_title: str,
    file_paths: list[str],
    ledger: dict,
    *,
    save_ledger,
    s3_client=None,
    media_items: list[MediaUploadItem] | None = None,
    ledger_lock: threading.Lock | None = None,
    http_session=None,
    album_file_lock=None,
) -> dict:
    """Upload media to a Google Photos app-created album; mutates ledger in place."""
    ctx = ledger_lock if ledger_lock is not None else nullcontext()
    ensure_album(
        credentials,
        album_title,
        ledger,
        save_ledger=save_ledger,
        ledger_lock=ledger_lock,
        http_session=http_session,
        album_file_lock=album_file_lock,
    )

    specs: list[MediaUploadItem] = list(media_items or [])
    if not specs:
        for p in file_paths:
            specs.append(
                MediaUploadItem(
                    ledger_key=sha256_file(p),
                    filename=os.path.basename(p),
                    content_type="image/jpeg",
                    size_bytes=os.path.getsize(p),
                    local_path=p,
                )
            )

    pending: list[str] = []
    for spec in specs:
        k = spec.ledger_key.lower()
        with ctx:
            it = ledger.setdefault("items", {}).setdefault(
                k,
                {
                    "path": spec.local_path or spec.s3_key or spec.filename,
                    "filename": spec.filename,
                    "state": "PENDING",
                },
            )
            it.setdefault("filename", spec.filename)
            already_created = it.get("state") == "CREATED"
        if already_created:
            continue
        _upload_item(
            credentials,
            spec,
            it,
            save_ledger,
            s3_client,
            ledger_lock=ledger_lock,
            http_session=http_session,
        )
        with ctx:
            if ledger["items"][k].get("state") == "UPLOADED":
                pending.append(k)

    for i in range(0, len(pending), 50):
        batch = pending[i : i + 50]
        body = {
            "albumId": ledger["album_id"],
            "newMediaItems": [
                {
                    "simpleMediaItem": {
                        "fileName": ledger["items"][k].get("filename")
                        or os.path.basename(ledger["items"][k].get("path", f"media-{k[:8]}")),
                        "uploadToken": ledger["items"][k]["upload_token"],
                    }
                }
                for k in batch
            ],
        }
        res = call(
            credentials,
            "POST",
            f"{API}/mediaItems:batchCreate",
            json=body,
            session=http_session,
        ).json().get("newMediaItemResults", [])
        with ctx:
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
