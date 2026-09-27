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


def test_delete_propagates_tombstone():
    from getaudio.cli import main
    init_db()
    with session_scope() as s:
        track = Track(platform="bilibili", video_id="delete-test", title="test",
                      source_url="", audio_path="test", updated_seq=next_updated_seq(s))
        s.add(track)
        s.flush()
        track_id, cursor = track.id, track.updated_seq
    assert main(["delete", str(track_id)]) == 0
    with TestClient(app) as client:
        page = client.get("/sync", params={"cursor": cursor}).json()
        assert any(t["id"] == track_id and t["deleted"] for t in page["items"])
        assert client.get(f"/audio/{track_id}").status_code == 404
    assert main(["delete", str(track_id)]) == 2


def test_download_headers_and_classification_contract():
    from getaudio.config import settings
    init_db()
    (settings.audio_dir / "download-test.mp3").write_bytes(b"test-audio")
    with session_scope() as s:
        track = Track(platform="bilibili", video_id="download-test", title="中文", source_url="",
                      audio_path="download-test.mp3", audio_format="mp3", audio_sha256="test-hash",
                      categories_json='["治愈"]', has_vocal=0, classify_stage="rules",
                      updated_seq=next_updated_seq(s))
        s.add(track)
        s.flush()
        track_id = track.id
    with TestClient(app) as client:
        response = client.get(f"/audio/{track_id}")
        assert response.headers["content-type"] == "audio/mpeg"
        assert response.content == b"test-audio"
        row = next(t for t in client.get("/tracks").json() if t["id"] == track_id)
        assert row["categories"] == ["治愈"]
        assert row["has_vocal"] is False
        assert row["audio_sha256"] == "test-hash"
