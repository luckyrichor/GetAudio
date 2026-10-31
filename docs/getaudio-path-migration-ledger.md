# GetAudio 子路径迁移实施记录

- 基线：2026-10-31，GitHub 快进同步后仍为 dc2abeb；开发分支 codex/getaudio-path-migration。保留两份原未跟踪方案文档。
- 采用现有工作目录中的独立开发分支，避免改变用户未提交文档的位置；没有另建 worktree。
- 阶段：1 双入口及链接；2 Android 地址/会话兼容；3 测试与构建；4 新入口部署及手机验收；5 满足所有门槛后停旧；6 GitHub/飞书交付。
- Ruling：保留旧生产地址作为数据归属的稳定身份，新旧生产入口共享原 cloudScope 哈希，不重写 Room 主键、映射、游标或列表引用。仅传输地址规范到 /getaudio/。比跨多个存储系统批量改 scope 更容易保持崩溃恢复和可逆性；非白名单地址不合并。
- Ruling：Nginx 将新前缀原样传给后端，由纯 ASGI 中间件剥离路由前缀、设置请求 root_path 和 ContextVar 链接前缀；旧请求保持原样。无需信任客户端 X-Forwarded-Prefix，也不依赖 Nginx 正文替换。
- 当前手机：23013RK75C，com.getaudio.app 0.9.10 (38)，与历史 REDMI K80 不同；安装前需比较签名。
- tx：VM-0-15-ubuntu，生产为 62a9643，服务 active，本机监听 8800。未跟踪 data/ 必须保留。
- 停旧门槛：手机独立网络的全部既定验收通过；任何必需环节无法验证时保留旧入口。
