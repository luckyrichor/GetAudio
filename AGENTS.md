# GetAudio 工作约定

## 更新交付（用户于 2026-10-02 明确要求）

每次功能或修复完成并通过相关验证后，完成以下流程：

1. 更新 README 和相关本地技术文档，注明实现行为、测试结果及尚未验收内容。
2. 将本次修改提交到 Git，并推送 GitHub；不强制推送，不包含凭据、数据库、音频或构建产物。
3. 将本次更新同步到飞书 GetAudio 项目文档，优先复用对应主题；新主题归档到已有 GetAudio 项目节点。
4. 交付 GitHub 提交链接和飞书文档链接，说明验证范围。

用户已授权该例行同步流程。每次执行前说明飞书标题、范围、位置和使用身份；相同项目及例行更新范围内无需重复索要批准。变更发布范围、身份或目标项目时另行确认。读取文档无需批准。

## 飞书身份、来源与归档

- 使用当前 bridge-bound Codex 环境，luochen 用户身份显式传 `--as user`；执行前核对应用、profile、用户和授权，不混用 Claude 配置。
- 已有 GetAudio 项目节点：https://kcn0pbn0hwo0.feishu.cn/wiki/OFRjw8Ud9ihsT7kFuxPcgS11nGd
- 项目节点 token：`OFRjw8Ud9ihsT7kFuxPcgS11nGd`；space ID：`7526908319489736708`。写入前核对实际节点，不散放根目录。
- 0.5.0 账号/Agent 文档：https://kcn0pbn0hwo0.feishu.cn/wiki/LFIYwAKrqieCUmkbtFzcMyvfnZf
- 0.6.0 播放进度/波形剪辑文档：https://kcn0pbn0hwo0.feishu.cn/wiki/GhHuwjHeXiuMI6kT4iscQXxrnSe
- 历史技术方案 v2：https://kcn0pbn0hwo0.feishu.cn/wiki/GYUmw4foMikGGTkuyXAcHOgwn4f
- 新文档正文第一行：`🤖 由 Codex 生成 · YYYY-MM-DD · 来源 GetAudio@实际短哈希 · 工具 lark-cli profile 实际profile`。日期用北京时间；提交及 profile 必须核实，未提交修改必须注明。
- 修改已有文档保留创建者与原来源，并追加本次助手、日期、版本和更新范围；不得将 Claude 原作改署为 Codex。
- 测试数量、耗时和结论只对应实际验证的版本。构建通过不等于真机验收。
- 写入后回查内容和父节点，交付所属目录与链接。
