# v2 开发计划

> 状态：**M1–M4 已实现并验证；下一步 M5**（2026-10-22）。M0 文档/基线已完成。
> 纪律：每个里程碑完成 → 跑验收 → 更新 `docs/status-matrix.md` 与 `HANDOVER.md`。
> 用户原始需求见 `docs/qa-log.md`；架构见 `docs/architecture.md`；协议见 `docs/protocol.md`。

## 1. 里程碑总览

| 里程碑 | 目标 | 主要产出 | 验收方式 | 预计（开发日） |
| --- | --- | --- | --- | --- |
| M0 | 方案 + 文档 + 基线 | agent.md、docs/*、构建基线 | 文档齐全；build 通过 | 已完成 |
| M1 | 核心库（纯 Java） | `core/`：清单模型、哈希、路径校验、黑名单、比对引擎、状态存储 | `gradlew test` 全绿 | 1 |
| M2 | 服务端发布 | 扫描/存储/代际/HTTP API/广告 | `runServer` + curl 清单；改内容生成新代际 | 1.5 |
| M3 | 客户端同步引擎 | 下载/校验/暂存/应用/备份/回收站/状态 | 单测 + 两个目录手工集成 | 2 |
| M4 | 进服前检查 + 一键同步 | JoinProbe、Mixin、中文界面、一键同步 | `runClient` 连 `runServer` 走通首次进服 | 1.5 |
| M5 | 版本管理与回退 + ApplyHelper | 代际回退、退出后应用器、重启提示 | 更新→回退→再更新；Windows 锁定场景 | 1.5 |
| M6 | 打磨与交付 | server list 状态、回收站 UI、迁移、文档、E2E | 手工清单全过；release 构建 | 1 |

依赖：M1 → M2/M3 → M4 → M5 → M6（M2 与 M3 可在 M1 完成后并行）。

## 2. 各里程碑详情

### M0 方案与文档（已完成）

- [x] 需求澄清（两轮问答，见 `docs/qa-log.md`）
- [x] 方案/架构/协议/计划/决策/状态表文档
- [x] agent.md、README.md、HANDOVER.md
- [x] 构建基线：`.\gradlew.bat build --offline` → SUCCESS，110 秒（2026-10-21）
- 验收：文档齐全、链接正确；build 通过。

### M1 核心库（纯 Java，无 Minecraft 依赖）

任务：

- [x] `core/PackManifest`、`PackEntry`、`Generation`：JSON 编解码（用 JDK 自带或已缓存库；不引新依赖）
- [x] `core/Hash`：SHA256 流式（文件/字节数组），十六进制规范化
- [x] `core/PathGuard`：相对路径校验（拒绝 `..`、绝对路径、盘符、`\`、控制字符、空路径）
- [x] `core/Blacklist`：`*` / `?` 通配符与文件名匹配（对相对路径）
- [x] `core/DiffEngine`：清单 × 本地扫描 → 动作列表（INSTALL/UPDATE/DELETE/KEEP/OPT_OUT/MANUAL_DISABLED/PENDING）
- [x] `core/LocalState` + `StateStore`：state.json 读写（原子写）
- [x] 单元测试：清单往返、路径攻击样例、通配符、比对矩阵（required/optional/opt-out/disabled/extra）、状态损坏恢复

> M1 完成（2026-10-21）：`.\gradlew.bat test` → **53 个测试全部通过**（含 4 次首轮失败修复：Gson 包装 record 构造异常需按 RuntimeException 捕获）。

验收：

- `.\gradlew.bat test` 全绿；`core/` 不 import 任何 `net.minecraft*` / `net.minecraftforge*`；
- 覆盖至少：3 个路径攻击样例、5 种比对状态组合、黑名单通配符、清单损坏容错。

### M2 服务端发布

任务：

- [x] `server/ContentScanner`：扫 `mods/`（排除自身/黑名单）+ `clientpack/`（按游戏目录结构）
- [x] `server/ContentStore`：SHA256 寻址存储（`objects/<sha>`，临时文件 + 原子替换）
- [x] `server/GenerationJournal` + `server/GenerationStore`：journal.jsonl 追加式历史、generation 生成（UTC 时间 + 内容摘要）、parent 链、按 `retainGenerations` 剪枝
- [x] 清单生成：实际由 `server/PackPublisher` 完成（原计划的 `server/ManifestBuilder` 合并进来，减少一层无意义抽象）
- [x] `server/HttpSyncServer`：`/api/v1/health|pack|generations|files/<sha>`
- [ ] `server/SyncAdvertise`：**延后到 M4**（原因：v1 的 Forge `SimpleChannel` 协议版本是 int，放不了字符串；改用 `IExtensionPoint.DisplayTest` 的 suppliedVersion 携带 `v2|<baseUrl>`，需在 M4 实测）
- [x] 服务端配置：`enabled`、`httpPort`、`bindAddress`、`clientpackFolder`、`retainGenerations`、`rescanIntervalSeconds`、`blacklist`、`packId`（v1 的 `publicSyncUrl`/`advertiseSyncSource` 暂时保留键但 v2 未使用，M6 清理）
- [x] 内容变化监听：`ServerSyncRuntime` 单线程调度器（`ftyupater-publisher`）每 `rescanIntervalSeconds`（默认 10s）扫描，内容哈希不变则不开新代际
- [x] Forge 接线：`FTYUpater` 在 `ServerStartingEvent` 启动 `ServerSyncRuntime`、`ServerStoppingEvent` 停止；配置修改需重启（去掉了 v1 的 tick 热重载）

验收（2026-10-21 全部实测通过）：

- [x] `runServer` 单机启动；`/api/v1/health` 与 `/api/v1/pack` 返回合法 JSON
- [x] 往服务器 `mods/` 放一个 jar → 10s 内新代际 `20261021-143714-33193e5a`，`/api/v1/generations` 出现历史并可用 `?generation=<旧代>` 取回旧清单（回退能力验证）
- [x] 黑名单：`blacklist=["mods/server-only.jar"]` 后该文件不出现在清单（7 → 6 个文件）
- [x] FTYUpater 自身 jar（run/mods/ftyupater-copy.jar）不出现
- [ ] 服务器列表 ping 广告：随 M4 一起做

> M2 完成（2026-10-21）：`.\gradlew.bat test` → **81 个测试全部通过**（M2 新增 28 个）；
> `runServer --offline` 端到端验证通过。手工验证步骤见 `docs/dev-workflow.md` 第 9 节。
> 首次代际 `20261021-143332-4784ccb4`（5 文件）→ 加 jar 后 `...143714...`（6）→ `...143924...`（7，剪掉最老代）→ 黑名单重启后 `20261021-144451-89e1ca0c`（6）。

### M3 客户端同步引擎

任务：

- [x] `client/ServerSourceCache`：servers.json（地址 → source）
- [x] `client/SyncEngine`：下载队列（4 并发）、`.part` + SHA256 校验、原子替换、重试
- [x] `client/ApplyService`：立即应用 + 需要重启操作写入 `pending/pending.json`
- [x] `client/BackupManager`：旧文件进 `backup/<generation>/`；清理文件进 `trash/<时间>/`
- [x] 磁盘空间预检查；同步结果写 `logs/sync.log`
- [x] 单测：下载校验（本地 HTTP 测试服务器）、失败重试、状态机
- [x] 辅助类型：`SyncPaths`、`PendingQueue`/`PendingOp`、`LockProbe`、`SyncReport`

验收：

- 单元/集成测试通过（用 JDK HttpServer 起本地测试服）；
- 两个独立游戏目录手工验证：加/删/改文件后同步结果正确；
- 忽略/opt-out 的条目完全不出现在差异统计里（v1 缺陷的回归测试）。

> M3 完成（2026-10-21）：`test` → **106 个测试全部通过**（M3 新增 25：SyncPaths 1 / ServerSourceCache 4 / PendingQueue 3 / BackupManager 4 / ApplyService 6 / SyncEngine 7）。
> 实现细节：下载按 sha 去重（4 线程）；每文件最多 3 次重试（间隔 100ms×次数）；`.part` 校验 SHA256 后才改名；下载前磁盘预检（需求 + 64MiB > 可用空间则整批放弃）；锁探测=改名探测（`LockProbe.moveProbe`，Windows 上被占用文件改名会失败）；被锁定的替换/删除写 `pending/pending.json` 并**保留 staging**；`state.json` 仅在应用成功后写入；日志 `ftyupater/logs/sync.log`。
> 验收差异：客户端引擎目前是纯逻辑单测（JDK HttpServer 假服务端），**尚未接 Forge/UI**；「两个独立游戏目录手工验证」与真机验证挪到 M4（接进服检查）时一起做。**M5 的 ApplyHelper 完成前，pending 里的操作不会被应用**（已知缺口，见 HANDOVER §4）。

### M4 进服前检查 + 一键同步

任务：

- [x] `mixin/ConnectScreenMixin`：`ConnectScreen.startConnecting` HEAD 拦截 + `JoinBypass` 放行标记（mixin 类里非注入器方法必须 private，标记放独立类）
- [x] `client/JoinProbe` + `client/StatusPinger`：自行发 MC status ping，解析 `forgeData` → `ServerStatusPing.mods()["ftyupater"]`
- [x] `client/JoinDecision`（原计划名 JoinGate）：一致自动继续 / 有差异弹界面 / 无 FTYUpater 放行 / 拉取失败给提示
- [x] `gui/JoinCheckScreen`：中文检查界面（同步源、需安装/更新/清理数量、逐条明细）+【一键同步】【进入服务器/仍然进服】【取消】
- [x] 服务端广告：`SyncAdvert` + `ModLoadingContext.registerDisplayTest` 用 suppliedVersion 携带 `v2|<baseUrl>`；客户端从 forgeData 的 mods 表读取
- [x] `client/PackClient`：`GET /api/v1/pack` 拉清单
- [x] dev 下 mixin 真正生效：MixinGradle 0.7.38 + `mixin { add sourceSets.main, "ftyupater.refmap.json" }` + `--mixin.config` + mixins.json `refmap`（ADR-016）
- [ ] 语言文件 zh_cn / en_us → 延后到 M6（当前中文硬编码，已在代码注释标注）
- [ ] 服务器列表「需同步/已一致」状态 → 延后到 M6（可选）

验收（2026-10-22）：

- [x] 服务端广告实测：raw status ping + `decode_status.py` 解出 `ftyupater="v2|http://192.168.0.250:8081"`、`fmlNetworkVersion=3`
- [x] 客户端拦截生效：`runClient --quickPlayMultiplayer 127.0.0.1:25599` 后 `run/ftyupater/servers.json` 写入 `{"127.0.0.1:25599":{"source":"http://192.168.0.250:8081",...}}`（来自 JoinCheckScreen→JoinProbe 回调）
- [x] 一键同步链路（引擎层）：新增 `LiveSyncIT` 对真实 dev 服务器真实 HTTP 下载 → 校验 → 应用 → 备份/回收站 → 写 state/log → 二次比对一致，通过（见下方完成注记）
- [ ] GUI 点击「一键同步→重启→进服」的人工点击验证：**未完成**（用户 2026-11-07 明确要求不上真机，因此不由 AI 跑 `runClient` 做点击验收；布局的「不重叠」改由 `CheckLayoutTest` 在数学层面保证）

> M4 完成（2026-10-22）：`.\gradlew.bat test --offline` → **130 个测试全部通过**（M4 新增 22 个纯逻辑测试 + `LiveSyncIT` 1 个真实服务器集成测试 + 重复 sha 回归测试 1 个）。
> M4 补强（2026-11-07）：界面纵向布局抽成 `client/gui/CheckLayout.java`（纯整数运算、零 Minecraft 依赖），新增 `CheckLayoutTest` 5 项——在 160~3840 宽 × 40~2160 高逐尺寸断言「文字 / 列表 / 按钮」三者不重叠、按钮行居中不出界、按钮宽度上限 150。当前全量 **148 个方法 / 147 通过 / 1 skip**（23 个 XML）；`build` 重新打包 **147,746 字节**（旧 jar 是 22:46 的陈旧产物，不含 `CheckLayout.class`，已覆盖）。
> 实测中发现并修复的真实缺陷：**清单里多个路径内容相同（同一 sha256）时，暂存文件被第一个目标 move 走导致后续全部失败** → 改为复制到临时文件再原子改名（ADR-015）。
> 已知缺口：①`pending`（Windows 文件锁）要等 M5 的 ApplyHelper 才会被应用；②语言文件与服务器列表状态延后 M6；③GUI 全流程需人工点击验证一次。

### M5 版本管理与回退 + ApplyHelper（下一步）

任务：

- [ ] `helper/ApplyHelper`：独立 JVM，等父进程退出后应用 pending（只用 JDK）
- [ ] 客户端启动时检测 pending / helper 结果，提示玩家
- [ ] 回退 UI：选择上一代（本地 backup 优先，否则服务器历史清单）→ 同步
- [ ] 服务器回退检测：清单 generation 比本地旧 → 提示并跟随
- [ ] 自动跟新：启动/主菜单/进服前检查

验收：

- 更新一代 → 回退上一代 → 再更新，文件状态完全正确；
- Windows：替换已加载 jar → 退出游戏 → helper 应用成功 → 再启动为正确版本；
- helper 失败模拟（杀掉进程/目录只读）→ 下次启动有提示且可重试。

### M6 打磨与交付

任务：

- [ ] 多人服务器列表里显示「需同步/已一致」状态（可选，若成本高则延后）
- [ ] 回收站恢复 UI（列表 + 还原）
- [ ] v1 配置/状态迁移（ignore、pending-deletions、last-server 读入新 state）
- [ ] E2E 手工清单写成 `docs/testing.md`
- [ ] README / HANDOVER / 状态表最终更新
- [ ] release 构建 `gradlew build --offline`，产物说明

验收：E2E 清单全过；文档与实现一致；无 TODO 级已知缺陷（剩余项写入 HANDOVER）。

## 3. 完成定义（DoD）

一个里程碑算完成，必须同时满足：

1. 计划内任务全部完成或明确移入下个里程碑（写明原因）。
2. 该里程碑「验收方式」全部跑过，结果记录在 `docs/status-matrix.md`。
3. 相关文档已更新（协议/架构/README/HANDOVER 按需）。
4. 没有为了过验收而留的临时 hack 未记录。
5. 向用户汇报：改了什么、怎么验证、下一步。

## 4. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 客户端广告通道被 Forge 版本/其他模组影响 | 首次进服拿不到同步地址 | 备选 marker 方案；实测二选一；兜底 remembered/config |
| Windows 文件锁导致更新失败 | 更新不生效 | pending + 退出后 helper；`.disabled` 替代删除 |
| 玩家自己模组被镜像删除（用户要求自动删） | 玩家丢失模组 | 清理前列表 + trash 可恢复 + opt-out 保护自身 jar |
| 服务端 HTTP 端口被防火墙/NAT 挡住 | 客户端下不到文件 | publicSyncUrl 必填提示；启动时自检日志 |
| 清单/内容变化时扫描开销大（大整合包） | 服务器卡顿 | mtime+size 缓存；异步线程；防抖 |
| 首次同步体积大（2GB+） | 等待久、玩家放弃 | 进度显示、并发下载、可选跳过可选内容、断点（后续） |
| 与 v1 配置不兼容 | 升级丢设置 | M6 做迁移；迁移前 README 说明 |

## 5. 待用户确认的默认选项

1. 客户端专属内容目录默认名：建议 `clientpack/`（也可用 `客户端内容/`）。
2. 「自动删除」的多余文件：建议先进 `ftyupater/trash/<时间>/`（可恢复），而不是立刻永久删除。
3. 回退保留：建议「当前 + 上一版」共 2 代（服务器/客户端一致）。
4. 是否需要「主菜单也自动检查更新」：建议默认开，只提示不自动装。

> 这四点不影响总体方案，确认后可立即开工 M1；不确认则按建议默认执行，并在实现时把配置项留好。

## 6. 汇报模板（每个里程碑结束时）

```
【M? 完成】
- 做了什么：
- 验证结果（命令 + 结果 + 耗时）：
- 改动文件：
- 文档更新：
- 遗留/风险：
- 下一步：
```
