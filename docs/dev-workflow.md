# 开发流程（dev-workflow）

> 配合 `agent.md` 使用。目标：少猜、少返工、少跑重构建、交接不靠记忆。

## 1. 每次开工的固定动作

1. 读 `agent.md` 第 2 节的必读清单（至少读 HANDOVER + status-matrix + 当前里程碑）。
2. 查 `docs/status-matrix.md`：要跑的任务上次结果如何、是否已 up-to-date。
3. 明确本次任务的验收标准（写在计划或 issue 里；没有就先问用户）。
4. 选最小的验证命令（见第 2 节），不要直接上 `runClient`。

## 2. 构建验证策略（对应状态表 B 节）

| 改了什么 | 先跑什么 | 不要做什么 |
| --- | --- | --- |
| `core/` 纯逻辑 | `gradlew test` | 不要跑 build/runClient |
| 任意 Java 代码 | `gradlew compileJava --offline` | 不要反复跑 build（含 reobf，增量 59 秒） |
| 发布 / 比对 / 下载 / 清理任意环节 | `gradlew test --tests "com.ftysync.client.SyncScenarioTest"` | 不要启动游戏验证同步逻辑 |
| mixin / 界面布局 / 进服流程 | 先 compileJava + 全量 test；界面外观**不上真机**（用户 2026-11-07 明确要求） | 不要跑 `runClient`/`runServer` 做 GUI 验收 |
| 准备交付 | `gradlew build --offline`，更新状态表 C 节 | 不要跳过测试直接交付 |

- **所有 gradle 调用走 `mc_gradle` 工具**（ADR-021）：bash 跑 `.bat` 会挂起；pwsh 直跑会把输出缓冲到进程结束，看不到进度。
- 每次跑完 gradle：**立刻更新 `docs/status-matrix.md` B 节**（时间、耗时、结果）。
- 测试数量以 `build/test-results/test/*.xml` 求和为准（当前 148 个方法 / 147 过 / 1 skip），不要凭控制台摘要估。
- 一次会话里同一个任务已经通过且代码没变，不要重复跑。

## 3. 编码约定

- 包结构见 `docs/architecture.md` 第 4 节；新代码放对模块，不要往 `FTYUpater.java` 里堆。
- `core/` 与 `helper/` **禁止 import Minecraft/Forge 类**（可在测试里断言）。
- 公共方法写 Javadoc（中文，说清「做什么、何时调用」）；复杂逻辑写行内注释说明原因，不写废话。
- 日志用 `FTYUpater.LOGGER`，格式中文 + 关键值；玩家可见文本进语言文件（`zh_cn`/`en_us`），不硬编码。
- 所有文件写入要原子（临时文件 + 移动）；所有删除要可恢复（trash/backup）。
- 网络、磁盘、哈希相关代码必须处理异常并降级，不允许静默失败。

## 4. 测试纪律

- 先写会失败的测试（red），再实现让它通过（green），最后整理（refactor）。
- `core/` 的测试必须覆盖：正常路径、路径攻击、哈希不符、状态文件损坏、通配符、required/optional/opt-out 组合。
- 游戏内行为写在里程碑「验收方式」里并记录证据（命令/日志路径写状态表）；**界面外观目前不做真机验收**（用户 2026-11-07 要求）。
- 集成测试用 JDK 自带 HttpServer 起临时服务，不依赖外网；「各种同步情况」由 `SyncScenarioTest` 的 13 项覆盖（见状态表 §G）。

## 5. 文档更新触发清单

| 发生了什么 | 必须更新 |
| --- | --- |
| 改了清单格式 / HTTP 接口 / 广告机制 | `docs/protocol.md` |
| 改了目录布局 / 包结构 / 数据流 | `docs/architecture.md` |
| 做了/推翻了技术决策 | `docs/decisions.md` |
| 用户提了新需求或改了需求 | `docs/qa-log.md`（先记录再实现） |
| 完成功能 / 跑了构建 | `docs/status-matrix.md` |
| 里程碑结束 | `HANDOVER.md` + `docs/plan.md` 勾选 |
| 对外可见行为变化 | `README.md` |

## 6. 与用户沟通规范

- 用大白话，不堆术语；一次最多问 3 个问题，每个问题给推荐选项。
- 不确定就停：需求/边界/验收没确认，不写实现代码（规则第 6 条）。
- 每个里程碑结束汇报：做了什么、怎么验证（命令+结果）、下一步；有风险直接说。
- 用户可能会说「先出方案」：先落文档，不写实现。

## 7. 里程碑验收流程

1. 对照 `docs/plan.md` 该里程碑的「验收方式」逐条执行。
2. 结果写 `docs/status-matrix.md`（A 表状态、B 表任务、C 表产物）。
3. 更新 `HANDOVER.md` 第 1/5 节。
4. 若有未完成项：明确写进 HANDOVER 的「遗留」，不隐瞒。

## 8. 禁止事项

- 禁止依赖对话上下文保存重要信息；先落盘。
- 禁止不可逆删除玩家文件（必须进 trash/backup）。
- 禁止在 `core/`/`helper/` 引入 Minecraft 依赖。
- 禁止用文件名/版本字符串猜测同步状态（ADR-011）。
- 禁止为了过验收隐藏失败、静默吞异常、留未记录的 hack。
- 禁止把服务器地址、密钥等环境相关值硬编码进源码（放配置/运行时）。
- **禁止用 pwsh 的 `Get-Content` + `Set-Content` 管道改 `.md` / 中文文件**：本机 PowerShell 按 ANSI 解码后回写会把 UTF-8 中文字符破坏成 `?`（2026-10-21 已损坏 `docs/status-matrix.md` 与 `docs/dev-workflow.md`）。改文档一律用 write/edit 工具。

## 9. 手工端到端验证（服务端 / M2 起）

前提：`run/` 下已有 dev 服务器环境（`eula.txt eula=true`、`server.properties` 端口 25599）。fixtures（假 mod / clientpack 文件）放在 `run/mods/`、`run/clientpack/`，**必须是合法 zip**，否则 Forge 启动会报 `zip END header not found`；生成假 jar：

```powershell
# 生成合法假 jar（先建 .zip，再改扩展名；Compress-Archive 不接受 .jar 作为目标）
$tmp = Join-Path $env:TEMP 'ftyfixture'; New-Item -ItemType Directory -Force $tmp | Out-Null
Set-Content (Join-Path $tmp 'dummy.txt') 'x'
Compress-Archive -Path (Join-Path $tmp 'dummy.txt') -DestinationPath run\mods\fake-server-mod.jar.zip -Force
Move-Item -Force run\mods\fake-server-mod.jar.zip run\mods\fake-server-mod.jar
```

启动与验证（runServer 在后台跑，别用 bash 跑 .bat）：

```powershell
# 终端 A：启动（日志落盘，末尾追加 EXIT 标记）
& .\gradlew.bat runServer --offline --console=plain *> build\runserver.log; Add-Content build\runserver.log "EXIT=$LASTEXITCODE"

# 终端 B：等 HTTP 就绪（中文日志在 Windows 控制台会乱码，别按中文匹配）
while (-not (try { Invoke-RestMethod http://127.0.0.1:8081/api/v1/health -TimeoutSec 2 } catch { $null })) { Start-Sleep 5 }

Invoke-RestMethod http://127.0.0.1:8081/api/v1/health | ConvertTo-Json -Compress
Invoke-RestMethod http://127.0.0.1:8081/api/v1/pack   | ConvertTo-Json -Depth 6
Invoke-RestMethod http://127.0.0.1:8081/api/v1/generations | ConvertTo-Json -Depth 5
# 回退取旧清单
Invoke-RestMethod "http://127.0.0.1:8081/api/v1/pack?generation=<旧的 generation>"
```

验证点清单：health/pack 合法 JSON；`/files/<sha>` 下载后 SHA256 与清单一致、带 ETag；`POST /health` → 405；`/files/notasha` → 400；不存在的 generation/sha → 404；往 `run/mods/` 加 jar 后 10s 内 `/generations` 出现新代（`retainGenerations=2` 时只保留 2 代）；改 `run/config/ftyupater-server.toml` 的 `blacklist` 后重启，命中文件不出现在清单。

停止服务器：按命令行精确杀进程（不要杀掉本机其他项目的 java）：

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*FTYUpater*' -and $_.CommandLine -like '*forgeserveruserdev*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

服务端数据落在 `run/ftyupater/server/`（`journal.jsonl`、`generations/<gen>/manifest.json`、`objects/<sha>`），清库直接删该目录即可。

## 10. 版本控制

远程库：<https://github.com/fy-1123/FTYUpater>（public，默认分支 `main`）。

```bat
git add -A
git commit -m "feat(server): ..."
git push
```

提交信息沿用约定前缀（`docs:` / `feat(core):` / `feat(server):` / `feat(client):` / `fix(...)` / `test:`），一个里程碑至少一个提交。

换行由 `.gitattributes` 统一：仓库内存 LF，Windows 检出为 CRLF；`gradlew` 强制 LF，`*.bat` 强制 CRLF，jar/图片按二进制处理。

> 历史注记：`git init` 于 2026-11-07 执行，首个提交 `2feb4b7`（v2 基线，91 文件）。此前的 v1 没有版本历史。
