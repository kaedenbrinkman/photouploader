"""Google Photos upload using refresh token from SSM SecureString."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import boto3
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gphotos.gphotos_upload import SCOPES, upload_album  # noqa: E402


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
