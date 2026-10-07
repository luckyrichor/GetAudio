# GetAudio 账号服务器访问 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if the user chooses delegation. Steps use checkbox syntax for tracking.

**Goal:** 普通注册用户无需共享 Basic 密码即可访问自己的服务器提取、同步和下载，并把平台授权归属同一 GetAudio 账号。

**Architecture:** 保留受 Basic 保护的 legacy 入口，新增账号认证 /v2 API。服务端 Track 按 owner_scope 隔离；Android 云端库、游标、删除抑制按服务器源及账号分区，旧数据保留。访客迁移用短期一次性票据，拒绝裸 UUID 认领。

**Tech Stack:** FastAPI、SQLAlchemy/SQLite、Nginx/systemd；Kotlin、Room、Retrofit/OkHttp、Compose。

**Spec:** ../specs/2026-10-07-account-server-access-design.md

## Global Constraints

- 本次不实现以平台身份直接登录 GetAudio，不加入短信登录或忘记密码。
- 手机直提取和设备已下载的音频继续离线可用。
- 不在 APK 内置 Basic 密码，不将凭据记录到日志或文档。
- 8800 保持回环监听；legacy 接口继续 Basic 管理入口保护。
- 没有归属证据的音频保留 legacy，不删除、不自动认领。
- 迁移冲突保留目标凭证；二维码、账号令牌和 Cookie 不能跨账号/跨源泄露。

## Review Focus

1. 两个账号提取同一链接、force 与并发：不能共享可变输出文件或未验证的私有缓存。
2. SQLite 旧 unique 重建：必须保留 ID、外键、索引、所有列和旧记录；二次启动不得重迁移。
3. 账号切换期间同步/下载返回：固定请求身份，晚到结果不得写入另一账号库或继续播放隐藏云端曲目。
4. 访客 UUID 或重放票据：不能迁移凭证；目标已有授权时不覆盖且来源凭证不丢失。
5. 公网路径匹配与二维码图片：新接口不需 Basic 但需要账号认证；旧接口仍受保护，图片必须验证会话归属。

---

### Task 1：账号分区模型、迁移和文件路径

**Files:** getaudio/models.py、db.py、pipeline.py、extractors/ytdlp.py；新增 getaudio/schema_migrations.py、storage_scope.py；tests/test_scoped_tracks.py、test_schema_migrations.py。

**Interfaces:** `Track.owner_scope: str` 默认 legacy；`extract(raw_input, user_id=None, force=False, owner_scope='legacy') -> ExtractOutcome`；`storage_scope.audio_scope(scope)` 为 ContextVar 上下文管理器，所有提取器共用的音频搬运函数根据该上下文写到独立目录。

- [ ] 写并运行失败测试：旧 SQLite tracks 表升级保留 ID/列和 ExtractJob/指纹引用；重复执行无变化；同链接两个 owner_scope 可以各保存一条。

```python
def test_two_scopes_can_store_same_video(session):
    session.add_all([Track(owner_scope=u, platform='douyin', video_id='same',
                           source_url='https://example.invalid/', audio_path=u+'/same.m4a')
                     for u in ['acct_a', 'acct_b']])
    session.commit()
    assert len(session.scalars(select(Track).where(Track.video_id == 'same')).all()) == 2
```

- [ ] 增加 owner_scope；唯一约束改为 owner_scope/platform/video_id；迁移在停写状态独立连接执行，外键关闭在 BEGIN 前，完成复制后核对 PRAGMA foreign_key_check / integrity_check，失败 rollback。旧表全部列动态读取对齐，新列统一 legacy，保留原 ID。恢复外键，持久版本标记；备份另由部署步骤执行。
- [ ] pipeline 查重/upsert、链接锁、指纹存储以 scope 为边界；音频输出目录由 ContextVar 确定，不修改全局 settings；同账号 force 保持编号，不改变别人文件。账号专用模式禁止回落到共享 Cookie 文件。

```python
scope = owner_scope
existing_query = select(Track).where(Track.owner_scope == scope,
                                     Track.platform == link.platform.value,
                                     Track.video_id == link.track_key)
```

- [ ] 用模拟提取器验证同账号复用、跨账号独立调用、force 不变更他人字节、同时运行输出路径不同；运行 `pytest tests/test_scoped_tracks.py tests/test_schema_migrations.py tests/test_pipeline.py`。通过后提交这组修改。

### Task 2：新 API、平台授权和助手归属

**Files:** 新增 getaudio/account_api.py；修改 api.py、login/__init__.py、agent.py、extraction_agent.py；tests/test_account_api.py、test_accounts_agent.py。

**Interfaces:** APIRouter(prefix='/v2')；所有资源处理函数依赖 accounts.current_user，owner_scope=user.id；API响应保持现有 TrackOut 字段，download_url 指向 /v2/audio/{id}。

- [ ] 写并运行失败测试：无令牌 401；两账号曲目、下载、平台凭证、二维码及助手候选相互不可见；user_id 额外字段 422；退出后失效。

```python
def test_private_audio(client, owned_track, alice_headers, bob_headers):
    path = f'/v2/audio/{owned_track.id}'
    assert client.get(path).status_code == 401
    assert client.get(path, headers=bob_headers).status_code == 404
    assert client.get(path, headers=alice_headers).status_code == 200
```

- [ ] 按 spec 的接口表实现 /v2/extract、tracks、sync、audio 和 platforms 路由；请求模型 extra='forbid'；提取从令牌取 user.id，不接受身份参数。曲目不存在和越权统一404，查询/分页/游标仅当前 scope；平台授权失效使用独立业务错误码。
- [ ] QR poll 增加可选 owner 参数，在调用平台 poll 和保存 Cookie 之前检查；图片也先检查归属。解除授权同步撤销当前账号该平台的未完成二维码，防止随后扫码恢复被解除凭证。
- [ ] 助手 make_plan/候选曲目、成功回执检查 owner_scope。记忆新增当前账号 /v2/memories 包装，手机不需要开放旧任意 user_id 路径。现有会话接口仍使用当前账号。
- [ ] 运行 API、账号、登录和 Agent 回归；更新旧测试中实际属于账号的曲目 fixture 为明确 scope，保留 legacy 测试。通过后提交。

### Task 3：安全访客迁移

**Files:** 新增 getaudio/credential_migration.py；models.py、account_api.py、credentials.py；tests/test_credential_migration.py。

**Interfaces:** 管理员本地函数 `issue_ticket(guest_id, expires_in=600) -> str`；公开 `POST /v2/migration/claim` 只接受 ticket，由令牌决定目标账号；可重试幂等结果。

- [ ] 写并运行失败测试：任意 guest_id 不可认领，过期/错误/重放不能转移到第二账号，目标已绑定返回409且两份凭证保持，事务失败恢复原状。
- [ ] 增加票据哈希、来源、过期时间、消费账号/结果字段。BEGIN IMMEDIATE 事务中锁定消费；依照目标 AAD 重加密，成功后来源撤销。服务端 Cookie 导出应重新生成且避免陈旧来源文件继续可用，失败保留可重试清理记录，不把文件操作声称为数据库原子事务。

```python
blob = encrypt(decrypt(source.cookie_blob, aad=credential_aad(source.user_id, platform)),
               aad=credential_aad(target_user.id, platform))
```

- [ ] 若当前手机有访客授权，调试只读核对其私有访客 ID，由管理员发行短期票据并私下写入设备加密存储；没有旧授权则不生成票据。页面需用户确认目标账号；其他旧设备无可信证明时重新授权，不自动认领。
- [ ] 用同一事务/并发领取测试验证只有一次迁移，通过后提交。

### Task 4：Android 云端分区与网络会话

**Files:** data/local/Entities.kt、AppDatabase.kt、TrackDao.kt（含 SyncStateDao）；data/repo/TrackRepository.kt、Settings.kt、AccountStore.kt、ApiProvider.kt；data/remote/GetAudioApi.kt；新增 AccountScope.kt、对应 JVM/真机回归。

**Interfaces:** scope=规范服务器源+账号 ID；请求快照固定 scope/token；TrackEntity 增加 cloudScope、remoteTrackId，游标和删除抑制按 scope 保存。手机本地 id 继续稳定；不同服务器重复 remote ID 用映射分配独立本地正编号，播放列表仍引用本地 ID。

- [ ] 写失败测试：两个服务器同 remote ID 不覆盖；账号 A 同步完成前切到 B 不能写 B；不同 scope 游标与抑制不同；Room 6→7 保留负编号、旧正编号、列表及文件路径。
- [ ] Room 迁移为旧记录标记 local 或 legacy_device，不凭当前登录状态自动认领共享记录；可证明归属后再映射。UI DAO 查询只显示本地、设备历史及当前 scope；已绑定其他 scope 缓存隐藏保留。类别和队列入口使用同一过滤，账号切换暂停隐藏队列。
- [ ] API 方法接 /v2；业务普通请求不再附加 Basic；会话仅同源、禁止跨源重定向泄露。同步和下载开始取得身份快照，提交数据库及播放操作前核对同一 scope；失败保留原数据。

```kotlin
val scope = AccountScope.requireCurrent(baseUrl)
val response = api.sync(cursor = syncDao.cursorFor(scope.key))
fileMutex.withLock {
    check(AccountScope.currentKey() == scope.key) { "账号已切换，请重试" }
    db.withTransaction { applyScopedSync(scope, response) }
}
```

- [ ] 下载临时文件按 scope 与 remote ID 命名；账号切换晚到文件仅清理其临时产物，不删除已有缓存。运行 JVM 与 Room 实机迁移/账号切换回归，通过后提交。

### Task 5：账号、平台授权与服务器页面

**Files:** AccountScreen.kt、LoginScreen.kt、ServerSettingsDialog.kt、MyScreen.kt、ExtractViewModel.kt、LibraryScreenV2.kt、AgentScreen.kt；版本号文件。

- [ ] 检查现有测试能断言注册账号后可调新接口，不填服务器共享凭据；401、网络失败与平台需授权提示互不混淆。
- [ ] 平台入口改名“平台授权”，游客先登录；抖音/B站两个独立区域，允许同时授权，说明归属当前账号。二维码 Coil 请求使用同源账号网络客户端，不能依赖图片组件的无授权默认连接。
- [ ] 移除服务器用户名/密码字段与普通页面 Basic 验证流程；保留默认 tx 地址、网络连通检查和账号状态。迁移票据确认只显示目标账号及平台，不显示票据。
- [ ] 注册、登录、退出及切换账号统一触发 scope 切换。手机直提取不要求账号。版本升至0.7.0/code18。
- [ ] 构建 debug/release、Lint、JVM 全回归；真机验证游客提示、账号登录、两个平台状态并存、原本地音乐播放、进度条与批量删除。通过后提交。

### Task 6：tx 部署、真实验收与交付

**Files:** deploy/tx-https/getaudio.conf、README.md、docs/agent-and-accounts.md、新增 docs/account-server-access.md。

- [ ] 写配置路径测试，明确公开 /account/register、login、me、logout 及 /v2/、/agent/v2/，其余继续 Basic。Nginx 覆盖转发头，API 对资源独立鉴权；8800保持回环。
- [ ] 部署前记录代码/服务配置，SQLite backup 一致快照与 Nginx 备份；检查备份权限和完整性。停止 API 写入，迁移与新代码启动成功后才 nginx -t/reload。迁移失败恢复旧代码与未变更入口；不覆盖在线数据库。
- [ ] 公网无 Basic 注册/登录成功；无令牌资源401；两临时账号隔离与旧入口 Basic401。临时测试账号只用于本次检查，测试产物和凭据私下清理。
- [ ] 原包同签名升级手机，用户本人填写/确认账号密码。真实公开链接由 tx 下载/提取→手机缓存→播放；不把模拟结果当真实平台授权。核对用户旧音频仍在。
- [ ] 完成最终整体代码审查；若选择 native 执行，仅在末尾安排独立审查。更新技术文档完整接口/迁移/回滚/验证结论，标注未验收的平台边界。
- [ ] git pull --ff-only、仅 stage 本次文件、diff --cached --check、commit、push，核对远端哈希。飞书追加现有账号文档 LFIYwAKrqieCUmkbtFzcMyvfnZf，写前核对 luochen/profile/父项目，保留原来源，署名实际提交及北京时间；写后回读内容和目录。

## 执行与验收记录

默认建议在当前会话由主助手逐项实施，末尾独立审查。代码实现尚未开始，任何测试项都未标记通过。每项执行时记录失败测试、通过结果、实际提交及偏离方案的依据；不把计划推送或部署说明写成已完成。
