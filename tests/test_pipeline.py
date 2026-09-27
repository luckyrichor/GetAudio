"""链路编排的单测。提取器打桩，不触网。"""
from __future__ import annotations

from pathlib import Path

import pytest

from getaudio import pipeline
from getaudio.config import settings
from getaudio.db import init_db, session_scope
from getaudio.extractors.base import ExtractResult, MediaInfo
from getaudio.models import ExtractJob, JobStatus, Track
from getaudio.sharelink import Platform


class _FakeExtractor:
    """产出一个真实的小 m4a，让后续的打标和哈希都能真跑。"""

    platform = Platform.BILIBILI

    def __init__(self) -> None:
        self.calls = 0

    def extract(self, link, *, cookie_file=None) -> ExtractResult:
        self.calls += 1
        info = MediaInfo(
            platform=link.platform,
            video_id=link.video_id,
            title="测试音频",
            webpage_url=link.url,
            uploader="测试 up 主",
            duration_sec=1.0,
        )
        dest = settings.audio_dir / link.platform.value / f"{link.video_id}-测试音频.m4a"
        dest.parent.mkdir(parents=True, exist_ok=True)
        import subprocess

        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "aac", str(dest)],
            check=True,
        )
        return ExtractResult(info=info, audio_path=dest, audio_format="m4a", audio_bytes=dest.stat().st_size)


@pytest.fixture
def fake(monkeypatch):
    init_db()
    ex = _FakeExtractor()
    monkeypatch.setattr(pipeline, "get_extractor", lambda _p: ex)
    # 不要在测试里触网做 cookie 预热
    monkeypatch.setattr(pipeline, "ensure_guest_cookies", lambda _p: None)
    with session_scope() as s:
        s.query(ExtractJob).delete()
        s.query(Track).filter(Track.video_id == "BV0000000001").delete()
    yield ex


def test_extract_creates_track(fake):
    out = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    assert fake.calls == 1
    assert out.reused is False
    assert out.title == "测试音频"
    assert (settings.audio_dir / out.audio_path).exists()
    assert out.audio_bytes == (settings.audio_dir / out.audio_path).stat().st_size

    with session_scope() as s:
        t = s.get(Track, out.track_id)
        assert t.audio_sha256 and len(t.audio_sha256) == 64
        assert t.updated_seq > 0
        assert t.audio_bytes == out.audio_bytes


def test_second_call_hits_dedupe(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    second = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    assert fake.calls == 1, "第二次不应该重新下载"
    assert second.reused is True
    assert second.track_id == first.track_id


def test_force_redownloads_without_duplicating(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    again = pipeline.extract("https://www.bilibili.com/video/BV0000000001", force=True)
    assert fake.calls == 2
    assert again.track_id == first.track_id, "force 应该覆盖原记录，而不是新增一条"


def test_updated_seq_advances_on_force(fake):
    first = pipeline.extract("https://www.bilibili.com/video/BV0000000001")
    with session_scope() as s:
        seq1 = s.get(Track, first.track_id).updated_seq
    pipeline.extract("https://www.bilibili.com/video/BV0000000001", force=True)
    with session_scope() as s:
        seq2 = s.get(Track, first.track_id).updated_seq
    assert seq2 > seq1, "重新提取后同步游标必须前进，否则手机端拉不到更新"


def test_bad_link_records_failed_job(fake):
    from getaudio.sharelink import LinkError

    with pytest.raises(LinkError):
        pipeline.extract("这里没有链接")
    with session_scope() as s:
        job = s.query(ExtractJob).order_by(ExtractJob.id.desc()).first()
        assert job.status is JobStatus.FAILED
        assert job.error
