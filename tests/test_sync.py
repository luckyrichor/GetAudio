from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient

from getaudio.api import app
from getaudio.db import init_db, session_scope, next_updated_seq
from getaudio.models import Track


def test_concurrent_writes_have_unique_cursors():
    init_db()
    def write(i):
        with session_scope() as s:
            seq = next_updated_seq(s)
            s.add(Track(platform="bilibili", video_id=f"concurrent-{i}", title="test",
                        source_url="", audio_path="test", updated_seq=seq))
            return seq
    with ThreadPoolExecutor(max_workers=8) as pool:
        seqs = list(pool.map(write, range(20)))
    assert len(set(seqs)) == 20
    with TestClient(app) as client:
        cursor = min(seqs) - 1
        seen = []
        while True:
            response = client.get("/sync", params={"cursor": cursor, "limit": 1})
            assert response.status_code == 200
            page = response.json()
            seen.extend(t["updated_seq"] for t in page["items"])
            cursor = page["next_cursor"]
            if not page["has_more"]:
                break
        assert set(seqs) <= set(seen)


def test_empty_page_size_rejected():
    with TestClient(app) as client:
        for path in ("/sync", "/tracks"):
            assert client.get(path, params={"limit": 0}).status_code == 422
            assert client.get(path, params={"limit": -1}).status_code == 422
