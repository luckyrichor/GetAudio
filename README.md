# GetAudio

从 B 站 / 抖音提取音频，AI 打标分类，服务器持续更新，Android 端定期同步。

**进度**：P0（链接提取）、P1（登录与凭证）、P4 骨架（Android App）已完成。P2/P3（AI 分类、定时抓取）待做。

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

### Android

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

装好后在「设置」里把服务器地址改成你的内网地址（默认 `10.0.2.2:8800` 是模拟器访问宿主机的地址）。

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
  pipeline.py        编排：解析 → 查重 → 下载 → 打标 → 入库
  api.py / cli.py    HTTP 接口 / 命令行
android/             Kotlin + Compose + Room + Media3 + WorkManager
config/keywords.yaml 定时抓取关键词表（P3）
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

登录 cookie 等同于账号本身，AES-GCM 加密后存库，密钥只从环境变量读。附加认证数据绑定 `user_id:platform` —— 把 A 的密文挪到 B 名下会直接解密失败，防的是数据库被改写后的串用。

### 同步：服务器单向权威

App 本地只保留「已下载路径」和「收藏」两个本地状态，同步时合并而非覆盖。删除走 tombstone。双向同步在这个场景没必要，省一大堆复杂度。

## 凭证获取

| 平台 | 方式 | 说明 |
|---|---|---|
| B 站 | **扫码登录** | 完整可用，`login` 命令或 App 登录页 |
| 抖音 | **导入浏览器 cookie** | 扫码接口返回 `error_code 4031`（风控），需要完整 JS 签名，维护成本过高 |

未登录时靠访客预热（B 站 buvid3、抖音 ttwid）也能提取公开内容，只是拿不到会员音质。

## 测试

```bash
.venv/bin/python -m pytest tests/ -q     # 79 个
```

包含一组 **API 契约测试**：解析 Kotlin 的 `@Serializable data class`，和服务端 Pydantic 模型逐字段对齐。字段名对不上不会在编译期暴露，只会在用户手机上炸 —— 这组用例把它挡在 CI 里。

测试会自动使用临时数据库、音频目录和独立加密密钥，不会清理真实音频库或凭证。标签回归测试使用 ffmpeg 生成 MP3、FLAC、Opus 和 M4A，验证中文标题、作者、专辑、年份与曲风可重新读取。入库文件大小以写完标签后的实际文件为准。

## 已知限制

- 抖音 Web 接口反爬改得很勤，`douyin.py` 是全项目最脆的一块。坏掉时先看 detail 接口返回的 `filter_reason`
- 抖音扫码登录走不通，只能导入 cookie
- SQLite 的同步游标用 `max+1` 分配，并发写有取到同值的风险；换 Postgres 时要改成 sequence
- 跨平台的同一首歌目前去不了重（只按 `platform + video_id`），音频指纹在 P2
- Android 端未在真机/模拟器上跑过 —— 构建通过、接口契约已校验，但交互路径需要实机验证
- **合规**：扒 B 站 / 抖音音频两边 ToS 都不允许。自用风险不大，做成多用户服务或再分发则不然
