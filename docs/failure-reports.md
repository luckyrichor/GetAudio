# 手机直提取失败自动上报（0.8.7）

手机直提取失败时，自动暂存原始分享文本或链接、版本、发生时间、失败阶段日志和错误原因。日志中的 URL、Cookie、密码、会话等脱敏，分享中的明显凭据字段也先移除。不会上传音频、视频、剪贴板其他内容或平台登录凭据。

## 上传与归属

本机 `files/failure-outbox/<UUID>.json` 原子保存，不依赖页面仍打开。WorkManager 在已联网时上传，退避起始 30 秒，平台可延迟执行；启动、登录和服务器切换会唤醒。游客先暂存，登录后第一次上传前固定归属；有归属的历史记录不会发到其他账号或其他服务器。

只对当前所选服务器、匹配账号的记录限每批 10 条；失败记录调整尝试顺序，避免旧记录堵住新记录。仅匹配 UUID 且 `stored=true` 的服务端 ACK 才删除本机待传副本。账号失效/断网/服务端错误时保留，同 UUID 重试不重复入库。上传不跟随任何重定向，使用原有 `X-GetAudio-Session`，无需共享服务器用户名/密码。

成功任务不自动上报；主动“从服务器提取”的失败本来已有服务器上下文，本次不另行上传。历史旧失败不会倒灌，因为旧版没有保存原始分享文本；新版本产生的手机直提取失败才进入队列。进程取消不冒充提取失败。

## 接口与数据

- `POST /v2/diagnostics/extractions`：账号鉴权。`id` UUID、`version` 1..64 字、`occurred_at_ms` 非负毫秒、`share_text` 1..10000 字、`logs` 最多80行且每行1000字。返回 `200 {"id":"…","stored":true}`；同账号同 UUID 幂等。`401` 未登录/过期，`422` 格式或长度非法（错误不返回原输入），服务器异常保留待传。
- `GET /v2/diagnostics/extractions?limit=50`：仅本账号，limit 1..200，按接收时间倒序。返回 id、版本、发生时间、接收时间、分享文本、日志。
- `GET /v2/diagnostics/extractions/{report_id}`：仅本账号；不存在或其他账号均 `404`。
- 新表 `extraction_failures`：owner_id+report_id 复合主键，version、occurred_at_ms、received_at、share_text、logs_json。启动 `create_all` 新增，不修改既有音频表。服务端会再次脱敏。

请求例：
```json
{"id":"b6656ba1-47de-41d2-9ac5-ecc4331dbad9","version":"0.8.7","occurred_at_ms":1793050000000,"share_text":"分享 https://v.douyin.com/example/","logs":["媒体下载：HTTP 404"]}
```

## 服务器排查（SSH 管理员，只读）

```bash
ssh tx
cd /home/ubuntu/project/GetAudio
.venv/bin/python -m getaudio.failure_reports --limit 20
.venv/bin/python -m getaudio.failure_reports --id <报告UUID>
.venv/bin/python -m getaudio.failure_reports --owner <账号ID> --limit 50
```

这条 SSH 命令可查看全部账号，HTTP 不能越权看全部。数据落在服务实际 SQLite 数据库，重启保留；不在普通访问日志中打印分享正文或凭据。当前不自动删除服务端历史记录。管理员可按 UUID、账号和时间定位平台失败，不必再让手机手工导出日志。

## 本轮 404

当前分享 `https://v.douyin.com/aW1fuUQ0yco/` 展开到图文 `7579193685948088430`，返回类型2，真实 MP3 为 `/obj/ies-music-hj/…mp3`。0.8.6 只允许 `/obj/ies-music/`，漏读有效地址，转而使用视频包装入口。Mac 真实 MP3 探测 HTTP206/audio-mpeg；包装入口 Mac 探测超时，手机404由用户反馈，未把 Mac 超时冒充404证据。修复仅扩展一个已验证的 bucket。

## 验证与遗留

源码版本 `f72a73e`：Android 75 项 JVM 测试通过，Lint 无错误，Debug/Release 构建通过；Mac 服务端163项通过、1项已知Chromaprint未运行，tx完整164项通过并部署、服务active。

手机升级0.8.7/code26后原图文已入库为「现在开始跳动」。受控无效文本触发失败，服务器留存报告 `1a2ec0a1-80bc-45f4-bef7-2236446f71de`（0.8.7、原测试文本、时间及失败步骤），SSH CLI 回查通过。未单独播放新文件；真实长时断网/系统杀后台/全部账号切换组合未逐一真机注入。

Android/服务端分别测试本地持久化、脱敏、ACK、归属、积压筛选、鉴权、隔离、幂等和长度限制。断网与系统后台调度时机存在平台延迟，不等于上传立即到达。
