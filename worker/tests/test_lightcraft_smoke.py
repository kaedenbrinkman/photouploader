"""Optional integration smoke against a real lightcraft-cli binary."""

from __future__ import annotations

import os
import subprocess
import urllib.request
from pathlib import Path

import pytest

from worker.lightcraft_runner import check_preview_only, import_preview_argv

DEFAULT_SAMPLE_CR3_URL = (
    "https://raw.pixls.us/RAW-samples/Canon/Canon_EOS_R5_0616.CR3"
)


@pytest.mark.integration
def test_import_preview_real_binary(tmp_path):
    bin_path = os.environ.get("LIGHTCRAFT_BIN")
    if not bin_path or not Path(bin_path).is_file():
        pytest.skip("LIGHTCRAFT_BIN not set")

    sample_url = os.environ.get("SAMPLE_CR3_URL", DEFAULT_SAMPLE_CR3_URL)
    cr3 = tmp_path / "sample.CR3"
    if not cr3.exists():
        urllib.request.urlretrieve(sample_url, cr3)

    cmd = import_preview_argv(bin_path, cr3)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    assert proc.returncode == 0, proc.stderr or proc.stdout
    wh = check_preview_only(bin_path, cr3)
    assert wh[0] >= 0 and wh[1] >= 0
