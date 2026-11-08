# docs/ — 项目文档索引

> 本目录存放 FTYUpater 的所有设计与工程文档。改文档前先读 `docs/dev-workflow.md`。
> 项目工作守则在根目录 `agent.md`，开工前必读。

## 文档列表

| 文档 | 用途 | 什么时候必须更新 |
| --- | --- | --- |
| [qa-log.md](qa-log.md) | 用户原始需求 + 两轮问答记录（需求最高依据） | 用户提出/确认新需求时 |
| [plan.md](plan.md) | 里程碑 M0–M6、任务、验收标准、风险、待确认项 | 里程碑完成 / 计划调整时 |
| [architecture.md](architecture.md) | 目标架构、目录布局、组件划分、数据流、失败恢复 | 架构或目录布局变化时 |
| [protocol.md](protocol.md) | 清单格式、HTTP API、进服前探测与广告、比对规则 | 协议/配置键/文件格式变化时 |
| [status-matrix.md](status-matrix.md) | 功能状态表 + gradle 任务表 + 构建产物状态 | **每次跑完 gradle / 每完成一个功能后** |
| [decisions.md](decisions.md) | 决策记录 ADR | 做出/推翻/修订决策时 |
| [dev-workflow.md](dev-workflow.md) | 日常开发流程、构建策略、禁止事项 | 流程变化时 |
| [testing.md](testing.md) | E2E 手工验收清单（必须开游戏 / 双进程的部分） | 跑完一项就勾选并记录日期 |

## 更新规则

1. **先写文档，再写代码**（需求、方案、验收标准）。
2. **代码合并前**：协议变更 → `protocol.md`；行为/配置变更 → `README.md` 或本文档；决策变更 → `decisions.md`。
3. **每次 gradle 之后**：更新 `status-matrix.md` 的任务表（时间、耗时、结果）。
4. **每个里程碑结束**：更新 `status-matrix.md`、`HANDOVER.md`。
5. 重要信息禁止只留在对话里；无法归类时写进 `HANDOVER.md`。
