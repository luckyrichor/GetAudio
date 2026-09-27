"""HTTP 接口。

手机端只跟这一层打交道：
    POST /extract    提交分享文本，返回音频记录
    GET  /tracks     浏览音频库
    GET  /sync       增量同步（手机端按 cursor 拉）
    GET  /audio/{id} 下载音频文件
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import credentials, login
from .config import settings
from .db import SessionLocal, init_db
from .extractors import ExtractorError, NeedsLoginError
from .models import Track
from .pipeline import extract as run_extract
from .sharelink import LinkError

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    settings.ensure_dirs()
    yield


app = FastAPI(title="GetAudio", version="0.1.0", lifespan=lifespan)


def get_session():
    s = SessionLocal()
    try:
        yield s
    finally:
        s.close()


# ---------- schema ----------


class ExtractRequest(BaseModel):
    text: str = Field(..., description="分享文本或链接，可以带平台的那堆废话")
    user_id: str | None = Field(None, description="用哪个用户的登录凭证")
    force: bool = Field(False, description="忽略去重，强制重新下载")


class TrackOut(BaseModel):
    id: int
    platform: str
    video_id: str
    title: str
    uploader: str | None
    duration_sec: float | None
    audio_bytes: int | None
    audio_format: str
    source_url: str
    updated_seq: int
    deleted: bool
    download_url: str

    @classmethod
    def of(cls, t: Track) -> "TrackOut":
        return cls(
            id=t.id,
            platform=t.platform,
            video_id=t.video_id,
            title=t.title,
            uploader=t.uploader,
            duration_sec=t.duration_sec,
            audio_bytes=t.audio_bytes,
            audio_format=t.audio_format,
            source_url=t.source_url,
            updated_seq=t.updated_seq,
            deleted=bool(t.deleted),
            download_url=f"/audio/{t.id}",
        )


class ExtractResponse(BaseModel):
    track: TrackOut
    reused: bool


class LoginStartRequest(BaseModel):
    user_id: str = Field(..., description="谁在登录")
    platform: str = Field(..., description="bilibili | douyin")


class LoginStartResponse(BaseModel):
    session_id: str
    platform: str
    qr_url: str = Field(..., description="把这段内容渲染成二维码让用户扫")
    qr_image_url: str = Field(..., description="服务端直接渲染好的 PNG，省得客户端自己画")
    expires_in: int


class LoginPollResponse(BaseModel):
    session_id: str
    platform: str
    state: str = Field(..., description="waiting | scanned | confirmed | expired | failed")
    message: str


class CookieImportRequest(BaseModel):
    user_id: str
    platform: str
    cookies: str = Field(..., description="浏览器里复制的 `a=1; b=2` 形式")


class CredentialOut(BaseModel):
    platform: str
    status: str
    last_checked_at: str | None


class SyncResponse(BaseModel):
    items: list[TrackOut]
    next_cursor: int
    has_more: bool


# ---------- 路由 ----------


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/extract", response_model=ExtractResponse)
def api_extract(req: ExtractRequest, session: Session = Depends(get_session)) -> ExtractResponse:
    try:
        outcome = run_extract(req.text, user_id=req.user_id, force=req.force)
    except LinkError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except NeedsLoginError as exc:
        # 401 而不是 400：手机端据此弹扫码登录
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    except ExtractorError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    track = session.get(Track, outcome.track_id)
    if track is None:  # pragma: no cover - 理论上不会发生
        raise HTTPException(status_code=500, detail="提取成功但记录丢失")
    return ExtractResponse(track=TrackOut.of(track), reused=outcome.reused)


@app.get("/tracks", response_model=list[TrackOut])
def api_tracks(
    q: str | None = Query(None, description="按标题模糊搜索"),
    platform: str | None = Query(None),
    limit: int = Query(50, le=200),
    offset: int = Query(0, ge=0),
    session: Session = Depends(get_session),
) -> list[TrackOut]:
    stmt = select(Track).where(Track.deleted == 0)
    if q:
        stmt = stmt.where(Track.title.contains(q))
    if platform:
        stmt = stmt.where(Track.platform == platform)
    stmt = stmt.order_by(Track.updated_seq.desc()).limit(limit).offset(offset)
    return [TrackOut.of(t) for t in session.execute(stmt).scalars()]


@app.get("/sync", response_model=SyncResponse)
def api_sync(
    cursor: int = Query(0, ge=0, description="上次同步到的 updated_seq"),
    limit: int = Query(200, le=500),
    session: Session = Depends(get_session),
) -> SyncResponse:
    """增量同步。

    含已删除记录（tombstone），手机端据此删本地文件 —— 所以这里
    不能过滤 deleted。
    """
    stmt = (
        select(Track)
        .where(Track.updated_seq > cursor)
        .order_by(Track.updated_seq.asc())
        .limit(limit + 1)
    )
    rows = list(session.execute(stmt).scalars())
    has_more = len(rows) > limit
    rows = rows[:limit]
    next_cursor = rows[-1].updated_seq if rows else cursor
    return SyncResponse(items=[TrackOut.of(t) for t in rows], next_cursor=next_cursor, has_more=has_more)


@app.get("/audio/{track_id}")
def api_audio(track_id: int, session: Session = Depends(get_session)) -> FileResponse:
    track = session.get(Track, track_id)
    if track is None or track.deleted:
        raise HTTPException(status_code=404, detail="音频不存在")
    path = settings.audio_dir / track.audio_path
    if not path.exists():
        raise HTTPException(status_code=410, detail="记录还在但文件已丢失")
    return FileResponse(
        path,
        media_type="audio/mp4",
        # 中文文件名交给 starlette 处理 RFC 5987 编码
        filename=f"{track.title[:60]}.{track.audio_format}",
    )


# ---------- 登录 ----------


@app.get("/login/platforms")
def api_login_platforms() -> dict:
    """哪些平台能扫码，哪些只能导入凭证。"""
    return {
        "platforms": login.supported_platforms(),
        "notes": {
            "bilibili": "支持扫码登录",
            "douyin": f"扫码接口被平台风控拦截，请用凭证导入。{login.how_to_export()}",
        },
    }


@app.post("/login/qr", response_model=LoginStartResponse)
def api_login_start(req: LoginStartRequest) -> LoginStartResponse:
    try:
        session = login.start(req.user_id, req.platform)
    except login.LoginNotSupported as exc:
        # 501 而不是 400：不是请求错了，是这条路暂时走不通
        raise HTTPException(status_code=501, detail=str(exc)) from exc
    except login.LoginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return LoginStartResponse(
        session_id=session.session_id,
        platform=session.platform,
        qr_url=session.qr_url,
        qr_image_url=f"/login/qr/{session.session_id}/image",
        expires_in=session.expires_in,
    )


@app.get("/login/qr/{session_id}", response_model=LoginPollResponse)
def api_login_poll(session_id: str) -> LoginPollResponse:
    try:
        session = login.poll(session_id)
    except login.LoginError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return LoginPollResponse(
        session_id=session.session_id,
        platform=session.platform,
        state=session.state.value,
        message=session.message,
    )


@app.get("/login/qr/{session_id}/image")
def api_login_qr_image(session_id: str) -> Response:
    """把二维码内容渲染成 PNG。

    服务端画好省得每个客户端各装一个二维码库，Android 端直接当图片加载。
    """
    try:
        session = login.poll(session_id)
    except login.LoginError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    import io

    import qrcode

    buf = io.BytesIO()
    qrcode.make(session.qr_url).save(buf, format="PNG")
    return Response(
        content=buf.getvalue(),
        media_type="image/png",
        # 二维码三分钟就废，不要让中间层缓存
        headers={"Cache-Control": "no-store"},
    )


@app.post("/login/cookies")
def api_login_import(req: CookieImportRequest) -> dict:
    try:
        return login.import_cookies(req.user_id, req.platform, req.cookies)
    except login.LoginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/credentials/{user_id}", response_model=list[CredentialOut])
def api_credentials(user_id: str) -> list[CredentialOut]:
    """只回状态，绝不回凭证内容。"""
    return [CredentialOut(**c) for c in credentials.list_for(user_id)]


@app.delete("/credentials/{user_id}/{platform}")
def api_credential_revoke(user_id: str, platform: str) -> dict:
    if not credentials.revoke(user_id, platform):
        raise HTTPException(status_code=404, detail="没有这份凭证")
    return {"ok": True}
