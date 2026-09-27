"""抖音选路逻辑的单测。

这条逻辑踩过一次真实的坑：某条视频的 music 条目只有 21 秒，而视频
本身 245 秒，默认取 music 会把音频截断成片段。用例把这个场景锁住。
"""
from __future__ import annotations

import pytest

from getaudio.extractors.base import ExtractorError
from getaudio.extractors.douyin import DouyinExtractor, _as_sec


def _aweme(*, music_sec, video_ms, music_url=True, video_url=True):
    d = {"duration": video_ms, "video": {}}
    if music_url:
        d["music"] = {
            "duration": music_sec,
            "play_url": {"url_list": ["https://example.com/music.mp3"]},
        }
    else:
        d["music"] = {"duration": music_sec, "play_url": {"url_list": []}}
    if video_url:
        d["video"] = {"play_addr": {"url_list": ["https://example.com/video.mp4"]}}
    return d


def test_full_length_music_is_preferred():
    """原声覆盖了整段视频 —— 用它，更干净。"""
    src, urls = DouyinExtractor()._audio_sources(_aweme(music_sec=60, video_ms=60000))
    assert src == "music"
    assert urls == ["https://example.com/music.mp3"]


def test_short_music_clip_falls_back_to_video():
    """线上真实案例：21 秒原声 vs 245 秒视频 —— 必须走视频音轨。"""
    src, urls = DouyinExtractor()._audio_sources(_aweme(music_sec=21, video_ms=244761))
    assert src == "video", "短片段被当成整首会导致音频截断"
    assert urls == ["https://example.com/video.mp4"]


def test_short_music_kept_when_no_video_url():
    """没有视频地址时只能退回片段，但要标记出来。"""
    src, _ = DouyinExtractor()._audio_sources(
        _aweme(music_sec=21, video_ms=244761, video_url=False)
    )
    assert src == "music_clip"


def test_coverage_threshold_is_respected():
    ex = DouyinExtractor(music_coverage=0.5)
    # 60/100 = 0.6 >= 0.5，够了
    assert ex._audio_sources(_aweme(music_sec=60, video_ms=100000))[0] == "music"
    # 40/100 = 0.4 < 0.5，不够
    assert ex._audio_sources(_aweme(music_sec=40, video_ms=100000))[0] == "video"


def test_unknown_duration_keeps_music():
    """时长拿不到时保守用原声 —— 多数情况下它就是整首。"""
    src, _ = DouyinExtractor()._audio_sources(_aweme(music_sec=None, video_ms=None))
    assert src == "music"


def test_prefer_video_always_takes_video():
    src, _ = DouyinExtractor(prefer="video")._audio_sources(_aweme(music_sec=300, video_ms=300000))
    assert src == "video"


def test_no_sources_raises():
    with pytest.raises(ExtractorError):
        DouyinExtractor()._audio_sources(
            _aweme(music_sec=10, video_ms=10000, music_url=False, video_url=False)
        )


@pytest.mark.parametrize(
    "value,unit,expected",
    [(21, "s", 21.0), (244761, "ms", 244.761), (0, "s", None), (None, "ms", None), ("x", "s", None)],
)
def test_as_sec(value, unit, expected):
    assert _as_sec(value, unit=unit) == expected
