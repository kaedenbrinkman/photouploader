import pytest

from photopipeline.identity import FileIdentity, file_identity_key
from photopipeline.manifest import ManifestValidationError, ShootManifest, validate_manifest
from photopipeline.state import ShootState, can_transition_shoot, next_shoot_state_on_finalize


def test_file_identity_key_stable():
    k1 = file_identity_key(1, 42, 10_000_000, 1_700_000_000_000, "a" * 64)
    k2 = FileIdentity(1, 42, 10_000_000, 1_700_000_000_000, "a" * 64).dedupe_key()
    assert k1 == k2
    assert k1 != file_identity_key(1, 43, 10_000_000, 1_700_000_000_000, "a" * 64)


def test_validate_manifest_rejects_duplicates():
    m = ShootManifest(
        shoot_id="s1",
        user_id="u1",
        album_title="Test",
        settings_hash="h1",
        files=[
            {"sha256": "b" * 64, "size_bytes": 100, "original_name": "a.CR3"},
            {"sha256": "b" * 64, "size_bytes": 100, "original_name": "b.CR3"},
        ],
    )
    manifest = ShootManifest.from_dict(
        {
            "shoot_id": m.shoot_id,
            "user_id": m.user_id,
            "album_title": m.album_title,
            "settings_hash": m.settings_hash,
            "files": [
                {"sha256": "b" * 64, "size_bytes": 100, "original_name": "a.CR3"},
                {"sha256": "b" * 64, "size_bytes": 100, "original_name": "b.CR3"},
            ],
        }
    )
    with pytest.raises(ManifestValidationError):
        validate_manifest(manifest)


def test_manifest_s3_key():
    from photopipeline.manifest import ManifestFile

    f = ManifestFile.from_dict(
        {"sha256": "c" * 64, "size_bytes": 1, "original_name": "IMG_0001.CR3"}
    )
    assert f.s3_raw_key("shoot-1") == f"shoots/shoot-1/raw/{'c' * 64}.CR3"

    custom = ManifestFile.from_dict(
        {
            "sha256": "d" * 64,
            "size_bytes": 1,
            "original_name": "IMG_0001.CR3",
            "s3_key": "shoots/shoot-1/raw/upload-metadata-ref.CR3",
        }
    )
    assert custom.s3_raw_key("shoot-1") == "shoots/shoot-1/raw/upload-metadata-ref.CR3"


def test_shoot_state_transitions():
    assert can_transition_shoot(ShootState.UPLOADING, ShootState.MANIFEST_SUBMITTED)
    assert not can_transition_shoot(ShootState.IMPORTING, ShootState.DONE)
    assert next_shoot_state_on_finalize(True) == ShootState.VERIFIED
    assert next_shoot_state_on_finalize(False) == ShootState.UPLOADING
