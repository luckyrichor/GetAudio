"""博主批量：输入解析、抖音适配器、任务生命周期。

这些用例都不打外网：抖音的 HTTP 客户端、浏览器桥接和下载步骤都用
假对象替换，重点验证「输入怎么变 sec_uid」「图文配乐会不会被漏掉」
「范围按什么排序」「预取 aweme 后不再请求 detail」。
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from getaudio import creator_service
from getaudio.config import settings
from getaudio.creator_service import (
    PreviewResult,
    cancel_job,
    claim_next_job,
    create_job,
    get_job,
    list_jobs,
    process_job,
    retry_job,
)
from getaudio.creators.base import (
    CreatorComment,
    CreatorProfile,
    CreatorVideo,
    CreatorWindow,
    VideoStats,
)
from getaudio.creators.douyin import DouyinCreatorProvider, _has_media
from getaudio.db import init_db
from getaudio.sharelink import (
    CreatorRef,
    LinkError,
    Platform,
    ResolvedLink,
    parse_creator_input,
    resolve_creator,
)

SEC_UID = "MS4wLjABAAAAtest_sec_uid_123456"


# ---------------------------------------------------------------- 输入解析


def test_bare_numeric_needs_platform():
    parsed = parse_creator_input("67194609689")
    assert parsed.platform is None
    assert parsed.kind == "numeric"

    with pytest.raises(LinkError) as excinfo:
        creator_service.resolve_ref("67194609689")
    assert "请注明平台" in str(excinfo.value)


def test_douyin_label_is_tolerated_not_required():
    parsed = parse_creator_input("抖音号：67194609689")
    assert parsed.platform is Platform.DOUYIN
    assert parsed.value == "67194609689"
    assert parsed.kind == "numeric"


def test_douyin_number_with_platform_uses_provider(monkeypatch):
    calls: list[tuple[str, str | None]] = []

    class FakeProvider:
        def resolve(self, value, *, cookie_file):
            calls.append(("resolve", value))
            return CreatorRef(Platform.DOUYIN, SEC_UID, f"https://www.douyin.com/user/{SEC_UID}")

    monkeypatch.setattr(creator_service, "get_provider", lambda _platform: FakeProvider())
    ref = creator_service.resolve_ref("67194609689", platform="douyin")
    assert ref.creator_id == SEC_UID
    assert calls == [("resolve", "67194609689")]


def test_single_video_link_is_rejected_with_hint():
    with pytest.raises(LinkError) as excinfo:
        resolve_creator("https://www.douyin.com/video/7300000000000000000")
    assert "单个视频" in str(excinfo.value)


def test_creator_urls_resolve_to_refs():
    douyin = resolve_creator(f"https://www.douyin.com/user/{SEC_UID}")
    assert douyin.platform is Platform.DOUYIN and douyin.creator_id == SEC_UID

    bili = resolve_creator("https://space.bilibili.com/12345/video")
    assert bili.platform is Platform.BILIBILI and bili.creator_id == "12345"


# ---------------------------------------------------------------- 抖音号 → sec_uid


class _Resp:
    def __init__(self, data, status=200, headers=None, text=None):
        self._data = data
        self.status_code = status
        self.headers = headers or {"content-type": "application/json"}
        self.text = text if text is not None else str(data)

    def json(self):
        return self._data

    def raise_for_status(self):
        if self.status_code >= 400:
            raise AssertionError(self.status_code)


class _FakeClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def get(self, url, params=None, **_kwargs):
        self.calls.append((url, params))
        return self.response


def test_unique_id_resolves_via_public_user_info():
    client = _FakeClient(_Resp({"status_code": 0, "user_info": {"sec_uid": SEC_UID}}))
    provider = DouyinCreatorProvider(client_factory=lambda **_: client)
    ref = provider.resolve("67194609689", cookie_file=None)
    assert ref.creator_id == SEC_UID
    assert ref.url == f"https://www.douyin.com/user/{SEC_UID}"
    assert client.calls and "user/info" in client.calls[0][0]
    assert client.calls[0][1] == {"unique_id": "67194609689"}


def test_unique_id_falls_back_to_browser_search(monkeypatch):
    looks = _FakeClient(_Resp({"status_code": 0, "user_info": {}}))
    provider = DouyinCreatorProvider(client_factory=lambda **_: looks)

    class FakeBridge:
        def resolve_user(self, query, *, cookie_file):
            return {"sec_uid": SEC_UID, "unique_id": query}

    provider._bridge_factory = FakeBridge
    ref = provider.resolve("67194609689", cookie_file=None)
    assert ref.creator_id == SEC_UID


# ---------------------------------------------------------------- 图文配乐


def test_has_media_accepts_image_post_with_music():
    image_post = {
        "aweme_id": "1",
        "aweme_type": 2,
        "music": {"play_url": {"url_list": ["https://cdn.example/a.mp3"]}},
        "video": {},
    }
    assert _has_media(image_post) is True

    video_only = {"aweme_id": "2", "video": {"play_addr": {"url_list": ["https://cdn.example/v.mp4"]}}}
    assert _has_media(video_only) is True

    empty = {"aweme_id": "3", "music": {}, "video": {}}
    assert _has_media(empty) is False


def test_window_sorts_by_create_time_not_page_order(monkeypatch):
    provider = DouyinCreatorProvider(client_factory=lambda **_: _FakeClient(_Resp({})))

    def fake_direct(ref, *, need, cookie_file):
        # 页面把置顶作品放在最前，create_time 不是递增的
        awemes = [
            {"aweme_id": "pinned", "desc": "置顶", "create_time": 100,
             "video": {"play_addr": {"url_list": ["https://cdn.example/p.mp4"]}}},
            {"aweme_id": "newest", "desc": "最新", "create_time": 300,
             "music": {"play_url": {"url_list": ["https://cdn.example/n.mp3"]}}},
            {"aweme_id": "middle", "desc": "中间", "create_time": 200,
             "video": {"play_addr": {"url_list": ["https://cdn.example/m.mp4"]}}},
        ]
        return {
            "profile": {"sec_uid": SEC_UID, "nickname": "博主", "aweme_count": 3},
            "items": awemes,
            "has_more": False,
        }

    monkeypatch.setattr(provider, "_fetch_direct", fake_direct)
    window = provider.fetch_window(
        CreatorRef(Platform.DOUYIN, SEC_UID, f"https://www.douyin.com/user/{SEC_UID}"),
        start=1,
        count=2,
        cookie_file=None,
    )
    assert [video.video_id for video in window.items] == ["newest", "middle"]
    assert [video.position for video in window.items] == [1, 2]


# ---------------------------------------------------------------- 预取 aweme


def test_prefetched_aweme_skips_detail_request(monkeypatch):
    from getaudio.extractors import douyin as douyin_extractor

    extractor = douyin_extractor.DouyinExtractor()

    def explode(*_args, **_kwargs):
        raise AssertionError("预取了 aweme 之后不应再请求 detail")

    monkeypatch.setattr(extractor, "_fetch_detail", explode)
    monkeypatch.setattr(
        extractor,
        "_download",
        lambda client, urls, dest: dest.write_bytes(b"0" * 4096),
    )

    def fake_extract_audio(raw_path, out_base):
        out = out_base.with_suffix(".m4a")
        out.write_bytes(b"1" * 4096)
        return out

    monkeypatch.setattr(douyin_extractor, "extract_audio", fake_extract_audio)
    monkeypatch.setattr(douyin_extractor, "probe_duration", lambda _path: 12.0)

    aweme = {
        "aweme_id": "7300000000000000001",
        "desc": "图文配乐",
        "create_time": 1_700_000_000,
        "author": {"nickname": "作者"},
        "music": {"title": "BGM", "duration": 12, "play_url": {"url_list": ["https://cdn.example/a.mp3"]}},
        "video": {"duration": 12_000},
        "statistics": {"digg_count": 1},
    }
    link = ResolvedLink(
        Platform.DOUYIN,
        "7300000000000000001",
        "https://www.douyin.com/video/7300000000000000001",
        "https://www.douyin.com/video/7300000000000000001",
    )
    result = extractor.extract(link, aweme=aweme)
    assert result.info.video_id == "7300000000000000001"
    assert result.audio_path.is_file()
    assert result.audio_path.suffix == ".m4a"


# ---------------------------------------------------------------- 任务生命周期


def _fake_window() -> PreviewResult:
    videos = [
        CreatorVideo(
            platform=Platform.DOUYIN,
            video_id=f"vid-{index}",
            title=f"第 {index} 条",
            source_url=f"https://www.douyin.com/video/vid-{index}",
            position=index,
            creator_id=SEC_UID,
            prefetched={"aweme_id": f"vid-{index}"},
        )
        for index in (1, 2)
    ]
    return PreviewResult(
        ref=CreatorRef(Platform.DOUYIN, SEC_UID, f"https://www.douyin.com/user/{SEC_UID}"),
        window=CreatorWindow(
            profile=CreatorProfile(Platform.DOUYIN, SEC_UID, "博主", f"https://www.douyin.com/user/{SEC_UID}"),
            items=videos,
            total=2,
            has_more=False,
        ),
        fetched_at="2026-10-30T00:00:00+00:00",
    )


def test_job_create_progress_cancel_retry_and_isolation(monkeypatch):
    init_db()
    from getaudio.db import session_scope
    from getaudio.models import Track

    with session_scope() as session:
        track = Track(owner_scope="acct_creator_a", platform="douyin", video_id="vid-1",
                      source_url="https://example.invalid/", audio_path="creator-test.m4a",
                      updated_seq=1)
        session.add(track)
        session.flush()
        track_id = track.id

    monkeypatch.setattr(creator_service, "load_window", lambda *a, **k: _fake_window())
    monkeypatch.setattr(settings, "creator_download_interval", 0)
    outcomes = []

    def fake_extract(url, *, user_id, force, owner_scope, prefetched_media=None):
        outcomes.append((url, owner_scope, prefetched_media))
        return SimpleNamespace(track_id=track_id, reused=False)

    monkeypatch.setattr(creator_service, "extract", fake_extract)

    job_id = create_job("抖音号：67194609689", owner_scope="acct_creator_a", start=1, count=2)
    other_job = create_job("抖音号：67194609689", owner_scope="acct_creator_b", start=1, count=2)
    assert other_job != job_id

    # 账号隔离
    assert get_job(job_id, "acct_creator_b") is None
    assert [job.id for job in list_jobs("acct_creator_b")] == [other_job]

    claimed = claim_next_job()
    assert claimed == job_id
    process_job(job_id)

    job = get_job(job_id, "acct_creator_a")
    assert job.status == "done"
    assert (job.processed_count, job.succeeded_count, job.failed_count) == (2, 2, 0)
    assert [row[0] for row in outcomes] == [
        "https://www.douyin.com/video/vid-1",
        "https://www.douyin.com/video/vid-2",
    ]
    assert all(row[1] == "acct_creator_a" for row in outcomes)
    assert all(row[2] for row in outcomes)  # prefetched aweme 被传给 pipeline

    # 取消：pending/running → cancelled，未开始项一起取消
    second = create_job("抖音号：67194609689", owner_scope="acct_creator_a", start=1, count=2)
    claim_next_job()
    cancelled = cancel_job(second, "acct_creator_a")
    assert cancelled.status == "cancelled"
    process_job(second)
    assert get_job(second, "acct_creator_a").status == "cancelled"

    # 重试：回到 pending，计数清零
    retried = retry_job(second, "acct_creator_a")
    assert retried.status == "pending"
    assert retried.processed_count == 0
    process_job(second)
    assert get_job(second, "acct_creator_a").status == "done"
