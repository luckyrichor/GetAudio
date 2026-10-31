# GetAudio 子路径迁移实施记录

- 基线：2026-10-31，GitHub 快进同步后仍为 dc2abeb；开发分支 codex/getaudio-path-migration。保留两份原未跟踪方案文档。
- 采用现有工作目录中的独立开发分支，避免改变用户未提交文档的位置；没有另建 worktree。
- 阶段：1 双入口及链接；2 Android 地址/会话兼容；3 测试与构建；4 新入口部署及手机验收；5 满足所有门槛后停旧；6 GitHub/飞书交付。
- Ruling：保留旧生产地址作为数据归属的稳定身份，新旧生产入口共享原 cloudScope 哈希，不重写 Room 主键、映射、游标或列表引用。仅传输地址规范到 /getaudio/。比跨多个存储系统批量改 scope 更容易保持崩溃恢复和可逆性；非白名单地址不合并。
- Ruling：Nginx 将新前缀原样传给后端，由纯 ASGI 中间件剥离路由前缀、设置请求 root_path 和 ContextVar 链接前缀；旧请求保持原样。无需信任客户端 X-Forwarded-Prefix，也不依赖 Nginx 正文替换。
- 当前手机：23013RK75C，com.getaudio.app 0.9.10 (38)，与历史 REDMI K80 不同；安装前需比较签名。
- tx：VM-0-15-ubuntu，生产为 62a9643，服务 active，本机监听 8800。未跟踪 data/ 必须保留。
- 停旧门槛：手机独立网络的全部既定验收通过；任何必需环节无法验证时保留旧入口。

## 实施结果（2026-10-31）

- 提交 8d67654 实现双入口、Android 0.9.11/code39 迁移；896ca88 修复 OpenAPI 缓存跨入口 server URL。源码由 GitHub 分支快进部署至 tx，后端继续本机 8800。
- Ruling 补充：Nginx 不剥离新前缀，完整传入后端；中间件设置 root_path，Starlette 在路由匹配时剥离它，外部 URL/重定向保留完整路径。无转发头信任或 JSON 替换。旧 API 与新 API 各自生成可用资源地址。
- 新 Nginx snippet 已增量 include；实施时从生产当前配置插入，没有用仓库模板覆盖其他项目。原配置及临近修改前版本均在生产备份目录。
- 数据保护：生产 SQLite .backup 完整性 ok，9.8 MB 媒体和原 Nginx 已备份；不更改密码、token、服务器密钥，不清理用户媒体。原 data/ 未跟踪目录保留。
- 红绿测试：新前缀最初 404，新增测试失败后实现；Android URL helper 缺失编译失败后实现；HTTPS 降级测试先失败后补 followSslRedirects(false)；OpenAPI servers 测试先失败后使用请求级 schema 副本。
- 本地 Android 104 项全通过，Lint 0 errors/27 warnings（另3项 Information），debug+release 构建成功。最后两轮仅服务端/文档变动，没有改变已安装 APK。
- Mac 服务端最新 187 passed / 1 failed：唯一失败是既有 test_real_fingerprint_ignores_metadata，本机 FFmpeg 无 Chromaprint，按用户既有决定不修。tx 最终结果另见后续记录。
- 独立只读审查未发现必须修复回归，指出降级缺口后已修；OpenAPI 后续追加复查受审查 Agent 额度限制未完成，执行者自行检查并运行失败→通过回归。
- 公网实际验证：新旧 health 200；新旧 account/me 无会话401；新 v2/sync/audio 无会话401；旧式新前缀 tracks 仍Basic401；新回调缺少参数422，不被Basic拦截；GetVideo8443/health和Paperdrift入口均保持200。
- 用临时账号完成新入口注册、登录、me、同步、登出和登出后401；相同会话同时访问旧入口。另以受控临时文件验证链接前缀、下载字节/哈希、Range206与未授权401；临时账号及文件均清理。
- 另用临时账号 force=true 新提取 B站 BV1KG411w7r2 第3P，确认 reused=false、HTTP200；新路径下载字节数与SHA256均匹配。该账号、任务、元数据、指纹与专属媒体目录均已清理。
- 手机23013RK75C，固定证书一致，adb install -r 原地升级0.9.10→0.9.11成功。升级后保持原账号登录、10首原本机文件、列表1及原正在播放00:10/00:12；没有换包、卸载或读取私有凭据。
- 手机新地址显示 /getaudio/，测试连接显示“服务器连接及账号登录正常”。点击云端同步新增1条真实媒体；GET /getaudio/v2/audio/... 200，播放进度00:01→00:12并正常暂停。手机直提取与服务器提取同一B站P3均显示已加入音频库。由本轮实际同步/直提取新增示例后本机总数12，原10首保留。
- 手机B站内容授权：新路径二维码与图片成功、用户本人确认；页面显示“登录成功，凭证已保存”及“有效”，服务端新路径轮询/凭据查询200。主账号B站绑定/快捷登录没有另行改变或验收。
- am force-stop 后重启：列表、当前P3歌曲、00:12/00:37暂停进度、原登录与新地址保留；再次连接检查正常。ADB forward/reverse列表为空，不依赖Mac端口转发。
- 本地真机截图在 android/app/build/outputs/verification/path-migration/（忽略、不提交）：连接、新下载播放、提取、授权完成；没有保存登录二维码或任何凭据。
- 最终状态：新入口可用，旧入口未删除、未重定向。抖音OAuth生产能力接口 available=false，redirect_uri未配置，因此真实回调不能验收。保留原配置，不擅自申请/替换平台客户端凭据。
- 下一步：具备抖音OAuth配置及回调登记条件后进行真实回调验证；必要功能全部通过后，才按方案单独执行停旧阶段，并复验其他项目。不要把本次交付描述为已完成旧入口退役。

## 最终服务端复验

- 896ca88 部署前在 tx 完整运行188项测试，全部通过（包括生产FFmpeg指纹）；随后重启服务为active。新旧health、GetVideo8443、Paperdrift均复验200。
- 实际运行进程的旧/新/旧 OpenAPI servers 分别为 /、/getaudio、/，缓存不串前缀。公开文档端点仍按原Basic保护，不为测试放开权限。
- B站授权完成后才重启服务，已写入的凭据保留；没有打断用户正在进行的扫码。
- 验证输入说明：一次 ADB input text 被当前中文输入法转换成非链接文字，产生 HTTP400；随后改用系统 ACTION_SEND 传入原始链接，直提取和服务器提取均成功。该400不是迁移路由错误。

## 发布产物

- GitHub main已快进至cc70b5f（实现8d67654、OpenAPI修复896ca88及验收文档）。本机公众开放设计v3仍未跟踪，未夹带提交。
- v0.9.11以prerelease发布，明确标记“双入口阶段、旧入口未退役”：https://github.com/luckyrichor/GetAudio/releases/tag/v0.9.11 。标签目标cc70b5f；后续仅文档收尾。
- 附件getaudio-0.9.11-release.apk为33571879字节；GitHub API digest与本地SHA256同为44fe772a485bfcf286f6d0eedb0719aec8b230cf4b72a780a1e60ef2bad7813c。固定签名与手机原包一致，手机安装版本为0.9.11/code39。
- 飞书开发记录已追加并回查正文与GetAudio父节点；发布产物另追加补充，避免改写已有验收记录。

## 最终停旧（2026-10-31，用户追加明确授权）

- 用户明确：抖音授权原本未提供，不再作为停旧条件；将来增加也只使用新路径。此指令取代此前“抖音回调无法验收则保留旧入口”的限制。
- 修改仓库Nginx模板和生产现有443 server：移除旧health、旧OAuth callback、根account/v2/agent正则及默认Basic代理；catch-all改为404。新getaudio-prefix及其他项目include完整保留，内部8800和80证书验证未改。
- 备份 `/home/ubuntu/backups/getaudio-root-retire-20261031T151834Z/nginx-before.conf`；nginx -t通过后reload，未重启应用、未修改数据或凭据。
- 新配置回归测试先失败（旧根仍有proxy_pass），修改后通过。Mac全量187 passed+1既有Chromaprint失败；生产测试结果后续追加。
- 公网GET旧根、health、account、v2、audio、tracks、sync、login、credentials、agent、docs、OpenAPI、旧抖音回调及路径变体全部404，无WWW-Authenticate/Location；旧注册/登录/提取POST同为404。
- 新getaudio/health200，account/me与sync匿名401，注册登录空请求422，未来新OAuth回调缺参422，缺尾斜杠getaudio规范化308；其他项目getvideo/health与paperdrift仍200。
- 用临时账号实际验证新注册/登录/me/同步/登出，旧路由即使带有效会话也404；测试账号已清理。活动Nginx中全部8800代理只来自新前缀snippet，其余include哈希未变。
- 本轮未改App或APK；手机前台为NotificationShade，未强占设备重跑UI，沿用此前0.9.11实机验收，新入口本轮补公网认证测试。当前0.9.11无需重装，旧版App需升级。
- 最终公网状态：GetAudio仅/getaudio/，旧根入口已退役；ASGI内部旧路由保留供本机业务匹配，不是公网兼容入口。
- 停旧提交d180a1b已推送main并同步tx，tx本轮188项完整通过、服务active。Mac独立公网探测旧根404、新health200。
- 飞书开发记录revision17已追加停旧更正（来源d180a1b），正文和GetAudio父节点已回查；不修改之前的历史双入口记录。
- v0.9.11发布说明已改为唯一/getaudio/入口并取消prerelease标记，记录本次服务器配置提交；安装包与标签不变，不需要重装。
