"""Guard downloads against filling the root volume."""

from __future__ import annotations

import errno
import shutil
from pathlib import Path

DEFAULT_MARGIN_BYTES = 512 * 1024 * 1024


def free_bytes(path: Path) -> int:
    return int(shutil.disk_usage(path).free)


def require_free_bytes(
    path: Path,
    needed: int,
    *,
    margin_bytes: int = DEFAULT_MARGIN_BYTES,
) -> None:
    """Raise OSError(errno.ENOSPC) when [path] does not have room for [needed] + margin."""
    need = max(0, int(needed)) + max(0, int(margin_bytes))
    free = free_bytes(path)
    if free < need:
        raise OSError(
            errno.ENOSPC,
            f"need {need} bytes free on {path}, have {free}",
        )
