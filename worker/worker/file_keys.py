"""DynamoDB FILE# keys (upload-ref), distinct from manifest content sha256."""

from __future__ import annotations

import re

_UPLOAD_REF = re.compile(r"/raw/([a-f0-9]{64})\.", re.IGNORECASE)


def upload_ref_from_raw_s3_key(key: str) -> str | None:
    m = _UPLOAD_REF.search(key)
    return m.group(1).lower() if m else None


def file_dynamo_key(shoot_id: str, entry: dict, raw_s3_key_fn) -> str:
    key = raw_s3_key_fn(shoot_id, entry)
    ref = upload_ref_from_raw_s3_key(key)
    if ref:
        return ref
    return str(entry["sha256"]).lower()
