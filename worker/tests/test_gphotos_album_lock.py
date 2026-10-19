import os
import time
from pathlib import Path

from worker.gphotos_album_lock import STALE_LOCK_SECONDS, album_creation_file_lock


def test_stale_album_lock_expires(tmp_path: Path):
    lock = tmp_path / ".gphotos_album.lock"
    lock.write_text(f"999999:{time.time() - STALE_LOCK_SECONDS - 5}\n")
    with album_creation_file_lock(tmp_path):
        assert lock.exists()
    assert not lock.exists()


def test_album_lock_released_after_block(tmp_path: Path):
    with album_creation_file_lock(tmp_path):
        pid = os.getpid()
        raw = (tmp_path / ".gphotos_album.lock").read_text()
        assert raw.startswith(f"{pid}:")
    assert not (tmp_path / ".gphotos_album.lock").exists()
