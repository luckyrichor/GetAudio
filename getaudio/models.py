"""数据模型。

P0 只需要三张表：音频、任务、用户凭证。分类标签先留字段不填，
等 P2 的 AI 分类上来直接用。
"""
from __future__ import annotations

import enum
from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class JobStatus(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    DONE = "done"
    FAILED = "failed"


class Track(Base):
    """一条已落盘的音频。"""

    __tablename__ = "tracks"
    __table_args__ = (
        # 同一平台同一视频只存一份 —— 去重的第一道闸
        UniqueConstraint("platform", "video_id", name="uq_track_platform_video"),
        Index("ix_track_updated_seq", "updated_seq"),
        Index("ix_track_audio_sha256", "audio_sha256"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)

    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    video_id: Mapped[str] = mapped_column(String(64), nullable=False)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)

    title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    uploader: Mapped[str | None] = mapped_column(Text)
    description: Mapped[str | None] = mapped_column(Text)
    thumbnail_url: Mapped[str | None] = mapped_column(Text)
    upload_date: Mapped[str | None] = mapped_column(String(16))
    duration_sec: Mapped[float | None] = mapped_column(Float)

    # 相对 settings.audio_dir 的路径，整目录搬走也不会失效
    audio_path: Mapped[str] = mapped_column(Text, nullable=False)
    audio_format: Mapped[str] = mapped_column(String(16), nullable=False, default="m4a")
    audio_bytes: Mapped[int | None] = mapped_column(BigInteger)
    # 内容哈希：跨平台同一首歌的去重依据（P2 会再加音频指纹）
    audio_sha256: Mapped[str | None] = mapped_column(String(64))

    # --- 以下留给 P2 的 AI 分类 ---
    categories_json: Mapped[str | None] = mapped_column(Text)
    has_vocal: Mapped[int | None] = mapped_column(Integer)
    classify_stage: Mapped[str | None] = mapped_column(String(16))

    # 同步游标：手机端靠它拉增量
    updated_seq: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    deleted: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    jobs: Mapped[list["ExtractJob"]] = relationship(back_populates="track")


class ExtractJob(Base):
    """一次提取请求。失败原因留在这里，便于排查平台接口变动。"""

    __tablename__ = "extract_jobs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[str | None] = mapped_column(String(64))

    raw_input: Mapped[str] = mapped_column(Text, nullable=False)
    platform: Mapped[str | None] = mapped_column(String(32))
    video_id: Mapped[str | None] = mapped_column(String(64))
    resolved_url: Mapped[str | None] = mapped_column(Text)

    status: Mapped[JobStatus] = mapped_column(Enum(JobStatus), nullable=False, default=JobStatus.PENDING)
    error: Mapped[str | None] = mapped_column(Text)

    track_id: Mapped[int | None] = mapped_column(ForeignKey("tracks.id"))
    track: Mapped[Track | None] = relationship(back_populates="jobs")

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class PlatformCredential(Base):
    """每用户、每平台一份登录凭证。

    cookie 内容加密后存 cookie_blob；扫码登录（P1）写这张表。
    """

    __tablename__ = "platform_credentials"
    __table_args__ = (UniqueConstraint("user_id", "platform", name="uq_cred_user_platform"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[str] = mapped_column(String(64), nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)

    cookie_blob: Mapped[bytes | None] = mapped_column()
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="active")
    last_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class CrawlState(Base):
    """关键词组的持久化调度状态，重启不丢轮询进度。"""

    __tablename__ = "crawl_state"
    name: Mapped[str] = mapped_column(String(128), primary_key=True)
    next_run: Mapped[float] = mapped_column(Float, nullable=False, default=0)
    last_run: Mapped[float | None] = mapped_column(Float)
    summary_json: Mapped[str | None] = mapped_column(Text)


class AudioFingerprint(Base):
    """Chromaprint 指纹独立建表，不改写已有 tracks 表。"""

    __tablename__ = "audio_fingerprints"
    track_id: Mapped[int] = mapped_column(ForeignKey("tracks.id"), primary_key=True)
    values_json: Mapped[str] = mapped_column(Text, nullable=False)
    duration_sec: Mapped[float] = mapped_column(Float, nullable=False)


class AgentMemory(Base):
    """用户明确保存的音乐偏好；不从普通对话自动推断。"""

    __tablename__ = "agent_memories"
    __table_args__ = (Index("ix_agent_memories_user", "user_id"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[str] = mapped_column(String(64), nullable=False)
    content: Mapped[str] = mapped_column(String(500), nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    deleted: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class AgentMemoryVersion(Base):
    """偏好的不可覆盖历史；删除也写一条版本记录。"""

    __tablename__ = "agent_memory_versions"
    __table_args__ = (UniqueConstraint("memory_id", "revision", name="uq_agent_memory_revision"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    memory_id: Mapped[int] = mapped_column(ForeignKey("agent_memories.id"), nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    action: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
