# GetAudio 工作约定

## 更新交付（用户于 2026-10-02 明确要求）

每次功能或修复完成并通过相关验证后，完成以下流程：

1. 更新 README 和相关本地技术文档，注明实现行为、测试结果及尚未验收内容。
2. 将本次修改提交到 Git，并推送 GitHub；不强制推送，不包含凭据、数据库、音频或构建产物。
3. 将本次更新同步到飞书 GetAudio 项目文档，优先复用对应主题；新主题归档到已有 GetAudio 项目节点。
4. 交付 GitHub 提交链接和飞书文档链接，说明验证范围。

用户已授权该例行同步流程。每次执行前说明飞书标题、范围、位置和使用身份；相同项目及例行更新范围内无需重复索要批准。变更发布范围、身份或目标项目时另行确认。读取文档无需批准。

## 飞书身份、来源与归档

- 使用当前 bridge-bound Codex 环境，luochen 用户身份显式传 `--as user`；执行前核对应用、profile、用户和授权，不混用 Claude 配置。
- 已有 GetAudio 项目节点：https://kcn0pbn0hwo0.feishu.cn/wiki/OFRjw8Ud9ihsT7kFuxPcgS11nGd
- 项目节点 token：`OFRjw8Ud9ihsT7kFuxPcgS11nGd`；space ID：`7526908319489736708`。写入前核对实际节点，不散放根目录。
- 0.5.0 账号/Agent 文档：https://kcn0pbn0hwo0.feishu.cn/wiki/LFIYwAKrqieCUmkbtFzcMyvfnZf
- 0.6.0 播放进度/波形剪辑文档：https://kcn0pbn0hwo0.feishu.cn/wiki/GhHuwjHeXiuMI6kT4iscQXxrnSe
- 0.8.0 手机号主账号/平台绑定登录：https://kcn0pbn0hwo0.feishu.cn/wiki/GDJVwJgVhiE77rkGX7EcqJ7anZd
- 历史技术方案 v2：https://kcn0pbn0hwo0.feishu.cn/wiki/GYUmw4foMikGGTkuyXAcHOgwn4f
- 新文档正文第一行：`🤖 由 Codex 生成 · YYYY-MM-DD · 来源 GetAudio@实际短哈希 · 工具 lark-cli profile 实际profile`。日期用北京时间；提交及 profile 必须核实，未提交修改必须注明。
- 修改已有文档保留创建者与原来源，并追加本次助手、日期、版本和更新范围；不得将 Claude 原作改署为 Codex。
- 测试数量、耗时和结论只对应实际验证的版本。构建通过不等于真机验收。
- 写入后回查内容和父节点，交付所属目录与链接。

## 项目交接（当前维护：Codex，2026-10-28）

### 项目概述与边界

GetAudio 是 Android 音频提取和本地播放工具，目标是把 B 站/抖音公开分享链接保存为可管理、可离线播放的音频。默认在手机直提取；可选 tx 服务端负责提取、账号/平台授权、单向同步、文本分类、指纹及提取 Agent。手机最终使用不依赖 Mac/USB。只处理有权访问的作品，不保证私密、付费、删除或受平台风控限制的内容可用。分类主要依赖标题/描述，不是音频听辨。

### 技术栈与环境

- Android：Kotlin 1.9.23、AGP 8.3.2、Gradle 8.7、JDK 17、Compose、Room、Media3、WorkManager；minSdk 26，compile/targetSdk 34。准确依赖见 `android/app/build.gradle.kts`。
- 服务端：Python（Mac 本轮借用 GetVideo 的 Python 3.12 venv 运行测试）、FastAPI、Pydantic、SQLAlchemy/SQLite、HTTPX、FFmpeg/ffprobe；准确版本见 `requirements.txt`。生产以 tx 的 `.venv` 和 FFmpeg 为准。
- 配置见 `getaudio/config.py`：`GETAUDIO_DATABASE_URL`、`GETAUDIO_AUDIO_DIR`、`GETAUDIO_TMP_DIR`、`GETAUDIO_COOKIE_DIR`、`GETAUDIO_SECRET_KEY`；可选 `ARK_API_KEY`、`GETAUDIO_ARK_MODELS`、`GETAUDIO_OLLAMA_MODEL`、`GETAUDIO_DOUYIN_CLIENT_KEY` / `CLIENT_SECRET` / `REDIRECT_URI`。不写入任何真实密钥。
- 手机正式包 `com.getaudio.app`。安装前重新检查当前版本/签名，不卸载、不换包名；不同包名不共享凭据。

### 可复制的开发命令

```bash
# 服务端（Python 虚拟环境与 FFmpeg 需已就绪）
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -q
.venv/bin/uvicorn getaudio.api:app --host 127.0.0.1 --port 8800

# Mac Android（Gradle wrapper 会下载 8.7）
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ANDROID_HOME=/Users/lc/Library/Android/sdk \
sh android/gradlew -p android testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain
# 依赖已缓存时可加 --offline；临时解压的 Gradle 目录会被系统清理，不作永久依赖。
/Users/lc/Library/Android/sdk/platform-tools/adb devices -l
/Users/lc/Library/Android/sdk/platform-tools/adb shell dumpsys package com.getaudio.app
```

生产服务在 `ssh tx` 的 `/home/ubuntu/project/GetAudio`，systemd 名称 `getaudio-api.service`。先核对 `hostname`、Git 状态、服务 WorkingDirectory，再部署。远程代码更新仅 fast-forward，数据库先备份，运行隔离测试后重启并核对 health/匿名鉴权。避免在 Mac 启动替代生产服务或把 localhost 写入手机正式设置。

### 关键目录与数据流

- `android/.../data/localextract/DirectExtractor.kt`：手机解析分享→平台元数据→音乐/视频候选→下载→无损抽轨→原子保存；`DouyinSharePageParser.kt`、`DouyinNoteAudio.kt` 处理公开分享页及图文配乐。
- `android/.../data/repo/TrackRepository.kt`：音频入库、下载、删除与同步；`AccountStore.kt` / `ApiProvider.kt` 管理加密账号会话和按来源发送凭据。
- `android/.../player/PlayerHolder.kt` / `PlaybackService.kt`：唯一 Media3 会话；`PlaybackMemory.kt` / `PlaybackSnapshot.kt` 保存退出恢复状态；`QueueReorder.kt` 计算不丢成员的队列移动。
- `android/.../ui/screen/QueueBar.kt` / `ReorderableQueue.kt`：底部播放与长按拖拽；`MainActivity.kt` 启动恢复。
- `android/.../data/repo/FailureOutbox.kt` / `sync/FailureReportWorker.kt`：手机直提取失败时，分享文本+脱敏日志原子暂存→按账号/服务器筛选→HTTPS 鉴权上报→收到匹配 UUID 的 ACK 后删除。登录、启动、服务器切换会重新调度。
- `getaudio/api.py` / `accounts.py` / `account_api.py` / `platform_accounts.py`：HTTP 和账号/平台授权；`models.py` / `db.py` 为数据库模型、事务和迁移。
- `getaudio/failure_reports.py`：失败报告入库及账号隔离查询；`extraction_failures` 以 `(owner_id, report_id)` 去重。也提供 SSH 管理员只读 CLI。
- `getaudio/pipeline.py` / `extractors/` / `login/`：服务器提取、凭据、抽轨；`classification.py` / `fingerprint.py` / `extraction_agent.py` 为可选增强。
- `tests/`、`android/app/src/test/`、`android/app/src/androidTest/` 分别是服务端、JVM 和真机测试；`docs/` 为详细实现/验收边界；`deploy/tx-https/` 为历史部署说明。

### 关键决策与已知坑

- 音频元数据和文件是主体，播放列表保存引用；删除时清除所有引用。排序只更新引用次序，并调用 Media3 `moveMediaItem`，不重新加载正在播放的文件。
- 库页顺序由 `tracks.sortIndex` 决定（0 = 未手动排过，排在已排序项之前；查询 `ORDER BY sortIndex ASC, addedAtMs DESC, id DESC`）。拖动后全库重编号 1..N，但只替换**可见槽位**，筛选状态下不会打乱被筛掉的条目；只有「手动」档允许拖动，字段排序时自动禁用，否则松手即被排序覆盖。见 `docs/library-sort-and-editor-0.9.x.md`。
- 冷启动恢复上次列表、歌曲、位置、播放方式，默认暂停；已有效连接的播放会话不重置。Room 顺序是权威，按歌曲 ID 定位，旧版本未保存的位置无法追溯。见 `docs/playback-resume.md`。
- B 站播放地址普通 HTTP 返回 412 时才启用受限 WebView 备用请求；保留 `p=N` 分 P。只确认两种请求上下文结果不同，不能臆测具体 IP/TLS/风控算法。
- 抖音图文 `aweme_type=2` 可能没有 `music.play_url`，真实 MP3 放在 `video.play_addr.uri`；包装 `url_list` 可能不可用。0.8.7 允许经验证的 `/obj/ies-music/` 和 `/obj/ies-music-hj/`，仍限制 HTTPS/443、平台 CDN、无用户信息和 MP3，不能随意扩大到任意 URI。
- 失败上报只发送分享文本和脱敏日志，不发送 Cookie、密码、会话、音视频文件。上传不跟随重定向。服务端按账号鉴权；游客暂存，首次实际上传前绑定账号，之后不串传给其他账号。
- 必须先筛选可上传账号/服务器再限制每批数量；旧账号积压不能堵塞新账号。失败记录轮转重试，不按重试次数重复插入服务器。
- Mac 的 FFmpeg 缺少 Chromaprint：`test_real_fingerprint_ignores_metadata` 本机已知失败，用户明确无需为仅开发用途修复；不要把它误报为音频提取失败。生产 FFmpeg 是否可用应现场验证。
- USB/MTP/ADB 是不同层：USB 已枚举、ADB 为空时先用 `ADB_LIBUSB=1` 启动 ADB server；2026-10-26 本轮需要在沙箱外启动才恢复。保持 DroidMac，不能据空列表断言未连接或让用户退出 DroidMac。仅 `unauthorized` 才需授权。
- 0.8.6 手机为非 debuggable 包，`run-as` 被拒绝是预期，不能当连接失败；通过页面/导出诊断或服务器验证。切勿读取或打印账号加密文件/凭据。

### 当前进度、下一步与验证

- 已交付：0.8.2 B 站 412/分 P、0.8.3 抖音图文音乐、0.8.4 拖拽、0.8.5 退出恢复（`c9a3a29` 真机恢复 18 首及 01:06）、0.8.6 授权入口崩溃修复（`7bcd659`）、0.8.7 图文 `ies-music-hj` 与失败自动上报（`f72a73e`）、0.9.0 批量提取/自定义背景/导入本地音频/412 加固（`7840de1`）、0.9.1–0.9.4 库页排序与剪辑页重构（`707577d`、`b982a85`、`cd1f82b`、`23ed6c9`）。当前 HEAD `23ed6c9` / v0.9.4 / versionCode 32，`origin/main` 同步。
- 本轮（2026-10-28）仅补文档：新增 `docs/library-sort-and-editor-0.9.x.md`，README 当前版本与 0.9.x 章节，AGENTS 交接与更新日志；删除误入库的编辑器临时文件。未改功能代码。
- 验证（本地实测）：Android `testDebugUnitTest --rerun-tasks` 75 项通过、`lintDebug` 0 errors / 29 warnings；服务端 `163 passed, 1 failed`，失败项为 `test_real_fingerprint_ignores_metadata`（本机 FFmpeg 缺 Chromaprint，已知限制）。本轮未部署 tx、未做真机验收。
- 真机结果（0.8.7 时期，仍是最近一次）：原图文已入库为「现在开始跳动」（作者下雨天气晴）；受控无效文本失败自动到达 tx，报告 `1a2ec0a1-80bc-45f4-bef7-2236446f71de` 已通过 SSH CLI 回查，包含版本、时间、分享文本和错误步骤。
- 进行中：无遗留代码实施；下一步仍是按专题文档做真机验收，先补 0.9.x 库页拖动/排序与剪辑页三键，再补断网/后台强杀/跨账号长时间积压的注入验收，不把已实现保护当作已全量实测。
- 待验收：0.9.x 长按拖动排序（含筛选状态下只重排可见槽位）、排序字段切换后的顺序保持、剪辑页三键听感与波形缓存二次进入、卡片不透明度 12%/100% 极值可读性、Room 8→9 真机升级；早期波形剪辑编解码与平台扫码完成/私密内容仍需逐项验收。
- 未开始：不额外承诺新平台、后台批量提取或服务端音频听辨；后续需求由用户决定。后续平台路径/权限变化先抓本次证据，再修复，不能凭旧报错直接套方案。

### 飞书归档与本轮同步

已有项目目录：<https://kcn0pbn0hwo0.feishu.cn/wiki/OFRjw8Ud9ihsT7kFuxPcgS11nGd>。写入前必须现场 `wiki +node-list`，本轮先列出现有 5 篇主题文档，再创建下述 2 篇；写后已回查两篇均位于同一 GetAudio 父节点。

使用 `LARKSUITE_CLI_CONFIG_DIR=/Users/lc/.lark-channel/profiles/codex/lark-cli/lark-channel`，已核对 `luochen --as user`、实际 app/profile `cli_aa03e21d4939dbdb`；不混用 Claude 或旧 app 凭据。

- 项目概览：<https://kcn0pbn0hwo0.feishu.cn/wiki/KuYawq8kvihfeKkq4vXcS0Vtn1d>。
- 开发记录（以后持续追加）：<https://kcn0pbn0hwo0.feishu.cn/wiki/LRmyw4wfhiBcwIkyz83cQ4IOnUm>。
- 0.9.0–0.9.4 主题：<https://kcn0pbn0hwo0.feishu.cn/wiki/Dc5hwuLCsisTibkNXnzc57a8nqV>（docx `QE7QdPpI5oFY27xocK0cKN4znbo`，父节点已回查为 GetAudio 项目节点）。
- 历史技术方案、账号与剪辑专题链接见本文前部「飞书身份、来源与归档」。

本轮已同步：0.9.x 文档补齐、本地测试数字、未验收边界与 GitHub 链接；概览「当前进度」已更新到 0.9.4，开发记录追加 2026-10-28 一节，新主题在 GetAudio 节点下，三篇正文均已回查。

### 更新日志（客户端日期）

- 2026-10-28：完成 0.9.1–0.9.4 文档补齐。核对 v0.9.0..v0.9.4 源码确认库页 `sortIndex`（Room 8→9）、可见槽位重排、五档排序字段与两段式控件、剪辑页三键与 `WaveformCache`、队列双通道拖动、四个子页 `BackHandler`、外观页精简；重跑 Android 75 项与 Lint、服务端 163 项取证；删除误入库的 `docs/*.mdxr7gPl` 临时文件。未改功能代码。

- 2026-10-27：额度中断后继续，重新核对手机0.8.7、tx服务active及飞书记录/目录；补齐最终验收状态、概览和开发记录链接。仅文档收尾，未改功能代码，未重跑已通过的测试。

- 2026-10-26：定位当前图文失败为真实 MP3 位于新增 bucket；补兼容和测试。实现失败报告私有暂存、鉴权上传、去重留存与只读查询；补齐 AGENTS 唯一正文，新增 CLAUDE 引用。75 项 Android / 164 项 tx 测试通过，手机入库与服务器上报已验证，GitHub、tx 与飞书交付完成。

## Android 发布签名（强制）

- `release` 包**必须**用 `~/.android-keys/getaudio.properties` 指向的固定证书签名，证书 SHA-256 `9DA16F85…443F50C5`（`CN=GetAudio`）。
- 换机器 / 换 Agent 环境首次构建前先跑 `scripts/setup-release-key.sh`（从 `tx:~/.app-keys/` 拉取）。
- `build.gradle.kts` 找不到密钥会**直接构建失败**，这是刻意设计：绝不产出签名不同的 release APK（装了会撞 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。仅在明确不发布时用 `-PallowDebugSignRelease=true` 绕过。
- 密钥文件（`*.jks` / `*.keystore` / `android/keystore.properties`）**绝不入库**。
- 详见 `docs/android-release-signing.md`。
