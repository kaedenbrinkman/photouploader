"""Persist Google Photos upload ledger to S3 for retry/resume."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

log = logging.getLogger("worker.ledger")


def ledger_s3_key(shoot_id: str) -> str:
    return f"shoots/{shoot_id}/gphotos_ledger.json"


def load_ledger(
    *,
    s3_client,
    bucket: str,
    shoot_id: str,
    local_path: Path,
    meta_album_id: str | None = None,
    meta_album_url: str | None = None,
) -> dict[str, Any]:
    ledger: dict[str, Any] = {"album_id": None, "items": {}}
    if local_path.exists():
        try:
            ledger = json.loads(local_path.read_text())
        except Exception:
            log.warning("ignoring corrupt local ledger at %s", local_path)
    key = ledger_s3_key(shoot_id)
    try:
        obj = s3_client.get_object(Bucket=bucket, Key=key)
        remote = json.loads(obj["Body"].read().decode())
        if isinstance(remote, dict):
            if remote.get("items"):
                ledger.setdefault("items", {}).update(remote.get("items") or {})
            if remote.get("album_id") and not ledger.get("album_id"):
                ledger["album_id"] = remote["album_id"]
            if remote.get("album_product_url") and not ledger.get("album_product_url"):
                ledger["album_product_url"] = remote["album_product_url"]
    except Exception:
        log.info("no S3 ledger at s3://%s/%s (first publish or legacy shoot)", bucket, key)
    if meta_album_id:
        ledger["album_id"] = meta_album_id
    if meta_album_url:
        ledger["album_product_url"] = meta_album_url
    return ledger


def save_ledger_to_s3(*, s3_client, bucket: str, shoot_id: str, local_path: Path) -> None:
    if not local_path.exists():
        return
    body = local_path.read_bytes()
    s3_client.put_object(
        Bucket=bucket,
        Key=ledger_s3_key(shoot_id),
        Body=body,
        ContentType="application/json",
    )


def merge_ledger_from_file_rows(
    ledger: dict[str, Any],
    *,
    entries: list[dict],
    file_key_for_entry,
    store,
    shoot_id: str,
) -> None:
    """Treat Dynamo FILE# GP_CREATED rows as already published in the ledger."""
    items = ledger.setdefault("items", {})
    for entry in entries:
        file_key = file_key_for_entry(entry)
        content_sha = entry["sha256"].lower()
        row = store.get_image(shoot_id, file_key) or {}
        st = row.get("state")
        if st not in ("GP_CREATED", "PUBLISHED"):
            continue
        item = items.setdefault(
            content_sha,
            {"filename": entry.get("original_name") or f"{content_sha[:8]}.jpg", "state": "PENDING"},
        )
        item["state"] = "CREATED" if st == "GP_CREATED" else "CREATED"
        if row.get("mediaItemId"):
            item["media_item_id"] = row["mediaItemId"]
        media = row.get("mediaType") or entry.get("media_type")
        if media:
            item["media_type"] = media
