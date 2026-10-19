"""Google Photos upload using refresh token from SSM SecureString."""

from __future__ import annotations

import json
import logging
import sys
import threading
from pathlib import Path
from typing import Callable

import boto3
from botocore.config import Config
from google.oauth2.credentials import Credentials

from gphotos.google_http import build_requests_session, refresh_credentials

log = logging.getLogger("worker.gphotos")


def _gphotos_sys_path_root() -> Path:
    here = Path(__file__).resolve()
    for parent in (here.parents[1], here.parents[2]):
        if (parent / "gphotos").is_dir():
            return parent
    return here.parents[1]


_GPHOTOS_ROOT = _gphotos_sys_path_root()
if str(_GPHOTOS_ROOT) not in sys.path:
    sys.path.insert(0, str(_GPHOTOS_ROOT))

from gphotos.gphotos_upload import MediaUploadItem, SCOPES, ensure_album, upload_album  # noqa: E402

_session_lock = threading.Lock()
_http_session = None


def get_gphotos_http_session():
    """One requests session per worker process (urllib3 pool + retry limits)."""
    global _http_session
    with _session_lock:
        if _http_session is None:
            _http_session = build_requests_session()
        return _http_session


def load_credentials_from_ssm(
    param_name: str,
    region: str,
    *,
    on_progress: Callable[[str], None] | None = None,
) -> Credentials:
    def progress(phase: str) -> None:
        log.info("gphotos: %s", phase)
        if on_progress:
            on_progress(phase)

    progress("GP_LOADING_TOKEN")
    ssm = boto3.client(
        "ssm",
        region_name=region,
        config=Config(connect_timeout=10, read_timeout=30, retries={"max_attempts": 3}),
    )
    raw = ssm.get_parameter(Name=param_name, WithDecryption=True)["Parameter"]["Value"]
    data = json.loads(raw)
    creds = Credentials.from_authorized_user_info(data, SCOPES)
    session = get_gphotos_http_session()
    if creds.expired and creds.refresh_token:
        progress("GP_REFRESH_TOKEN")
        refresh_credentials(creds, session=session)
        progress("GP_SAVING_TOKEN")
        ssm.put_parameter(Name=param_name, Value=creds.to_json(), Type="SecureString", Overwrite=True)
    elif not creds.valid:
        raise RuntimeError("Google Photos OAuth token missing or invalid; re-link in app settings")
    progress("GP_TOKEN_READY")
    return creds


def upload_shoot_jpegs(
    token_param: str,
    region: str,
    album_title: str,
    jpeg_paths: list[Path],
    ledger: dict,
    save_ledger,
) -> dict:
    creds = load_credentials_from_ssm(token_param, region)
    return upload_album(
        creds,
        album_title,
        [str(p) for p in jpeg_paths],
        ledger,
        save_ledger=save_ledger,
        http_session=get_gphotos_http_session(),
    )


def upload_shoot_media(
    token_param: str,
    region: str,
    album_title: str,
    media_items: list[MediaUploadItem],
    jpeg_paths: list[Path],
    ledger: dict,
    save_ledger,
    *,
    s3_client=None,
    ledger_lock: threading.Lock | None = None,
    album_file_lock=None,
    on_progress: Callable[[str], None] | None = None,
    on_album_id: Callable[[str, str | None], None] | None = None,
    on_batch_created: Callable[[int, int], None] | None = None,
    on_publish_phase: Callable[[], None] | None = None,
) -> dict:
    creds = load_credentials_from_ssm(token_param, region, on_progress=on_progress)
    return upload_album(
        creds,
        album_title,
        [str(p) for p in jpeg_paths],
        ledger,
        save_ledger=save_ledger,
        s3_client=s3_client,
        media_items=media_items,
        ledger_lock=ledger_lock,
        http_session=get_gphotos_http_session(),
        album_file_lock=album_file_lock,
        on_album_id=on_album_id,
        on_batch_created=on_batch_created,
        on_publish_phase=on_publish_phase,
    )


def ensure_shoot_album(
    token_param: str,
    region: str,
    album_title: str,
    ledger: dict,
    save_ledger,
    *,
    ledger_lock: threading.Lock | None = None,
    album_file_lock=None,
    on_progress: Callable[[str], None] | None = None,
    on_album_id: Callable[[str, str | None], None] | None = None,
) -> str:
    creds = load_credentials_from_ssm(token_param, region, on_progress=on_progress)
    if on_progress:
        on_progress("GP_ENSURE_ALBUM")
    log.info("gphotos: ensure album (ledger album_id=%s)", ledger.get("album_id"))
    return ensure_album(
        creds,
        album_title,
        ledger,
        save_ledger=save_ledger,
        ledger_lock=ledger_lock,
        http_session=get_gphotos_http_session(),
        album_file_lock=album_file_lock,
        on_album_id=on_album_id,
    )
