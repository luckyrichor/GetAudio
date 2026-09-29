# GetAudio

从 B 站 / 抖音分享链接提取音频。Android 手机可直接解析公开链接、下载音轨、保存、播放与导出，不需要 GetAudio 服务器。

**当前可用**：手机端直接提取公开链接；可选服务端提供规则/可选 Ollama 文本分类、Chromaprint 相似曲目候选、持久化定时抓取与同步。

可选服务端是可信内网单机版本：分类依据标题与描述，不是音频听辨；跨平台相似结果仅供核对，不自动合并。平台联网能力受风控影响，具体实测见文末。

## 快速开始

### 服务端

```bash
sudo apt-get install -y ffmpeg
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# 提取一条
.venv/bin/python -m getaudio.cli extract "【标题-哔哩哔哩】 https://www.bilibili.com/video/BVxxxxxxxxxx/"

# 扫码登录（终端直接显示二维码）
.venv/bin/python -m getaudio.cli login --user me --platform bilibili

# 起服务
.venv/bin/uvicorn getaudio.api:app --host 0.0.0.0 --port 8800
```

分享文案整段粘进去就行，不用自己抠链接。

B 站合集链接可带 `?p=N` 提取指定分 P；各分 P 独立入库和去重。省略 `p` 或使用 `p=1` 都对应第一 P。

### Android

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

安装后在抖音或 B 站分享作品到 GetAudio，或在「提取」页粘贴链接。手机直接向原平台请求公开作品信息和音频，音频保存在 App 私有目录，可在「音频库」播放或点导出按钮选择手机文件位置。默认服务器地址为空，无须配置；只有需要旧版服务端音频库同步时才填写可信内网地址。手机端本地提取不需要 Ollama 或 API Key，也不进行 AI 分类。

提取页会显示手机直提取的分步诊断，并可一键复制。遇到 403 时，诊断会区分短链、分享页、访客凭证、作品详情、浏览器重试和媒体下载；复制内容只含步骤、状态码和大小，不含 Cookie、API Key 或完整媒体地址。

抖音优先重复请求公开分享页，让手机自动取得访客 Cookie，并解析页面服务端提供的作品数据。若分享页没有作品数据，才尝试作品详情接口和浏览器重试。提取页始终显示可选的「通过服务器提取」按钮；只有主动点击时才会将分享文本发往设置中的 GetAudio 服务器，并把服务器处理好的音频下载到手机。服务器地址需要事先在设置中填写并保存。提取成功后保留分享文本，方便用另一种方式重试。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/extract` | 提交分享文本，返回音频记录 |
| GET | `/tracks` | 浏览 / 搜索音频库 |
| GET | `/sync?cursor=N` | 增量同步，手机端用 |
| GET | `/audio/{id}` | 下载音频文件 |
| GET | `/login/platforms` | 各平台支持哪种登录方式 |
| POST | `/login/qr` | 发起扫码登录 |
| GET | `/login/qr/{sid}` | 轮询扫码状态 |
| GET | `/login/qr/{sid}/image` | 二维码 PNG |
| POST | `/login/cookies` | 导入浏览器 cookie |
| GET | `/credentials/{user}` | 凭证状态（不含内容） |
| DELETE | `/credentials/{user}/{platform}` | 删除凭证 |

状态码有含义：`400` 链接解析不了，`401` 需要登录（客户端据此弹登录页），`501` 该平台不支持这种登录方式，`502` 平台侧提取失败。

## 目录

```
getaudio/
  sharelink.py       分享文本 → 规范化链接
  guest.py           访客 cookie 预热（B 站 buvid3 / 抖音 ttwid）
  crypto.py          AES-GCM 凭证加密
  credentials.py     凭证存取 + 导出 cookie 文件
  login/             扫码登录（bilibili 可用；douyin 走凭证导入）
  extractors/
    bilibili.py      view + WBI 签名 playurl，只下 DASH 音频轨
    douyin.py        detail 接口，按时长在原声/视频音轨间选路
    remux.py         无损抽轨（能拷贝就不重编码）
    probe.py         ffprobe 实测时长
    ytdlp.py         yt-dlp 通用实现（备选路径）
  classification.py  规则兜底 + Ollama 结构化文本分类
  fingerprint.py     Chromaprint 相似音频候选
  scheduler.py       配置校验、搜索、持久化调度、失败重试
  pipeline.py        编排：解析 → 查重 → 下载 → 分类/指纹 → 打标 → 入库
  api.py / cli.py    HTTP 接口 / 命令行
android/             Kotlin + Compose + Room + Media3 + WorkManager
config/keywords.yaml 定时抓取关键词和链接订阅
```

## 几个不显然的设计决定

### 无损优先：能拷贝就绝不重编码

源音频本来就是有损压缩过的，再转一次 AAC 是第二次有损。所以先探测源编码，再选能直接容纳它的容器（aac→m4a、mp3→mp3、opus→ogg、flac→flac），拷出来的流和源**逐字节一致**。只有拷贝走不通才退回重编码。

实测：某条抖音视频源是 HE-AACv2 64kbps，早期的 192k 重编码版本 5.73 MB，无损拷贝版本 1.89 MB —— 重编码在把文件撑大的同时还损失了音质。

### 两个平台都没用 yt-dlp

- **B 站**：yt-dlp 要先抓视频 HTML 页，而该页在机房 IP 上被风控直接回 `412`。改走开放 API（`view` + WBI 签名的 `playurl`）就通了，还能只下 DASH 音频轨，省九成带宽。
- **抖音**：作品不可见时 yt-dlp 一律报 `Fresh cookies are needed`，掩盖了真实原因。原生实现读 `filter_reason` 翻成人话。

`ytdlp.py` 保留作备选，平台改接口时可临时顶一下。

### 抖音选路：完整性优先于干净

抖音一条视频有两路音频 —— `music.play_url`（原声 BGM，干净）和视频合成流音轨（含人声环境音）。做 BGM 库当然想要前者，但**原声条目常常只是 20 秒左右的片段**。

所以先比时长再下载：原声覆盖不到视频时长的 90% 就判定为片段，改走视频音轨。另外成品时长一律用 `ffprobe` 实测，不信接口字段 —— 接口的 `duration` 描述的是视频，不等于实际取到的音轨。

### 凭证：加密 + AAD 绑定

登录 cookie 等同于账号本身，AES-GCM 加密后存库，正式环境的密钥从环境变量读；未设置时本地开发密钥保存在 `data/.secret_key`（权限 600，已忽略入库）。附加认证数据绑定 `user_id:platform` —— 把 A 的密文挪到 B 名下会直接解密失败，防的是数据库被改写后的串用。

### 可选同步：服务器单向权威

对服务端记录，App 本地保留「已下载路径」和「收藏」，同步时合并而非覆盖；删除走 tombstone。手机直接提取的记录独立保存在本地，不上传到服务端。

## 凭证获取

| 平台 | 方式 | 说明 |
|---|---|---|
| B 站 | **扫码登录** | 完整可用，`login` 命令或 App 登录页 |
| 抖音 | **导入浏览器 cookie** | 扫码接口返回 `error_code 4031`（风控），需要完整 JS 签名，维护成本过高 |

未登录时靠访客预热（B 站 buvid3、抖音 ttwid）也能提取公开内容，只是拿不到会员音质。

## 测试

```bash
.venv/bin/python -m pytest tests/ -q
cd android && ./gradlew testDebugUnitTest assembleDebug
```

包含一组 **API 契约测试**：解析 Kotlin 的 `@Serializable data class`，和服务端 Pydantic 模型逐字段对齐。字段名对不上不会在编译期暴露，只会在用户手机上炸 —— 这组用例把它挡在 CI 里。

测试会自动使用临时数据库、音频目录和独立加密密钥，不会清理真实音频库或凭证。标签回归测试使用 ffmpeg 生成 MP3、FLAC、Opus 和 M4A，验证中文标题、作者、专辑、年份与曲风可重新读取。入库文件大小以写完标签后的实际文件为准。

## 已知限制

- 抖音 Web 接口反爬改得很勤，`douyin.py` 是全项目最脆的一块。手机端遇到作品详情 HTTP 403 会用 Android 浏览器内核重试；若网络或平台继续拒绝，需根据 App 显示的具体步骤排查
- 抖音扫码登录走不通，只能导入 cookie
- SQLite 写事务通过 `BEGIN IMMEDIATE` 串行分配同步游标；当前仅验证 SQLite，切换其他数据库需重新设计与验证提交顺序
- 相同平台/视频自动去重；Chromaprint 可提示跨平台相似候选，但片段剪辑、静音、较大起始偏移不保证识别，不自动删除来源
- Android 构建通过、接口字段契约已校验；尚未做真机/模拟器交互和 Room 迁移实测，不能用编译通过替代实机验收
- 手机端仅处理公开可访问作品；抖音/B 站接口及风控可能变化，登录专属作品不支持。手机抽取视频内 AAC 音轨时无损封装成 M4A，其他视频音频编码暂不支持
- **合规**：扒 B 站 / 抖音音频两边 ToS 都不允许。自用风险不大，做成多用户服务或再分发则不然


## 分类与相似音频

默认使用本地规则，`classify_stage=rules`。服务端可在 Git 忽略的 `.env` 中配置 `ARK_API_KEY` 启用[火山方舟 Chat Completions](https://docs.volcengine.com/docs/ark/chat-api?lang=zh&redirect=1) 分类；`GETAUDIO_ARK_MODELS` 是按先后顺序尝试的模型 ID 列表，默认先 `doubao-seed-2-0-mini-260428`，失败再试 `doubao-seed-2-1-pro-260915`。这两个模型已经用当前账号实际调用验证；[模型目录](https://docs.volcengine.com/docs/ark/model-list?lang=zh)中出现不等于账号已开通。还可设置 `GETAUDIO_OLLAMA_MODEL` 使用本地 Ollama 后备；所有 AI 路径失败时回退规则。只有有效且符合分类 schema 的回答会标记 `ai`，`has_vocal=null` 表示没有足够证据。API Key 不放进 APK，因此手机直提取只做本地保存，不调用方舟分类。

```bash
# 为旧音频补分类（默认最多 100 条）；重算已完成 AI 分类可加 --force
.venv/bin/python -m getaudio.cli classify --limit 100
# 为旧音频补指纹，列出相似候选；不会删除或合并记录
.venv/bin/python -m getaudio.cli duplicates --backfill --limit 500
# 逻辑删除指定条目：手机下次同步接收 tombstone；服务器文件保留
.venv/bin/python -m getaudio.cli delete 123
```

新提取音频会自动分类并计算指纹。指纹使用前 120 秒，缺少 Chromaprint 的 ffmpeg 会跳过并记录日志。Android 显示类别和人声判断，搜索框可搜类别，平台、搜索和收藏条件可组合。v1→v2 数据库升级保留收藏；下载使用文件大小和 SHA-256 校验，源音频变更后重新下载。

## 音频库助手

Android 的「助手」页连接已配置的 GetAudio 服务器。它根据服务器曲库和手机直提取的本地曲目回答推荐问题，不搜索互联网，也不负责提取音频。手机只上传最近最多 100 首本地曲目的标题、作者、平台、时长和分类，不上传音频文件。`ARK_API_KEY` 只保留在服务端：按 `GETAUDIO_ARK_MODELS` 顺序尝试模型，模型不可用时明确退回库内曲目列表。回答引用的曲目 ID 必须属于本次检索结果；模型的描述仍需用户判断，不能当作试听结论。

「我的音乐偏好」由用户手动保存、修改或删除；普通对话不自动变成记忆。修改需要当前 revision，旧版本写入会返回 409；每次变更保留不可覆盖的历史记录。手机端提问、候选曲目元数据和当前偏好会发到服务器上的方舟模型。首次使用新服务端代码时，应用启动会自动创建记忆表。

当前服务器以手机生成的 `user_id` 区分偏好，**它不是登录鉴权**。只应部署在可信任的个人网络环境，不要在偏好里保存密码、Cookie 或 API Key，也不要将 API 直接公开给陌生人。

## 定时抓取

```bash
.venv/bin/python -m getaudio.cli crawl --check           # 只校验 YAML，不触网
.venv/bin/python -m getaudio.cli crawl --once            # 执行到期任务一轮
.venv/bin/python -m getaudio.cli crawl --once --force    # 忽略到期时间
.venv/bin/python -m getaudio.cli crawl                   # 每 30 秒检查到期任务
```

`keywords` 使用 yt-dlp 的 `bilisearch`（只搜索 B 站），`urls` 可填写 B 站或抖音分享链接。`top_n` 是每组每轮的总提取上限；关键词结果交错选取并去重。周期支持 `m/h/d`，可设置 `enabled: false` 暂停某组，`user_id` 指定提取凭证。

```yaml
groups:
  - name: 我的链接
    interval: 12h
    top_n: 5
    urls:
      - https://www.bilibili.com/video/实际BV号/
    enabled: true
```

调度状态写入 `crawl_state`，重启保留进度；部分失败不妨碍其余链接，失败组最多五分钟后重试。同一临时目录上的多个 worker 用文件锁互斥。只支持单机工作目录共享场景，不是分布式调度。使用独立 worker，不在每个 Uvicorn 进程里重复启动。

## 运行与部署

提供 `deploy/` 下的 systemd 模板，默认路径为 `/home/ubuntu/project/GetAudio`、用户为 `ubuntu`。按机器实际情况调整后安装；开发过程没有自动启用常驻抓取，也没有批量下载默认关键词结果。API 模板默认监听回环地址，手机访问需配置可信内网入口。当前 API 没有独立访问鉴权，不应直接暴露到公网。

首次启动会自动新增调度、指纹表，不修改原 tracks 列。备份应包含 SQLite 数据库（使用 SQLite backup 方式取得一致快照）、音频目录和加密密钥；丢失密钥后凭证无法恢复。

## 验证状态（2026-09-28）

- Python 回归覆盖分类输出校验、AI 超时降级、任务周期/重试/互斥、真实音频指纹、并发同步游标和提取失败记录；测试数据与真实库隔离。
- Android `assembleDebug` 和 `testDebugUnitTest` 成功；手机端链接解析有 3 个 JVM 测试。公开链接的真实下载、封装与导出仍待真机验收。
- 当前主机 B 站关键词搜索实测返回 HTTP 412；已实现错误记录与重试，真实搜索成功路径尚待网络/凭证条件允许后验证。抖音关键词搜索未实现，只支持订阅链接提取。
- 未配置 Ollama 模型，真实模型推断尚未验证；已用模拟 HTTP 响应验证请求协议、校验和降级行为。

GitHub Actions 对推送运行 Python 测试和 Android 构建。
