from worker.publish_meta import count_publish_progress


class FakeStore:
    def __init__(self, states: dict[str, str]):
        self._states = states

    def get_image(self, _shoot_id: str, file_key: str):
        st = self._states.get(file_key)
        return {"state": st} if st else None


def test_count_publish_progress_photos_and_videos():
    store = FakeStore(
        {
            "p1": "GP_CREATED",
            "p2": "RENDERED",
            "v1": "PUBLISHED",
        }
    )
    rendered, pub_photos, pub_videos, total = count_publish_progress(
        store,
        "shoot",
        {"p1", "p2"},
        {"v1"},
    )
    assert rendered == 2
    assert pub_photos == 1
    assert pub_videos == 1
    assert total == 2
