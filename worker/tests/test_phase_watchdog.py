import threading
import time

from worker.phase_watchdog import PhaseStallWatchdog


def test_phase_watchdog_fires_on_stall():
    phase = {"v": "GP_ENSURE_ALBUM"}
    fired: list[str] = []

    def on_stall(msg: str, _idle: float) -> None:
        fired.append(msg)

    w = PhaseStallWatchdog(
        get_phase=lambda: phase["v"],
        on_stall=on_stall,
        stall_minutes=1,
        poll_seconds=0.5,
    )
    w._stall_seconds = 1
    w.note_phase("GP_ENSURE_ALBUM")
    w.start()
    time.sleep(2.5)
    w.stop()
    assert fired, "expected stall callback"
