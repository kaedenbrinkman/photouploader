"""Photo vs video routing (mirrors infra/lambda/media-kind.ts)."""

from __future__ import annotations

VIDEO_EXT = (".MP4", ".MOV", ".M4V", ".AVI", ".MKV", ".3GP")


def is_video_file_name(original_name: str | None) -> bool:
    u = (original_name or "").upper()
    return any(u.endswith(ext) for ext in VIDEO_EXT)


def is_video_content_type(content_type: str | None) -> bool:
    return (content_type or "").lower().startswith("video/")


def is_video_manifest_entry(entry: dict) -> bool:
    return is_video_content_type(entry.get("content_type")) or is_video_file_name(
        entry.get("original_name")
    )


def raw_extension_for_name(original_name: str | None) -> str:
    u = (original_name or "").upper()
    if u.endswith(".JPG") or u.endswith(".JPEG"):
        return ".JPG"
    if u.endswith(".CR2"):
        return ".CR2"
    if u.endswith(".MP4"):
        return ".MP4"
    if u.endswith(".MOV"):
        return ".MOV"
    if u.endswith(".M4V"):
        return ".M4V"
    if u.endswith(".HEIC"):
        return ".HEIC"
    return ".CR3"


def content_type_for_name(original_name: str | None) -> str:
    u = (original_name or "").upper()
    if u.endswith(".MP4") or u.endswith(".M4V"):
        return "video/mp4"
    if u.endswith(".MOV"):
        return "video/quicktime"
    if u.endswith(".JPG") or u.endswith(".JPEG"):
        return "image/jpeg"
    if u.endswith(".HEIC"):
        return "image/heic"
    return "image/x-canon-cr3"


def split_manifest_files(files: list[dict]) -> tuple[list[dict], list[dict], int]:
    photos: list[dict] = []
    videos: list[dict] = []
    video_bytes = 0
    for f in files:
        if is_video_manifest_entry(f):
            videos.append(f)
            video_bytes += int(f.get("size_bytes") or 0)
        else:
            photos.append(f)
    return photos, videos, video_bytes
