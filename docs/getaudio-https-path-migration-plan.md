# GetAudio HTTPS 443 子路径入口迁移方案

> Codex · 2026-10-31 · 本机代码依据 GetAudio@dc2abeb。
> 状态：方案，未实施。仅完成本地代码与 tx 当前 Nginx/systemd 配置的只读检查，没有修改 App、服务、数据库、Nginx 或平台配置。
> 目标：最终只保留 `https://43.142.37.199/getaudio/` 作为 GetAudio 公网基础地址。先增加新入口并保留旧入口，手机实际验证全部通过后再停用旧入口；存在未验环节时不得执行停旧步骤。

## 1. 最终地址与范围

| 用途 | 最终公网地址示例 |
| --- | --- |
| 基础地址 | `https://43.142.37.199/getaudio/` |
| 健康检查 | `https://43.142.37.199/getaudio/health` |
| 注册 | `https://43.142.37.199/getaudio/account/register` |
| 登录 | `https://43.142.37.199/getaudio/account/login` |
| 当前账号 | `https://43.142.37.199/getaudio/account/me` |
| 同步 | `https://43.142.37.199/getaudio/v2/sync` |
| 服务端提取 | `https://43.142.37.199/getaudio/v2/extract` |
| 文件下载 | `https://43.142.37.199/getaudio/v2/audio/{id}` |
| 抖音回调 | `https://43.142.37.199/getaudio/account/platform-auth/douyin/callback` |

大小写严格使用 `/getaudio/`，基础地址保留尾斜杠。可将恰好缺少尾斜杠的 `/getaudio` 规范到 `/getaudio/`；不对旧根路径提供永久兼容重定向。

本次只迁移 GetAudio 的公网路径，不合并 GetVideo、不迁移其 8443 入口、不改其他项目路由，也不重建账号系统。

## 2. 已确认的风险点

1. tx 当前 GetAudio 后端通过 Uvicorn 监听 `127.0.0.1:8800`，不需要改内部端口。
2. 当前 `/etc/nginx/sites-enabled/getaudio` 的 443 server 不只承载 GetAudio，还包含其他项目的 snippet。不能使用仓库模板整份覆盖生产配置。
3. 当前旧入口有公开健康检查、应用账号鉴权接口、免 Basic Auth 的 OAuth 回调，以及 Basic Auth 保护的旧接口。新路径必须逐类复刻原有权限，不能把全部接口统一放开或统一加 Basic Auth。
4. Android `ServerConnectionPolicy.kt` 默认地址是旧根地址；`Settings.kt` 中用户显式保存的地址不会因为修改默认值自动更新。
5. `AccountStore.forServer()` 精确比较服务器地址。仅改设置会导致旧登录态不匹配。
6. `AccountScope.kt` 的 `cloudScopeKey` 包含完整服务器地址；本地媒体可见性、云端映射与同步游标依赖该 scope。路径迁移不能被误判为新服务器。
7. `FailureOutbox.kt` 按服务器地址和用户筛选待上报记录，旧地址积压也需要迁移。
8. 下载、二维码加载存在 `trimEnd('/') + path` 手工拼接；服务端多个输出仍返回 `/v2/audio/...`、`/v2/platforms/qr/...` 等根相对路径。
9. `ApiProvider.okHttp(account)` 只检查 HTTPS、host、port 就附加令牌，没有限制项目路径。共享域名端口后必须补上路径授权边界。
10. OAuth 回调访问日志脱敏目前匹配旧路径，新路径也要覆盖，不能因迁移泄漏 code/state。

## 3. 服务器改动设计

### 3.1 Nginx 路由

保留现有 443 server、证书与其他项目 include，在原配置上增加 GetAudio 子路径路由。

新请求 `/getaudio/v2/sync` 对应后端现有 `/v2/sync`。采用 Nginx 去掉固定前缀的方式，避免把所有业务 Router 都复制一套。

需特别处理 Nginx location 优先级：目前有匹配 `/account/`、`/v2/`、`/agent/v2/` 的正则规则，新前缀下的规则必须显式设计并测试，不能只加一个笼统 location。`proxy_pass` 是否带 URI、尾斜杠及正则 location 的限制须按有效配置验证。

权限沿用原状：

- 新健康检查可匿名调用，只返回必要状态。
- 账号注册、登录等按现有应用逻辑处理；需要会话的 API 继续服务端鉴权。
- 抖音浏览器回调不能要求设备 Basic Auth，继续由授权状态、防重放、提供方校验保护。
- 旧式受 Basic Auth 保护的接口在新前缀下继续保留相同保护，不借迁移扩大权限。
- 保持下载 Range、Content-Length、Content-Disposition、流式响应及提取超时行为。

### 3.2 FastAPI 外部前缀

业务路由内部仍保持 `/account/...`、`/v2/...`。增加可信的“当前请求公网前缀”处理，供生成链接、重定向、文档和 OpenAPI 使用。

并存期按请求区分旧根入口与 `/getaudio/` 入口。不能直接为整个 Uvicorn 固定一个全局 `root_path=/getaudio`，否则可能改变旧入口返回的地址。

建议由 Nginx 覆写前缀标记，后端只在受信反代链路中读取，严格限制取值为空或 `/getaudio`，按请求设置 ASGI root_path。实施前需用真实 ASGI/框架版本验证 path、root_path、重定向的组合，不能假定添加 `X-Forwarded-Prefix` 就会自动生效。

最终停旧后仍保留同一套前缀处理，不再需要双入口语义。

技术依据：[FastAPI 反代与 root_path](https://fastapi.tiangolo.com/advanced/behind-a-proxy/)、[Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)。root_path 不会自动修复业务代码手写的 JSON 链接，后者要单独处理。

## 4. App 地址及登录态迁移

### 4.1 统一地址处理

涉及 `ServerConnectionPolicy.kt`、`Settings.kt`、`ApiProvider.kt`、`LoginRepository.kt`、`TrackRepository.kt` 及所有资源加载调用。

- 默认地址改为新基础地址。
- Retrofit 注解继续使用不带前导斜杠的相对端点，如 `account/login`；禁止 `/account/login` 把 base path 丢掉。
- 建立统一的资源 URL 解析函数，替代手工字符串拼接，统一处理 API 相对链接、完整新路径链接和过渡期旧链接。
- 原来源平台视频 URL 不是 GetAudio API 地址，不能批量加 `/getaudio/`。
- 自定义服务器、localhost 调试地址及其他项目地址不自动改写；仅迁移明确核实过的旧 GetAudio 地址。缺省端口和显式 `:443` 等等价写法要统一处理。

### 4.2 保留账号、文件和数据归属

实施一次有版本号、可重复执行、可从中断恢复的迁移：

1. 等待或暂停同步、下载与失败上报任务，阻止旧地址请求结果在迁移后写回。
2. 在迁移标记中记录开始状态，备份必要本地状态，但不打印凭据。
3. 将确认属于旧 GetAudio 地址的设置和加密会话 server 字段更新到新地址，原 token、user_id 不变。
4. 在 Room 事务中迁移原账号 scope：`tracks.cloudScope`、`cloud_mapping`、`cloud_sync`、`active_scope` 及现场审计到的其他归属字段。保持 localId、remoteId、文件路径、重命名、分类与列表引用。
5. 保留同步游标，避免无故全量重建和重复入库；冲突 scope 先检查一致性，不盲目覆盖。
6. 待上报记录迁移 server 字段，保持 UUID、owner 与正文不变，避免丢失或重复上报。
7. 同步检查播放器恢复数据、定时/后台任务、缓存和保存的 API 资源链接；不删除媒体文件、不重建播放列表。
8. 设置完成标记，再刷新可见性并恢复任务；跨 DataStore、加密文件、Room 无法依靠一个数据库事务，因此需明确重入及故障恢复顺序。

不通过卸载重装、清数据或更换包名实现迁移。使用固定发布证书同包升级；安装前核实手机当前版本，不能覆盖更新的版本。

若旧 token 本身已过期，仅提示正常重新登录，不清除旧媒体。迁移白名单不能扩展为“同 IP 的地址都是同一个服务”。

## 5. 资源链接与凭据边界

### 5.1 服务端返回链接

审计 `track_out.py`、`account_api.py`、`library_api.py`、`api.py` 等输出点。

- 新入口的下载、二维码、任务资源、分页/后续链接和重定向应落在 `/getaudio/` 下。
- 并存期旧入口仍返回旧客户端能使用的链接，避免新版服务端先把旧 App 的资源拼接打坏。
- 用一个按请求前缀生成链接的 helper，不在各函数中随意硬编码。
- 不能依赖 Nginx 对 JSON 正文进行字符串替换修补。
- 文件 ID、数据库音频路径、平台来源地址不随公网路径改变。

### 5.2 App 凭据

所有请求和重定向逐跳检查：可信 HTTPS host + port + 项目路径边界。新 App 的 GetAudio 会话仅附加到 `/getaudio/` 范围，不能发往 `/getaudio-other/`、根 `/account/` 或其他项目路径。

下载重定向到平台 CDN 等外部位置时不携带 GetAudio 凭据；协议降级拒绝。若需要过渡期探测旧入口，必须用明确隔离的迁移流程，不能让全局拦截器继续把整个站点视为可信。

当前 app 主要使用会话头，但仍审计响应 Set-Cookie、Basic Auth、二维码图片加载器及浏览器流程；存在 Cookie 时检查 Path、Secure 和 HttpOnly，避免项目间串用。

## 6. 授权回调与特殊流程

抖音回调迁移为：

`https://43.142.37.199/getaudio/account/platform-auth/douyin/callback`

需同时核对服务端 redirect_uri、开放平台登记的回调地址和新路径 Nginx 放行规则。不能只改本地环境变量；纯 IP 回调是否被提供方接受必须实际确认。

并存期间让旧授权尝试自然结束或明确取消后重新发起，保留旧回调接收能力，避免用户授权到一半断链。新旧回调均应对日志中的 code/state 脱敏。

B 站分别验证“主账号绑定/快捷登录流程”和“内容访问二维码授权流程”，覆盖图片加载、轮询、完成、取消、过期与解绑；二维码成功显示不是授权成功。

若抖音当前未配置、提供方回调无法更新或不能完成真实用户确认，标为未验，旧入口保持，不执行最终切换。

## 7. 实施顺序与回滚

| 阶段 | 操作 | 旧入口状态 |
| --- | --- | --- |
| A 准备 | 核对生产版本/全部路由，备份数据库、Nginx有效配置、必要配置与媒体清单，确认恢复方式 | 保留 |
| B 新入口 | 后端前缀适配及链接兼容；增量增加 Nginx 新路径，nginx -t 后 reload | 保留，旧 App 不受影响 |
| C App 适配 | 完成地址、会话、scope、资源与后台任务迁移，同签名升级 | 保留 |
| D 实际验收 | 手机独立网络按矩阵验证；回查服务器请求落点与数据完整性 | 保留 |
| E 停旧 | 全部必要环节有通过证据后，删除原 GetAudio 根路由代理与鉴权匹配，仅保留新前缀 | 停用，不重定向 |
| F 回归 | 新入口重验、旧入口不可访问验证、其他项目回归 | 不恢复，除非发生故障回滚 |

回滚以恢复前一版已备份的 GetAudio 路由与兼容后端为主；不立即降级手机 APK，也不盲目恢复整份旧数据库覆盖期间产生的新数据。并存阶段后端必须能兼容新旧 App。停旧后故障可临时恢复旧入口，修复并重新验收后再次停旧。

最终删除的是公网旧 GetAudio 入口，不是数据库、账号、媒体、加密密钥或本地 8800 服务。80 端口证书验证路径保留；其他项目的 443 路径与 GetVideo 8443 不在删除范围。

## 8. 验收与停旧门槛

| 项目 | 必须取得的证据 |
| --- | --- |
| 旧版兼容 | 并存期旧 App 登录、同步、资源下载仍正常 |
| 登录迁移 | 新版同包升级后有效会话保留，旧库/列表/进度未丢；新注册、登录、登出再登录正常 |
| 同步 | 增量、重复同步、已有删除记录和跨账号可见性正常，无重复或隐藏音频 |
| 提取 | 本机提取不回归；服务器 B站/抖音允许访问的样本完成提取与保存 |
| 文件/播放 | 旧文件重下、新文件下载、Range、文件大小与哈希、实际听播、重启恢复正常 |
| 平台授权 | B站实际扫码确认并完成对应流程；抖音实际回调完成或明确记录无法验证 |
| 后台 | 失败上报旧积压迁移、重试及 ACK 正常，取消任务不越界写入 |
| 链接安全 | 返回链接与 Location 无旧根业务链接，无双重前缀；跨项目与外域请求不带 GetAudio token |
| 其他项目 | 迁移前建立可公开探测的基线，迁移后 GetVideo 与其余既有项目状态不变 |
| 停旧 | 新入口正常；旧 `/health`、`/account/*`、`/v2/*`、`/audio/*`、`/login/*`、`/agent/*` 等全部旧 GetAudio 路由及变体不再代理至 GetAudio |

最终停旧使用明确的拒绝响应（例如 404/410），不是 301/302 继续可用。若旧路径恰好由其他项目合法使用，应由对应项目处理，而不是为停 GetAudio 一刀切屏蔽所有根路径。

手机实测必须脱离 Mac USB 转发，验证最终产品独立访问能力。仅本机 curl、构建通过、二维码显示或健康检查成功均不足以停旧。

## 9. 当前验证边界

- 已核对：本机源码、生产主机身份、当前 GetAudio Nginx 配置、服务工作目录与内部监听。
- 尚未实施：全部迁移改动；新路径当前可用性也未以本方案作保证。
- 尚未验收：当前手机版本/连接、同签名升级及登录迁移、真实下载与播放、B站授权、抖音平台登记和真实回调、其他项目完整回归。
- 结论：可以按本方案开展下一轮实施，但现在不删除任何旧入口；任一必要功能无法实测时保留双入口并明确交付未验清单。

## 10. 预计改动文件

- 服务端：`getaudio/api.py`、`track_out.py`、`account_api.py`、`library_api.py`、`platform_accounts.py`，新增统一前缀/链接 helper，以及所有实际发现的资源 URL 输出点。
- Android：`ServerConnectionPolicy.kt`、`Settings.kt`、`AccountStore.kt`、`AccountScope.kt`、`ApiProvider.kt`、`LoginRepository.kt`、`TrackRepository.kt`、`FailureOutbox.kt`，必要的 DAO/迁移标记及相关资源加载调用。
- 部署：仓库 `deploy/tx-https/getaudio.conf` 与实际 `/etc/nginx/sites-enabled/getaudio` 的对应增量；回调配置只调整地址，不更换任何密钥。
- 测试：地址解析/凭据路径、App 数据迁移及中断恢复、双入口链接、回调/日志脱敏、Nginx 路由与退役检查。
- 文档：HTTP API 示例、部署/运维说明、README、AGENTS 与飞书开发记录；实施完成后注明真实验收范围和最终停旧状态。

上述为范围清单，不代表每个文件都必须修改。实施前以最新本机、生产及手机版本重新审计，不把当前 dc2abeb 当作固定部署基线。
