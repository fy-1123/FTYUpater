# 状态表（status-matrix）

> 目的：回答两个问题——**这个功能现在能不能用、在什么前提下能用**；**这个 gradle 任务要不要重跑**。
> 规则：每次跑完 gradle、每完成一个功能，立刻更新本表。不要靠记忆，也不要反复跑重任务。
> 最后更新：2026-11-06（v1 链路已物理删除；进服检查界面按原版控件重写并自适应窗口）

图例：✅ 已验证可用 ｜ ⚠️ 可用但有已知缺陷 ｜ 🚧 开发中 ｜ 📋 未开始 ｜ 🗑️ 已废弃/待重写

## A. 功能状态表

### A.1 v1 旧功能（2026-11-06 已全部移除）

v1 的 11 项功能（`/manifest` 平铺清单、`SyncManager` 文件名启发式比对、主菜单「FTYUpater 同步」按钮、
`PreJoinGuard` 缓存拦截、`SyncNetwork` 入服后询问源等）在 2026-11-06 整条删除，
源码移入 `.trash-v1-20261106/`（可恢复）。**不要按旧文档去找这些类**。删除范围与理由见 `docs/decisions.md` ADR-018。

| 原 ID | 原功能 | 现状 |
| --- | --- | --- |
| V1-01 | `/manifest`、`/file/<name>` HTTP | 🗑️ 已删除（由 `/api/v1/*` 取代） |
| V1-02 | 平铺清单（mods + `ftyupater/` 目录） | 🗑️ 已删除（由 ContentScanner + 代际取代） |
| V1-03 | 客户端下载到 `mods/` | 🗑️ 已删除（由 SyncEngine 取代） |
| V1-04 | 忽略列表仍被识别 | 🗑️ 已删除（改为 state.json 的 path 维度 opt-out/ignore） |
| V1-05 | 镜像删除无回收站 | 🗑️ 已删除（改为 trash/） |
| V1-06 | 进服前缓存拦截 | 🗑️ 已删除（由 JoinProbe + JoinDecision 取代） |
| V1-07 | 入服后询问同步源 | 🗑️ 已删除（由 status ping 广告取代） |
| V1-08 | `ftyupater-last-server.txt` | 🗑️ 已删除（由 `servers.json` 取代） |
| V1-09 | `ftyupater-pending-deletions.txt` | 🗑️ 已删除（由 `pending/pending.json` 取代） |
| V1-10 | 服务端配置热重载 | 🗑️ 已删除（改配置需重启） |
| V1-11 | 客户端配置自动写回 | 🗑️ 已删除（`syncUrl` 现在只由玩家/服务器广告提供） |

### A.2 v2 目标功能（按里程碑）

| ID | 功能 | 状态 | 所属里程碑 |
| --- | --- | --- | --- |
| V2-01 | 核心库：清单模型/哈希/路径校验/黑名单/比对引擎 | ✅ | M1（53 测试；`core/` 纯 JVM） |
| V2-02 | 服务端：扫描 + 内容寻址存储 + 代际 journal | ✅ | M2（ContentScanner/ContentStore/GenerationJournal/PackPublisher；runServer 实测） |
| V2-03 | 服务端：`/api/v1/*` HTTP API | ✅ | M2（health/pack/generations/files；curl 全通过） |
| V2-04 | 服务端：ping 广告同步地址 | ✅ | M4（`registerDisplayTest` 的 suppliedVersion 携带 `v2|<baseUrl>`；raw ping + decode 实测 `ftyupater="v2\|http://192.168.0.250:8081"`） |
| V2-05 | 客户端：下载/校验/暂存/应用/备份/回收站 | ✅ | M3（25 单测）+ M4 `LiveSyncIT` 对真实 dev 服务器跑通真实下载/应用/备份/回收站/state/log |
| V2-06 | 客户端：进服前探测 + 中文检查界面 + 一键同步 | ✅ 逻辑 / ⚠️ 外观 | **2026-11-06 重写**：界面改为原版 `ObjectSelectionList` + 全按窗口现算的布局（`MARGIN=8`/`BUTTON_H=20`/`ROW_H=24`，按钮贴底、列表吃中间剩余空间），任何窗口尺寸不重叠；差异列表按 必需安装/更新/手工禁用/待重启/多余清理/可选安装/已选择不要 排序并标色；点击路径行为原版控件。**主菜单入口已移除**，同步只在进服时发生。链路逻辑已由 `SyncScenarioTest` 13 项端到端覆盖；**界面在真机上的观感未确认**（用户要求不上真机测试） |
| V2-07 | 客户端：可选模组 opt-out（不计入差异） | ✅ | 比对/引擎层早支持（M1+M3 测试）；**2026-11-06 补上玩家 UI**：可选行右侧自绘 56×14 小按钮「我不要 / 恢复」，点击写 `state.json` 的 optOut 并立即重算差异刷新列表；required 行不显示该按钮（DiffEngine 对 required 无视 opt-out） |
| V2-08 | 版本管理：自动跟新 + 回退上一代 | ✅ 逻辑 / ⚠️ 点击流程 | **M5（2026-11-08）**：`LocalState.previousGeneration` + `advanceGeneration` 记住上一代；`RollbackPlanner` 合并本地备份与服务器历史（「刚退下来的」排第一）；`RollbackService` 两条路径（服务器代际清单重新同步 = 准，本地备份还原 = 离线兜底且标「可能不完整」）；`RollbackDetect` 检测服务器回退并在检查界面明说。单测 33 项（Planner 13 / Service 9 / Detect 11）。UI 见 V2-08b |
| V2-08b | 回退界面（`gui/RollbackScreen`） | ✅ 逻辑 / ⚠️ 点击流程 | 列表复用 `CheckLayout` 与 `ObjectSelectionList`；每行标来源（本地/服务器/两者）与文件数；按钮文案在只有本地备份时带「（本地备份）」。入口：进服前检查界面【版本回退】。**真机点击未验证**（用户要求不上真机） |
| V2-09 | ApplyHelper：退出后应用锁定文件变更 | ✅ 逻辑 | **M5（2026-11-08）**：`helper/ApplyHelper` 独立 JVM（`java -cp <jar> com.ftysync.helper.ApplyHelper <gameDir> <pid>`），`ProcessHandle` 轮询父进程退出（500ms，上限 10 分钟）后应用 pending；`MiniJson` 自带极简解析器（helper 不能假设 Gson 在）；结果写 `pending/helper-result.jsonl`，客户端 `ClientStartup` 下次启动弹 Toast 并收走。单测 36 项（MiniJson 17 / PendingApplier 19） |
| V2-10 | 回收站恢复 UI | ✅ 逻辑 / ⚠️ 点击流程 | **M6（2026-11-08）**：`TrashBrowser` 列时间戳目录 / 递归列文件 / 非破坏性还原（目标已存在则跳过，绝不覆盖）/ `pruneEmpty` 清空目录；`gui/TrashScreen` 界面，入口在回退界面的【回收站】按钮。还原用 `move` 而非 `copy`，文件离开回收站后目录才会变空。单测 13 项 |
| V2-11 | v1 配置/状态迁移 | ✅ 逻辑 | **M6（2026-11-08）**：`V1Migration` 读 `config/ftyupater-ignore.txt`（忽略列表）、`ftyupater-pending-deletions.txt`（待删）、`ftyupater-last-server.txt`（`host=url` 服务器记忆）；读完改名为 `*.migrated` 留证（不删）；按 `mods/` 真实大小写匹配；拒绝路径穿越。`ClientStartup` 首次 tick 调用并三路合并进 `state.json`/`pending.json`/`servers.json`。单测 10 项 |
| V2-12 | 单元测试套件（`core`/`server`/`client`/`client.gui`/`helper` 纯 JVM） | ✅ | 持续维护；当前 **251 个测试方法**（250 通过 + 1 skip），31 个 XML，见 §G 同步场景覆盖表；界面布局由 `CheckLayoutTest` 5 项在数学层面保证不重叠 |

## B. gradle 任务表（避免反复重跑）

| 任务 | 命令 | 产物 | 什么时候需要重跑 | 最近验证 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| 编译 | `.\gradlew.bat compileJava --offline` | `build/classes/java/main` | 改 Java 源码后 | **2026-11-08 17:51 SUCCESS（2m42s）**；仅 3 条 `ModLoadingContext.get()` removal 警告 | ~40s~2m42s（含 MixinGradle APT + downloadMcpConfig） |
| 单元测试（全量） | `.\gradlew.bat test --offline` | `build/test-results/test` | 改 `core/`/`server/`/`client/`/`helper/`/测试后 | **2026-11-08 SUCCESS（5m31s）**：250 PASSED / 0 FAILED / 1 SKIPPED（`LiveSyncIT`，需 runServer 在跑）；共 **251** 个测试方法 / 31 个 XML（数字以 `build/test-results/test/*.xml` 求和为准，别看控制台摘要） | ~5m31s（含 compileJava 全量） |
| 同步场景（新增） | `.\gradlew.bat test --offline --tests "com.ftysync.client.SyncScenarioTest"` | `build/test-results/test` | 改发布/比对/下载/清理任何环节后 | **2026-11-07 SUCCESS（41s）13/13 通过**；进程内真 `HttpSyncServer` + 临时游戏目录，不启动游戏 | ~41s |
| 单元测试（仅客户端） | `.\gradlew.bat test --offline --tests "com.ftysync.client.*"` | `build/test-results/test` | 只改客户端代码时 | 本次未单跑；按 XML 分类求和为 **121 个方法**（含 `LiveSyncIT` 1 skip）；`gui.CheckLayoutTest` 5 项、`helper.*` 36 项可分别单独跑 | ~1m5s~2m30s |
| 单元测试（仅 helper） | `.\gradlew.bat test --offline --tests "com.ftysync.helper.*"` | `build/test-results/test` | 改 ApplyHelper / PendingApplier / MiniJson 后 | **2026-11-08 SUCCESS**：36 项全过（MiniJson 17 + PendingApplier 19） | ~2m |
| 单元测试（回退与回收站） | `.\gradlew.bat test --offline --tests "com.ftysync.client.Rollback*" --tests "com.ftysync.client.TrashBrowserTest" --tests "com.ftysync.client.V1MigrationTest"` | `build/test-results/test` | 改 M5/M6 逻辑后 | **2026-11-08 SUCCESS**：RollbackPlanner 13 + RollbackService 9 + RollbackDetect 11 + TrashBrowser 13 + V1Migration 10 = 56 项全过 | ~2m30s |
| 真实服务器集成（手工） | 先 `runServer`，再 `.\gradlew.bat test --offline --tests "com.ftysync.client.LiveSyncIT"` | 临时目录，无仓库污染 | 改下载/应用/备份/回收站/HTTP 后 | 2026-10-22 PASSED（真实下载 6 文件、清理多余 jar、备份旧文件、state/log、二次比对一致） | ~2m30s |
| 打包 | `.\gradlew.bat build --offline` | `build/libs/FTYUpater-2.0.0.jar`（reobf） | 交付/发布/需要最终 jar 时 | **2026-11-08 19:07 SUCCESS（1m58s）**；`addMixinsToJar` + `jar` + `reobfJar` 均执行，`jarJar`/`reobfJarJar` SKIPPED；jar **236,915 字节**，已含 M5/M6 全部新类（`helper/*`、`RollbackScreen`、`TrashScreen`、`TrashBrowser`、`RollbackPlanner/Service/Detect`、`V1Migration`、`PendingNotice`、`ClientStartup`） | ~2m（首次含 downloadMcpConfig 时更久） |
| 客户端运行 | `.\gradlew.bat runClient --offline --args="--quickPlayMultiplayer <host:port>"` | 启动游戏（`run/`） | 改 mixin/UI/客户端网络/进服流程后 | **暂停使用**：用户 2026-11-07 明确要求不上真机测试；界面外观因此仍是「未验证」 | 3~4 分钟（起服约 2.5 分钟） |
| 服务端运行 | `.\gradlew.bat runServer --offline` | 启动服务器（`run/`，需 EULA；v2 HTTP :8081） | 改服务端发布/HTTP/广告后 | **暂停使用**：同上；服务端行为改由进程内 `HttpSyncServer` 测试覆盖 | 2~3 分钟 |
| 数据生成 | `.\gradlew.bat runData` | `src/generated/resources` | 改数据生成时（当前项目未用） | 未跑 | 待测 |

**基线事实（2026-11-07，`build --offline`）**：

- 结果：BUILD SUCCESSFUL，1 分 58 秒（19:07）；
- 执行任务：`addMixinsToJar`、`jar`、`configureReobfTaskForReobfJar`、`reobfJar`、`assemble`、`build`；UP-TO-DATE：`compileJava`、`processResources`、`classes`、`test`、`check`；SKIPPED：`jarJar`、`reobfJarJar`；
- 注意：`build` 会触发 `downloadMcpConfig`，是耗时大头之一。**日常改代码不要跑 `build`**，用 `compileJava` / `test`。

## C. 构建产物状态

| 产物 | 路径 | 生成方式 | 状态 | 生成时间 |
| --- | --- | --- | --- | --- |
| v2 发布 jar | `build/libs/FTYUpater-2.0.0.jar`（236,915 字节） | `build`（reobfJar） | ✅ **当前交付产物**（含 `ftyupater.mixins.json`、`ftyupater.refmap.json`、`CheckLayout`、`JoinCheckScreen`、`helper/*`、`RollbackScreen`、`TrashScreen`） | 2026-11-08 19:07 |
| v1 旧 jar | `.trash-v1-20261106/FTYUpater-1.0.0.jar` | 旧 `build` | 🗑️ 已移出 `build/libs/`（可恢复） | 2026-09-13 |
| 开发 classes | `build/classes/java/main` | `compileJava` | ✅ | 2026-11-08 17:51 |
| Mixin refmap | `build/resources/main/ftyupater.refmap.json` | 注解处理器 | ✅（v2） | 随 build 生成 |

## D. 环境事实

| 项 | 值 |
| --- | --- |
| JDK（JAVA_HOME） | 21.0.12+7-LTS（Oracle），编译 release=17 |
| Gradle | wrapper 8.8；`org.gradle.daemon=false`、`-Xmx3G` |
| 离线构建 | ✅ 可行（依赖已缓存，`--offline` 成功） |
| ForgeGradle 环境检查 | ⚠️ 会在插件 apply 时联网 HEAD `maven.minecraftforge.net`/`libraries.minecraft.net`（无超时），网络不通时构建静默卡 2~5 分钟；已在 `gradle.properties` 用 `systemProp.net.minecraftforge.gradle.check.certs=false` 关闭 |
| run/ 开发环境 | `run/eula.txt`(true)、`run/server.properties`(端口 25599)、`run/mods/` 假 jar fixtures、`run/clientpack/`；服务端数据在 `run/ftyupater/server/` |
| Forge | 1.20.1-47.4.0，mappings official |
| dev mixin | MixinGradle 0.7.38 + `mixin { add sourceSets.main, "ftyupater.refmap.json" }` + runs `--mixin.config=ftyupater.mixins.json` + mixins.json `refmap`；dev 没有 manifest MixinConfigs 也能加载（ADR-016） |
| 桌面会话 | 2026-11-07 实测已解锁（`SessionId=1`、`UserInteractive=True`，截图可用）；但用户明确要求**不上真机测试**，所以不再用它做 GUI 验收 |
| 文本编辑 | ⚠️ 禁止用 PowerShell `Set-Content`/`Get-Content` 回写源码/文档（会加 BOM 导致 javac 失败，或按 ANSI 破坏中文，ADR-013）；一律用编辑工具/python（UTF-8 无 BOM） |
| 工作目录 | `C:\CodeSpace\Java\FTYUpater`（git 仓库，远程 <https://github.com/fy-1123/FTYUpater>，分支 `main`） |

## E. 维护规则

1. 跑完任何 gradle 任务 → 更新 B 表对应行（时间/结果/耗时）；产物变化 → 更新 C 表。
2. 功能可用性变化 → 更新 A 表（状态、前提、证据）。
3. 功能不可用必须写明**前提条件**（例如「需要服务器端口可达」「只在 Windows 出现」），不要只写「可用/不可用」。
4. 里程碑结束时，A 表中对应行改为 ✅ 或 ⚠️，并补充证据（测试名/命令/截图路径）。

## F. 里程碑记录

| 里程碑 | 日期 | 结果 | 证据 |
| --- | --- | --- | --- |
| M0 方案/文档/基线 | 2026-10-21 | ✅ | 文档齐全；`build --offline` 110 秒 |
| M1 core 纯逻辑库 | 2026-10-21 | ✅ | `test` 53 全绿（2m09s，含 4 次首轮失败修复） |
| M2 服务端发布 | 2026-10-21 | ✅ | `test` 81 全绿；`runServer` E2E 全通过（`/api/v1` 全路由、自动 rescan、保留 2 代、旧代回退、黑名单、自身排除） |
| M2 文档事故 | 2026-10-21 | ⚠️ 已恢复 | `status-matrix.md`/`dev-workflow.md` 被 PowerShell 回写破坏 UTF-8，已按 DSH 会话记录原文恢复；规矩见 ADR-013 |
| M3 客户端同步引擎 | 2026-10-21 | ✅ | `test` 106 全绿（M3 新增 25：下载/校验/重试/锁→pending/备份/回收站/磁盘预检） |
| M4 文档误报 | 2026-10-22 | ⚠️ 已更正 | 曾汇报「M4 已完成」但磁盘上文档/代码未同步；以磁盘为准重做 M4 真机验证后才在文档标记完成 |
| M4 进服检查 + 一键同步 | 2026-10-22 | ⚠️ 基本完成 | `test` **130 全绿**；服务端广告 decode 实测；客户端拦截/探测/清单/差异实测（servers.json）；`LiveSyncIT` 真实服务器同步通过；GUI 点击全流程待人工（锁屏） |
| M4 dev mixin 修复 | 2026-10-22 | ✅ | `ConnectScreenMixin` 在 dev 生效：MixinGradle + refmap + `--mixin.config`；`JoinBypass` 解决非 private 静态方法限制 |
| v1 链路删除 + 界面重写 | 2026-11-06 | ✅ 逻辑 / ⚠️ 外观 | 19 个 v1 类移入 `.trash-v1-20261106/`；`JoinCheckScreen` 改原版控件 + 自适应布局；`compileJava`/`test`/`build` 全绿；界面外观未在真机确认（用户要求不上真机） |
| 同步场景端到端测试 | 2026-11-07 | ✅ | 新增 `SyncScenarioTest` 13 项；全量 `test` **148 个方法 / 147 通过 / 1 skip**（23 个 XML） |
| 界面布局抽成 CheckLayout | 2026-11-07 | ✅ 逻辑 / ⚠️ 点击流程 | 纵向坐标全部移入 `client/gui/CheckLayout.java`（纯整数、零 MC 依赖）；新增 `CheckLayoutTest` 5 项，160~3840 宽 × 40~2160 高逐尺寸断言文字/列表/按钮三者不重叠；`build` 重新打包 147,746 字节，jar 内已含 `CheckLayout.class`（旧 jar 是 22:46 的陈旧产物，已覆盖）；**进服→一键同步→重启→进服 点击流程仍未验证**（用户要求不上真机） |
| M5 版本管理与回退 + ApplyHelper | 2026-11-08 | ✅ 逻辑 / ⚠️ 点击流程 | 新增 10 个类：`helper/{ApplyHelper,PendingApplier,HelperResult,MiniJson}`、`client/{HelperLauncher,PendingNotice,ClientStartup,RollbackPlanner,RollbackService,RollbackDetect,ServerGeneration}`、`gui/RollbackScreen`；改 `LocalState`（`previousGeneration` + `advanceGeneration`）、`SyncEngine`、`PackClient`（带 generation 重载 + `fetchGenerations`）、`FTYUpater`（DistExecutor 挂客户端启动检查）、`JoinCheckScreen`（回退检测 + 【版本回退】入口）。新增单测 **63 项**（MiniJson 17 / PendingApplier 19 / PendingNotice 11 / RollbackPlanner 13 / RollbackService 9 / RollbackDetect 11 —— 注：前两类的 36 项在 helper 包）。**所有点击流程未在真机验证** |
| M6 打磨与交付 | 2026-11-08 | ✅ 逻辑 / ⚠️ 点击流程 | `TrashBrowser`（回收站读取/非破坏性还原/清空目录）+ `gui/TrashScreen`（界面，入口在回退界面）+ `V1Migration`（v1 三件文本配置迁移，改名留证）+ `ClientStartup.migrateFromV1` 接线；新增 `docs/testing.md`（14 节 E2E 手工清单，全部未执行并如实标注）；单测 **23 项**（TrashBrowser 13 / V1Migration 10）。全量 `test` **251 个方法 / 250 通过 / 1 skip / 31 个 XML**；`build` 236,915 字节 |

## G. 同步场景覆盖表（「什么情况下可用 / 不可用」）

全部由 `src/test/java/com/ftysync/client/SyncScenarioTest.java` 覆盖，纯 JVM（进程内 `HttpSyncServer` + 临时游戏目录），不启动 Minecraft。

| # | 场景 | 期望结果 | 覆盖测试 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 全新客户端，服务器有必需 + 客户端专属文件 | 缺必需 → 拦进服；同步后装齐；再比对一致 | `freshClientInstallsEverything` | ✅ |
| 2 | 客户端已与服务器一致 | 自动继续，不下载任何东西 | `inSyncClientAutoContinues` | ✅ |
| 3 | 服务器新增一个模组 | 只下载新的那一个，老文件不重写 | `serverAddsNewModOnlyNewOneInstalled` | ✅ |
| 4 | 服务器更新同一个模组 | 覆盖新内容，旧版本进 `backup/<gen>/` | `serverUpdatesModBacksUpOldVersion` | ✅ |
| 5 | 客户端 `mods/` 里有服务器没有的 jar | 自动清理进 `trash/<时间>/`，不直接删 | `extraClientJarGoesToTrash` | ✅ |
| 6 | 玩家对可选模组点了「我不要」 | 不下载、不报错、不算差异、不拦进服 | `optedOutOptionalIsSkippedAndDoesNotBlock` | ✅ |
| 7 | 玩家手动把必需模组改成 `.disabled` | 识别为「手工禁用」，拦住进服 | `manualDisabledRequiredBlocksJoin` | ✅ |
| 8 | 服务器没装 FTYUpater | 什么都不提示，直接进服 | `serverWithoutModAutoContinues` | ✅ |
| 9 | 服务器装了模组但没配同步地址 | 提示一句，仍允许进服 | `serverWithModButNoSourceContinuesWithNotice` | ✅ |
| 10 | 同步服务连不上 / 拉清单失败 | 提示后仍可进服，不能把玩家关在门外 | `fetchFailureContinuesWithNotice` | ✅ |
| 11 | 同一份内容出现在两个路径 | 只下载一次，两处都装好 | `sameContentTwoPathsBothInstalled` | ✅ |
| 12 | 连续同步两次 | 第二次完全无动作（幂等） | `secondSyncIsNoOp` | ✅ |
| 13 | 服务器黑名单里的文件 | 清单里没有，客户端不会被要求下载 | `blacklistedFileIsNotPublished` | ✅ |

**未覆盖 / 需要真机才能验证的**（诚实标注，不要当已通过）：

| 场景 | 为什么现在测不了 |
| --- | --- |
| 进服检查界面的实际观感、不重叠 | 需要启动游戏看画面；用户要求不上真机 → 只有布局代码层面的保证 |
| 点「一键同步」按钮 → 进度 → 完成 | 同上，需要鼠标点击真界面 |
| 正在被 JVM 锁住的 `mods/*.jar` 替换 | 纯 JVM 测试里文件不被锁，永远走「立即应用」分支；锁 → pending 的分支由 `ApplyServiceTest.lockedReplaceGoesToPending` 用桩覆盖；真锁 + helper 补上的完整链路见 `docs/testing.md` 第 7 项 |
| `pending/pending.json` 在退出后自动应用 | 逻辑已由 `PendingApplierTest` 19 项覆盖（含 `runWritesResultAndClearsPending`、`lockedTargetStaysPending`）；**真进程等待与真锁未验证**，见 `docs/testing.md` 第 7/8 项 |
| 服务器广告经真实 Forge status ping 传回客户端 | 需要真服务器 + 真客户端；协议层由 `AdvertParserTest` / `JoinDecisionTest` 覆盖 |
| 版本回退的完整点击流程 | 逻辑已由 `RollbackPlannerTest` 13 / `RollbackServiceTest` 9 / `RollbackDetectTest` 11 覆盖；界面点击见 `docs/testing.md` 第 10 项 |
| 回收站还原的完整点击流程 | 逻辑已由 `TrashBrowserTest` 13 项覆盖；界面点击见 `docs/testing.md` 第 6 项 |
| v1 迁移在真机首次启动的行为 | 逻辑已由 `V1MigrationTest` 10 项覆盖；真机见 `docs/testing.md` 第 12 项 |

