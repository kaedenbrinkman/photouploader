"""Process-local file lock for album creation with stale-lock expiry."""
from __future__ import annotations

import os
import threading
import time
from contextlib import contextmanager
from pathlib import Path

STALE_LOCK_SECONDS = 600
_thread_depth = threading.local()


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _lock_stale(lock_path: Path) -> bool:
    try:
        raw = lock_path.read_text().strip()
        pid_s, ts_s, *_ = (raw.split(":", 2) + ["0", "0"])[:2]
        pid = int(pid_s)
        ts = float(ts_s)
    except (OSError, ValueError):
        return True
    age = time.time() - ts
    if age > STALE_LOCK_SECONDS:
        return True
    return not _pid_alive(pid)


@contextmanager
def album_creation_file_lock(work_dir: Path):
    """
    Serialize album POST across threads/processes on one worker host.
    Stale locks (dead pid or >10 min) are removed automatically.
    Reentrant in the same thread (ensure_album may nest calls).
    """
    depth = int(getattr(_thread_depth, "depth", 0) or 0)
    if depth > 0:
        _thread_depth.depth = depth + 1
        try:
            yield
        finally:
            _thread_depth.depth = depth
        return

    lock_path = work_dir / ".gphotos_album.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    while True:
        if lock_path.exists() and _lock_stale(lock_path):
            try:
                lock_path.unlink(missing_ok=True)
            except OSError:
                pass
        try:
            fd = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(f"{os.getpid()}:{time.time():.0f}\n")
            break
        except FileExistsError:
            if _lock_stale(lock_path):
                continue
            time.sleep(0.25)
    _thread_depth.depth = 1
    try:
        yield
    finally:
        _thread_depth.depth = 0
        try:
            lock_path.unlink(missing_ok=True)
        except OSError:
            pass
