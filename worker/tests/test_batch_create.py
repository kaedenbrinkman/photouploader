import sys
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from gphotos.batch_create import run_batch_create_for_keys  # noqa: E402


def test_batch_create_retries_429_then_succeeds():
    ledger = {
        "album_id": "album-1",
        "items": {
            "aa": {"filename": "a.jpg", "state": "UPLOADED", "upload_token": "tok"},
        },
    }
    saved = []
    rate = MagicMock(status_code=429, headers={"Retry-After": "0"})
    ok = MagicMock(status_code=200, headers={})
    ok.json.return_value = {
        "newMediaItemResults": [{"status": {"code": 0}, "mediaItem": {"id": "m1"}}],
    }
    session = MagicMock()
    session.request.side_effect = [rate, ok]

    with patch("gphotos.batch_create.time.sleep"):
        created, failed = run_batch_create_for_keys(
            MagicMock(valid=True, token="t"),
            ledger,
            ["aa"],
            save_ledger=lambda: saved.append(1),
            ledger_lock=None,
            session=session,
            max_wait_s=60,
        )
    assert created == 1
    assert failed == []
    assert ledger["items"]["aa"]["state"] == "CREATED"


def test_human_last_error_truncates():
    from worker.failure_report import human_last_error

    msg = human_last_error("x" * 400)
    assert len(msg) <= 280
