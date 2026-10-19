"""Detect stuck worker phases and dump thread stacks before failing the shoot."""
from __future__ import annotations

import faulthandler
import logging
import os
import signal
import sys
import threading
import time
import traceback
from typing import Callable

log = logging.getLogger("worker.watchdog")

DEFAULT_STALL_MINUTES = int(os.environ.get("WORKER_PHASE_STALL_MINUTES", "8"))
POLL_SECONDS = 30


def install_diagnostic_handlers() -> None:
    """Register SIGUSR1 stack dump and a delayed traceback dump on boot."""
    try:
        faulthandler.enable(all_threads=True, file=sys.stderr)
    except Exception:
        log.exception("faulthandler.enable failed")
    try:
        faulthandler.register(signal.SIGUSR1, all_threads=True, chain=False)
    except Exception:
        log.exception("SIGUSR1 faulthandler.register failed")
    later_s = int(os.environ.get("WORKER_FAULTHANDLER_LATER_SECONDS", "900"))
    if later_s > 0:
        try:
            faulthandler.dump_traceback_later(later_s, repeat=True, file=sys.stderr)
            log.info("faulthandler will dump stacks every %ss after first %ss", later_s, later_s)
        except Exception:
            log.exception("faulthandler.dump_traceback_later failed")


class PhaseStallWatchdog:
    def __init__(
        self,
        *,
        get_phase: Callable[[], str | None],
        on_stall: Callable[[str, float], None],
        stall_minutes: int = DEFAULT_STALL_MINUTES,
        poll_seconds: float = POLL_SECONDS,
    ) -> None:
        self._get_phase = get_phase
        self._on_stall = on_stall
        self._stall_seconds = max(60, stall_minutes * 60) if stall_minutes > 0 else 60
        self._poll_seconds = max(1.0, poll_seconds)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._last_phase: str | None = None
        self._phase_since = time.monotonic()

    def note_phase(self, phase: str | None) -> None:
        phase = phase or ""
        if phase != self._last_phase:
            self._last_phase = phase
            self._phase_since = time.monotonic()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="phase-watchdog", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        while not self._stop.wait(self._poll_seconds):
            phase = self._get_phase()
            self.note_phase(phase)
            idle = time.monotonic() - self._phase_since
            if idle < self._stall_seconds:
                continue
            msg = (
                f"Worker stalled in phase {self._last_phase!r} for {idle / 60:.1f} min "
                f"(limit {self._stall_seconds / 60:.0f} min)"
            )
            log.error("%s — dumping all thread stacks", msg)
            try:
                faulthandler.dump_traceback(all_threads=True, file=sys.stderr)
            except Exception:
                traceback.print_exc(file=sys.stderr)
            try:
                self._on_stall(msg, idle)
            except Exception:
                log.exception("phase stall handler failed")
            self._stop.set()
