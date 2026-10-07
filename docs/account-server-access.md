# GetAudio 0.7.0 账号服务器访问

后续0.8.0增加手机号主账号下的平台绑定登录，见[平台绑定登录](platform-account-login.md)。本文保留0.7.0的实现与验收记录。

普通用户以自己的 GetAudio 手机号＋密码注册、登录后，服务器提取、云端同步、下载和平台授权使用个人会话，不需要 Nginx 共享用户名/密码。抖音、B站是同一 GetAudio 账号下可同时保存的两份平台授权。本次没有第三方直接登录、短信验证或找回密码。

## 架构与调用

手机 → HTTPS Nginx 443 → 回环 FastAPI 8800 → 平台下载/FFmpeg/账号目录 → SQLite → 手机缓存。Mac 仅用于开发，不参与产品运行。Nginx 放行账号入口与新资源路径；FastAPI 以 `X-GetAudio-Session` 验证身份。旧管理接口保留 Basic。Basic 密码不是 SSH 系统登录密码；新客户端不读取或发送历史 Basic 配置。

账号模块保存 scrypt 密码哈希、30 天会话令牌哈希；手机用 Android Keystore AES-GCM 保存令牌，账号密码不持久化。平台 Cookie 按账号与平台使用 AAD 加密；服务端 `.env` 的加密密钥必须与数据库一起备份。手机号尚未通过短信验证。

`account_api.py` 组织 /v2 路由；`pipeline.py` 从服务端身份生成任务和曲目归属；`storage_scope.py` 使用 ContextVar 隔离输出目录；`credential_migration.py` 提供管理员票据迁移。Android `AccountStore` 驱动 Room 可见分区与播放器清理，`ApiProvider` 为长请求生成固定账号客户端，`TrackRepository` 在提交前核对账号。

## 数据模型与兼容

- Track 增加 `owner_scope`，历史值 `legacy`；唯一约束为 `(owner_scope, platform, video_id)`。同链接不同账号分别验证平台访问并生成独立文件。相同账号重复提取复用原编号；force 不改变他人的文件。
- 输出路径：legacy 沿用原目录；新账号在 `audio/accounts/SHA256(owner_scope)/`。提取器共用搬运函数，不改变全局配置。
- SQLite 启动迁移在独立事务中重建 tracks，保留原 ID、字段、索引、任务及指纹引用；迁移后核验行数、外键和完整性。非 SQLite 旧库拒绝自动迁移，须独立迁移方案。部署必须先停写备份。
- Android Room 6→7：负编号为 local，历史正编号为 legacy_device；新增 active_scope、cloud_mapping、cloud_sync。云端归属键是规范服务器 URL＋账号 ID 的 SHA-256；远端 ID 冲突分配新的稳定本地 ID。映射在本机删除后仍保留，删除抑制据该本地编号保持，游标按 scope 分开。
- DAO 显示 local、legacy_device、当前账号云端记录。切换账号保留缓存并隐藏别人的云端记录，移除不可见播放队列项；播放列表引用稳定本地 ID。原本地文件、用户列表与类别不删除。
- 历史服务器音频不自动认领。本次核对 tx 在升级前有 5 条历史曲目、0 个 GetAudio 注册账号，未发现可验证的账号归属，全部保留 legacy。将来有可信成功任务证据时，管理员须先核对再复制独立曲目与文件；本版不提供批量猜测归属工具。

## API

完整字段、类型、约束、响应 schema 见 [OpenAPI 合约](account-v2.openapi.json)，由本次代码生成。所有 /v2 与 /agent/v2 资源均需 `X-GetAudio-Session`。注册、登录和 health 不需令牌，不需 Basic。JSON 请求为 `Content-Type: application/json`；下表字段未标可选均必填。

| 方法 / 路径 | 输入 | 响应 / 约束 |
|---|---|---|
| POST /account/register | phone 字符串 11–32；password 8–128 | user_id、phone、session_token、expires_at；号码规范为 +86，已存在409 |
| POST /account/login | 同注册 | 同上；无效身份/密码401 |
| GET /account/me | 会话头 | user_id、phone |
| POST /account/logout | 会话头 | ok；撤销当前会话 |
| POST /v2/extract | text 字符串 1–4000；force 布尔可选默认false | track、reused；不允许 user_id 或其他额外字段 |
| GET /v2/tracks | q/platform 可选字符串；limit 整数1–200默认50；offset ≥0默认0 | 当前账号未删除 Track 数组，updated_seq倒序 |
| GET /v2/sync | cursor ≥0默认0；limit 1–500默认200 | items、next_cursor、has_more；包含本账号删除标记 |
| GET /v2/audio/{track_id} | 整数ID | 音频文件；不存在/越权/删除404，文件丢失410 |
| GET /v2/platforms | 会话头 | platforms 授权方式列表 |
| POST /v2/platforms/qr | platform：bilibili或douyin | session_id、platform、qr_url、qr_image_url、expires_in；不支持501 |
| GET /v2/platforms/qr/{sid} | sid 字符串 | session_id、platform、state、message；非本人404 |
| GET /v2/platforms/qr/{sid}/image | sid 字符串 | PNG，Cache-Control:no-store；非本人404 |
| POST /v2/platforms/cookies | platform 枚举；cookies字符串1–65536 | status:saved；校验失败400，不回显Cookie |
| GET /v2/platforms/credentials | 会话头 | platform、status、updated_at、last_checked_at数组，不含Cookie |
| DELETE /v2/platforms/credentials/{platform} | platform 路径 | ok:true；撤销自己的凭证与未完成二维码，不支持404 |
| POST /v2/migration/claim | ticket 字符串32–128，无其他字段 | platforms数组；错误票据404、过期410、目标冲突/他人已用409 |
| GET /v2/memories/{user_id} | 必须当前user_id | 当前偏好列表；他人404 |
| POST /v2/memories | user_id、content（见schema） | 新偏好；他人403 |
| PUT /v2/memories/{user_id}/{memory_id} | content、revision | 新版本；版本冲突409、他人404 |
| DELETE /v2/memories/{user_id}/{memory_id} | 当前user_id、整数memory_id | 逻辑删除；他人404 |

其他资源和助手接口的逐项请求/响应见 OpenAPI；助手会话、任务、候选音频、回执都检查当前账号。服务器成功证据必须匹配该账号任务和曲目。

请求示例（替换令牌；不要将真实值放进日志）：

```http
POST /v2/extract
X-GetAudio-Session: <个人会话>
Content-Type: application/json

{"text":"https://www.bilibili.com/video/<BV>/","force":false}
```

```json
{"track":{"id":123,"platform":"bilibili","video_id":"示例","title":"示例音频","download_url":"/v2/audio/123","deleted":false,"updated_seq":18},"reused":false}
```

上例只展示关键字段；所有 Track 响应字段、可空类型见 schema。同步空页为 `{"items":[],"next_cursor":原游标,"has_more":false}`；错误统一 `{"detail":"说明"}`。400链接/平台凭证无效，401账号未登录/会话失效，403写入身份不符，404不可见资源，409迁移/版本冲突，410过期票据/丢失文件，422字段校验，428平台需重新授权，429限流，502平台或提取器失败。账号401与平台428分别提示。

## 请求隔离、重试和失败

客户端每次长操作捕获账号、源、令牌，跨源请求不附带令牌或 Basic。同步游标只在对应分页成功入库后推进；下载用临时文件并核验大小和 SHA-256，成功前不覆盖正式文件。恢复原版提交成功后只保留已提交文件，切换账号时跳过播放器更新。

助手 ViewModel 绑定原账号与服务器。晚到响应不能填入新账号页面；操作回执加密保存到原账号 outbox，回传失败可重试，不重新下载。claim 后切换账号导致未执行时保存原账号中断回执。服务器提取显式使用调用方账号，不能重新抓取新账号执行旧操作。

平台解除授权与扫码完成串行，解除时取消未完成 QR；二维码图片与轮询检查同一账号。二维码仅内存保存，重启重新授权；真实平台风控仍可能失败。抖音不支持扫码，需 Cookie 授权。

迁移只允许管理员通过已验证来源设备发行随机短期票据（默认600秒）；服务器存哈希。领取在 BEGIN IMMEDIATE 中加密重绑定、消费、撤销来源；同账号重复领取幂等，第二账号拒绝。目标已有授权409并保留两边。导出Cookie是可重建缓存，来源文件清理不是数据库事务；重复领取会再次清理。手机票据私有加密保存，需本人确认。本次手机访客ID与服务器旧B站凭证不匹配，未发行票据、未转移旧授权；新账号需要自行授权。

## 配置、部署、回滚

继续使用现有 `.env`、audio、数据库、加密密钥、方舟配置，详见 `.env.example` 与旧技术文档。生产服务 `getaudio-api.service` 位于 tx，Nginx 宿主机运行；此版不引入 Docker 或新公网端口。

发布前记录 Git HEAD、systemd状态，SQLite backup 一致快照和 Nginx配置备份保存在私有目录。停 API 后升级代码、启动迁移，验证健康与数据库约束后 `nginx -t`、reload。Nginx `/account/register|login|me|logout`、`/v2/`、`/agent/v2/`不再要求 Basic；其他路径保留 `/etc/nginx/getaudio.htpasswd`。8800不对外。

回退入口可先恢复 Nginx备份并 reload；API回退必须兼容已升级数据库。完整恢复旧数据库需要停止写入、保存升级后的数据、恢复同一备份和旧代码；不得直接覆盖运行库或丢弃备份后新用户数据。手机号注册入口保留单进程每IP五分钟20次限制；并非多进程全局限流。运维日志不得记录令牌、票据、Cookie、密码；诊断只含任务失败步骤。

## 验证记录

候选代码：tx隔离数据库 Python **148项通过**；Mac **147项通过、1项排除**（已知FFmpeg缺Chromaprint）；Android **49项JVM通过**，Lint **0错误、9警告、1提示**，debug/release/test APK构建成功。独立代码审查发现并修复账号切换竞态，复核通过。

生产代码 c9b3dcb 已部署至 tx，Nginx/API active，8800仍回环。数据库旧5条记录/ID保留，唯一约束及PRAGMA检查通过。备份：`/home/ubuntu/backups/getaudio-pre-070-20261007T023536Z`，数据库完整性与私有权限已核验。

公网 HTTPS 无Basic注册/登录、会话查询成功；无令牌401、跨账号音频404、个人同步隔离、伪造身份422、退出后401、旧路径Basic401均实测通过。两临时账号、令牌、测试音频均已清理。

手机同包名同签名已升级0.7.0/code18，原两条音频仍显示，游客同步正确弹出GetAudio登录。独立Room测试组件两次被手机拦截（INSTALL_FAILED_USER_RESTRICTED），该项未运行；等待用户允许测试组件及完成本人注册后继续真实提取验证。构建和模拟提取测试不代表平台真实授权成功。
