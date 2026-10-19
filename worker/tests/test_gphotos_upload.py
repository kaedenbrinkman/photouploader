import hashlib
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import MagicMock, patch

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
    sys.modules.setdefault(_mod, MagicMock())

from gphotos.gphotos_upload import (  # noqa: E402
    MediaUploadItem,
    ensure_album,
    upload_album,
)


def _mock_call_album(credentials, method, url, **kw):
    resp = MagicMock()
    if method == "POST" and url.endswith("/albums"):
        resp.json.return_value = {"id": "album-1", "productUrl": "https://photos.app.goo.gl/x"}
        return resp
    if method == "POST" and url.endswith("/uploads"):
        resp.text = "upload-token-1"
        return resp
    if method == "POST" and url.endswith("/mediaItems:batchCreate"):
        resp.json.return_value = {
            "newMediaItemResults": [
                {"status": {"code": 0}, "mediaItem": {"id": "media-1"}},
            ]
        }
        return resp
    raise AssertionError(f"unexpected call {method} {url}")


def test_upload_album_ledger_key_is_manifest_sha_not_jpeg_hash(tmp_path):
    jpeg = tmp_path / "photo.jpg"
    jpeg.write_bytes(b"jpeg-bytes")
    jpeg_sha = hashlib.sha256(b"jpeg-bytes").hexdigest()
    manifest_sha = "a" * 64

    ledger = {"album_id": "album-1", "items": {}}
    saved: list[dict] = []

    def save_ledger():
        saved.append(json.loads(json.dumps(ledger)))

    def _mock_batch_http(credentials, body, *, session, max_wait_s=600):
        resp = MagicMock()
        resp.status_code = 200
        resp.json.return_value = {
            "newMediaItemResults": [
                {"status": {"code": 0}, "mediaItem": {"id": "media-1"}},
            ]
        }
        return resp

    with patch("gphotos.gphotos_upload.call", side_effect=_mock_call_album), patch(
        "gphotos.batch_create._batch_create_http",
        side_effect=_mock_batch_http,
    ):
        upload_album(
            MagicMock(),
            "title",
            [],
            ledger,
            save_ledger=save_ledger,
            media_items=[
                MediaUploadItem(
                    ledger_key=manifest_sha,
                    filename="photo.jpg",
                    content_type="image/jpeg",
                    size_bytes=jpeg.stat().st_size,
                    local_path=str(jpeg),
                )
            ],
        )

    assert manifest_sha in ledger["items"]
    assert jpeg_sha not in ledger["items"]
    assert ledger["items"][manifest_sha]["state"] == "CREATED"
    assert ledger["items"][manifest_sha]["media_item_id"] == "media-1"


def test_ensure_album_once_under_concurrency():
    ledger = {"album_id": None, "items": {}}
    lock = threading.Lock()
    album_posts: list[str] = []

    def save_ledger():
        pass

    def call(credentials, method, url, **kw):
        if method == "POST" and url.endswith("/albums"):
            album_posts.append(url)
            resp = MagicMock()
            resp.json.return_value = {"id": "album-shared"}
            return resp
        raise AssertionError(f"unexpected {method} {url}")

    with patch("gphotos.gphotos_upload.call", side_effect=call):

        def worker():
            ensure_album(
                MagicMock(),
                "Shoot title",
                ledger,
                save_ledger=save_ledger,
                ledger_lock=lock,
            )

        with ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(lambda _: worker(), range(12)))

    assert len(album_posts) == 1
    assert ledger["album_id"] == "album-shared"


def test_upload_album_skips_reupload_when_ledger_created(tmp_path):
    manifest_sha = "b" * 64
    jpeg = tmp_path / "photo.jpg"
    jpeg.write_bytes(b"x")
    ledger = {
        "album_id": "album-1",
        "items": {
            manifest_sha: {
                "state": "CREATED",
                "media_item_id": "existing-media",
                "filename": "photo.jpg",
            }
        },
    }
    calls: list[tuple[str, str]] = []

    def save_ledger():
        pass

    def call(credentials, method, url, **kw):
        calls.append((method, url))
        return MagicMock()

    with patch("gphotos.gphotos_upload.call", side_effect=call):
        upload_album(
            MagicMock(),
            "title",
            [],
            ledger,
            save_ledger=save_ledger,
            media_items=[
                MediaUploadItem(
                    ledger_key=manifest_sha,
                    filename="photo.jpg",
                    content_type="image/jpeg",
                    size_bytes=1,
                    local_path=str(jpeg),
                )
            ],
        )

    assert calls == []
