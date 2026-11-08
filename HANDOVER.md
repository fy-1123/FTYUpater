# HANDOVER.md — 交接快照

> 更新时间：2026-11-08（M5 + M6 完成：版本回退 / ApplyHelper / 回收站还原 / v1 迁移 / E2E 清单；测试 251 项、jar 236,915 字节）
> 更新人：AI 协作者
> 读者：下一位接手本项目的人 / AI。开工前请先读 `agent.md` 第 2 节。

---

## 1. 一句话现状

**M1–M6 全部实现完毕；M5（版本回退 + ApplyHelper）与 M6（回收站还原 + v1 迁移 + E2E 清单）于 2026-11-08 完成。所有逻辑都有纯 JVM 单测覆盖；所有需要鼠标点击的界面流程仍「未在真机验证」（用户 2026-11-07 明确要求不上真机），清单在 `docs/testing.md`。**

- 测试：`.\gradlew.bat test --offline` → **251 个测试方法 / 250 PASSED / 0 FAILED / 1 SKIPPED**（skip 的是 `LiveSyncIT`，需要 dev 服务器在跑）。数字以 `build/test-results/test/*.xml` 求和为准（2026-11-08 实测：31 个 XML，`tests 251, failures 0, skipped 1`）。
- 编译：`compileJava --offline` → SUCCESS（2m42s，仅 3 条 `ModLoadingContext.get()` removal 警告）。
- 打包：`build --offline` → SUCCESS（1m58s，`addMixinsToJar` + `jar` + `reobfJar` 均执行），产物 **`build/libs/FTYUpater-2.0.0.jar`**（236,915 字节，含 `ftyupater.mixins.json` + `ftyupater.refmap.json` + `helper/*` + `RollbackScreen` + `TrashScreen` + 全部 M5/M6 新类）。v1 旧 jar 已移入 `.trash-v1-20261106/`。
- **主菜单按钮已移除**：`client/ClientEvents.java`（唯一出处）已删除，`ModConfig.CLIENT.autoCheckOnMainMenu` 一并删除。启动游戏不再发任何同步网络请求，同步只发生在进服时。
- **界面已重写**：`client/gui/JoinCheckScreen.java` 改用原版 `ObjectSelectionList`，全部纵向坐标由 `client/gui/CheckLayout.java` 现算（按钮贴底、列表吃中间），`CheckLayoutTest` 在 160~3840 宽 × 40~2160 高逐像素断言「文字/列表/按钮三者不重叠」；可选模组行自带「我不要 / 恢复」按钮。
- **GUI 点击验收仍未完成**：布局的「不重叠」已由 `CheckLayoutTest` 在数学层面保证，但「进服 → 点一键同步 → 重启 → 进服」这条完整点击流程还没人跑过。用户 2026-11-07 明确要求不上真机测试，因此这一项保持「未验证」，不谎报。全部 14 节手工验收项见 `docs/testing.md`（每项都是未勾选状态）。
- **已知缺口**：无功能性缺口。剩下的全是「需要真机点一遍」的验证项。
- 历史事故：2026-10-21 两次文档被 PowerShell 回写破坏/覆盖（UTF-8 损坏与 M4 状态误报），均已按 DSH 会话记录原文恢复/更正；纪律见 ADR-013、ADR-017。

---

## 2. 项目背景

- 目录：`C:\CodeSpace\Java\FTYUpater`，git 仓库，远程 <https://github.com/fy-1123/FTYUpater>（public，`main`）。
- Forge 1.20.1 模组：`mod_id=ftyupater`，版本 `2.0.0`，包名 `com.ftysync`，许可证 MIT。
- 构建：Gradle 8.8 wrapper，ForgeGradle `[6.0.16,6.2)`，mappings official 1.20.1，MixinGradle `0.7.38`。
- 编译：本机 JAVA_HOME 是 JDK 21.0.12，但 `options.release = 17`。
- `gradle.properties` 已加 `systemProp.net.minecraftforge.gradle.check.certs=false`：否则 ForgeGradle 插件 apply 时会联网做 TLS 检查且无超时，构建会静默卡几分钟。
- 用户原始诉求（完整问答见 `docs/qa-log.md`）：

> 彻底改造：引入「模组提供、服务器接入、版本管理」并改进；额外目录存放客户端的 config、mods、资源包、光影包等；考虑玩家电脑不支持或不想用的客户端模组，在「删除不影响进服」的前提下给玩家留路。

---

## 3. v2 代码现状

### 3.1 已完成模块

```
src/main/java/com/ftysync/
  core/（M1，纯 JVM，无 Minecraft 依赖）
    Json, ManifestException, Hash, PathGuard, Blacklist,
    PackEntry, PackManifest, LocalFile, SyncAction, DiffResult,
    DiffEngine, LocalState, StateStore
  server/（M2）
    ScannedFile, ContentScanner, ContentStore, GenerationJournal,
    GenerationStore, PackPublisher, SyncPackRepository,
    FilePackRepository, HttpSyncServer
  client/（M3 引擎 + M4 接线 + M5 回退 + M6 迁移）
    SyncPaths, ServerSourceCache, PendingOp, PendingQueue,
    BackupManager, LockProbe, ApplyService, SyncEngine,
    Advert, AdvertParser, LocalScanner, JoinDecision,
    StatusPinger, JoinProbe, PackClient, SyncAdvert, JoinBypass,
    HelperLauncher, PendingNotice, ClientStartup,
    ServerGeneration, RollbackPlanner, RollbackService, RollbackDetect,
    TrashBrowser, V1Migration,
    gui/JoinCheckScreen, gui/CheckLayout, gui/RollbackScreen, gui/TrashScreen
  helper/（M5，独立 JVM，只用 JDK）
    ApplyHelper, PendingApplier, HelperResult, MiniJson
  mixin/ConnectScreenMixin
  ServerSyncRuntime.java（Forge 接线：启动/停止发布器 + 设置广告）
  FTYUpater.java（启动 v2 运行时 + registerDisplayTest 广告 + DistExecutor 挂客户端启动检查）
  ModConfig.java（Server 11 键 + Client 4 键，v1 孤儿键已删）
src/test/java/com/ftysync/
  core/（6 个测试类）
  server/（5 个测试类）
  client/（16 个测试类，含 LiveSyncIT、SyncScenarioTest、TestSyncServer）
  client/gui/（1 个测试类：CheckLayoutTest）
  helper/（2 个测试类：MiniJsonTest、PendingApplierTest）
```

**2026-11-06 删除的 v1 类全部在 `.trash-v1-20261106/src/...` 下（19 个文件，可恢复）**：
`client/ClientEvents.java`、`client/gui/SyncScreen.java`、`client/SyncManager.java`、`client/ModStatus.java`、
`client/PreJoinGuard.java`、`client/PreJoinCheckScreen.java`、`client/ClientTarget.java`、
`client/ClientSyncJoinHandler.java`、`client/ServerManifestCache.java`、`common/ManifestData.java`、
`server/ServerManifest.java`、`server/SyncHttpServer.java`、`network/` 全部 5 个类、
`mixin/ClientConnectionMixin.java`。理由见 ADR-018。

### 3.2 服务端运行机制

1. `ServerStartingEvent` → `ServerSyncRuntime.start()`：创建扫描器/存储/journal/发布器/HTTP 服务，并起单线程调度器 `ftyupater-publisher`。
2. 每 `rescanIntervalSeconds`（默认 10s）扫描一次：
   - `gameDir/mods/*.jar`（排除 FTYUpater 自身、黑名单）→ `origin=server-mods`、`role=required`；
   - `gameDir/clientpack/**`（按游戏目录结构，递归）→ `origin=clientpack`、`role=optional`。
3. 内容不变 → 不开新代际；内容变化（规范化行哈希变化）→ 新 generation（UTC `yyyyMMdd-HHmmss` + `-` + 内容哈希前 8 位）：
   - `generations/<gen>/manifest.json` 先写，再追加 `journal.jsonl`；
   - 超出 `retainGenerations`（默认 2）的最老代际被剪掉。
4. HTTP `:8081 /api/v1`（只允许 GET）：
   - `/health`、`/pack[?generation=]`、`/generations`、`/files/<sha>`（带 ETag，流式）。
5. 数据目录：开发下 `run/ftyupater/server/`（`journal.jsonl`、`generations/`、`objects/<sha>`）；线上是游戏目录下 `ftyupater/server/`。

### 3.3 服务端配置键（`config/ftyupater-server.toml`）

`enabled`、`httpPort=8081`、`bindAddress=0.0.0.0`、`syncFolder=ftyupdater`、`advertiseSyncSource=true`、`publicSyncUrl=""`、`clientpackFolder=clientpack`、`retainGenerations=2`、`rescanIntervalSeconds=10`、`blacklist=[]`、`packId=fty`。**改配置需重启服务器**（没有热重载）。

客户端配置（`config/ftyupater-client.toml`）2026-11-06 起只有 4 个键：
`enabled=true`、`syncUrl=""`、`joinCheckEnabled=true`、`autoContinueIfInSync=true`。
已删除的旧键（`autoCheckOnMainMenu` / `mirror` / `autoCheckOnJoin` / `openSyncScreenOnJoin` /
`blockJoinOnModMismatch` / `adoptServerSource` / `adoptServerSourceAlways`）在已有玩家的 toml 里会残留，
Forge 不会因此报错，只是忽略。

### 3.4 M2 实测证据（2026-10-21）

- 首次 `Done` 约 96.8s；健康检查：`{"ok":true,"mod":"ftyupater","schema":2,...}`。
- 代际链：`20261021-143332-4784ccb4`（5 文件）→ 加 jar → `20261021-143714-33193e5a`（6）→ `20261021-143924-901251e3`（7，剪掉最老代）→ 改黑名单重启 → `20261021-144451-89e1ca0c`（6，`mods/server-only.jar` 被排除）。
- 旧代回退：`/pack?generation=<旧代>` 能取回旧清单；`/files/<sha>` 下载内容哈希与清单一致；`POST` → 405、坏 sha → 400、未知代/sha → 404。
- 自身 jar（`run/mods/ftyupater-copy.jar`）未出现在清单。
- 手工验证步骤见 `docs/dev-workflow.md` 第 9 节。

### 3.5 客户端引擎（M3）与进服检查（M4）

数据目录（游戏目录下 `ftyupater/`，实现见 `client/SyncPaths`）：

```
state.json              已安装条目 sha/size、generation、opt-out/ignore
servers.json            服务器地址 → 同步源（ServerSourceCache）
staging/<generation>/   下载暂存（.part → 校验 SHA256 → 复制到目标）
pending/pending.json    被锁定、需重启后应用的操作（PendingQueue）
backup/<generation>/    被替换/删除前的旧文件
trash/<yyyyMMdd-HHmmss>/ 清理出的多余文件
logs/sync.log           每次同步的统计行
```

`SyncEngine.sync(manifest, diff)` 流程：

1. 取 `diff.actions()` 里需要下载的动作（INSTALL_REQUIRED / INSTALL_OPTIONAL / UPDATE），按 sha 去重；
2. 磁盘预检：需求总大小 + 64MiB > 可用空间 → 整批放弃（不下载、不删本地文件）；
3. 清空并重建 `staging/<generation>/`，4 线程并发下载 `GET /api/v1/files/<sha>`（连接 3s、读取 10s），每文件最多 3 次；
4. `.part` 校验 SHA256 后才改名；哈希不符/HTTP 非 200 记为失败，绝不写入目标位置；
5. `ApplyService`：未锁定的立即应用（复制 + 原子改名；替换前旧文件进 `backup/`，删除进 `trash/`）；被锁定的写 pending；应用成功后逐条更新 `state.json`；
6. 有 pending 时**保留 staging**（等 M5 helper），否则清掉；追加一行 `logs/sync.log`。

进服检查链路（M4）：

1. 所有「连接服务器」入口经 `ConnectScreenMixin` 拦截（`startConnecting` HEAD，`JoinBypass` 放行标记）；
2. `JoinCheckScreen` 打开后 `JoinProbe` 起 `StatusPinger`（自建 MC status ping，每 tick 驱动，80 tick ≈ 4s 超时）；
3. 从 `ServerStatus.forgeData()` 的 mods 表读 key `ftyupater`，值形如 `v2|http://<host>:<port>`（服务端由 `FTYUpater` 里的 `registerDisplayTest(() -> SyncAdvert.marker(), ...)` 发布；无源时为 `IGNORESERVERONLY`）；
4. `PackClient` 拉 `/api/v1/pack`，`LocalScanner` 扫本地，`DiffEngine` 算差异，`JoinDecision` 决定：无 FTYUpater → 直接进服；有源且一致 → 自动进服（可配置）；有差异 → 显示界面；
5. 界面按钮：一键同步 → `SyncEngine.sync`（后台线程）→ 完成提示重启；「进入服务器」继续原流程；「取消」回上一级。
6. 服务器地址 → 同步源会记进 `servers.json`（下次可用作兜底）。

M4 关键修复：dev 下 mixin 原本完全不加载，补了 MixinGradle + refmap + `--mixin.config`（ADR-016）；`ApplyService` 对同 sha 多路径改为复制应用（ADR-015）。

---

## 4. 已知问题 / 遗留

1. **所有 GUI 点击流程未验证**：布局不重叠由 `CheckLayoutTest` 数学保证，但「进服 → 点一键同步 → 重启 → 进服」「版本回退」「回收站还原」这三条点击流程都需人工跑一次（用户要求不上真机，所以不由 AI 声称已验证）。完整清单见 `docs/testing.md`，14 节全部未勾选。
2. 服务端 HTTP 无鉴权（ADR 决定）；公网部署前需在 README 说明风险/建议前置反向代理。
3. 服务端发布期间扫描是同步阻塞在发布线程里的（大整合包首次扫描可能慢）；可做 mtime/size 缓存优化。
4. `LiveSyncIT` 依赖 dev 服务器（默认 `http://127.0.0.1:8081`，可用环境变量 `FTYUPATER_LIVE_URL` 覆盖）；服务器没跑时自动 skip。
5. 客户端 dev 首次运行要等 ForgeGradle 起服约 2.5~4 分钟；`runClient` 与 `runServer` 共用 `run/` 日志目录，日志里会混有另一进程输出。
6. `RollbackService` 走本地备份时**只能还原被覆盖过的文件**，那代之后新增而备份里没有的文件无法删除，所以报告会标「可能不完整」——这是设计上的兜底路径，首选永远是服务器代际清单。

---

## 5. 下一步（M7 候选，尚未排期）

M5/M6 的功能任务已全部完成。若要继续，候选方向：

- **把 `docs/testing.md` 的 14 节手工清单真机跑一遍**，把结果回填（这是当前唯一能提升置信度的事）。
- 服务端扫描加 mtime/size 缓存（已知问题 3），大整合包首次扫描不再阻塞发布线程。
- 服务端 HTTP 鉴权或反向代理示例（已知问题 2）。
- 服务器列表里显示「需同步 / 已一致」（`docs/plan.md` M6 里被标为可选、成本高则延后的一项）。

---

## 6. 如何验证 / 如何继续

- 读文档：`agent.md` → `HANDOVER.md` → `docs/status-matrix.md` → `docs/plan.md` → `docs/testing.md`。
- 只验证逻辑：`.\gradlew.bat test --offline`（约 5 分半；当前 **251 个方法 / 250 过 / 1 skip**；skip 的是 `LiveSyncIT`，因为要 dev 服务器在跑）。
- 只改界面布局：`.\gradlew.bat test --offline --tests "com.ftysync.client.gui.CheckLayoutTest"`（5 项，纯整数运算，不依赖 Minecraft）。
- 只改 helper：`.\gradlew.bat test --offline --tests "com.ftysync.helper.*"`（36 项）。
- 只改回退 / 回收站 / 迁移：`.\gradlew.bat test --offline --tests "com.ftysync.client.Rollback*" --tests "com.ftysync.client.TrashBrowserTest" --tests "com.ftysync.client.V1MigrationTest"`（56 项）。
- 只改客户端代码：`.\gradlew.bat test --offline --tests "com.ftysync.client.*"`。
- 各种同步场景：`.\gradlew.bat test --offline --tests "com.ftysync.client.SyncScenarioTest"`（13 项，纯 JVM：进程内 `HttpSyncServer` + 临时游戏目录，不启动游戏）。覆盖表见 `docs/status-matrix.md` §G。
- 编译检查：`.\gradlew.bat compileJava --offline`（40 秒~2 分 40 秒，取决于是否触发 APT 与 downloadMcpConfig）。
- 打包：`.\gradlew.bat build --offline`（约 2 分钟；2026-11-08 实测产物 236,915 字节）。
- **所有 gradle 调用走 `mc_gradle` 工具**（ADR-021）：bash 跑 `.bat` 会挂起，pwsh 直跑会把输出缓冲到进程结束。
- **用户明确要求不上真机测试**（2026-11-07）：不跑 `runClient` / `runServer` 做 GUI 验收；界面外观目前是「逻辑已验证、外观未验证」。
- **不要用 PowerShell `Get-Content|Set-Content` 回写源码/文档**（BOM 与 ANSI 都会坏事）。
- 版本控制：git 已建，远程 <https://github.com/fy-1123/FTYUpater>（public，`main`）；基线提交 `2feb4b7`（v2 基线，91 文件），`b0ba18e` 补 `.gitattributes` 统一换行。

---

## 7. 里程碑进度

| 里程碑 | 状态 | 证据 |
| --- | --- | --- |
| M0 方案/文档/基线 | ✅ | 文档齐全 |
| M1 core 纯逻辑库 | ✅ | core 6 个测试类全绿 |
| M2 服务端发布 | ✅ | server 5 个测试类全绿 |
| M3 客户端同步引擎 | ✅ | client 引擎测试全绿 |
| M4 进服前检查 + 一键同步 | ✅ 逻辑 / ⚠️ 外观 | 界面按原版控件重写 + `CheckLayout` 布局可单测；同步场景 13 项端到端覆盖；**点击流程未在真机确认**（用户要求不上真机） |
| M5 版本回退 + ApplyHelper | ✅ 逻辑 / ⚠️ 点击流程 | 新增 `helper/`（4 类，独立 JVM）+ `RollbackPlanner/Service/Detect` + `gui/RollbackScreen` + `PendingNotice`/`ClientStartup`/`HelperLauncher`/`ServerGeneration`；`LocalState.previousGeneration` + `advanceGeneration`；单测 63 项（含 helper 36） |
| M6 打磨/迁移/打包 | ✅ 逻辑 / ⚠️ 点击流程 | `TrashBrowser` + `gui/TrashScreen` + `V1Migration` + `docs/testing.md`（14 节手工清单）；单测 23 项；全量 **251 方法 / 250 过 / 1 skip / 31 XML**；jar **236,915 字节** |

---

## 8. 关键决策索引

全部决策见 `docs/decisions.md`。摘要：

- ADR-001：只做 Forge 1.20.1。
- ADR-002：清单 + 进服拦截推倒重写；下载机制保留重写。
- ADR-003：内容 = 服务器 `mods/`（自动）+ `clientpack/`（客户端专属，按游戏目录结构）+ 黑名单；config 不自动扫。
- ADR-004：客户端专属模组默认装，玩家可勾掉，不影响进服。
- ADR-005：自动跟最新 + 保留上一版可回退。
- ADR-006：镜像清理，但进回收站可恢复。
- ADR-007：进服前用 MC status ping 自行探测，不依赖上次缓存。
- ADR-008：需替换/删除已加载文件的更新由「退出游戏后由独立 helper JVM 应用」。
- ADR-011：一律 path + sha256 比对。
- ADR-013（文档纪律）：中文文档只用 write/edit 工具改，禁止 PowerShell 管道回写。
- ADR-014：客户端「先暂存、再应用」+ 锁探测 + pending 保留 staging。
- ADR-015：同 sha 多路径用「复制 + 原子改名」应用（LiveSyncIT 发现）。
- ADR-016：dev 下 Mixin 加载配置（MixinGradle + refmap + `--mixin.config`）。
- ADR-017：桌面锁屏时不追求 GUI 自动化，人工验收 GUI 全流程。
- ADR-018：整条删除 v1 链路，只留 v2 进服检查。
- ADR-019：界面用原版控件，布局全部按窗口现算。
- ADR-020：GUI 类签名一律以 mapped_official jar 为准（javap）。
- ADR-021：构建一律走 mc_gradle，不直接 shell gradlew。
- ADR-022：验证只走纯 JVM 测试，不启动游戏做真机验收（用户 2026-11-07 要求）。
- ADR-023：测试数量以 `build/test-results/test/*.xml` 求和为准，不看控制台摘要。
