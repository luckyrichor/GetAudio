# GetAudio 0.8.0 手机号主账号与平台绑定登录

## 用户流程

首次必须使用手机号＋密码注册，注册成功取得个人会话。手机号规范化与唯一约束沿用现有账号系统；没有短信验证，手机号输入本身不证明号码归属。

入口为「我的 → 头像/账号」。登录后在「关联平台账号」绑定B站或抖音；同一手机号可同时绑定两个平台，每个平台一个账号。平台身份全局只能归属一个手机号，冲突拒绝，不自动换绑。退出后可在同一账号页使用已绑定平台授权登录，返回原手机号账号、原数据归属与原账号 ID。

未绑定平台授权只能证明平台身份，不能注册 GetAudio 账号，也不能猜手机号自动关联。用户需先手机号注册/登录并主动绑定。显示核验得到的昵称，用户确认后才提交绑定或登录。解除绑定要求当前手机号账号密码；解除后不允许新的平台登录，现有 GetAudio 会话仍有效，手机号账号和音频保留。

## 平台方式与能力边界

B站复用现有网站扫码流程，使用B站 App 扫码确认；服务器再请求B站 nav 接口核验已登录身份及 mid，不信任 Cookie 中的 DedeUserID。此流程并非B站官方第三方 OAuth。授权依赖平台网络和风控，手机同屏扫码的可用性需要真机验收。

抖音使用开放平台 OAuth 网站授权，通过系统浏览器打开，完成后返回账号页检查并确认。需要已审核应用与登记的 HTTPS 回调，服务端配置 `GETAUDIO_DOUYIN_CLIENT_KEY`、`GETAUDIO_DOUYIN_CLIENT_SECRET`、`GETAUDIO_DOUYIN_REDIRECT_URI`。回调路径必须为 `/account/platform-auth/douyin/callback`，无查询参数。Client Secret 只保存在服务端私有环境，不进 APK、Git或群消息。未配置时禁用抖音按钮并说明原因，不假装授权成功。

抖音授权仅用于身份核验，不等同视频下载 Cookie；OAuth Token 不持久化。B站成功确认后，可将扫码 Cookie 加密保存到同一手机号的提取凭证。原「平台授权」页继续管理提取凭证；导入 Cookie 不创建登录绑定，历史凭证不自动认领为登录身份。解除平台登录绑定不删除提取 Cookie，可在原授权页单独解除。

## 数据与接口

新增 `account_platform_bindings` 与 `account_platform_attempts` 表；启动只创建新表，不重建手机号、音频或会话表。绑定以内部 AppUser ID 为外键，平台 subject 是子身份，手机号仍是主身份。

| 接口 | 用途 |
|---|---|
| GET /account/platforms | 平台可用性与授权方式 |
| GET /account/platform-bindings | 当前账号绑定昵称，不返回平台 subject |
| POST /account/platform-auth/start | platform、mode=bind/login；bind 必须已登录 |
| POST /account/platform-auth/image | 私有证明获取B站二维码 |
| POST /account/platform-auth/status | 私有证明轮询平台核验结果 |
| POST /account/platform-auth/complete | 单次确认；已绑定 login 返回原主账号会话 |
| POST /account/platform-auth/cancel | 取消并清除临时凭证 |
| GET /account/platform-auth/douyin/callback | 浏览器回调，仅接收临时 state、code |
| POST /account/platform-bindings/{platform}/unbind | 个人会话＋当前手机号账号密码 |

授权证明由随机 attempt_id 与独立 poll_token 组成，数据库只存哈希。公开 OAuth state 不可单独轮询或取会话；二维码 key 和待确认 Cookie 加密保存。B站180秒、抖音600秒后过期。bind 固定发起时的主账号及具体会话，退出或切换账号后不能确认。数据库条件更新消费授权，绑定与新会话处于同一事务，防重放、取消竞态及跨手机号归属冲突。

Nginx 精确放行新账号接口，其他历史路径继续 Basic；回调关闭 Nginx access log，Uvicorn access log 移除回调查询参数。请求校验错误不回显密码或 poll_token。注册、登录、发起授权与解绑沿用每IP单进程五分钟20次限制。

## 发布与验证

Android 版本0.8.0/code19。Python回归161项通过，涵盖未绑定拒绝注册、主手机号会话、跨手机号冲突、单次消费、过期/取消/退出、独立轮询秘密、解绑密码、抖音state/回调重放、服务端身份核验、敏感回调日志及Android请求合约。平台HTTP响应使用模拟数据；这些结果不代表真实平台登录已经验收。

Android debug APK构建通过，49项JVM测试通过，Lint 0错误、22警告、1提示。真实B站扫码/风控、手机浏览器往返，以及配置后抖音真实授权仍需本人设备验收。新表可兼容旧代码回退；不要恢复旧数据库覆盖发布后的新账号数据。部署先备份当前SQLite和Nginx配置，再更新Nginx并重启API。

公网部署验证：HTTPS平台能力查询200、未登录绑定/绑定列表401、无效OAuth状态400、历史路径和伪造账号路径仍Basic401；真实B站二维码申请和图片获取均200，随后取消。未扫描或绑定本人账号。抖音当前配置缺失，能力available=false。数据库完整性与外键检查通过，服务已重启，Nginx配置验证并重载；保留现网额外的mihomo-mobile include。备份目录 `/home/ubuntu/backups/getaudio-pre-080-20261007T130008Z`。Uvicorn回调日志实测无查询参数。
