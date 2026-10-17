# B站授权入口修复与验证（0.8.6）

## 原因与修改

0.8.5 在 REDMI K80 点击账号管理出现 IllegalArgumentException：空字符串没有 URL scheme。AccountScreen 首帧的服务器地址为空，PlatformAccountPanel 已调用 ApiProvider.api 创建 Retrofit，尚未等设置加载完成便崩溃。

PlatformAccountPanel 先检查空地址、URL 结构及 AccountStore.requireSecureServer，再创建网络客户端；AccountScreen 订阅服务器设置 Flow，设置改变后继续更新。LoginScreen 的未登录分支去掉父级 verticalScroll，保留 AccountScreen 内部滚动，避免同方向无限高度测量。版本递增为 versionCode 25 / versionName 0.8.6。

## 真机结果

保留 0.8.5 原 APK，核对安装包签名一致，使用 adb install -r 升级，无卸载操作。原登录状态保留。

1. 账号管理正常打开，点击绑定B站成功显示二维码。该流程关联 GetAudio 手机号主账号，用于后续平台登录。
2. 我的 → 平台授权正常打开，点击 B站扫码授权成功显示二维码和等待扫码状态。该流程保存当前 GetAudio 用户的服务器内容访问凭据。
3. 二维码生成已实测；用户扫码确认、绑定完成及授权后私有内容下载本轮未验收。GetVideo 的绑定不复用到 GetAudio。
4. TX 现有 GetAudio 平台能力接口返回 bilibili 可用、QR 方式。此次仅升级 Android，不替换 GetAudio 生产后端。

## 验证

Android testDebugUnitTest：69 tests，0 failures，0 errors；assembleDebug、assembleRelease 成功。

服务端全量 pytest：161 passed、1 failed。失败为 test_real_fingerprint_ignores_metadata，本机 FFmpeg 缺少 Chromaprint 支持，属于未解决的运行环境限制。首次单独运行平台测试遇到测试表尚未初始化，改为项目工作目录完整套件后，账号与平台相关测试均通过。

只读代码复核未发现重要问题。此次修复未修改接口协议、数据库或平台扫码业务规则。
