"""Google Photos upload using refresh token from SSM SecureString."""

from __future__ import annotations

import json
import sys
import threading
from pathlib import Path

import boto3
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials


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


def load_credentials_from_ssm(param_name: str, region: str) -> Credentials:
    ssm = boto3.client("ssm", region_name=region)
    raw = ssm.get_parameter(Name=param_name, WithDecryption=True)["Parameter"]["Value"]
    data = json.loads(raw)
    creds = Credentials.from_authorized_user_info(data, SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
        ssm.put_parameter(Name=param_name, Value=creds.to_json(), Type="SecureString", Overwrite=True)
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
) -> dict:
    creds = load_credentials_from_ssm(token_param, region)
    return upload_album(
        creds,
        album_title,
        [str(p) for p in jpeg_paths],
        ledger,
        save_ledger=save_ledger,
        s3_client=s3_client,
        media_items=media_items,
        ledger_lock=ledger_lock,
    )


def ensure_shoot_album(
    token_param: str,
    region: str,
    album_title: str,
    ledger: dict,
    save_ledger,
    *,
    ledger_lock: threading.Lock | None = None,
) -> str:
    creds = load_credentials_from_ssm(token_param, region)
    return ensure_album(
        creds,
        album_title,
        ledger,
        save_ledger=save_ledger,
        ledger_lock=ledger_lock,
    )
