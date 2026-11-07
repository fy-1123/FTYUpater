# 目标架构（v2）

> 状态：M1（core）/ M2（server）/ M3（client 引擎）/ M4（进服检查 + 一键同步）已实现并有测试；M4 的 GUI 人工点击验收待补。进度与证据见 `docs/status-matrix.md`。
> 相关：`docs/protocol.md`（清单/HTTP/广告）、`docs/plan.md`（里程碑）、`docs/decisions.md`（ADR）。

## 1. 设计原则

1. **纯 Forge 模组**：不依赖额外启动器、代理、外部进程，只做模组内可做的部分。
2. **服务器是唯一事实来源**：内容、版本、哪些必需/可选，都由服务器清单决定。
3. **内容寻址**：文件按 SHA256 识别和校验，路径 + 哈希决定一切；不猜文件名、不猜版本号。
4. **玩家可反悔**：删除进回收站、更新保留上一版、可选模组可关、清理前有列表。
5. **服务端零手工**：把 jar 丢进 `mods/`、把客户端专属文件丢进 `clientpack/`，其余自动。
6. **可测试**：核心逻辑（`core/`）不依赖 Minecraft，能用 `gradlew test` 快速验证。

## 2. 总体数据流

```
┌────────────────────────── 服务器 ──────────────────────────┐
│ mods/*.jar ─┐                                             │
│ clientpack/** ─┼─→ ContentScanner ─→ PackPublisher ─→ HTTP API │
│ 黑名单/自身排除 ┘        (SHA256)         (generation)   /api/v1   │
│                                  │                         │
│                                  └─→ ContentStore + journal（保留 N 代）│
└────────────────────────────────────────────────────────────┘
                                  ▲
              MC status ping（forgeData：服务器 mod 列表 + 同步地址广告）
                                  │
┌────────────────────────── 客户端 ──────────────────────────┐
│ 连接服务器 ─→ JoinProbe ─→ 拉清单 ─→ DiffEngine ─→ JoinCheckScreen │
│                                                      │ 一键同步     │
│                            SyncEngine（下载→校验→暂存→应用）        │
│                                    │                              │
│                     可立即应用 ────┤──── 需重启（锁定/替换/删除）     │
│                                    │        └→ pending + ApplyHelper │
│                                    ▼                              │
│                        state.json / backup / trash                │
└────────────────────────────────────────────────────────────┘
```

## 3. 目录布局

### 3.1 服务器

```
服务器根目录/
  mods/                         ← 自动发布（黑名单/FTYUpater 自身除外，role=required）
  clientpack/                   ← 客户端专属内容（按游戏目录结构，role=optional）
    config/...
    mods/...
    resourcepacks/...
    shaderpacks/...
  config/ftyupater-server.toml
  ftyupater/                    ← 服务端状态目录（不发布）
    server/
      journal.jsonl             ← 代际历史（追加式：generation、parent、contentHash、createdAt、fileCount）
      generations/<generation>/manifest.json   ← 每代清单正文（HTTP 直接读它）
      objects/<sha256>          ← 内容寻址存储（回退用，保留 N 代）
```

### 3.2 客户端

```
.minecraft/
  mods/ ...                     ← 同步落点（服务器 mods 的镜像 + clientpack/mods）
  config/ ...                   ← 仅清单声明的文件会被管理
  resourcepacks/ ... shaderpacks/ ...
  ftyupater/
    state.json                  ← 已安装清单、generation、opt-out/ignore、待应用
    servers.json                ← 「服务器地址 → 同步源」记忆
    staging/<generation>/       ← 下载暂存（校验通过才会应用）
    pending/pending.json        ← 需重启才能应用的操作（替换/删除已加载文件）
    backup/<generation>/        ← 上一版文件（回退用）
    trash/<timestamp>/          ← 清理出的文件（可恢复）
    logs/sync.log               ← 同步日志
    logs/helper.log             ← 退出后应用器日志
```

## 4. 组件划分

依赖方向：`core ← server`、`core ← client`、`core ← helper`；`gui → client`。`core` 与 `helper` **不依赖 Minecraft**。

```
com.ftysync/
  FTYUpater.java                 模组入口（注册配置/Mixin 广告、启停服务端运行时；M5 起负责启动 ApplyHelper）
  ModConfig.java                 全部配置键（服务端 11 键 + 客户端 4 键；v1 孤儿键已于 2026-11-06 随 v1 链路一并删除）
  ServerSyncRuntime.java         服务端运行时：扫描→发布→HTTP→广告
  core/                          ← 纯 Java，可单测
    Json.java                    Gson 实例（pretty / compact，统一关闭 HTML 转义）
    ManifestException.java       清单/状态非法时的受检异常
    Hash.java                    SHA256 流式计算 + 校验
    PathGuard.java               相对路径校验（拒绝绝对路径、..、盘符、控制字符、Windows 保留名）
    Blacklist.java               文件名/通配符黑名单
    PackEntry.java               单个文件条目（path/sha256/size/origin/role）
    PackManifest.java            清单模型 + JSON 编解码（schema 2）
    LocalFile.java               本地扫描到的文件（path/sha256/size）
    SyncAction.java              单个动作（INSTALL/UPDATE/DELETE/KEEP/OPT_OUT…）
    DiffResult.java              同步计划汇总（isInSync / requiresSync / blocksJoin）
    DiffEngine.java              清单 × 本地 → 同步计划
    LocalState.java / StateStore.java   本地状态读写（state.json，损坏自动备份）
  server/
    ScannedFile.java             扫描结果条目
    ContentScanner.java          扫描 mods/ + clientpack/，套黑名单/自身排除
    ContentStore.java            内容寻址存储（objects/<sha256>）
    GenerationJournal.java       代际日志（journal.jsonl，追加式）
    GenerationStore.java         每代清单读写（generations/<gen>/manifest.json）
    PackPublisher.java           扫描→存对象→开代际→写清单/journal→裁剪旧代
    SyncPackRepository.java      只读仓库接口（HTTP 层用）
    FilePackRepository.java      基于 journal/manifest/objects 的实现
    HttpSyncServer.java          JDK HttpServer，/api/v1/*
  client/
    SyncPaths.java               客户端状态目录（state/servers/staging/pending/backup/trash/logs）
    ServerSourceCache.java       servers.json 读写
    Advert.java / AdvertParser.java   广告串 v2|<url> 的模型与解析
    SyncAdvert.java              本服广告串（经 DisplayTest 发布；无源时为 IGNORESERVERONLY）
    LocalScanner.java            本地受管文件扫描 + .disabled 标记
    StatusPinger.java            自建 MC status ping（读 forgeData）
    JoinProbe.java               连接前探测服务器是否提供同步源
    JoinDecision.java            决定拦 / 放行 / 自动继续
    PackClient.java              拉取 /api/v1/pack
    SyncEngine.java              下载→校验→暂存→应用（按 sha 去重，4 并发，3 次重试）
    ApplyService.java            区分「可立即应用」与「需重启」的操作
    LockProbe.java               文件占用探测（改名探测）
    PendingQueue.java / PendingOp.java   需重启操作持久化（pending/pending.json）
    BackupManager.java           上一版备份、回收站、恢复
    JoinBypass.java              放行标记（Mixin 不能有非 private 静态方法，故独立成类）
    gui/JoinCheckScreen.java     进服前检查界面（大白话 + 一键同步）
    gui/CheckLayout.java         界面的纵向布局算法（纯整数运算、零 Minecraft 依赖，可单测）
  mixin/
    ConnectScreenMixin.java      ConnectScreen.startConnecting HEAD 拦截（dev 需 --mixin.config，见 ADR-016）
  helper/                        M5：ApplyHelper（独立 JVM，只用 JDK，等游戏退出后应用 pending）
```

**v1 旧类已于 2026-11-06 整条删除**（19 个文件，源码保留在项目根的 `.trash-v1-20261106/`，可恢复）：
`client/ClientEvents`、`client/gui/SyncScreen`、`client/SyncManager`、`client/ModStatus`、
`client/PreJoinGuard`、`client/PreJoinCheckScreen`、`client/ClientTarget`、`client/ClientSyncJoinHandler`、
`client/ServerManifestCache`、`common/ManifestData`、`server/ServerManifest`、`server/SyncHttpServer`、
`network/` 全部 6 个类、`mixin/ClientConnectionMixin`。理由见 ADR-018。
**主菜单不再有任何 FTYUpater 入口**：同步只在进服时发生。

## 5. 关键流程

### 5.1 服务端发布

1. 服务器启动（或定时/监听到内容变化）→ `ContentScanner` 扫描 `mods/` 与 `clientpack/`。
2. 计算每个文件的 SHA256（M2 实现为**每次全量计算**，未做 mtime/size 缓存；大整合包每 10s 重扫的成本留到 M6 优化）。
3. 生成新清单；如果内容与上一代一致则复用 generation，否则新开 generation 写 journal。
4. 新文件复制进 `ContentStore`；按保留代数清理旧文件（日志记录被清理的代际）。
5. HTTP 服务对外提供清单与文件。

### 5.2 进服前检查（替代 v1 的「进服后询问 + 缓存拦截」）

1. `ConnectScreen.startConnecting` 被 Mixin 拦截（首次，未 bypass）。
2. 取消原连接，显示「正在检查服务器…」并异步执行 `JoinProbe`：
   - 自行连服务器做一次 MC status ping（3 秒超时）；
   - 从 `ServerStatus.forgeData()` 读服务器 mod 列表；
   - 从广告通道读同步地址。
3. 拉取清单（HTTP，超时/重试），`DiffEngine` 与本地比对。
4. 展示 `JoinCheckScreen`：
   - 一致 → 自动继续连接（可配置）；
   - 有差异 → 列出「要下载 N 个 / 要更新 N 个 / 要清理 N 个 / 可选未装 N 个」；
   - 按钮：【一键同步】【仍然连接】【取消】；若服务器没有启用 FTYUpater → 放行。
5. 同步完成后若无需重启 → 自动继续连接；若需重启 → 提示重启，重启后再进服。

### 5.3 同步

1. `SyncEngine` 按 `DiffEngine` 的计划执行：先磁盘预检，再按 sha 去重下载到 `staging/<generation>/`（4 线程、每文件最多 3 次重试、`.part` + SHA256 校验 + 原子改名）。
2. `ApplyService` 应用：
   - 未锁定的文件：立即替换；被替换/删除的旧文件先进 `backup/<generation>/` 或 `trash/<时间>/`；
   - 被占用（已加载的 jar）：写入 `pending/pending.json`，启动 `helper/ApplyHelper`（M5）；此时**保留 staging** 供 helper 使用；
3. 应用成功后把已安装的 path/sha/size 写回 `state.json`（含 generation/lastSync）；失败文件绝不写入目标位置。
4. 需要重启时提示玩家；`ApplyHelper` 在游戏退出后应用并写 `helper.log`。

### 5.4 更新与回退

- 自动跟最新：进服前检查时比对源的 generation 是否比本地新。
- 回退：`JoinCheckScreen` 提供「回到上一版」（用本地 `backup/` 或向服务器请求历史 generation），同样走同步 + 需重启时 helper 应用。

### 5.5 忽略 / 可选

- opt-out（可选模组不要）：按 path 记录，该文件从比对中**彻底排除**（不计入「缺 / 多」），不下载；若已安装则删除进 trash。
- ignore（永不管理）：按 path 记录，同样彻底排除。
- required 条目不允许 opt-out；界面说明原因（「服务器需要它才能进服」）。

## 6. 失败与恢复

| 场景 | 行为 |
| --- | --- |
| 探测超时 / 服务器离线 | 提示后允许普通连接；不阻塞玩家 |
| 服务器没装 FTYUpater | 正常放行，不显示同步 UI |
| 清单拉取失败 | 提示 + 重试按钮；可「仍然连接」 |
| 下载失败 / 哈希不符 | 重试 3 次；仍失败则该项标红，其余继续 |
| 磁盘空间不足 | 应用前预检查，不足则不动任何文件 |
| Windows 文件被占用 | 写入 pending，退出后 helper 应用；下次启动检测结果 |
| helper 没能运行 | 下次启动时检测 pending 并再次尝试；提示玩家重启 |
| 服务器 generation 比本地旧 | 提示「服务器回退了版本」，默认跟随服务器（可配置） |
| 玩家手改被管理文件 | SHA 不符视为本地修改；同步时覆盖并备份进 trash |
| 路径非法（穿越/绝对/盘符） | 拒绝该条目并告警，整个清单不信任 |

## 7. 与 v1 的对应关系

> 下表是历史记录：v1 的类**已经全部删除**（`.trash-v1-20261106/`），此处只说明 v2 是从哪些东西重写来的。

| v1 | v2 处理 |
| --- | --- |
| `SyncHttpServer`（/manifest、/file/<name>） | 重写为 `/api/v1/*`，内容寻址 |
| `ServerManifest`（mods + 平铺同步目录） | 重写为 ContentScanner + PackPublisher + 代际 |
| `SyncManager`（文件名启发式、忽略仍识别） | 重写为 core.DiffEngine + SyncEngine（path+sha256） |
| `PreJoinGuard` + `ClientConnectionMixin` + `ServerManifestCache` | 重写为 JoinProbe + JoinDecision + ConnectScreenMixin |
| `SyncNetwork`（入服后问同步源） | 改为 status ping 广告（`SyncAdvert` + DisplayTest）；入服后不再需要 |
| `SyncScreen` / `PreJoinCheckScreen` | 重写为中文大白话 UI（`JoinCheckScreen`） |
| 待删除列表 `ftyupater-pending-deletions.txt` | 升级为 `pending/pending.json` + ApplyHelper |
| 忽略列表 `ftyupater-ignore.txt` | 升级为 state.json 内 path 维度 opt-out/ignore |
| 配置热重载 | v1 的 tick 热重载已移除：服务端配置改动后需重启；内容变化由定时 rescan 自动发布 |

## 8. 约束与非目标

**约束**：

- Forge 1.20.1 / Java 17 字节码 / Gradle 8.8；只用 JDK 自带 HTTP 与 JUnit 等已缓存依赖。
- 客户端与服务端可同 jar（Forge 双端 mod）。
- 客户端广告机制必须不破坏原版客户端连接（额外通道不 required）。
- dev 环境必须保留 MixinGradle + refmap + `--mixin.config` 配置（ADR-016），否则客户端 Mixin 静默不生效。
- GUI 全流程验收需要解锁的桌面会话（ADR-017）；自动化只覆盖到「拦截/探测/清单/差异/引擎同步」。

**非目标（v2 不做）**：

- Fabric / NeoForge / 其他 MC 版本（见 ADR-001）。
- 第三方启动器集成、自动重启游戏、断点续传、P2P 分发。
- 权限/账号体系（HTTP 只读公开内容，无敏感数据）。
- 修改服务器端模组加载行为（不注入 loader）。
