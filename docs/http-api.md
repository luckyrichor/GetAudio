# GetAudio 服务端 HTTP API

面向手机 App 与 curl。除注册/登录/健康检查外，所有接口都要带：

```
X-GetAudio-Session: <session_token>
```

服务端默认监听 `http://127.0.0.1:8800`，生产走 HTTPS；下面的 `$BASE` 按环境替换。

```bash
export BASE=http://127.0.0.1:8800
```

## 1. 注册 / 登录

```bash
# 注册（手机号 11 位，密码至少 8 位）
curl -s $BASE/account/register \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","password":"test-password-123"}'

# 登录
curl -s $BASE/account/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","password":"test-password-123"}'

# 两者都返回：{"user_id":"acct_...","phone":"...","session_token":"...","expires_at":"..."}
export TOKEN='把 session_token 粘这里'
export AUTH="X-GetAudio-Session: $TOKEN"
```

```bash
curl -s $BASE/account/me -H "$AUTH"          # 当前账号
curl -s -X POST $BASE/account/logout -H "$AUTH"
```

## 2. 提取与音频库

```bash
# 分享文本 / 视频链接 → 下载音频并入库
curl -s -X POST $BASE/v2/extract -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"text":"https://www.douyin.com/video/7300000000000000000","force":false}'

# 浏览音频库（q/platform/limit/offset）
curl -s "$BASE/v2/tracks?q=钢琴&platform=douyin&limit=20" -H "$AUTH"

# 增量同步（手机端按 cursor 拉，含 deleted tombstone）
curl -s "$BASE/v2/sync?cursor=0&limit=200" -H "$AUTH"

# 下载音频文件（这就是音频下载路径，不是 /v2/video/{id}）
curl -s -L "$BASE/v2/audio/123" -H "$AUTH" -o track.m4a
```

## 3. 只解析、不下载

```bash
curl -s -X POST $BASE/v2/resolve -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"text":"https://www.douyin.com/video/7300000000000000000"}'
# → {"platform":"douyin","video_id":"730...","track_key":"douyin:730...","url":"...","page":1}
```

单条视频链接只解析不下载；批量下载请走下面的博主接口。

## 4. 曲目管理

```bash
curl -s $BASE/v2/tracks/123 -H "$AUTH"

curl -s -X PATCH $BASE/v2/tracks/123 -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"title":"新标题","uploader":"作者","description":"简介","categories":["钢琴"],"has_vocal":false}'

# 逻辑删除：保留服务器文件，同步时下发 tombstone
curl -s -X DELETE $BASE/v2/tracks/123 -H "$AUTH"

curl -s -X POST $BASE/v2/tracks/123/restore -H "$AUTH"

# 最近的提取任务（失败原因在这里）
curl -s "$BASE/v2/extraction-jobs?limit=20" -H "$AUTH"
```

## 5. 博主批量下载

### 5.1 输入规则（重点）

`url` / `input` / `text` 三个字段名任选其一，值可以是：

| 输入 | 示例 | 说明 |
|---|---|---|
| 抖音号（裸数字） | `{"input":"67194609689","platform":"douyin"}` | **必须带 `platform`**，否则 400，避免把 B 站 UID 当抖音号 |
| 抖音号（带标签） | `{"input":"抖音号：67194609689"}` | 标签只做容错，不要求用户写 |
| 抖音主页 | `{"input":"https://www.douyin.com/user/MS4wLjAB..."}` | 不需要登录 |
| B 站 UID | `{"input":"12345","platform":"bilibili"}` | |
| B 站空间 | `{"input":"https://space.bilibili.com/12345/video"}` | |
| 昵称 | `{"input":"某某","platform":"douyin"}` | 抖音昵称搜索需要登录 Cookie |
| 单条视频 | `{"input":"https://www.douyin.com/video/730..."}` | **拒绝**，提示改传主页或抖音号 |

`platform` 可选值：`bilibili`、`douyin`。

抖音号解析顺序：

1. `https://www.iesdouyin.com/web/api/v2/user/info/?unique_id=<抖音号>` → `user_info.sec_uid`（公开接口，不要求登录）；
2. 失败再回退到浏览器搜索（需要登录 Cookie）。

### 5.2 预览

```bash
curl -s -X POST $BASE/v2/creators/preview -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"input":"67194609689","platform":"douyin","start":1,"count":10,"include_stats":true}'
```

返回主页信息 + 按 `create_time` 倒序的窗口；置顶作品不会因为页面顺序被当成最新。

### 5.3 建任务

```bash
curl -s -X POST $BASE/v2/creators/jobs -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"input":"67194609689","platform":"douyin","start":1,"count":10,"force":false}'
# → 202 {"job_id":1,"status":"pending",...}
```

范围：`start` 1-based（最新一条为 1，最大 500），`count` 1..50。

### 5.4 查进度 / 项 / 取消 / 重试

```bash
curl -s "$BASE/v2/creators/jobs" -H "$AUTH"
curl -s "$BASE/v2/creators/jobs/1?include_items=true" -H "$AUTH"
curl -s "$BASE/v2/creators/jobs/1/items?status=done&limit=50" -H "$AUTH"

curl -s -X POST "$BASE/v2/creators/jobs/1/cancel" -H "$AUTH"
curl -s -X POST "$BASE/v2/creators/jobs/1/retry" -H "$AUTH"
```

状态：`pending → running → done | failed | cancelled`。
单条失败不影响其余；平台授权失效会停止整批并标记 `failed`，可 retry。
任务、任务项、预览都按当前账号隔离。

## 6. 维护接口

```bash
curl -s -X POST "$BASE/v2/maintenance/reclassify?limit=100&force=false" -H "$AUTH"
curl -s -X POST "$BASE/v2/maintenance/repair-audio-sizes" -H "$AUTH"
curl -s -X POST "$BASE/v2/maintenance/fingerprints/backfill?limit=500" -H "$AUTH"
curl -s "$BASE/v2/maintenance/duplicates?limit=500&threshold=0.95" -H "$AUTH"
```

都只扫描当前账号的曲目，不会跨账号。

## 7. 定时抓取

```bash
# 查看配置与运行状态（不返回关键词/链接内容）
curl -s $BASE/v2/scheduler -H "$AUTH"

# 只触发当前账号自己的组；全局组（user_id 为空）仍由 scheduler worker 执行
curl -s -X POST "$BASE/v2/scheduler/run-once?force=false" -H "$AUTH"
```

没有自己的组时返回 400；配置文件缺失返回 404。

## 8. 平台授权（摘要）

```bash
curl -s "$BASE/v2/platforms" -H "$AUTH"
curl -s -X POST "$BASE/v2/platforms/qr" -H "$AUTH" -H 'Content-Type: application/json' -d '{"platform":"douyin"}'
curl -s "$BASE/v2/platforms/qr/{sid}" -H "$AUTH"
curl -s -X POST "$BASE/v2/platforms/cookies" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"platform":"douyin","cookies":"sessionid=...; ttwid=..."}'
```

凭证只回状态，不回内容。

## 9. 错误码

| 码 | 含义 |
|---|---|
| 400 | 输入/范围不合法（单条视频、裸数字没带 platform、start/count 越界） |
| 401 | 没有或过期的 `X-GetAudio-Session` |
| 404 | 资源不存在或不属于当前账号 |
| 409 | 任务正在执行，不能重试 |
| 428 | 平台需要重新授权 |
| 502 | 平台接口/浏览器桥接失败 |
| 503 | 平台风控或依赖不可用 |

## 10. 明确不在服务端的

- **播放列表、定时播放、本地剪辑**：Android 本地 Room 数据，服务端没有对应表，也没有 `/v2/playlists` 之类接口；
- 全局 scheduler 组由独立 worker 执行，`run-once` 只负责当前账号自己的组；
- 不保证私密、付费、已删除或受平台风控限制的作品可用。

## 11. 抖音批量可能失败的地方

- `aweme/v1/web/aweme/post/` 被 Argus 风控：直连通常 403，要装 Playwright + Chromium，用浏览器捕获页面自己签名的响应；
- 无登录 Cookie 时用干净上下文（不注入访客 ttwid/msToken），仍可能遇到验证码；
- 遇到验证码时无头模式会返回 503；有头模式可人工完成一次验证。

安装浏览器桥接：

```bash
pip install playwright
python -m playwright install chromium
```


## 12. 实测记录

2026-10-30 用临时数据库 + 临时音频目录跑通一次（不写生产库/生产音频目录）：

- 输入 `{"input":"67194609689","platform":"douyin"}`；
- 公开 `user/info` 返回 `sec_uid`，不需要登录；
- Playwright 打开主页，抓到 `aweme/post` 共 259 条；
- 取最新一条 `7699794704686239011`（2026-10-23，17.1s），把列表里的 aweme 直接传给 `DouyinExtractor`；
- 产物为 MP3 415,059 字节，`ffprobe` 实测 17.16s，音频流 `mp3`；
- 验证后删除临时数据库和音频。

未验收：登录 Cookie 场景、验证码/有头模式、不同出口 IP、B 站博主批量、真机 App 调用（Android 端还没有这些 DTO）。
