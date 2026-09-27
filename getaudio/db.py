"""数据库 session 管理。"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

from sqlalchemy import create_engine, event, func, select
from sqlalchemy.orm import Session, sessionmaker

from .config import settings
from .models import Base, Track

_is_sqlite = settings.database_url.startswith("sqlite")

engine = create_engine(
    settings.database_url,
    future=True,
    connect_args={"check_same_thread": False} if _is_sqlite else {},
)

if _is_sqlite:
    @event.listens_for(engine, "connect")
    def _sqlite_pragmas(dbapi_conn, _record):  # pragma: no cover
        cur = dbapi_conn.cursor()
        # WAL：下载 worker 写库时不阻塞同步接口的读
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

SessionLocal = sessionmaker(bind=engine, expire_on_commit=False, future=True)


def init_db() -> None:
    Base.metadata.create_all(engine)


@contextmanager
def session_scope() -> Iterator[Session]:
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def next_updated_seq(session: Session) -> int:
    """分配下一个同步游标值。

    单调递增即可，不要求连续。SQLite 下靠 max+1；换 Postgres 时
    应改成 sequence，避免并发下取到同值。
    """
    current = session.execute(select(func.max(Track.updated_seq))).scalar()
    return (current or 0) + 1
