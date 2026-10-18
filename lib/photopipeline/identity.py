"""Stable file identity for MTP imports (not filename alone)."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass


@dataclass(frozen=True)
class FileIdentity:
    storage_id: int
    object_handle: int
    size_bytes: int
    capture_time_epoch_ms: int
    sha256: str

    def dedupe_key(self) -> str:
        return file_identity_key(
            self.storage_id,
            self.object_handle,
            self.size_bytes,
            self.capture_time_epoch_ms,
            self.sha256,
        )


def file_identity_key(
    storage_id: int,
    object_handle: int,
    size_bytes: int,
    capture_time_epoch_ms: int,
    sha256: str,
) -> str:
    raw = f"{storage_id}:{object_handle}:{size_bytes}:{capture_time_epoch_ms}:{sha256.lower()}"
    return hashlib.sha256(raw.encode()).hexdigest()[:32]
