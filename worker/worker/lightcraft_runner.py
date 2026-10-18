"""Run LightCraft headless with preview-only detection."""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


class PreviewOnlyError(Exception):
    pass


class LightcraftRenderError(Exception):
    pass


@dataclass
class RenderResult:
    output_path: Path
    preview_only: bool
    settings_hash: str


PREVIEW_ONLY_RE = re.compile(r"previewOnly\s*=\s*Some", re.I)


def check_preview_only(lightcraft_bin: str, raw_path: Path) -> None:
    """Reject CR3 files that would silently use embedded JPEG preview."""
    proc = subprocess.run(
        [
            lightcraft_bin,
            "run",
            "--import",
            str(raw_path),
            "library.importPreview",
        ],
        capture_output=True,
        text=True,
        timeout=120,
    )
    combined = (proc.stdout or "") + (proc.stderr or "")
    if PREVIEW_ONLY_RE.search(combined):
        raise PreviewOnlyError(f"preview-only fallback detected for {raw_path.name}")
    if proc.returncode != 0 and "previewOnly=None" not in combined:
        # Some builds log preview on stdout; non-zero without previewOnly=None is failure
        if "previewOnly=None" not in combined and "previewOnly" not in combined.lower():
            raise LightcraftRenderError(combined.strip() or f"importPreview exit {proc.returncode}")


def render_jpeg(
    lightcraft_bin: str,
    raw_path: Path,
    output_path: Path,
    *,
    settings_hash: str,
    quality: int = 90,
) -> RenderResult:
    check_preview_only(lightcraft_bin, raw_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    # develop.auto, as-shot WB (no auto WB), full resolution longEdge=0
    cmd = [
        lightcraft_bin,
        "run",
        "--import",
        str(raw_path),
        "develop.auto",
        "app.export",
        f"path={output_path}",
        "longEdge=0",
        f"quality={quality}",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise LightcraftRenderError((proc.stderr or proc.stdout or "").strip())
    if not output_path.is_file() or output_path.stat().st_size < 1024:
        raise LightcraftRenderError(f"missing or tiny output: {output_path}")
    return RenderResult(output_path=output_path, preview_only=False, settings_hash=settings_hash)


def validate_jpeg(path: Path) -> None:
    data = path.read_bytes()[:3]
    if data[:2] != b"\xff\xd8":
        raise LightcraftRenderError(f"not a JPEG: {path}")
