"""B 站原生提取器。

为什么不用 yt-dlp：它要先抓视频 HTML 页拿初始化数据，而这个页面在
机房 IP 上会被风控直接回 HTTP 412。而 B 站的开放 API（view + 带 WBI
签名的 playurl）是通的，且直接给出 DASH 音频轨地址 —— 只下音频，
不碰视频流。

WBI 签名：B 站从 2023 年起要求部分接口带 w_rid（参数按字典序拼接后
加盐 md5）和 wts 时间戳。盐值 mixin_key 由 nav 接口下发的两张图片
文件名按固定重排表打散得到。
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import math
import random
import string
import tempfile
import time
import urllib.parse
from pathlib import Path

import httpx

from ..config import settings
from ..sharelink import Platform, ResolvedLink
from .base import ExtractResult, ExtractorError, MediaInfo, NeedsLoginError
from .probe import probe_duration
from .remux import extract_audio
from .ytdlp import _move_into_library

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

API_VIEW = "https://api.bilibili.com/x/web-interface/view"
API_NAV = "https://api.bilibili.com/x/web-interface/nav"
API_PLAYURL = "https://api.bilibili.com/x/player/wbi/playurl"

# B 站官方的 mixin_key 重排表，硬编码（服务端不下发，只能跟随）
_MIXIN_TABLE = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
]

# 音频流 id → 可读名。数值越大音质越好；后两个要大会员。
AUDIO_QUALITY = {
    30216: "64K",
    30232: "132K",
    30280: "192K",
    30250: "Dolby Atmos",
    30251: "Hi-Res FLAC",
}

_wbi_cache: tuple[float, str] | None = None
_WBI_TTL = 12 * 3600


def _mixin_key(img_key: str, sub_key: str) -> str:
    raw = img_key + sub_key
    return "".join(raw[i] for i in _MIXIN_TABLE)[:32]


def _key_from_url(url: str) -> str:
    return url.rsplit("/", 1)[-1].split(".")[0]


def _get_mixin_key(client: httpx.Client) -> str:
    global _wbi_cache
    now = time.time()
    if _wbi_cache and now - _wbi_cache[0] < _WBI_TTL:
        return _wbi_cache[1]

    resp = client.get(API_NAV)
    data = resp.json()
    # nav 未登录时 code=-101，但 wbi_img 照样下发 —— 不能因为 code 非 0 就报错
    wbi = (data.get("data") or {}).get("wbi_img") or {}
    if not wbi.get("img_url") or not wbi.get("sub_url"):
        raise ExtractorError("拿不到 WBI 密钥（nav 接口返回异常）")

    key = _mixin_key(_key_from_url(wbi["img_url"]), _key_from_url(wbi["sub_url"]))
    _wbi_cache = (now, key)
    return key


def _sign(params: dict, mixin_key: str) -> dict:
    """给参数补上 wts 和 w_rid。"""
    signed = dict(params)
    signed["wts"] = int(time.time())
    query = "&".join(
        f"{k}={urllib.parse.quote(str(signed[k]), safe='')}" for k in sorted(signed)
    )
    signed["w_rid"] = hashlib.md5((query + mixin_key).encode()).hexdigest()
    return signed


def reset_wbi_cache() -> None:
    """空间接口返回 412/-352 时丢弃缓存的 WBI 密钥，下次重新向 nav 取。"""
    global _wbi_cache
    _wbi_cache = None


def dm_params() -> dict[str, str]:
    """空间接口还会看浏览器指纹参数，缺了容易被 412。"""
    width, height = 1920, 1080
    rnd = math.floor(114 * random.random())
    wh = [2 * width + 2 * height + 3 * rnd, 4 * width - height + rnd, rnd]
    scroll_top, scroll_left = 10, 10
    rnd2 = math.floor(514 * random.random())
    of = [3 * scroll_top + 2 * scroll_left + rnd2, 4 * scroll_top - 4 * scroll_left + 2 * rnd2, rnd2]
    return {
        "dm_img_list": "[]",
        "dm_img_str": base64.b64encode(
            "".join(random.choices(string.printable, k=random.randint(16, 64))).encode()
        )[:-2].decode(),
        "dm_cover_img_str": base64.b64encode(
            "".join(random.choices(string.printable, k=random.randint(32, 128))).encode()
        )[:-2].decode(),
        "dm_img_inter": json.dumps({"ds": [], "wh": wh, "of": of}, separators=(",", ":")),
    }


def _parse_cookie_file(path: Path | None) -> dict[str, str]:
    """读 Netscape cookie 文件。yt-dlp 用同一份文件，两个提取器共享凭证。"""
    if not path or not path.exists():
        return {}
    jar: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 7:
            jar[parts[5]] = parts[6]
    return jar


class BilibiliExtractor:
    platform = Platform.BILIBILI

    def extract(self, link: ResolvedLink, *, cookie_file: Path | None = None) -> ExtractResult:
        settings.ensure_dirs()
        headers = {
            "User-Agent": _UA,
            "Referer": "https://www.bilibili.com/",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Origin": "https://www.bilibili.com",
        }
        cookies = _parse_cookie_file(cookie_file)

        with httpx.Client(timeout=settings.http_timeout, headers=headers, cookies=cookies, follow_redirects=True) as client:
            info_raw, cid = self._fetch_view(client, link)
            audio = self._pick_audio(client, link, info_raw, cid, cookies)
            info = self._to_media_info(info_raw, link)

            with tempfile.TemporaryDirectory(dir=settings.tmp_dir) as td:
                stream_path = Path(td) / f"{info.video_id}.m4s"
                self._download(client, audio["baseUrl"], stream_path, audio.get("backupUrl") or [])
                # DASH 音频分片本身就是成品音轨，直接拷进容器即可
                out_path = extract_audio(stream_path, Path(td) / f"{info.video_id}-audio")
                if probed := probe_duration(out_path):
                    info.duration_sec = probed
                final = _move_into_library(out_path, info)

        quality = AUDIO_QUALITY.get(audio.get("id"), str(audio.get("id")))
        log.info("B 站音频轨: id=%s (%s) %.0f kbps", audio.get("id"), quality, audio.get("bandwidth", 0) / 1000)
        return ExtractResult(
            info=info,
            audio_path=final,
            audio_format=final.suffix.lstrip("."),
            audio_bytes=final.stat().st_size,
        )

    # ---------- 各步骤 ----------

    def _fetch_view(self, client: httpx.Client, link: ResolvedLink) -> tuple[dict, int]:
        params = {"aid": link.video_id[2:]} if link.video_id.lower().startswith("av") else {"bvid": link.video_id}
        try:
            data = client.get(API_VIEW, params=params).json()
        except Exception as exc:
            raise ExtractorError(f"view 接口请求失败：{exc}") from exc

        code = data.get("code")
        if code != 0:
            msg = data.get("message") or f"code={code}"
            # -404 稿件不存在；62002 已下架；-403 需要权限
            if code in (-403, 62002):
                raise NeedsLoginError(f"稿件不可访问（{msg}），可能需要登录或已限制地区")
            raise ExtractorError(f"view 接口返回错误：{msg}")

        d = data["data"]
        pages = d.get("pages") or []
        # 分 P：p 是 1-based；无效页不能悄悄退回 P1。
        page_no = link.page
        if page_no > 1 and (not pages or page_no > len(pages)):
            raise ExtractorError(f"稿件没有 P{page_no}（共 {len(pages)} P）")
        if pages:
            cid = pages[page_no - 1]["cid"]
            if page_no > 1:
                # 分 P 标题要带上，否则一个合集里所有条目同名
                d = {**d, "title": f"{d.get('title','')} - P{page_no} {pages[page_no-1].get('part','')}".strip()}
                d["duration"] = pages[page_no - 1].get("duration") or d.get("duration")
        else:
            cid = d.get("cid")
        if not cid:
            raise ExtractorError("拿不到 cid，无法请求播放地址")
        return d, int(cid)

    def _pick_audio(self, client: httpx.Client, link: ResolvedLink, view: dict, cid: int, cookies: dict) -> dict:
        mixin_key = _get_mixin_key(client)
        params = {
            "bvid": view.get("bvid") or link.video_id,
            "cid": cid,
            "fnval": 4048,  # 请求全部 DASH 能力，含 Hi-Res / Dolby
            "fnver": 0,
            "fourk": 1,
            "platform": "pc",
        }
        resp = client.get(API_PLAYURL, params=_sign(params, mixin_key))
        ctype = resp.headers.get("content-type", "")
        if "json" not in ctype:
            # 风控页是 HTML —— 明确说出来，别让人以为是网络问题
            raise ExtractorError("playurl 被风控拦截（返回了 HTML 而非 JSON），稍后重试或换出口 IP")

        data = resp.json()
        if data.get("code") != 0:
            raise ExtractorError(f"playurl 返回错误：{data.get('message')}")

        dash = (data.get("data") or {}).get("dash") or {}
        candidates: list[dict] = list(dash.get("audio") or [])
        # 大会员音质在单独字段里
        if flac := (dash.get("flac") or {}).get("audio"):
            candidates.append(flac)
        for dolby in (dash.get("dolby") or {}).get("audio") or []:
            candidates.append(dolby)

        if not candidates:
            if not cookies:
                raise NeedsLoginError("这个稿件没有可用音频轨，可能需要登录（扫码后重试）")
            raise ExtractorError("playurl 没有返回任何音频轨")

        # 码率最高的那一路。没有会员时会员轨的 url 拿不到，所以只按 bandwidth 排
        return max(candidates, key=lambda a: a.get("bandwidth") or 0)

    def _download(self, client: httpx.Client, url: str, dest: Path, backups: list[str]) -> None:
        urls = [url, *backups]
        last: Exception | None = None
        for candidate in urls:
            try:
                # CDN 校验 Referer，缺了就 403
                with client.stream("GET", candidate, headers={"Referer": "https://www.bilibili.com/"}) as resp:
                    resp.raise_for_status()
                    with dest.open("wb") as f:
                        for chunk in resp.iter_bytes(1 << 16):
                            f.write(chunk)
                if dest.stat().st_size > 0:
                    return
                last = ExtractorError("下载得到 0 字节")
            except Exception as exc:
                last = exc
                log.warning("音频流下载失败，尝试备用地址: %s", exc)
        raise ExtractorError(f"音频流下载失败：{last}")

    def _to_media_info(self, d: dict, link: ResolvedLink) -> MediaInfo:
        owner = d.get("owner") or {}
        pubdate = d.get("pubdate")
        return MediaInfo(
            platform=Platform.BILIBILI,
            video_id=link.track_key,
            title=(d.get("title") or "").strip() or f"bilibili-{link.video_id}",
            webpage_url=link.url,
            uploader=owner.get("name"),
            description=d.get("desc"),
            thumbnail_url=d.get("pic"),
            upload_date=time.strftime("%Y%m%d", time.localtime(pubdate)) if pubdate else None,
            duration_sec=float(d["duration"]) if d.get("duration") else None,
            tags=_tags_of(d),
        )


def _tags_of(d: dict) -> list[str]:
    tags = []
    if tname := d.get("tname"):
        tags.append(tname)
    for t in d.get("tags") or []:
        if isinstance(t, dict) and t.get("tag_name"):
            tags.append(t["tag_name"])
        elif isinstance(t, str):
            tags.append(t)
    return tags
