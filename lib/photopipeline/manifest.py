"""Shoot manifest validation and S3 key layout."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable


@dataclass
class ManifestFile:
    sha256: str
    size_bytes: int
    original_name: str
    storage_id: int = 0
    object_handle: int = 0
    capture_time_epoch_ms: int = 0
    content_type: str = "image/x-canon-cr3"
    s3_key: str | None = None

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> ManifestFile:
        return cls(
            sha256=d["sha256"].lower(),
            size_bytes=int(d["size_bytes"]),
            original_name=d.get("original_name") or d.get("name", "unknown.cr3"),
            storage_id=int(d.get("storage_id", 0)),
            object_handle=int(d.get("object_handle", 0)),
            capture_time_epoch_ms=int(d.get("capture_time_epoch_ms", 0)),
            content_type=d.get("content_type", "image/x-canon-cr3"),
            s3_key=d.get("s3_key"),
        )

    def s3_raw_key(self, shoot_id: str) -> str:
        if self.s3_key:
            _validate_s3_key(shoot_id, self.s3_key)
            return self.s3_key
        ext = ".CR3"
        if self.original_name.upper().endswith(".CR2"):
            ext = ".CR2"
        elif self.original_name.upper().endswith(".JPG"):
            ext = ".JPG"
        return f"shoots/{shoot_id}/raw/{self.sha256.lower()}{ext}"


def _validate_s3_key(shoot_id: str, key: str) -> None:
    prefix = f"shoots/{shoot_id}/raw/"
    if not key.startswith(prefix) or ".." in key:
        raise ManifestValidationError(f"invalid s3_key: {key}")


@dataclass
class ShootManifest:
    shoot_id: str
    user_id: str
    album_title: str
    settings_hash: str
    files: list[ManifestFile] = field(default_factory=list)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> ShootManifest:
        files = [ManifestFile.from_dict(f) for f in d.get("files", [])]
        return cls(
            shoot_id=d["shoot_id"],
            user_id=d["user_id"],
            album_title=d.get("album_title", d["shoot_id"]),
            settings_hash=d.get("settings_hash", "develop.auto-longEdge0-q90"),
            files=files,
        )


class ManifestValidationError(Exception):
    pass


def validate_manifest(manifest: ShootManifest) -> None:
    if not manifest.shoot_id or not manifest.user_id:
        raise ManifestValidationError("shoot_id and user_id are required")
    if not manifest.files:
        raise ManifestValidationError("manifest must contain at least one file")
    seen_sha: set[str] = set()
    seen_keys: set[str] = set()
    for f in manifest.files:
        if len(f.sha256) != 64:
            raise ManifestValidationError(f"invalid sha256: {f.sha256}")
        if f.sha256 in seen_sha:
            raise ManifestValidationError(f"duplicate sha256 in manifest: {f.sha256}")
        seen_sha.add(f.sha256)
        if f.size_bytes <= 0:
            raise ManifestValidationError(f"invalid size for {f.sha256}")
        if f.s3_key:
            _validate_s3_key(manifest.shoot_id, f.s3_key)
            if f.s3_key in seen_keys:
                raise ManifestValidationError(f"duplicate s3_key in manifest: {f.s3_key}")
            seen_keys.add(f.s3_key)


def manifest_complete_on_s3(
    manifest: ShootManifest,
    head_object: Callable[[str], int],
) -> tuple[bool, list[str]]:
    """Return (complete, missing_keys). head_object(key) -> size or raises."""
    missing: list[str] = []
    for f in manifest.files:
        key = f.s3_raw_key(manifest.shoot_id)
        try:
            size = head_object(key)
            if int(size) != f.size_bytes:
                missing.append(key)
        except Exception:
            missing.append(key)
    return (len(missing) == 0, missing)
