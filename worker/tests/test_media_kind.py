from worker.media_kind import (
    content_type_for_name,
    is_video_manifest_entry,
    raw_extension_for_name,
    split_manifest_files,
)


def test_video_manifest_entry():
    assert is_video_manifest_entry({"original_name": "clip.MP4"})
    assert not is_video_manifest_entry({"original_name": "img.CR3"})


def test_raw_extension_for_video():
    assert raw_extension_for_name("a.MOV") == ".MOV"
    assert content_type_for_name("a.MP4") == "video/mp4"


def test_split_manifest_files():
    photos, videos, video_bytes = split_manifest_files(
        [
            {"original_name": "a.CR3", "size_bytes": 1},
            {"original_name": "b.MP4", "size_bytes": 1000},
        ]
    )
    assert len(photos) == 1
    assert len(videos) == 1
    assert video_bytes == 1000
