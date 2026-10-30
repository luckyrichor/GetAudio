"""v2 库管理 / 维护 / 解析接口的账号隔离。

这些接口原来只存在于 CLI 或内部函数里；补成 HTTP 后最容易出的问题是
「A 账号能改到 B 账号的数据」，所以每个用例都同时建两个账号。
"""
from __future__ import annotations

import hashlib
import json
from uuid import uuid4

from fastapi.testclient import TestClient

from getaudio.accounts import _windows
from getaudio.api import app
from getaudio.config import settings
from getaudio.db import next_updated_seq, session_scope
from getaudio.models import AudioFingerprint, ExtractJob, JobStatus, Track


def register(client):
    _windows.clear()
    phone = "139" + str(int(uuid4().hex[:10], 16))[-8:].zfill(8)
    response = client.post("/account/register", json={"phone": phone, "password": "test-password-123"})
    assert response.status_code == 200, response.text
    data = response.json()
    return data["user_id"], {"X-GetAudio-Session": data["session_token"]}


def make_track(owner: str, *, title: str = "曲目", audio_bytes: int | None = None) -> tuple[int, str]:
    video_id = "vid-" + uuid4().hex
    name = video_id + ".m4a"
    payload = b"audio-bytes-" + video_id.encode()
    (settings.audio_dir / name).write_bytes(payload)
    with session_scope() as session:
        track = Track(
            owner_scope=owner,
            platform="douyin",
            video_id=video_id,
            title=title,
            source_url="https://example.invalid/" + video_id,
            audio_path=name,
            audio_format="m4a",
            audio_bytes=len(payload) if audio_bytes is None else audio_bytes,
            audio_sha256=hashlib.sha256(payload).hexdigest(),
            updated_seq=next_updated_seq(session),
        )
        session.add(track)
        session.flush()
        return track.id, video_id


def test_resolve_requires_auth_and_parses_link():
    with TestClient(app) as client:
        assert client.post("/v2/resolve", json={"text": "x"}).status_code == 401
        _, headers = register(client)

        ok = client.post(
            "/v2/resolve",
            headers=headers,
            json={"text": "https://www.douyin.com/video/7300000000000000000"},
        )
        assert ok.status_code == 200, ok.text
        assert ok.json()["video_id"] == "7300000000000000000"
        assert ok.json()["platform"] == "douyin"

        bad = client.post("/v2/resolve", headers=headers, json={"text": "不是链接"})
        assert bad.status_code == 400


def test_track_read_write_is_account_isolated():
    with TestClient(app) as client:
        alice, a = register(client)
        bob, b = register(client)
        track_id, _ = make_track(alice, title="原题")

        assert client.get(f"/v2/tracks/{track_id}", headers=b).status_code == 404
        got = client.get(f"/v2/tracks/{track_id}", headers=a)
        assert got.status_code == 200 and got.json()["title"] == "原题"
        assert got.json()["download_url"] == f"/v2/audio/{track_id}"

        assert client.patch(f"/v2/tracks/{track_id}", headers=b, json={"title": "偷改"}).status_code == 404
        patched = client.patch(
            f"/v2/tracks/{track_id}", headers=a, json={"title": "新题", "has_vocal": True}
        )
        assert patched.status_code == 200
        assert patched.json()["title"] == "新题"
        assert patched.json()["has_vocal"] is True
        assert patched.json()["classify_stage"] == "manual"

        assert client.delete(f"/v2/tracks/{track_id}", headers=b).status_code == 404
        deleted = client.delete(f"/v2/tracks/{track_id}", headers=a)
        assert deleted.status_code == 200 and deleted.json()["deleted"] is True

        tombstone = client.get("/v2/sync", headers=a).json()["items"]
        assert any(item["id"] == track_id and item["deleted"] for item in tombstone)

        assert client.post(f"/v2/tracks/{track_id}/restore", headers=b).status_code == 404
        restored = client.post(f"/v2/tracks/{track_id}/restore", headers=a)
        assert restored.status_code == 200 and restored.json()["deleted"] is False


def test_extraction_jobs_are_account_scoped():
    with TestClient(app) as client:
        alice, a = register(client)
        bob, b = register(client)
        with session_scope() as session:
            mine = ExtractJob(user_id=alice, raw_input="mine", status=JobStatus.DONE)
            theirs = ExtractJob(user_id=bob, raw_input="theirs", status=JobStatus.DONE)
            session.add_all([mine, theirs])
            session.flush()
            mine_id, theirs_id = mine.id, theirs.id

        rows = client.get("/v2/extraction-jobs", headers=a).json()
        ids = [row["id"] for row in rows]
        assert mine_id in ids and theirs_id not in ids


def test_reclassify_only_touches_current_account():
    with TestClient(app) as client:
        alice, a = register(client)
        bob, b = register(client)
        make_track(alice, title="安静钢琴曲")
        bob_track, _ = make_track(bob, title="摇滚现场")

        response = client.post("/v2/maintenance/reclassify", headers=a)
        assert response.status_code == 200
        assert response.json()["updated"] >= 0

        with session_scope() as session:
            assert session.get(Track, bob_track).classify_stage is None


def test_repair_audio_sizes_only_touches_current_account():
    with TestClient(app) as client:
        alice, a = register(client)
        bob, b = register(client)
        alice_track, _ = make_track(alice, audio_bytes=0)
        bob_track, _ = make_track(bob, audio_bytes=0)

        response = client.post("/v2/maintenance/repair-audio-sizes", headers=a)
        assert response.status_code == 200
        assert alice_track in response.json()["track_ids"]
        assert bob_track not in response.json()["track_ids"]

        with session_scope() as session:
            assert session.get(Track, bob_track).audio_bytes == 0


def test_duplicates_only_return_current_account_pairs():
    with TestClient(app) as client:
        alice, a = register(client)
        bob, _ = register(client)

        def pair(owner):
            first, _ = make_track(owner)
            second, _ = make_track(owner)
            values = json.dumps([index % 64 for index in range(128)])
            with session_scope() as session:
                for track_id in (first, second):
                    session.add(AudioFingerprint(track_id=track_id, values_json=values, duration_sec=100.0))
            return {first, second}

        mine = pair(alice)
        theirs = pair(bob)
        rows = client.get("/v2/maintenance/duplicates", headers=a).json()
        assert rows, "同一账号内的相同指纹应被识别"
        for row in rows:
            assert set(row["track_ids"]) <= mine
        assert all(not (set(row["track_ids"]) & theirs) for row in rows)


def test_scheduler_status_and_run_once_boundaries():
    with TestClient(app) as client:
        _, headers = register(client)
        status = client.get("/v2/scheduler", headers=headers)
        assert status.status_code == 200
        assert "configured" in status.json()

        run = client.post("/v2/scheduler/run-once", headers=headers)
        # 没有用户组 → 400；连配置文件都没有 → 404；都不应 500
        assert run.status_code in (400, 404)


def test_creator_preview_rejects_bare_numeric_without_platform():
    with TestClient(app) as client:
        _, headers = register(client)
        response = client.post(
            "/v2/creators/preview",
            headers=headers,
            json={"input": "67194609689", "start": 1, "count": 2},
        )
        assert response.status_code == 400
        assert "请注明平台" in response.text
