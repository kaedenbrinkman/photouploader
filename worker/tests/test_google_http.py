import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import requests

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
):
    sys.modules.setdefault(_mod, MagicMock())

from gphotos.google_http import (  # noqa: E402
    MAX_CALL_ATTEMPTS,
    photos_api_call,
    refresh_credentials,
)


def test_photos_api_call_fails_fast_on_ssl_error():
    creds = MagicMock()
    creds.valid = True
    creds.token = "tok"
    session = MagicMock()
    session.request.side_effect = requests.exceptions.SSLError("TLS handshake failed")

    with pytest.raises(RuntimeError, match="SSLError"):
        photos_api_call(creds, "GET", "https://photoslibrary.googleapis.com/v1/albums", session=session)

    assert session.request.call_count == 1


def test_photos_api_call_bounded_attempts_on_generic_error():
    creds = MagicMock()
    creds.valid = True
    creds.token = "tok"
    session = MagicMock()
    session.request.side_effect = OSError("network down")

    with patch("gphotos.google_http.google_auth_request", return_value=MagicMock()):
        with pytest.raises(RuntimeError, match="giving up"):
            photos_api_call(creds, "POST", "https://photoslibrary.googleapis.com/v1/albums", session=session)

    assert session.request.call_count == MAX_CALL_ATTEMPTS


def test_refresh_credentials_uses_timeout_request():
    creds = MagicMock()
    creds.valid = False
    session = MagicMock()

    def _refresh(_req):
        creds.valid = True

    creds.refresh.side_effect = _refresh
    with patch("gphotos.google_http.google_auth_request") as gar:
        req = MagicMock()
        gar.return_value = req
        refresh_credentials(creds, session=session)
        creds.refresh.assert_called_once_with(req)
