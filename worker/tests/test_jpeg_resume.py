from pathlib import Path
from unittest.mock import MagicMock

from worker.jpeg_resume import try_fetch_existing_jpeg


def test_try_fetch_existing_jpeg_local_file(tmp_path):
    out = tmp_path / "a.jpg"
    out.write_bytes(b"jpeg")
    s3 = MagicMock()
    assert try_fetch_existing_jpeg(s3, "b", "k", out) is True
    s3.head_object.assert_not_called()


def test_try_fetch_existing_jpeg_downloads_from_s3(tmp_path):
    out = tmp_path / "a.jpg"
    s3 = MagicMock()

    def fake_download(_bucket, _key, dest):
        Path(dest).write_bytes(b"from-s3")

    s3.download_file.side_effect = fake_download
    assert try_fetch_existing_jpeg(s3, "bucket", "jpeg/key", out) is True
    s3.head_object.assert_called_once()
    assert out.read_bytes() == b"from-s3"


def test_try_fetch_existing_jpeg_missing_on_s3(tmp_path):
    out = tmp_path / "a.jpg"
    s3 = MagicMock()
    s3.head_object.side_effect = Exception("404")
    assert try_fetch_existing_jpeg(s3, "bucket", "jpeg/key", out) is False
