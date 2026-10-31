# 0.9.11 HTTPS 项目路径迁移（2026-10-31）

唯一公网基础地址：`https://43.142.37.199/getaudio/`。后端仍监听本机8800；Nginx保留URI，ASGI中间件按请求root_path处理，不使用全局uvicorn --root-path。旧根入口已按用户追加指令删除，根地址及旧API路径返回404，不跳转、不弹身份验证。

在现有 443 server 内增量 include `/etc/nginx/snippets/getaudio-prefix.conf`（源文件为本目录同名文件）。不要用仓库 getaudio.conf 整份覆盖生产配置，生产 server 内还有其他项目路径。nginx -t 通过后 reload；健康检查、应用账号鉴权、浏览器 OAuth 回调与旧 Basic Auth 接口分开处理。

完整范围、备份、验收及回滚见 `docs/getaudio-https-path-migration-plan.md`、`docs/getaudio-path-migration-ledger.md`。停旧只删除旧根路由的代理，最终catch-all为 `location / { return 404; }`；保留getaudio-prefix及其他项目include，80证书验证不变。抖音OAuth未配置，未来只用新路径增加，不能恢复旧根回调。不要因内部8800仍支持旧路由就认为旧公网入口还存在。

以下为历史部署记录，旧地址只描述当时情况，不作为新版默认配置：

# tx 手机 HTTPS 直连（2026-09-30 已部署）

## 当前入口与产品流程

手机默认地址为 `https://43.142.37.199/`，手机通过 Wi-Fi 或移动网络直接提交分享文本，tx 下载视频、提取并保存音频，手机自动下载音频到本地。Mac 和 USB 只用于开发、安装和本次验证操作，不参与业务网络链路。

原服务继续监听 `127.0.0.1:8800`；nginx 提供 HTTPS 443。80 仅服务 `/.well-known/acme-challenge/`，其他路径返回404。`/health` 返回健康状态；其余业务接口需要手机专用随机访问凭据，未授权返回401。

## 已部署配置

- nginx 1.24，独立 Certbot 5.8.0 位于 `/opt/getaudio-certbot`；不修改 GetAudio Python 环境。
- Let's Encrypt IP 证书路径：`/etc/letsencrypt/live/43.142.37.199/`。
- nginx 站点：`/etc/nginx/sites-available/getaudio`，源配置见 `getaudio.conf`。
- 凭据验证文件：`/etc/nginx/getaudio.htpasswd`；手机凭据在应用私有目录 `files/server-access.json`，不包含在源码、APK、日志或 Android 备份中。
- `getaudio-cert-renew.timer` 每天两次检查续期；成功后验证 nginx 配置并重新加载。timer/service 源文件在本目录。
- 腾讯云上海轻量实例 `lhins-6h8y270u`（控制台名称 lc，SSH 别名 tx）：原防火墙只有 IPv6 HTTPS 443，无 IPv4 HTTPS 443。本次在用户授权并完成登录后，仅新增 `0.0.0.0/0 TCP 443` 允许规则，备注“GetAudio HTTPS 手机直连”。其余规则保持原样。
- Android 旧 USB / 模拟器默认地址自动迁移到生产入口，用户自定义地址保留。共享客户端对每次网络请求及跳转检查 HTTPS 主机和端口；访问凭据仅发送到上述服务器，包括音频下载。

## 已核验

测试与正式证书签发成功；`certbot renew --dry-run` 成功；服务器本机和公网 HTTPS /health 正常；业务接口未授权401、授权读取成功。手机 ADB reverse 为空，在移动网络上同步成功，nginx 记录到手机网络来源的 okhttp 请求。

通过公网接口强制重新下载现有 B站公开视频、提取音频成功（`reused=false`）。手机移动网络提交提取、自动下载与播放已通过：POST /extract 200、GET /audio/1 200；5,402,460字节及SHA256均与服务器一致。原先本机已删除音频无法再次主动提取的问题已修复。最终证据见 `android/app/build/outputs/verification/verification.md`。

## 运维与回滚

只读检查：`ssh tx 'systemctl status nginx getaudio-api getaudio-cert-renew.timer --no-pager'`。证书续期日志：`journalctl -u getaudio-cert-renew.service`。手动测试续期：`sudo /opt/getaudio-certbot/bin/certbot renew --dry-run`。

手机访问凭据损坏或换机，需要重新配置该设备的私有凭据；不能通过安装 APK 自动获得服务端秘密。随机凭据若需轮换，先生成并配置设备，再替换 nginx 验证文件。

回滚：关闭新增 nginx 站点与续期 timer，撤销本次新增的 IPv4 443 防火墙规则。原音频服务、数据库和音频路径保持不变。生产入口不会回退到依赖 Mac 的地址；调试连接需明确选择并建立。

## 授权记录

先前自动审批拒绝安装，用户一度暂缓；2026-09-30 用户明确授权按此方案实施，后续安装、HTTPS及续期配置执行成功。控制台登录由用户完成。

参考：[Let's Encrypt IP certificates and Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

## 0.5.0 账号入口

2026-10-02 新增手机号/密码与 Agent 会话鉴权，应用令牌通过 `X-GetAudio-Session` 传递；原 nginx Basic 设备访问配置继续使用。nginx 显式覆盖 `X-Forwarded-For` 为 `$remote_addr`，配合 Uvicorn 对回环代理的默认信任，使登录限流使用真实客户端来源，避免客户端自填来源地址。配置验证通过后 reload，业务 API 在数据库备份后重启加载新表。


## 账号与 Agent 运维补充

实现版本为 GetAudio `91a3dd4` / 0.5.0。详细协议、数据表、故障恢复与验收范围见 [音频 Agent 与手机号账号说明](../../docs/agent-and-accounts.md)。

- 部署时先以 SQLite backup API 取得数据库一致快照，再更新代码、重启 API；新增账号、会话和操作表由启动自动创建。备份与已有加密密钥一并私下保管，不放入 Git。
- `ARK_API_KEY` 与模型列表在服务端 `.env` 配置，真实 Key 不写进文档或 APK；更新配置后需重启 API。模型失败会依次尝试后备，全部失败返回明确降级结果。
- 账号请求需保留 nginx 设备访问凭据；登录后额外带 `X-GetAudio-Session`。401 可能来自 nginx 访问控制或应用账号会话，需要先确认失败发生在哪一层。安装 APK 不会自动配置新的设备访问秘密。
- Agent 选择手机提取仍由手机访问原平台；GetAudio 服务端用于模型、记忆和结果记录。服务器提取必须由用户主动确认。
- 结果回传失败可在手机助手中同步原回执，不要因回传失败直接重复下载。若状态停在“已开始”且无回执，先检查手机音频库再决定是否创建新操作。
- 公网 `/health` 已验证正常，未授权 `/account/me` 返回 401；手机号登录、Agent 按钮和跨进程恢复仍需手机验收。
