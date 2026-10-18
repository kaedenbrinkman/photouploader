"""Lightweight moto-backed checks for manifest verification logic."""

import boto3
import pytest
from moto import mock_aws

from photopipeline.manifest import ManifestFile, ShootManifest, manifest_complete_on_s3


@pytest.fixture
def s3_bucket():
    with mock_aws():
        s3 = boto3.client("s3", region_name="us-west-2")
        s3.create_bucket(Bucket="test-bucket", CreateBucketConfiguration={"LocationConstraint": "us-west-2"})
        yield s3, "test-bucket"


def test_manifest_complete_on_s3(s3_bucket):
    s3, bucket = s3_bucket
    shoot_id = "shoot-1"
    sha = "a" * 64
    key = f"shoots/{shoot_id}/raw/{sha}.CR3"
    s3.put_object(Bucket=bucket, Key=key, Body=b"x" * 100)
    manifest = ShootManifest(
        shoot_id=shoot_id,
        user_id="u1",
        album_title="t",
        settings_hash="h",
        files=[ManifestFile.from_dict({"sha256": sha, "size_bytes": 100, "original_name": "a.CR3"})],
    )

    def head(k: str) -> int:
        return s3.head_object(Bucket=bucket, Key=k)["ContentLength"]

    ok, missing = manifest_complete_on_s3(manifest, head)
    assert ok
    assert missing == []
