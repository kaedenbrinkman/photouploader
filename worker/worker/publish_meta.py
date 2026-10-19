"""Dynamo META counters during Google Photos publish."""

from __future__ import annotations

from worker.dynamo_store import DynamoStore

_PHOTO_PUBLISHED = frozenset({"GP_CREATED", "PUBLISHED"})
_VIDEO_PUBLISHED = frozenset({"PUBLISHED", "GP_CREATED"})


def count_publish_progress(
    store: DynamoStore,
    shoot_id: str,
    photo_keys: set[str],
    video_keys: set[str],
) -> tuple[int, int, int, int]:
    rendered_photos = 0
    published_photos = 0
    published_videos = 0
    for file_key in photo_keys:
        st = (store.get_image(shoot_id, file_key) or {}).get("state")
        if st in ("RENDERED", "GP_CREATED", "PUBLISHED"):
            rendered_photos += 1
        if st in _PHOTO_PUBLISHED:
            published_photos += 1
    for file_key in video_keys:
        st = (store.get_image(shoot_id, file_key) or {}).get("state")
        if st in _VIDEO_PUBLISHED:
            published_videos += 1
    published_total = published_photos + published_videos
    return rendered_photos, published_photos, published_videos, published_total
