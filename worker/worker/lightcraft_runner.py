"""Run LightCraft headless with preview-only detection."""

from __future__ import annotations

import json
import struct
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class PreviewOnlyError(Exception):
    pass


class LightcraftRenderError(Exception):
    pass


@dataclass
class RenderResult:
    output_path: Path
    preview_only: bool
    settings_hash: str


def import_preview_argv(lightcraft_bin: str, raw_path: Path) -> list[str]:
    """Argv for `library.importPreview` — params must be JSON with paths."""
    params = json.dumps({"paths": [str(raw_path)]}, separators=(",", ":"))
    return [
        lightcraft_bin,
        "run",
        "library.importPreview",
        params,
    ]


def _parse_cli_json(stdout: str) -> dict[str, Any]:
    text = (stdout or "").strip()
    if not text:
        raise LightcraftRenderError("importPreview produced empty output")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise LightcraftRenderError(f"importPreview JSON parse failed: {e}: {text[:500]}") from e
    if not isinstance(data, dict):
        raise LightcraftRenderError(f"importPreview expected object, got {type(data).__name__}")
    return data


def _candidate_dimensions(candidate: dict[str, Any]) -> tuple[int, int] | None:
    for w_key, h_key in (("width", "height"), ("rawWidth", "rawHeight")):
        w, h = candidate.get(w_key), candidate.get(h_key)
        if isinstance(w, (int, float)) and isinstance(h, (int, float)) and w > 0 and h > 0:
            return int(w), int(h)
    return None


def parse_import_preview_response(data: dict[str, Any]) -> tuple[int, int]:
    """Return RAW pixel dimensions; reject preview-only candidates."""
    if not data.get("ok", True) and data.get("ok") is not False:
        # Some builds omit ok when successful
        pass
    if data.get("ok") is False:
        err = data.get("error") or data.get("message") or "importPreview failed"
        raise LightcraftRenderError(str(err))

    result = data.get("result") if isinstance(data.get("result"), dict) else data
    candidates = result.get("candidates") if isinstance(result, dict) else None
    if not isinstance(candidates, list) or not candidates:
        raise LightcraftRenderError("importPreview: no candidates")

    first = candidates[0]
    if not isinstance(first, dict):
        raise LightcraftRenderError("importPreview: invalid candidate")

    kind = str(first.get("kind", "")).lower()
    if kind and kind != "raw":
        raise PreviewOnlyError(f"importPreview candidate kind={kind!r} (expected raw)")

    dims = _candidate_dimensions(first)
    if dims is None:
        # Still accept raw kind without dimensions; dimension check skipped later.
        return 0, 0
    return dims


def read_jpeg_dimensions(path: Path) -> tuple[int, int]:
    """Read width/height from JPEG SOF marker without external deps."""
    data = path.read_bytes()
    if len(data) < 4 or data[0:2] != b"\xff\xd8":
        raise LightcraftRenderError(f"not a JPEG: {path}")
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        i += 2
        if marker in (0xD8, 0xD9):
            continue
        if i + 2 > len(data):
            break
        seg_len = struct.unpack(">H", data[i : i + 2])[0]
        if seg_len < 2:
            break
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if i + 7 > len(data):
                break
            height = struct.unpack(">H", data[i + 3 : i + 5])[0]
            width = struct.unpack(">H", data[i + 5 : i + 7])[0]
            if width > 0 and height > 0:
                return width, height
        i += seg_len
    raise LightcraftRenderError(f"could not read JPEG dimensions: {path}")


def verify_export_dimensions(raw_wh: tuple[int, int], jpeg_wh: tuple[int, int]) -> None:
    """Reject exports that look like embedded previews (much smaller than RAW)."""
    rw, rh = raw_wh
    jw, jh = jpeg_wh
    if rw <= 0 or rh <= 0:
        return
    raw_long = max(rw, rh)
    jpeg_long = max(jw, jh)
    if jpeg_long < int(raw_long * 0.45):
        raise PreviewOnlyError(
            f"export {jw}x{jh} looks like preview for RAW {rw}x{rh}",
        )


def check_preview_only(lightcraft_bin: str, raw_path: Path) -> tuple[int, int]:
    """Confirm LightCraft will decode RAW (not embedded preview). Returns RAW dimensions."""
    cmd = import_preview_argv(lightcraft_bin, raw_path)
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=120,
    )
    combined = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        raise LightcraftRenderError(combined.strip() or f"importPreview exit {proc.returncode}")
    data = _parse_cli_json(proc.stdout or "")
    return parse_import_preview_response(data)


def render_jpeg(
    lightcraft_bin: str,
    raw_path: Path,
    output_path: Path,
    *,
    settings_hash: str,
    quality: int = 90,
) -> RenderResult:
    raw_wh = check_preview_only(lightcraft_bin, raw_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
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
    jpeg_wh = read_jpeg_dimensions(output_path)
    verify_export_dimensions(raw_wh, jpeg_wh)
    return RenderResult(output_path=output_path, preview_only=False, settings_hash=settings_hash)


def validate_jpeg(path: Path) -> None:
    data = path.read_bytes()[:3]
    if data[:2] != b"\xff\xd8":
        raise LightcraftRenderError(f"not a JPEG: {path}")
