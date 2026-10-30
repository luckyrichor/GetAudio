"""运行期配置。所有路径都相对项目根，方便整目录搬迁。"""
from __future__ import annotations

from pathlib import Path

from pydantic import Field
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

    # 设置模型名才启用 AI；默认本地规则，不产生外部调用。
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = ""
    ark_api_key: str = Field(default="", validation_alias="ARK_API_KEY")
    ark_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    ark_models: str = "doubao-seed-2-0-mini-260428,doubao-seed-2-1-pro-260915"
    classify_timeout: float = 60.0

    # Only server-side credentials; OAuth is disabled until all three are configured.
    douyin_client_key: str = ""
    douyin_client_secret: str = ""
    douyin_redirect_uri: str = ""

    # 博主批量：按发布时间倒序取范围，后台单 worker 顺序抽取音轨。
    creator_max_items: int = 50
    creator_max_start: int = 500
    creator_request_interval: float = 0.15
    creator_download_interval: float = 0.5
    creator_worker_enabled: bool = True
    creator_worker_poll_seconds: float = 2.0

    # 抖音主页受 Argus 风控，允许 Playwright 浏览器桥接兜底。
    douyin_browser: bool = True
    douyin_browser_headless: bool = True
    douyin_browser_timeout: float = 45.0
    douyin_browser_max_scrolls: int = 20
    douyin_browser_channel: str = ""

    def ensure_dirs(self) -> None:
        for d in (self.audio_dir, self.tmp_dir, self.cookie_dir):
            d.mkdir(parents=True, exist_ok=True)


settings = Settings()
settings.ensure_dirs()
