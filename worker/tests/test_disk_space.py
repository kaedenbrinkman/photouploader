import errno
from pathlib import Path

import pytest

from worker.disk_space import require_free_bytes


def test_require_free_bytes_raises_enospc(monkeypatch, tmp_path: Path):
    monkeypatch.setattr(
        "worker.disk_space.shutil.disk_usage",
        lambda _p: type("U", (), {"free": 1000, "total": 10_000, "used": 9000})(),
    )
    with pytest.raises(OSError) as exc:
        require_free_bytes(tmp_path, 5000, margin_bytes=0)
    assert exc.value.errno == errno.ENOSPC
