"""Regression: ensure_shoot_album must not self-deadlock on ledger lock + save_ledger."""
from __future__ import annotations

import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from unittest.mock import MagicMock as _MagicMock

_GPHOTOS_ROOT = Path(__file__).resolve().parents[2]
if str(_GPHOTOS_ROOT) not in sys.path:
    sys.path.insert(0, str(_GPHOTOS_ROOT))

for _mod in (
    "google",
    "google.auth",
    "google.auth.transport",
    "google.auth.transport.requests",
    "google.oauth2",
    "google.oauth2.credentials",
    "google_auth_oauthlib",
    "google_auth_oauthlib.flow",
):
    sys.modules.setdefault(_mod, _MagicMock())

from worker.gphotos_client import ensure_shoot_album  # noqa: E402


def test_ensure_shoot_album_completes_with_non_reentrant_lock_pattern():
    """
    ensure_album calls save_ledger while holding ledger_lock; must use RLock in production.
    This test uses threading.Lock to prove the old pattern deadlocks without RLock.
    """
    ledger = {"album_id": None, "items": {}}
    saved = []

    def save_ledger():
        with ledger_lock:
            saved.append(json.dumps(ledger))

    ledger_lock = threading.Lock()
    creds = MagicMock()
    creds.valid = True
    creds.token = "access"

    def fake_call(*_a, **_k):
        resp = MagicMock()
        resp.json.return_value = {"id": "album-test-1", "productUrl": "https://example.com/a"}
        return resp

    ensure_mod = __import__("gphotos.gphotos_upload", fromlist=["ensure_album"])
    with patch("gphotos.gphotos_upload.call", side_effect=fake_call):
        ex = ThreadPoolExecutor(max_workers=1)
        try:
            fut = ex.submit(
                ensure_mod.ensure_album,
                creds,
                "Import test",
                ledger,
                save_ledger=save_ledger,
                ledger_lock=ledger_lock,
            )
            with pytest.raises(FuturesTimeout):
                fut.result(timeout=2.0)
        finally:
            ex.shutdown(wait=False, cancel_futures=True)


def test_ensure_shoot_album_e2e_mocked_under_30s(tmp_path: Path):
    ledger = {"album_id": None, "items": {}}
    ledger_lock = threading.RLock()

    def save_ledger():
        with ledger_lock:
            (tmp_path / "ledger.json").write_text(json.dumps(ledger))

    creds = MagicMock()
    creds.valid = True
    creds.token = "tok"

    def fake_call(*_a, **_k):
        resp = MagicMock()
        resp.json.return_value = {"id": "album-e2e", "productUrl": "https://photos.app.goo.gl/x"}
        return resp

    with patch("worker.gphotos_client.load_credentials_from_ssm", return_value=creds):
        with patch("gphotos.gphotos_upload.call", side_effect=fake_call):
            with ThreadPoolExecutor(max_workers=1) as pool:
                fut = pool.submit(
                    ensure_shoot_album,
                    "/ssm/token",
                    "us-west-2",
                    "Import 290f9d10",
                    ledger,
                    save_ledger,
                    ledger_lock=ledger_lock,
                )
                album_id = fut.result(timeout=30.0)

    assert album_id == "album-e2e"
    assert ledger["album_id"] == "album-e2e"
