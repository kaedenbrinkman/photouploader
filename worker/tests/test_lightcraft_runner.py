import json
import subprocess
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from worker.lightcraft_runner import (
    PreviewOnlyError,
    LightcraftRenderError,
    check_preview_only,
    import_preview_argv,
    parse_import_preview_response,
    read_jpeg_dimensions,
    render_jpeg,
    verify_export_dimensions,
)


def test_import_preview_argv_shape():
    raw = Path("/tmp/IMG_0001.CR3")
    cmd = import_preview_argv("/opt/lc", raw)
    assert cmd[:3] == ["/opt/lc", "run", "library.importPreview"]
    params = json.loads(cmd[3])
    assert params == {"paths": [str(raw)]}


def test_parse_import_preview_accepts_raw():
    data = {
        "ok": True,
        "result": {"candidates": [{"kind": "raw", "width": 6000, "height": 4000}]},
    }
    assert parse_import_preview_response(data) == (6000, 4000)


def test_parse_import_preview_rejects_preview_kind():
    data = {"ok": True, "result": {"candidates": [{"kind": "preview", "width": 1620, "height": 1080}]}}
    with pytest.raises(PreviewOnlyError):
        parse_import_preview_response(data)


def test_check_preview_only_rejects_preview_kind(monkeypatch):
    payload = json.dumps(
        {"ok": True, "result": {"candidates": [{"kind": "preview", "width": 100, "height": 100}]}},
    )

    def fake_run(cmd, **kwargs):
        return subprocess.CompletedProcess(cmd, 0, payload, "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    with pytest.raises(PreviewOnlyError):
        check_preview_only("/bin/true", Path("x.CR3"))


def test_check_preview_only_parses_raw(monkeypatch):
    payload = json.dumps(
        {"ok": True, "result": {"candidates": [{"kind": "raw", "width": 4000, "height": 3000}]}},
    )

    def fake_run(cmd, **kwargs):
        assert "library.importPreview" in cmd
        assert json.loads(cmd[3])["paths"] == ["x.CR3"]
        return subprocess.CompletedProcess(cmd, 0, payload, "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    assert check_preview_only("/bin/true", Path("x.CR3")) == (4000, 3000)


def test_render_jpeg_success(monkeypatch, tmp_path):
    out = tmp_path / "out.jpg"
    raw = tmp_path / "a.CR3"
    raw.write_bytes(b"raw")

    def fake_run(cmd, **kwargs):
        if "library.importPreview" in cmd:
            payload = json.dumps(
                {"ok": True, "result": {"candidates": [{"kind": "raw", "width": 2000, "height": 1500}]}},
            )
            return subprocess.CompletedProcess(cmd, 0, payload, "")
        if "app.export" in " ".join(cmd):
            # minimal JPEG 2000x1500 SOF0
            out.write_bytes(
                b"\xff\xd8\xff\xc0\x00\x11\x08\x05\xdc\x07\xd0\x03\x01\x22\x00\x02\x11\x01\x03\x11\x01"
                + b"\xff\xd9"
                + b"\x00" * 2000,
            )
            return subprocess.CompletedProcess(cmd, 0, "", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    res = render_jpeg("/opt/lc", raw, out, settings_hash="h1")
    assert res.output_path == out


def test_verify_export_dimensions_rejects_tiny_jpeg():
    with pytest.raises(PreviewOnlyError):
        verify_export_dimensions((6000, 4000), (800, 600))


def test_read_jpeg_dimensions(tmp_path):
    p = tmp_path / "t.jpg"
    p.write_bytes(
        b"\xff\xd8\xff\xc0\x00\x11\x08\x00\x64\x00\x80\x03\x01\x22\x00\x02\x11\x01\x03\x11\x01\xff\xd9",
    )
    assert read_jpeg_dimensions(p) == (128, 100)
