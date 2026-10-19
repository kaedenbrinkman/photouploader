"""Adaptive pacing for Google Photos API (AIMD + Retry-After)."""
from __future__ import annotations

import threading
import time


class AdaptivePacer:
    """Token-bucket style spacing between mutating API calls."""

    def __init__(
        self,
        *,
        min_interval_s: float = 0.25,
        max_interval_s: float = 60.0,
        start_interval_s: float = 0.5,
    ) -> None:
        self._min = min_interval_s
        self._max = max_interval_s
        self._interval = start_interval_s
        self._lock = threading.Lock()
        self._next_allowed = 0.0

    @property
    def interval_s(self) -> float:
        with self._lock:
            return self._interval

    def wait_turn(self) -> None:
        with self._lock:
            now = time.monotonic()
            if now < self._next_allowed:
                time.sleep(self._next_allowed - now)
            self._next_allowed = time.monotonic() + self._interval

    def on_success(self) -> None:
        with self._lock:
            self._interval = max(self._min, self._interval * 0.85 - 0.05)

    def on_rate_limited(self, retry_after_s: float | None = None) -> float:
        with self._lock:
            if retry_after_s is not None and retry_after_s > 0:
                sleep_s = min(self._max, max(retry_after_s, self._interval))
            else:
                self._interval = min(self._max, max(self._min, self._interval * 2.0))
                sleep_s = self._interval
            self._next_allowed = time.monotonic() + sleep_s
            return sleep_s


_photos_pacer = AdaptivePacer()


def photos_api_pacer() -> AdaptivePacer:
    return _photos_pacer


def parse_retry_after_seconds(value: str | None) -> float | None:
    if not value:
        return None
    value = value.strip()
    if not value:
        return None
    try:
        return float(value)
    except ValueError:
        return None
