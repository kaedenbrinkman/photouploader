import subprocess
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from worker.lightcraft_runner import (
    PreviewOnlyError,
    check_preview_only,
    render_jpeg,
)


def test_check_preview_only_rejects(monkeypatch):
    def fake_run(*args, **kwargs):
        return subprocess.CompletedProcess(
            args=args[0],
            returncode=0,
            stdout="previewOnly=Some(embedded)",
            stderr="",
        )

    monkeypatch.setattr(subprocess, "run", fake_run)
    with pytest.raises(PreviewOnlyError):
        check_preview_only("/bin/true", Path("x.CR3"))


def test_render_jpeg_success(monkeypatch, tmp_path):
    out = tmp_path / "out.jpg"

    def fake_run(cmd, **kwargs):
        if "importPreview" in cmd:
            return subprocess.CompletedProcess(cmd, 0, "previewOnly=None", "")
        if "app.export" in " ".join(cmd):
            out.write_bytes(b"\xff\xd8\xff" + b"\x00" * 2000)
            return subprocess.CompletedProcess(cmd, 0, "", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    res = render_jpeg("/opt/lc", tmp_path / "a.CR3", out, settings_hash="h1")
    assert res.output_path == out
