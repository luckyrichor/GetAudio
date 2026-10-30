"""抖音原生提取器。

抖音有两路音频可取，对这个项目差别很大：

  1. ``music.play_url``  —— 视频用的那首 BGM 原声，干净、就是歌本身
  2. 视频合成流里的音轨 —— 含拍摄者的人声、环境音、口播

做 BGM 收集库要的是第 1 路 —— 但有个坑：``music`` 条目常常只是
一段 20 秒左右的片段（``music.duration`` 远小于 ``aweme.duration``），
直接取它会把四分钟的音频截成二十秒。

所以选路策略是「先比时长再下载」：两个时长接口里都给了，music 覆盖
不到视频大部分长度时就改走视频音轨。哪一路来的会记在 tags 里。

注意：抖音的 Web 接口反爬改得很勤，这个文件是全项目最脆的一块。
坏掉时先看 detail 接口返回的 filter_reason。
"""
from __future__ import annotations

import logging
import subprocess
import tempfile
import time
from pathlib import Path

import httpx

from ..config import settings
from ..sharelink import Platform, ResolvedLink
from .base import ExtractResult, ExtractorError, MediaInfo, NeedsLoginError
from .bilibili import _parse_cookie_file
from .probe import probe_duration
from .remux import extract_audio
from .ytdlp import _move_into_library

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

API_DETAIL = "https://www.douyin.com/aweme/v1/web/aweme/detail/"

# Web 端固定参数。aid=6383 是抖音 Web 的应用 id。
_BASE_PARAMS = {
    "device_platform": "webapp",
    "aid": "6383",
    "channel": "channel_pc_web",
    "pc_client_type": "1",
    "version_code": "170400",
    "version_name": "17.4.0",
    "cookie_enabled": "true",
    "platform": "PC",
    "downlink": "10",
}

# filter_reason → 给用户看的人话
_FILTER_MSG = {
    "core_dep": "视频不存在或已被删除",
    "self_see": "作品仅作者自己可见",
    "friend": "作品仅好友可见",
    "private": "作品已设为私密",
    "reviewing": "作品审核中",
}


class DouyinExtractor:
    platform = Platform.DOUYIN

    def __init__(self, prefer: str = "music", music_coverage: float = 0.9) -> None:
        # prefer: "music" = 倾向原声 BGM；"video" = 一律取视频音轨
        # music_coverage: 原声时长至少要覆盖视频的这个比例才采用，
        #   否则认为它只是片段，改走完整的视频音轨
        self.prefer = prefer
        self.music_coverage = music_coverage

    def extract(self, link: ResolvedLink, *, cookie_file: Path | None = None,
                aweme: dict | None = None) -> ExtractResult:
        settings.ensure_dirs()
        cookies = _parse_cookie_file(cookie_file)
        if "ttwid" not in cookies:
            # 说得具体些：这是最常见的失败原因
            log.warning("抖音 cookie 里没有 ttwid，detail 接口大概率会 403")

        headers = {
            "User-Agent": _UA,
            "Referer": f"https://www.douyin.com/video/{link.video_id}",
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }

        with httpx.Client(timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True) as client:
            detail = aweme if isinstance(aweme, dict) else None
            if detail is None:
                detail = self._fetch_detail(client, link)
            info = self._to_media_info(detail, link)
            try:
                source, urls = self._audio_sources(detail)
            except ExtractorError:
                if aweme is None:
                    raise
                detail = self._fetch_detail(client, link)
                info = self._to_media_info(detail, link)
                source, urls = self._audio_sources(detail)

            with tempfile.TemporaryDirectory(dir=settings.tmp_dir) as td:
                raw_path = Path(td) / f"{info.video_id}.bin"
                try:
                    self._download(client, urls, raw_path)
                except ExtractorError:
                    if aweme is None:
                        raise
                    detail = self._fetch_detail(client, link)
                    info = self._to_media_info(detail, link)
                    source, urls = self._audio_sources(detail)
                    self._download(client, urls, raw_path)
                # 无损抽轨：容器由源编码决定，不做二次压缩
                out_path = extract_audio(raw_path, Path(td) / f"{info.video_id}-audio")
                # 接口给的 duration 描述的是视频，不一定等于我们取到的音轨长度
                # —— 以成品文件实测为准，否则库里的时长是错的
                if probed := probe_duration(out_path):
                    info.duration_sec = probed
                info.tags = [*info.tags, f"audio_source:{source}"]
                final = _move_into_library(out_path, info)

        log.info("抖音音频来源: %s", source)
        return ExtractResult(
            info=info,
            audio_path=final,
            audio_format=final.suffix.lstrip("."),
            audio_bytes=final.stat().st_size,
        )

    # ---------- 各步骤 ----------

    def _fetch_detail(self, client: httpx.Client, link: ResolvedLink) -> dict:
        params = {**_BASE_PARAMS, "aweme_id": link.video_id}
        try:
            resp = client.get(API_DETAIL, params=params)
        except httpx.HTTPError as exc:
            raise ExtractorError(f"detail 接口请求失败：{exc}") from exc

        if resp.status_code == 403:
            raise NeedsLoginError("抖音拒绝了请求（403），访客凭证可能已失效，请重试或扫码登录")
        if "json" not in resp.headers.get("content-type", ""):
            raise ExtractorError("detail 接口返回了非 JSON（大概率被风控拦截）")

        data = resp.json()
        aweme = data.get("aweme_detail")
        if not aweme:
            reason = (data.get("filter_detail") or {}).get("filter_reason", "")
            msg = _FILTER_MSG.get(reason, f"接口没有返回作品内容（filter_reason={reason or '未知'}）")
            if reason in ("self_see", "friend", "private"):
                raise NeedsLoginError(msg + "，需要有权限的账号登录后才能提取")
            raise ExtractorError(msg)
        return aweme

    def _audio_sources(self, aweme: dict) -> tuple[str, list[str]]:
        """返回 (来源标记, 候选下载地址列表)。

        选路只看时长，不猜内容：原声条目够长就用它（干净），
        明显是片段就用视频音轨（完整）。完整性优先于干净。
        """
        music = aweme.get("music") or {}
        music_urls = _url_list(music.get("play_url"))
        video_urls = _video_urls(aweme)

        if self.prefer == "music" and music_urls:
            music_sec = _as_sec(music.get("duration"), unit="s")
            video_sec = _as_sec(aweme.get("duration") or (aweme.get("video") or {}).get("duration"), unit="ms")

            # 时长不明时保守用原声（多数情况下就是整首）
            if not music_sec or not video_sec:
                return "music", music_urls

            if music_sec >= video_sec * self.music_coverage:
                return "music", music_urls

            log.info(
                "原声只有 %.0fs，视频 %.0fs —— 判定为片段，改取视频音轨",
                music_sec, video_sec,
            )
            if video_urls:
                return "video", video_urls
            log.warning("没有可用的视频地址，只能退回截断的原声片段")
            return "music_clip", music_urls

        if video_urls:
            return "video", video_urls
        if music_urls:
            return "music", music_urls
        raise ExtractorError("没有找到任何可下载的音频或视频地址")

    def _download(self, client: httpx.Client, urls: list[str], dest: Path) -> None:
        last: Exception | None = None
        for url in urls:
            try:
                with client.stream("GET", url, headers={"Referer": "https://www.douyin.com/"}) as resp:
                    resp.raise_for_status()
                    with dest.open("wb") as f:
                        for chunk in resp.iter_bytes(1 << 16):
                            f.write(chunk)
                if dest.stat().st_size > 1024:
                    return
                last = ExtractorError(f"下载内容过小（{dest.stat().st_size} 字节）")
            except Exception as exc:
                last = exc
                log.warning("地址下载失败，换下一个: %s", exc)
        raise ExtractorError(f"所有候选地址都下载失败：{last}")

    def _to_media_info(self, aweme: dict, link: ResolvedLink) -> MediaInfo:
        author = aweme.get("author") or {}
        music = aweme.get("music") or {}
        video = aweme.get("video") or {}
        create_time = aweme.get("create_time")

        # 抖音没有独立标题，desc 就是文案；太长就截断当标题用
        desc = (aweme.get("desc") or "").strip()
        music_title = (music.get("title") or "").strip()
        title = music_title or desc[:60] or f"douyin-{link.video_id}"

        tags = [t["hashtag_name"] for t in (aweme.get("text_extra") or []) if t.get("hashtag_name")]
        if music_title and music_title != title:
            tags.append(music_title)

        duration_ms = aweme.get("duration") or video.get("duration")
        return MediaInfo(
            platform=Platform.DOUYIN,
            video_id=str(aweme.get("aweme_id") or link.video_id),
            title=title,
            webpage_url=f"https://www.douyin.com/video/{aweme.get('aweme_id') or link.video_id}",
            uploader=author.get("nickname") or (music.get("author") or None),
            description=desc,
            thumbnail_url=_first(_url_list((video.get("cover") or {}))),
            upload_date=time.strftime("%Y%m%d", time.localtime(create_time)) if create_time else None,
            duration_sec=float(duration_ms) / 1000 if duration_ms else None,
            tags=tags,
        )


def _as_sec(value, *, unit: str) -> float | None:
    """时长归一到秒。抖音同一份响应里 music 用秒、aweme 用毫秒。"""
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if v <= 0:
        return None
    return v / 1000 if unit == "ms" else v


def _url_list(node) -> list[str]:
    if not isinstance(node, dict):
        return []
    return [u for u in (node.get("url_list") or []) if isinstance(u, str) and u.startswith("http")]


def _first(items: list[str]) -> str | None:
    return items[0] if items else None


def _video_urls(aweme: dict) -> list[str]:
    """视频地址候选，无水印优先。"""
    video = aweme.get("video") or {}
    urls: list[str] = []
    # play_addr 系列里 h264/bit_rate 通常是无水印源
    for key in ("play_addr_h264", "play_addr_265", "play_addr", "download_addr"):
        urls.extend(_url_list(video.get(key)))
    for br in video.get("bit_rate") or []:
        urls.extend(_url_list(br.get("play_addr")))
    # 去重保序
    seen: set[str] = set()
    return [u for u in urls if not (u in seen or seen.add(u))]
