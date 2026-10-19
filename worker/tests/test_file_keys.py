from worker.file_keys import file_dynamo_key, upload_ref_from_raw_s3_key


def test_upload_ref_from_s3_key():
    ref = "a" * 64
    assert upload_ref_from_raw_s3_key(f"shoots/s1/raw/{ref}.CR3") == ref


def test_file_dynamo_key_uses_s3_key_upload_ref():
    content = "f" * 64
    upload = "13cae47c" + "0" * 56
    entry = {
        "sha256": content,
        "s3_key": f"shoots/s1/raw/{upload}.CR3",
        "size_bytes": 1,
    }

    def raw_key(_shoot, e):
        return e["s3_key"]

    assert file_dynamo_key("s1", entry, raw_key) == upload
