# agent.md — FTYUpater 项目工作守则（最高优先级）

> 本文件是本项目所有协作者（人 / AI）的**最高优先级工作守则**。
> 任何重要信息必须落盘到本文件、`docs/`、`README.md` 或 `HANDOVER.md`。
> **严禁只依赖对话上下文**：上下文会丢，文件不会。
> 开工前先读第 2 节；开工后按第 4 / 7 节更新文档。

## 1. 项目是什么

FTYUpater 是一个 **Minecraft Forge 1.20.1** 的服务器模组包同步模组：

- 服务器把自己的模组内容发布成清单；
- 玩家进服前自动检查、一键同步，保证「服务器要什么，客户端就有什么」；
- 支持客户端专属内容（config / mods / 资源包 / 光影包）、可选模组、版本回退、多余文件清理。

- 当前：**v2 重构进行中**（M1/M2/M3/M4 完成；2026-11-06 已删除整条 v1 链路、进服检查界面按原版控件重写并自适应窗口；下一步 M5 = 版本回退 + ApplyHelper；详见 `HANDOVER.md`、`docs/plan.md`）
- **v1 已不存在**：19 个 v1 类移入项目根 `.trash-v1-20261106/`（可恢复），主菜单不再有 FTYUpater 入口。不要再按旧文档去找 `SyncManager` / `SyncScreen` / `PreJoinGuard` 等类（ADR-018）。
- 技术栈：Java 17 字节码、Forge 47.x、Gradle 8.8（wrapper）、Mixin、JDK 自带 HttpServer
- 许可证：MIT
- 工作目录：`C:\CodeSpace\Java\FTYUpater`

## 2. 开工前必读（顺序）

1. `HANDOVER.md` —— 当前实际状态、下一步
2. `docs/status-matrix.md` —— 功能状态表 + gradle 任务表（避免重复构建）
3. `docs/plan.md` —— 里程碑与验收标准
4. `docs/architecture.md` —— 目标架构与数据流
5. `docs/protocol.md` —— 清单 / HTTP / 服务器广告协议
6. `docs/decisions.md` —— 已定决策（ADR），**不要重复讨论已定内容**
7. `docs/qa-log.md` —— 用户原始需求与两轮问答记录（需求最高依据）
8. `docs/dev-workflow.md` —— 日常开发流程

## 3. 七条硬性规则（来自用户原始要求，m00001）

1. **工程思维**：先设计后编码；代码分模块、可测试；不为省事写一次性代码。
2. **少猜多问**：需求/边界不清楚先问用户；**提问用大白话，禁止堆专业术语**。
3. **agent.md + docs/**：重要事情写进这里；agent.md 必须指向 docs。
4. **README.md / HANDOVER.md**：记录架构、详细信息、决策、使用方法，必须随代码更新。
5. **重要信息严禁依靠上下文记录**：先写文档，再继续。
6. **先问清楚再开工**：需求、边界、验收标准未确认前，不写实现代码。
7. **维护状态表**：`docs/status-matrix.md` 标记每个结果在什么状态下可用；避免反复跑 gradle 任务。

## 4. 构建 / 验证策略（省时间，规则第 7 条）

| 目的 | 命令 | 说明 |
| --- | --- | --- |
| 逻辑正确性 | `.\gradlew.bat test` | 纯 JVM 单元测试，不启动游戏（**当前 148 个方法 / 147 过 / 1 skip**） |
| 同步场景 | `.\gradlew.bat test --tests "com.ftysync.client.SyncScenarioTest"` | 13 项端到端（进程内 HttpSyncServer），见 `docs/status-matrix.md` §G |
| 编译检查 | `.\gradlew.bat compileJava --offline` | 改完先编译（约 40 秒） |
| 打包 / 交付 | `.\gradlew.bat build --offline` | 基线 59 秒（2026-11-07，增量） |
| 游戏内验证 | `.\gradlew.bat runClient` / `runServer` | 很重，只在里程碑验收或改 mixin/网络时跑 |

**所有 gradle 调用走 `mc_gradle` 工具**（ADR-021）：bash 跑 `.bat` 会挂起，pwsh 直跑会把输出缓冲到进程结束、看不到进度。
**用户明确要求不上真机测试**（2026-11-07）：不再用 `runClient`/`runServer` 做 GUI 验收，同步场景一律用纯 JVM 测试覆盖（进程内 `HttpSyncServer` + `TestSyncServer`）。

**每次跑 gradle 前先查 `docs/status-matrix.md`**：任务是否已 up-to-date、是否需要重跑、上次结果如何。
**每次跑完 gradle 后更新状态表**（任务、时间、耗时、结果、产物）。

## 5. 文档地图

| 文件 | 内容 |
| --- | --- |
| `README.md` | 对外说明：是什么、怎么装、怎么用、怎么构建 |
| `HANDOVER.md` | 交接快照：当前状态、已知问题、下一步 |
| `docs/README.md` | 文档索引与维护约定 |
| `docs/architecture.md` | 目标架构、目录布局、数据流、失败恢复 |
| `docs/protocol.md` | 清单格式、HTTP API、服务器广告、比对规则 |
| `docs/plan.md` | 里程碑 M0–M6、任务、验收标准、风险 |
| `docs/status-matrix.md` | 功能状态表 + gradle 任务表 + 构建产物状态 |
| `docs/decisions.md` | 决策记录（ADR-001 起） |
| `docs/dev-workflow.md` | 日常开发流程、文档更新清单、禁止事项 |
| `docs/qa-log.md` | 用户原始需求与两轮问答记录 |

## 6. 重要约定

- **只支持 Forge 1.20.1**（ADR-001）；不引入 Stonecutter / Fabric / NeoForge 多版本，除非用户明确要求。
- **识别与比对一律用「相对路径 + SHA256」**，禁止文件名/版本字符串启发式（ADR-011）。
- 变更公开行为、协议、配置键、目录布局时，**必须同步更新 `docs/protocol.md` 或 `README.md`**。
- 用户需求以 `docs/qa-log.md` 为准，高于任何推测；新增需求先记入 qa-log 再实现。
- 删除/清理玩家文件必须可恢复（回收站或备份），不做不可逆操作。
- 代码注释与游戏内文本使用**中文 + 大白话**，面向普通玩家。
- **改 `.md` / 中文文本文件只用编辑工具（write/edit），禁止用 pwsh 的 `Get-Content` + `Set-Content` 管道回写**：本机 PowerShell 会把 UTF-8 中文破坏成 `?`（2026-10-21 已损坏过 `docs/status-matrix.md` 与 `docs/dev-workflow.md`，已从会话记录恢复）。

## 7. 每完成一个里程碑

1. 跑该里程碑「验收方式」（见 `docs/plan.md`）。
2. 更新 `docs/status-matrix.md`（功能状态、gradle 任务、构建产物）。
3. 更新 `HANDOVER.md`（当前状态、下一步、已知问题）。
4. 如涉及决策变化，更新 `docs/decisions.md`。
5. 向用户汇报：改了什么、怎么验证、下一步。
