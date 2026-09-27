"""运行期配置。所有路径都相对项目根，方便整目录搬迁。"""
from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GETAUDIO_", env_file=str(ROOT / ".env"), extra="ignore")

    # P0 先用 SQLite：换 Postgres 只要改这一行 URL，ORM 层不动
    database_url: str = f"sqlite:///{ROOT / 'data' / 'getaudio.db'}"

    audio_dir: Path = ROOT / "data" / "audio"
    tmp_dir: Path = ROOT / "data" / "tmp"
    cookie_dir: Path = ROOT / "data" / "cookies"

    # 目标音频格式。m4a/aac 兼容性最好，Android 原生解码
    audio_format: str = "m4a"
    audio_quality: str = "0"  # yt-dlp: 0 = 保留源码率

    http_timeout: float = 20.0
    max_redirects: int = 10

    def ensure_dirs(self) -> None:
        for d in (self.audio_dir, self.tmp_dir, self.cookie_dir):
            d.mkdir(parents=True, exist_ok=True)


settings = Settings()
settings.ensure_dirs()
