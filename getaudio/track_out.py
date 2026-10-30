"""曲目对外的响应模型。

单独放一个模块，避免 ``api`` 与 ``library_api`` 互相 import 形成环：
两个路由都要用它，但它本身只依赖 Track 模型。
"""
from __future__ import annotations

import json
from datetime import timezone

from pydantic import BaseModel, Field

from .models import Track


class TrackOut(BaseModel):
    id: int
    platform: str
    video_id: str
    title: str
    uploader: str | None
    duration_sec: float | None
    audio_bytes: int | None
    audio_format: str
    audio_sha256: str | None = None
    source_url: str
    updated_seq: int
    updated_at_ms: int
    deleted: bool
    categories: list[str] = Field(default_factory=list)
    has_vocal: bool | None = None
    classify_stage: str | None = None
    download_url: str

    @classmethod
    def of(cls, t: Track) -> "TrackOut":
        return cls(
            categories=json.loads(t.categories_json or "[]"),
            has_vocal=None if t.has_vocal is None else bool(t.has_vocal),
            classify_stage=t.classify_stage,
            id=t.id,
            platform=t.platform,
            video_id=t.video_id,
            title=t.title,
            uploader=t.uploader,
            duration_sec=t.duration_sec,
            audio_bytes=t.audio_bytes,
            audio_format=t.audio_format,
            audio_sha256=t.audio_sha256,
            source_url=t.source_url,
            updated_seq=t.updated_seq,
            updated_at_ms=int(t.updated_at.replace(tzinfo=timezone.utc).timestamp() * 1000)
                if t.updated_at.tzinfo is None else int(t.updated_at.timestamp() * 1000),
            deleted=bool(t.deleted),
            download_url=f"/audio/{t.id}",
        )
