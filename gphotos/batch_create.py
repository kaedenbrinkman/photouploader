"""Serialized Google Photos mediaItems:batchCreate with long 429 retry."""

from __future__ import annotations

import logging
import random
import threading
import time
from typing import Any, Callable

import requests
from google.oauth2.credentials import Credentials

from gphotos.adaptive_pace import parse_retry_after_seconds, photos_api_pacer
from gphotos.google_http import DEFAULT_TIMEOUT, google_auth_request

log = logging.getLogger("gphotos.batch")

API = "https://photoslibrary.googleapis.com/v1"
BATCH_SIZE = 50
DEFAULT_BATCH_MAX_WAIT_S = 600.0

_batch_create_serializer = threading.Lock()


def batch_create_serializer() -> threading.Lock:
    return _batch_create_serializer


def _batch_create_http(
    credentials: Credentials,
    body: dict[str, Any],
    *,
    session: requests.Session,
    max_wait_s: float = DEFAULT_BATCH_MAX_WAIT_S,
) -> requests.Response:
    """POST batchCreate; retry 429/5xx until max_wait_s (per batch)."""
    url = f"{API}/mediaItems:batchCreate"
    auth_req = google_auth_request(session)
    pacer = photos_api_pacer()
    started = time.monotonic()
    attempt = 0
    refreshes = 0
    headers_extra: dict[str, str] = {}

    while time.monotonic() - started < max_wait_s:
        attempt += 1
        pacer.wait_turn()
        if not credentials.valid:
            credentials.refresh(auth_req)
            refreshes += 1
        headers = {"Authorization": f"Bearer {credentials.token}", **headers_extra}
        r = session.request(
            "POST",
            url,
            headers=headers,
            json=body,
            timeout=DEFAULT_TIMEOUT,
        )
        if r.status_code == 429:
            retry_after = parse_retry_after_seconds(r.headers.get("Retry-After"))
            backoff = min(60.0, 2 ** min(attempt, 6) + random.random())
            sleep_s = max(retry_after or 0.0, backoff)
            elapsed = time.monotonic() - started
            sleep_s = min(sleep_s, max(0.0, max_wait_s - elapsed))
            log.warning(
                "batchCreate rate limited; sleep %.1fs (attempt %s, Retry-After=%s)",
                sleep_s,
                attempt,
                r.headers.get("Retry-After"),
            )
            pacer.on_rate_limited(retry_after)
            if sleep_s <= 0:
                break
            time.sleep(sleep_s)
            continue
        if r.status_code >= 500:
            sleep_s = min(30.0, 2 ** min(attempt, 5) + random.random())
            elapsed = time.monotonic() - started
            sleep_s = min(sleep_s, max(0.0, max_wait_s - elapsed))
            log.warning("batchCreate server %s; sleep %.1fs", r.status_code, sleep_s)
            if sleep_s <= 0:
                break
            time.sleep(sleep_s)
            continue
        if r.status_code == 401 and refreshes < 2:
            credentials.refresh(auth_req)
            refreshes += 1
            continue
        if r.status_code >= 400:
            r.raise_for_status()
        pacer.on_success()
        return r

    raise RuntimeError(
        f"batchCreate still rate-limited or failing after {max_wait_s:.0f}s "
        f"({attempt} attempts)"
    )


def apply_batch_create_results(
    batch_keys: list[str],
    results: list[dict[str, Any]],
    ledger: dict,
    *,
    save_ledger: Callable[[], None],
    ledger_lock: threading.Lock | None,
) -> tuple[int, list[str]]:
    """Update ledger per item; return (created_count, failed_keys)."""
    from contextlib import nullcontext

    ctx = ledger_lock if ledger_lock is not None else nullcontext()
    created = 0
    failed: list[str] = []
    with ctx:
        items = ledger.setdefault("items", {})
        for k, item_res in zip(batch_keys, results):
            it = items.get(k, {})
            st = item_res.get("status", {})
            if item_res.get("mediaItem") and st.get("code", 0) == 0:
                it.update(state="CREATED", media_item_id=item_res["mediaItem"]["id"])
                it.pop("upload_token", None)
                created += 1
            else:
                it.update(state="FAILED_CREATE", error=st)
                failed.append(k)
        save_ledger()
    return created, failed


def run_batch_create_for_keys(
    credentials: Credentials,
    ledger: dict,
    keys: list[str],
    *,
    save_ledger: Callable[[], None],
    ledger_lock: threading.Lock | None,
    session: requests.Session,
    on_batch_created: Callable[[int, int], None] | None = None,
    max_wait_s: float = DEFAULT_BATCH_MAX_WAIT_S,
) -> tuple[int, list[str]]:
    """Serialize batchCreate for up to 50 keys per HTTP call."""
    if not keys:
        return 0, []
    album_id = ledger.get("album_id")
    if not album_id:
        raise RuntimeError("batchCreate without album_id in ledger")

    total_created = 0
    all_failed: list[str] = []
    deduped = list(dict.fromkeys(k.lower() for k in keys))

    with _batch_create_serializer:
        total_created_before = sum(
            1 for v in ledger.get("items", {}).values() if v.get("state") == "CREATED"
        )
        for i in range(0, len(deduped), BATCH_SIZE):
            batch = deduped[i : i + BATCH_SIZE]
            body = {
                "albumId": album_id,
                "newMediaItems": [
                    {
                        "simpleMediaItem": {
                            "fileName": ledger["items"][k].get("filename")
                            or f"media-{k[:8]}",
                            "uploadToken": ledger["items"][k]["upload_token"],
                        }
                    }
                    for k in batch
                    if ledger.get("items", {}).get(k, {}).get("upload_token")
                ],
            }
            if not body["newMediaItems"]:
                continue
            try:
                resp = _batch_create_http(
                    credentials,
                    body,
                    session=session,
                    max_wait_s=max_wait_s,
                )
                results = resp.json().get("newMediaItemResults", [])
            except Exception as e:
                log.exception("batchCreate HTTP failed for batch of %s", len(batch))
                from contextlib import nullcontext

                ctx = ledger_lock if ledger_lock is not None else nullcontext()
                with ctx:
                    for k in batch:
                        ledger.setdefault("items", {}).setdefault(k, {})[
                            "state"
                        ] = "FAILED_CREATE"
                        ledger["items"][k]["error"] = {"message": str(e)[:200]}
                    save_ledger()
                all_failed.extend(batch)
                continue

            if len(results) != len(body["newMediaItems"]):
                log.warning(
                    "batchCreate result count mismatch %s vs %s",
                    len(results),
                    len(body["newMediaItems"]),
                )
            created, failed = apply_batch_create_results(
                batch[: len(results)],
                results,
                ledger,
                save_ledger=save_ledger,
                ledger_lock=ledger_lock,
            )
            total_created += created
            all_failed.extend(failed)
            if on_batch_created and created:
                on_batch_created(created, total_created_before + total_created)

    return total_created, all_failed


class BatchCreateQueue:
    """Collect UPLOADED ledger keys from parallel upload workers; flush serially."""

    def __init__(self) -> None:
        self._keys: list[str] = []
        self._lock = threading.Lock()

    def enqueue(self, ledger_key: str) -> None:
        k = ledger_key.lower()
        with self._lock:
            if k not in self._keys:
                self._keys.append(k)

    def pending_keys(self) -> list[str]:
        with self._lock:
            return list(self._keys)

    def flush(
        self,
        credentials: Credentials,
        ledger: dict,
        *,
        save_ledger: Callable[[], None],
        ledger_lock: threading.Lock | None,
        session: requests.Session,
        on_batch_created: Callable[[int, int], None] | None = None,
        only_states: tuple[str, ...] = ("UPLOADED",),
    ) -> tuple[int, list[str]]:
        keys = [
            k
            for k in self.pending_keys()
            if ledger.get("items", {}).get(k, {}).get("state") in only_states
            and ledger["items"][k].get("upload_token")
        ]
        return run_batch_create_for_keys(
            credentials,
            ledger,
            keys,
            save_ledger=save_ledger,
            ledger_lock=ledger_lock,
            session=session,
            on_batch_created=on_batch_created,
        )

    def retry_failed(
        self,
        credentials: Credentials,
        ledger: dict,
        *,
        save_ledger: Callable[[], None],
        ledger_lock: threading.Lock | None,
        session: requests.Session,
        on_batch_created: Callable[[int, int], None] | None = None,
    ) -> tuple[int, list[str]]:
        keys = [
            k
            for k, v in ledger.get("items", {}).items()
            if v.get("state") == "FAILED_CREATE" and v.get("upload_token")
        ]
        return run_batch_create_for_keys(
            credentials,
            ledger,
            keys,
            save_ledger=save_ledger,
            ledger_lock=ledger_lock,
            session=session,
            on_batch_created=on_batch_created,
        )
