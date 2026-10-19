import json
from pathlib import Path
from unittest.mock import MagicMock

from worker.ledger_s3 import ledger_s3_key, load_ledger


def test_load_ledger_prefers_meta_album_id(tmp_path: Path):
    local = tmp_path / "gphotos_ledger.json"
    local.write_text(json.dumps({"album_id": "ledger-album", "items": {}}))
    s3 = MagicMock()
    s3.get_object.side_effect = Exception("missing")
    led = load_ledger(
        s3_client=s3,
        bucket="b",
        shoot_id="shoot-1",
        local_path=local,
        meta_album_id="meta-album",
    )
    assert led["album_id"] == "meta-album"
    assert ledger_s3_key("shoot-1") == "shoots/shoot-1/gphotos_ledger.json"
