# 同步协议 v2（草案）

> 状态：设计稿，M2/M4 落地时以实现为准并回写本文档。
> 版本：schema = 2；客户端与服务端模组版本需匹配 schema 主版本，否则拒绝同步并提示升级。

## 1. 基本约定

- 服务器通过内嵌 HTTP（JDK HttpServer）只读地提供清单与文件。
- 基础地址 `baseUrl` 来自服务端配置 `publicSyncUrl`（推荐），或由客户端记住的来源 / 客户端配置提供。无尾斜杠。
- 文件按 **SHA256 内容寻址**，路径 `/api/v1/files/<sha256>`；同一内容只存一份、可永久缓存。
- 所有路径都是**相对游戏目录**的路径（如 `mods/xxx.jar`、`config/xxx.toml`），见第 6 节校验规则。
- 字符编码 UTF-8；JSON 字段名小驼峰；时间戳 ISO-8601 UTC。

## 2. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/health` | 存活检查 + 协议版本 |
| GET | `/api/v1/pack` | 获取**当前**清单 |
| GET | `/api/v1/pack?generation=<id>` | 获取指定历史代际清单（仍在保留期时） |
| GET | `/api/v1/generations` | 保留的代际列表（供回退 UI） |
| GET | `/api/v1/files/<sha256>` | 下载文件（不可变，可强缓存） |

### 2.1 health

```json
{ "ok": true, "mod": "ftyupater", "schema": 2, "modVersion": "2.0.0", "generation": "20261021-201800-1a2b3c4d" }
```

### 2.2 pack（清单）

```json
{
  "schema": 2,
  "packId": "fty",
  "generation": "20261021-201800-1a2b3c4d",
  "parentGeneration": "20261020-120000-9f8e7d6c",
  "createdAt": "2026-10-21T12:18:00Z",
  "minecraft": "1.20.1",
  "loader": "forge-47.4.0",
  "files": [
    {
      "path": "mods/jei-1.20.1-forge-15.20.0.106.jar",
      "sha256": "0f1e2d...（64 位小写十六进制）",
      "size": 12345678,
      "origin": "server-mods",
      "role": "required"
    },
    {
      "path": "config/sodium-options.json",
      "sha256": "a1b2c3...",
      "size": 2048,
      "origin": "clientpack",
      "role": "optional"
    }
  ]
}
```

字段语义：

| 字段 | 说明 |
| --- | --- |
| `schema` | 协议版本，客户端不认识的版本直接拒绝 |
| `packId` | 同一服务器内容命名空间；变化视为另一套包 |
| `generation` | 代际 ID，内容不变则不变；变化 = 服务器内容有更新 |
| `parentGeneration` | 上一代（回退链） |
| `minecraft` / `loader` | 兼容性校验；不匹配时客户端提示但允许玩家决定 |
| `files[].path` | 相对游戏目录路径，POSIX 风格 `/` |
| `files[].sha256` | 文件内容哈希 |
| `files[].size` | 字节数（用于进度与磁盘预检查） |
| `files[].origin` | `server-mods`（来自服务器 mods）或 `clientpack`（来自客户端专属目录） |
| `files[].role` | `required`（缺了不能进服）或 `optional`（玩家可关） |

> **路径规则（容易搞错，2026-11-07 实测确认）**：`clientpack/` 是「按游戏目录结构」的镜像，
> 发布时**会把 `clientpack/` 这个前缀剥掉**。即服务器上 `clientpack/config/x.toml`
> 在清单里是 `config/x.toml`，客户端也装在 `config/x.toml`。
> 服务器 `mods/a.jar` 的路径是 `mods/a.jar`（`mods/` 前缀保留）。
> 所以 `clientpack/mods/foo.jar` 和服务器 `mods/foo.jar` **是同一个路径**，按规则服务器 mods 优先。

> 说明：`server-mods` 里也可能有客户端模组（例如优化模组，服务端加载但客户端也需要），它属于 required；想让它「可关」，服主应把它放进 `clientpack/mods/` 而不是服务器 `mods/`。

### 2.3 generations

```json
{ "schema": 2, "generations": [ { "generation": "...", "createdAt": "...", "files": 42 } ] }
```

### 2.4 错误响应

```json
{ "ok": false, "error": "not_found" | "bad_request" | "internal", "message": "人类可读说明" }
```

状态码：400（请求非法）、404（不存在/代际已清理）、500（服端错误）。

### 2.5 客户端 HTTP 策略

- 连接/读取超时：连接 3s，读取 10s（文件下载按大小放宽）。
- 重试：清单 2 次；文件每个 3 次（指数退避 1s/2s/4s）。
- 下载：流式写 `staging/<generation>/<path>.part`，边写边算 SHA256，完成后校验再原子改名。
- 并发：默认 4 个文件并行，其余排队。

## 3. 服务器广告（进服前发现同步地址）

目标：玩家**第一次**连服务器就能知道「这服有 FTYUpater，内容在哪」。

机制：利用 Minecraft 原版状态查询（server list ping）中 Forge 注入的 `forgeData`：

- Forge 的 `ServerStatus.forgeData()` 含 `mods`（modId → 标记）与 `channels`（通道名 → 版本字符串）两张表；
- 我们在服务端注册一个不传消息的通道 `ftyupater:syncsource`，把版本字符串设为广告串：

```
v2|<baseUrl>
```

例：`v2|http://mc.example.com:8081`

- 客户端在 `JoinProbe` 里解析：找 `channels` 中 key 为 `ftyupater:syncsource` 的项，取 `version`，以 `v2|` 开头则其后即 `baseUrl`。
- 通道以「接受任意对端版本」注册，且对原版客户端标记为不 required，**不破坏原版/无模组客户端连接**。
- 备选方案（若通道方案在实测中有兼容问题）：把广告串放进 FTYUpater 自己的 `DisplayTest.suppliedVersion`（`mods` 表里 `ftyupater` 的值），客户端从 `forgeData.mods` 读取。M4 前做一次实测二选一，结果写回本文档。

发现顺序（客户端）：

1. ping 广告；
2. 该服务器地址在 `servers.json` 里记住的来源；
3. 客户端配置 `syncUrl`；
4. 都没有 → 提示「服务器没有告诉客户端去哪里下载，请联系服主」，允许继续普通连接。

服务器端 `publicSyncUrl` 配置建议：

- 公网服务器必须填（NAT 后客户端不一定能直连 8081）；
- 局域网可留空，客户端会尝试用自己连接用的地址 + `httpPort` 推导（作为最后兜底）。

## 4. 比对规则（客户端 DiffEngine）

本地每个条目归入且仅归入以下一种状态：

| 状态 | 判定 | 动作 |
| --- | --- | --- |
| OK | 文件存在且 SHA256 一致 | 无 |
| MISSING | 清单有、本地没有 | 下载（required 必装；optional 未 opt-out 也装） |
| OUTDATED | 存在但哈希不同 | 备份旧文件 → 下载替换 |
| MODIFIED_LOCAL | 本地哈希不同且文件属于「玩家修改」（不区分，统一按 OUTDATED 处理，旧文件进 trash） | 替换 |
| MANUAL_DISABLED | 存在 `xxx.jar.disabled`（Forge 不加载） | 视为未安装：required 提示恢复/下载，optional 保持不动 |
| EXTRA | 受管目录里存在、清单里没有 | 移入 trash（镜像） |
| OPTED_OUT | 玩家勾掉的可选条目 | **完全排除**，不计入任何统计、不下载、不提示 |
| IGNORED | 玩家标记忽略的 path | **完全排除**（同 OPTED_OUT） |
| PENDING | 有待重启应用的操作 | 计入「需重启」 |

受管目录（镜像范围）：

- `mods/` 下所有 `.jar`：由服务器清单决定；清单外的移入 trash（用户要求「自动删除，与服务器完全一致」），但**清理前展示列表**，且以下受保护：
  - FTYUpater 自己的 jar；
  - 玩家明确 opt-out 的文件；
  - `ftyupater/` 状态目录本身。
- `config/`、`resourcepacks/`、`shaderpacks/`：只管理清单声明的文件；玩家自己的其它文件不动（避免误删服务器没管的本地文件）。

## 5. 应用规则（何时需要重启）

| 变更 | 是否可立即应用 |
| --- | --- |
| 新增文件（模组/资源包/光影/配置） | 可立即（模组下次启动生效） |
| 替换未加载的 jar | 可立即（下次启动生效） |
| 替换已加载的 jar（Windows 文件锁） | 需重启 → pending |
| 删除已加载的 jar | 需重启 → pending（或先改名 `.disabled` 让下次不加载） |
| config / 资源包 / 光影 | 可立即（资源包/光影需玩家在游戏内重载） |

pending 由独立 JVM `ApplyHelper` 在游戏退出后执行（只用 JDK，不依赖 Minecraft）；执行结果写 `ftyupater/logs/helper.log`，下次启动读取并提示。

## 6. 安全与健壮性

- **路径校验**：必须是相对路径、用 `/`；拒绝空、`..`、`.`、绝对路径、盘符、`\`、控制字符；解析后的真实路径必须仍在游戏目录内。
- **大小限制**：单个文件默认上限 512 MiB（可配置）；清单大小上限 8 MiB。
- **哈希校验**：所有下载必须 SHA256 匹配才应用；不匹配即删除 `.part` 并重试。
- **不信任服务器内容**：服主本身可信，但协议仍防路径穿越与超大文件；不支持符号链接。
- **无鉴权**：只读公开内容，不含敏感数据；如服主要求，可后续加 shared token（v2 不做）。
- HTTP 明文可用；公网建议 `publicSyncUrl` 用 https（客户端用 JDK HttpClient 自动支持）。
- 不执行下载内容中的任何代码（模组加载是玩家自己选择的游戏行为）。

## 7. 兼容性

- 协议 `schema` 不匹配：客户端在同步前检查，提示「服务器与客户端模组版本不匹配，请升级」。
- Minecraft/loader 不匹配：提示，但由玩家决定是否继续。
- v1 服务器（无 `/api/v1`、入服后才给源）与新客户端：新客户端视为「不支持 FTYUpater 同步」，放行普通连接。
- 新服务器与 v1 客户端：v1 客户端把广告通道当普通通道忽略，能正常连接（不 required）。
