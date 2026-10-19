"""Reuse rendered JPEGs from local disk or S3 without re-running Lightcraft."""

from __future__ import annotations

from pathlib import Path


def try_fetch_existing_jpeg(s3, bucket: str, jpeg_key: str, out: Path) -> bool:
    """Return True when ``out`` exists locally or was downloaded from S3."""
    if out.exists():
        return True
    try:
        s3.head_object(Bucket=bucket, Key=jpeg_key)
        out.parent.mkdir(parents=True, exist_ok=True)
        s3.download_file(bucket, jpeg_key, str(out))
        return out.exists()
    except Exception:
        return False
