"""无损抽取音轨。

原则：**能拷贝就绝不重编码**。源音频已经是有损压缩过的，再转一次
AAC 是第二次有损 —— 听感变差，文件还未必更小。

做法是先探测源里的音频编码，再选一个能直接容纳它的容器：

    aac          → .m4a
    mp3          → .mp3
    opus/vorbis  → .ogg
    flac         → .flac

拷贝出来的音频流和源里的字节完全一致。只有在编码未知、或拷贝真的
失败时才退回重编码，并且会在日志里说明。
"""
from __future__ import annotations

import json
import logging
import subprocess
from pathlib import Path

log = logging.getLogger(__name__)

# 音频编码 → 能无损容纳它的容器扩展名
_CODEC_CONTAINER = {
    "aac": "m4a",
    "alac": "m4a",
    "mp3": "mp3",
    "opus": "ogg",
    "vorbis": "ogg",
    "flac": "flac",
    "ac3": "ac3",
    "eac3": "eac3",
}

# 重编码兜底时的参数。只在拷贝走不通时用。
_FALLBACK_CODEC = "aac"
_FALLBACK_BITRATE = "192k"


def probe_audio_codec(src: Path) -> str | None:
    """返回第一条音频流的编码名；探测失败返回 None。"""
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "a:0",
        "-show_entries", "stream=codec_name",
        "-of", "json", str(src),
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if proc.returncode != 0:
            log.warning("ffprobe 探测编码失败: %s", proc.stderr.strip()[:200])
            return None
        streams = json.loads(proc.stdout).get("streams") or []
        return streams[0].get("codec_name") if streams else None
    except (subprocess.TimeoutExpired, json.JSONDecodeError, OSError) as exc:
        log.warning("ffprobe 异常: %s", exc)
        return None


def extract_audio(src: Path, dest_stem: Path) -> Path:
    """从 src 抽出音轨，返回实际产出的文件路径（扩展名由源编码决定）。

    dest_stem 是不带扩展名的目标路径。
    """
    codec = probe_audio_codec(src)
    if codec is None:
        log.warning("探测不到音频编码，退回重编码")
        return _transcode(src, _with_ext(dest_stem, "m4a"))

    container = _CODEC_CONTAINER.get(codec)
    if container is None:
        log.warning("编码 %s 没有对应的无损容器，退回重编码", codec)
        return _transcode(src, _with_ext(dest_stem, "m4a"))

    dest = _with_ext(dest_stem, container)
    if _copy(src, dest):
        log.info("音轨无损拷贝: %s → .%s", codec, container)
        return dest

    log.warning("%s 流拷贝失败，退回重编码", codec)
    return _transcode(src, _with_ext(dest_stem, "m4a"))


def _with_ext(stem: Path, ext: str) -> Path:
    """拼扩展名。不用 with_suffix —— 文件名里带点时它会把点后面截掉。"""
    return stem.with_name(f"{stem.name}.{ext}")


def _copy(src: Path, dest: Path) -> bool:
    """流拷贝。成功返回 True。"""
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src),
        "-vn",              # 丢掉视频轨
        "-map", "0:a:0",    # 只要第一条音频轨
        "-c:a", "copy",     # 不重编码 —— 字节级一致
        str(dest),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        log.debug("流拷贝 ffmpeg 输出: %s", proc.stderr.strip()[:300])
        dest.unlink(missing_ok=True)
        return False
    return dest.exists() and dest.stat().st_size > 0


def _transcode(src: Path, dest: Path) -> Path:
    from .base import ExtractorError

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src), "-vn",
        "-c:a", _FALLBACK_CODEC, "-b:a", _FALLBACK_BITRATE,
        str(dest),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not dest.exists() or dest.stat().st_size == 0:
        raise ExtractorError(f"ffmpeg 转码失败：{proc.stderr.strip()[:500]}")
    return dest
