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
