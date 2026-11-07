# FTYUpater

Minecraft **Forge 1.20.1** 服务器模组包同步模组：服务器把自己的模组发布成清单，玩家进服前自动检查、一键同步，保证「服务器要什么，客户端就有什么」。

- 模组加载器：Forge 1.20.1（47.x）
- 当前版本：v1.0.0 旧实现；**v2 重构进行中**（方案与计划见 [docs/plan.md](docs/plan.md)）
- 许可证：MIT

## 为什么做这个

现在的 FTYUpater v1 能下载、能拦进服，但不好用：

- 清单只覆盖 `mods/`，客户端专属的 config、资源包、光影包没法下发；
- 靠文件名/版本字符串识别，判断「哪些要下载、哪些要删」经常不对；
- 玩家在同步界面忽略了某个文件，检查结果里却还把它算进去；
- 进服拦截的提示看不懂，出问题时玩家不知道该怎么办。

v2 的目标是把「服务器发内容 → 玩家同步 → 进服」这条链做成玩家看得懂、服主零额外工具的一整套。

## 目标功能（v2）

- **服务器内容发布**：自动扫描服务器 `mods/`；另设「客户端专属内容目录」（按游戏目录结构放 config / mods / 资源包 / 光影包）；支持黑名单不发布某些文件。
- **进服前检查**：连接服务器前自动探测（服务器 mod 列表 + 同步地址），用大白话列出差多少、缺什么，【一键同步】。
- **版本管理**：自动跟随服务器最新版本；保留上一版，可一键回退。
- **可选模组**：客户端专属模组默认安装，玩家可逐个关掉；关掉不影响进服。
- **镜像一致**：服务器没有的多余文件自动清理，进回收站可恢复。
- **无需额外启动器 / 代理**：纯 Forge 模组，服务端内嵌 HTTP，客户端内置下载与校验。

## 服务器管理员：怎么用（v2 目标形态）

1. 把要同步的模组放进服务器 `mods/`（FTYUpater 不会发布它自己）。
2. 客户端专属内容放进服务器根目录 `clientpack/`，目录结构照游戏目录来：
   - `clientpack/config/xxx.toml`
   - `clientpack/mods/仅客户端模组.jar`
   - `clientpack/resourcepacks/xxx.zip`
   - `clientpack/shaderpacks/xxx.zip`
3. 编辑 `config/ftyupater-server.toml`：`httpPort`（默认 8081）、`publicSyncUrl`（公网地址，NAT 后必填）。
4. 启动服务器，用浏览器访问 `http://服务器地址:8081/api/v1/pack` 验证清单可访问。

> 目标形态，随 v2 里程碑落地；旧版用法见 [HANDOVER.md](HANDOVER.md)。

## 玩家：怎么用

1. 安装与服务器相同版本的 FTYUpater。
2. 进服时自动检查：缺什么、多什么，都会用大白话列出来。
3. 点【一键同步】，等下载校验完成，按提示重启游戏。
4. 不想要某个客户端专属模组？在同步界面关掉即可，不影响进服。

## 从源码构建

需要 JDK 21（编译目标 Java 17）和本机已缓存依赖：

```bat
.\gradlew.bat build --offline
```

产物：`build/libs/FTYUpater-2.0.0.jar`（147,746 字节，增量约 59 秒，2026-11-07 验证通过）。

日常开发验证顺序（省时间）：

```bat
.\gradlew.bat test
.\gradlew.bat compileJava --offline
```

详见 [agent.md](agent.md) 第 4 节与 [docs/status-matrix.md](docs/status-matrix.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [agent.md](agent.md) | 项目工作守则（开工前必读） |
| [HANDOVER.md](HANDOVER.md) | 交接快照：当前状态、已知问题、下一步 |
| [docs/architecture.md](docs/architecture.md) | 目标架构 |
| [docs/protocol.md](docs/protocol.md) | 清单 / HTTP / 广告协议 |
| [docs/plan.md](docs/plan.md) | 开发计划与验收标准 |
| [docs/status-matrix.md](docs/status-matrix.md) | 状态表（功能 / gradle 任务 / 产物） |
| [docs/decisions.md](docs/decisions.md) | 决策记录（ADR） |
| [docs/dev-workflow.md](docs/dev-workflow.md) | 开发流程 |
| [docs/qa-log.md](docs/qa-log.md) | 需求问答记录 |
