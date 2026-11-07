# 决策记录（ADR）

> 格式：背景 → 决策 → 理由 → 后果/代价。新决策追加编号；推翻旧决策时写新 ADR 并标注「替代 ADR-xxx」。
> 记录日期：2026-10-21（方案阶段）

## ADR-001 只支持 Forge 1.20.1

- 状态：已采纳
- 背景：用户明确「只做现在的 Forge 1.20.1」；多版本多加载器方案不在范围内。
- 决策：v2 只构建 Forge 1.20.1 / Java 17 字节码；不引入多版本抽象层。
- 理由：用户只玩这个版本；多版本会显著增加构建/测试成本，且 1.20.1 生态最大。
- 后果：未来若要 NeoForge/Fabric，需另开分支或大改；`core/` 保持无 MC 依赖，为将来留余地。

## ADR-002 清单与进服拦截推倒重写；下载机制保留重写

- 状态：已采纳
- 背景：用户原话「清单、进服拦截很烂，别用，下载勉强可以，下载的东西直接放到 mods」。
- 决策：`ServerManifest`/`SyncHttpServer` 协议、`SyncManager` 比对、`PreJoinGuard`/`ClientConnectionMixin` 全部重写；下载能力保留但改为「内容寻址 + 校验 + 暂存 + 应用」。
- 理由：问题根因在识别模型（文件名/版本字符串）和进服时机（依赖缓存、入服后才问），不是下载本身。
- 后果：v1 大部分类会删除或重写；迁移工作集中在 M6。

## ADR-003 内容来源：服务器 mods 自动 + clientpack 目录 + 黑名单；config 不自动扫

- 状态：已采纳
- 背景：用户要求「服务器的 mods 就够了，config 就不自动了」；另需额外目录放客户端专属内容。
- 决策：
  1. 服务器 `mods/*.jar` 自动进入清单（role=required，自动排除 FTYUpater 自身，黑名单可排除更多）；
  2. 服务器根目录 `clientpack/` 按游戏目录结构组织（config/mods/resourcepacks/shaderpacks），全部 role=optional；
  3. 黑名单：文件名 + 通配符（如 `mods/服务端专用.jar`、`*.server.jar`），对两处来源都生效；
  4. 服务器 `config/` 不自动发布，要下发就显式放进 `clientpack/config/`。
- 理由：服主零手工、最不容易配错；客户端专属内容有独立入口；黑名单解决服务端专用模组。
- 后果：想把客户端模组做成「可关」，必须放 `clientpack/mods/` 而非服务器 `mods/`；文档需讲清。

## ADR-004 可选模组：默认安装，玩家可 opt-out，服务器不阻塞

- 状态：已采纳
- 背景：用户要求「默认全部装好，玩家可以一个一个关掉」「同步界面勾选这个模组我不要，服务器不因此拒绝进服」。
- 决策：clientpack 的内容 role=optional，默认安装；玩家在同步界面勾掉后记录为 opt-out（path 维度），不再下载/提示；服务器端只校验 required，不因 optional 缺失拒绝。
- 理由：满足「电脑不支持/不想用」的玩家留路，同时默认体验一致。
- 后果：opt-out 必须彻底从差异统计中排除（修复 v1「忽略了也会识别」）；required 不允许 opt-out。

## ADR-005 版本管理：自动跟最新 + 保留上一版可回退

- 状态：已采纳
- 背景：用户选择「自动跟到最新 + 保留上一版可回退」。
- 决策：服务器每次内容变化生成 generation（内容不变则不变），保留当前 + 上一代（可配）；客户端自动检测新代际，同步后保留上一代文件到 `backup/`；UI 提供「回到上一版」。
- 理由：既保证一致性，又给翻车留后路；比完整的代际 journal 简单够用。
- 后果：回退需要文件仍在（本地 backup 或服务器保留期），服务端 GC 策略要保守。

## ADR-006 镜像清理 + 回收站

- 状态：已采纳
- 背景：用户选择「自动删除，保证和服务器完全一致」；但直接删玩家文件风险高。
- 决策：`mods/` 下清单外 jar 自动清理，但先展示列表、移入 `ftyupater/trash/<时间>/` 可恢复；FTYUpater 自身 jar、opt-out 文件受保护。
- 理由：满足「完全一致」，同时把不可逆风险降为零；回收站成本低。
- 后果：磁盘会多占一份；需回收站清理策略（保留时间/大小上限，M6）。

## ADR-007 进服前检查用 MC status ping 自行探测

- 状态：已采纳（M4 需实测验证广告通道）
- 背景：v1 依赖「上次记住的源」，第一次进服无法检查；Forge 的 server list ping 会带 `forgeData`（服务器 mod 列表 + 通道版本）。
- 决策：在 `ConnectScreen.startConnecting` 拦截，自行发一次 status ping，从 `ServerStatus.forgeData()` 读服务器 mod 列表与同步地址广告，再拉清单比对，最后决定继续/同步/取消。
- 理由：不依赖缓存、首次即可用；纯 Forge API 可达（`ServerStatus.forgeData()` 为 Forge 补丁字段）。
- 后果：进服多一次 ping 延迟（约几十毫秒）；广告通道方案需实测二选一（备选：DisplayTest marker）；服务器未开 FTYUpater 时直接放行。

## ADR-008 应用策略：立即应用 + 退出后 helper

- 状态：已采纳
- 背景：Windows 上游戏运行中不能替换/删除已加载的 jar；纯模组加载顺序太晚，无法在更早阶段应用。
- 决策：未锁定文件立即应用；需要替换/删除已加载文件的变更写入 `pending/pending.json`，启动独立 JVM `ApplyHelper` 在游戏退出后应用；下次启动检测结果并提示。
- 理由：不注入 loader、不依赖启动器；Windows 下最稳。
- 后果：锁定文件更新需要退出再启动；helper 是额外进程，需处理失败重试；Linux 可尽量立即应用。

## ADR-009 文档驱动 + 状态表

- 状态：已采纳
- 背景：用户要求（第 3/4/5/7 条），并明确「重要信息严禁依靠上下文记录」。
- 决策：以 `agent.md` 为守则、`docs/` 为事实库；每个里程碑更新状态表；每完成功能更新文档。
- 理由：跨会话/跨协作者可恢复；避免重复 gradle 任务。
- 后果：写文档有成本，但换来可交接与低返工。

## ADR-010 （已撤销）

- 状态：已撤销（2026-11-07）
- 说明：原内容是一条关于第三方参考项目的许可注记。本项目源码全部自行实现，未包含任何第三方代码，该注记已无保留必要，按用户要求移除。**编号保留不复用**，以免 ADR-011 起的全部引用失效。

## ADR-011 识别与比对一律 path + SHA256

- 状态：已采纳
- 背景：v1 按文件名/版本字符串判断，结果常有误（用户抱怨）。
- 决策：清单条目以「相对路径 + SHA256」为唯一身份；忽略/opt-out 也按 path 记录；不解析 jar 元数据做同步判断。
- 理由：确定性、可校验、换文件名/版本号不影响判断。
- 后果：需要本地扫描 + 哈希缓存（mtime+size）以控制开销。

## ADR-012 服务端零手工发布

- 状态：已采纳
- 背景：用户希望服务器把内容丢进目录就能用；外部打包目录/规则的做法门槛太高。
- 决策：服务端只扫 `mods/` 与 `clientpack/`，无需额外打包工具/清单文件；内容变化自动新开代际。
- 理由：降低服主门槛，减少「清单过期」类问题。
- 后果：无法对清单做精细人工编辑（如需，放 `clientpack/` 显式控制）；大目录扫描需异步/缓存。

## ADR-013 中文文档只用编辑工具改，禁止 PowerShell 管道回写

- 状态：已采纳
- 背景：2026-10-21 用 `(Get-Content x.md -Raw) -replace ... | Set-Content x.md` 更新状态表后，`docs/status-matrix.md` 与 `docs/dev-workflow.md` 的 UTF-8 中文被破坏成 `?`（PowerShell 按 ANSI 解码后回写），文件失效。
- 决策：`.md` / 中文文本一律用 write/edit 工具修改；需要批量文本替换时用脚本读 UTF-8 写 UTF-8（Node/Python），不得用 pwsh 的 Get-Content + Set-Content 管道。
- 理由：一次事故就毁掉两份文档，恢复成本高；编辑工具有编码保证。
- 后果：大规模文本改动需写小脚本而非一行命令；恢复手段：DSH 会话记录里有原始 write 参数，可用 python 提取重建。

## ADR-014 客户端「先暂存、再应用」+ 锁探测 + pending 保留 staging

- 状态：已采纳
- 背景：Windows 上游戏运行时无法替换/删除已加载的模组 jar；直接下载到 `mods/` 会把游戏目录置于「半更新」状态（v1 的问题之一）。
- 决策：
  1. 所有下载先进 `ftyupater/staging/<generation>/`，SHA256 校验通过后才应用；失败文件绝不进游戏目录。
  2. 应用前用改名探测判断目标是否被占用（`LockProbe.moveProbe`：改名为临时名再改回）。未占用 → 立即应用；被占用 → 写 `pending/pending.json`（替换记录目标 sha，删除记录 action）。
  3. 有 pending 时**保留 staging**（M5 helper 需要暂存文件），全部即时应用成功才清 staging。
  4. `state.json` 只在应用成功后写入；备份进 `backup/<generation>/`，清理进 `trash/<时间>/`。
- 理由：失败可恢复、不破坏正在运行的游戏、helper 有据可依；与架构 §5.3 一致。
- 后果：需要 M5 的 `ApplyHelper` 才能真正完成「重启后生效」；在此之前 pending 只记录不执行（已在 HANDOVER §4 标注缺口）。

## ADR-015 暂存文件按「复制 + 原子改名」应用（同 sha 多路径）

- 状态：已采纳
- 背景：M4 的 `LiveSyncIT` 对真实 dev 服务器跑同步时失败：`mods/fake-server-mod.jar: 暂存文件不存在`。原因是服务器清单里多个条目内容相同（同一 sha256，测试 fixtures 的假 jar 就是这种），`ApplyService` 用 `Files.move` 把唯一一份暂存文件移到第一个目标后，后面的条目就找不到暂存文件了。
- 决策：应用阶段不再 move 暂存文件，而是 `Files.copy` 到目标目录的临时文件（`<name>.fty-<nano>.part`）再原子改名覆盖目标；staging 由 `SyncEngine` 在同步结束（无 pending）时统一清理。
- 理由：同一 sha256 可被多个路径复用是合法清单（内容寻址的必然结果）；复制 + 原子改名既保证目标不出现半截文件，也不依赖目标文件可锁/可 rename。
- 后果：多一次磁盘写入（仅在同内容多路径时才多拷贝；普通模组包几乎不会重复）；`LiveSyncIT` 增加「同内容多路径」回归测试。

## ADR-016 dev 环境 Mixin 加载配置（MixinGradle + refmap + --mixin.config）

- 状态：已采纳
- 背景：M4 的 `ConnectScreenMixin` 在 dev 下完全不生效（客户端直接从原版 `ConnectScreen` 连服，没有任何 mixin 报错）。本机同版本工作范例（`C:\CodeSpace\Java\DeltaNexus`）已验证可用的配置是：MixinGradle 插件 + refmap + runs 的 `--mixin.config`。
- 决策：
  1. `build.gradle` 加 `id 'org.spongepowered.mixin' version '0.7.38'`，加 `mixin { add sourceSets.main, "ftyupater.refmap.json" }`；
  2. 所有 runs 加 `args '--mixin.config=ftyupater.mixins.json'`（dev 下没有 jar manifest，不靠 `MixinConfigs`）；
  3. `ftyupater.mixins.json` 加 `"refmap": "ftyupater.refmap.json"`，`mods.toml` 删除旧的顶层 `mixins=` 字符串（1.20.1 不再读取）；
  4. `@Inject` 目标写 official 名（如 `startConnecting`），由注解处理器映射成 SRG 写进 refmap；不要手写 `m_xxx_` + `remap=false`。
  5. Mixin 类里除注入器方法外不能有非 private 静态方法（`JoinBypass` 因此拆成独立类）。
- 理由：实测这套组合在 dev 能加载并注入，且与生产 jar 的 manifest `MixinConfigs` 路径一致。
- 后果：dev 首次编译要跑 MixinGradle 生成 refmap（约 +20s）；`build.gradle` 多一个插件依赖（已在 settings.gradle 的 forge maven 里）。

## ADR-017 桌面锁屏时不追求 GUI 自动化

- 状态：已采纳
- 背景：M4 收尾想用 PowerShell 注入鼠标点击「一键同步」验证全流程，但本机桌面会话处于锁屏（前台窗口 `LockApp`），`SetForegroundWindow`/`PostMessage` 均无效，无法点击。
- 决策：GUI 全流程（点击一键同步 → 重启 → 进服）留给人工在解锁桌面上验证并记录结果；自动化只覆盖到「拦截/探测/清单/差异/引擎同步」这些能用文件与日志证明的部分（`LiveSyncIT` 真实服务器同步）。
- 理由：不为了自动化在模组里塞测试后门（`-Dftyupater.autotest` 之类），避免生产代码背测试逻辑；锁屏属环境限制，不是实现问题。
- 后果：M4 验收在 status-matrix 标为 ⚠️（自动化部分全过，人工点击待补）；M5/M6 的 GUI 验收同样需人工。

## ADR-018 删除整条 v1 链路，只留 v2 进服检查

- 状态：已采纳（2026-11-06）
- 背景：仓库里同时存在两套同步实现。v1（`SyncScreen` + `SyncManager` + `PreJoinGuard` + `PreJoinCheckScreen` + `SyncNetwork` 通道）仍在编译进 jar，且主菜单上挂着「FTYUpater 同步」按钮；v2（`JoinCheckScreen` + `SyncEngine` + `JoinProbe`）才是新链路。用户要求「删掉主菜单那个按钮」，并确认「一并清掉，只留进服检查这一条链路」。
- 决策：把 v1 的 19 个源文件整体移入 `.trash-v1-20261106/`（可恢复），不在原地留死代码：
  - 界面/引擎：`client/gui/SyncScreen.java`、`client/SyncManager.java`、`client/ModStatus.java`、`client/ClientEvents.java`（主菜单按钮的出处）
  - 旧拦截链：`client/PreJoinGuard.java`、`client/PreJoinCheckScreen.java`、`client/ClientTarget.java`、`client/ClientSyncJoinHandler.java`、`client/ServerManifestCache.java`、`mixin/ClientConnectionMixin.java`
  - 旧网络栈：`network/` 下全部 5 个类 + `common/ManifestData.java`
  - 旧服务端：`server/ServerManifest.java`、`server/SyncHttpServer.java`
- 理由：
  1. **manifest push 通道救不回来也不必要**。v1 的网络栈（`SyncNetwork` → `ManifestPushPacket` → `ServerManifest` → `ServerSyncSource`）只在「玩家已经进了服、连接已建立」时才能推清单，而进服前检查恰恰发生在没有连接的时刻；v2 已经用 status ping 的 DisplayTest 广告 + HTTP `/api/v1/pack` 覆盖了这个需求，整条通道是纯负担。
  2. **`ClientConnectionMixin` 从来没生效过**：它没在 `ftyupater.mixins.json` 的 `client` 数组里注册，是死代码。删掉不改变任何运行时行为。
  3. **`SyncHttpServer.isRunning()` 是 v1/v2 混用 bug**：`ServerSyncSource.currentFor` 用 v1 的 `SyncHttpServer` 判断「HTTP 在不在跑」，而真正在跑的是 v2 的 `HttpSyncServer`，导致广告地址判定永远是「没在跑」。整条删掉后这个 bug 自然消失。
  4. **删除可恢复**：移入 `.trash-v1-20261106/` 而非 `rm`，符合「删除必须可恢复」。
- 后果：
  - 主菜单不再有「FTYUpater 同步」按钮，也不再在启动时偷偷发网络请求；同步只在进服时发生。
  - `ModConfig` 的 Client 段从 11 键缩到 4 键（`enabled` / `syncUrl` / `joinCheckEnabled` / `autoContinueIfInSync`）；孤儿键 `autoCheckOnMainMenu`、`mirror`、`autoCheckOnJoin`、`openSyncScreenOnJoin`、`blockJoinOnModMismatch`、`adoptServerSource`、`adoptServerSourceAlways` 全部删除。**注意**：已有玩家的 `config/ftyupater-client.toml` 里这些旧键会留在文件里（Forge 不会因为多出的键报错，只是忽略）。
  - `FTYUpater.java` 去掉 `SyncNetwork.register()`；`FTYUpater` 现在只负责注册两个 config、发布广告、启停服务端运行时。
  - 测试零破坏：25 个测试类对已删符号零引用，`test --offline` 119 PASSED / 1 SKIPPED。

## ADR-019 进服检查界面改用原版控件，布局全部按窗口现算

- 状态：已采纳（2026-11-06）
- 背景：旧 `JoinCheckScreen` 用手绘行 + 固定 `height/2` 锚点（文本从 `height/2 - 90` 起、按钮在 `height/2 + 22`），在 240px 高的小窗口里明细行会直接压在按钮上；观感也和原版界面不一致。用户要求「原版控件风格重排，自适应窗口大小」。
- 决策：重写 `src/main/java/com/ftysync/client/gui/JoinCheckScreen.java`：
  1. 差异列表改用原版 `ObjectSelectionList`（自带滚动条、剪裁、悬停），行高 `ROW_H = 24`；
  2. 布局不写死任何 y：`buttonY() = height - MARGIN - BUTTON_H`（按钮永远贴底）、`listBottom() = buttonY() - 18`、`headerBottom() = (height>=200 ? STATUS_Y : SUBTITLE_Y) + 12`、`listTop() = max(headerBottom(), min(LIST_TOP, listBottom() - ROW_H*2))`；窗口矮到连两行都放不下时干脆不建列表，按钮仍然贴底可用；
  3. 所有文本过 `truncate()`（按 `width - MARGIN*2` 裁并补「…」），长模组路径不会溢出；
  4. 按钮行 `addButtonRow` 按 `usable = width - MARGIN*2` 等分（每颗最宽 150），整行水平居中，按钮数量随阶段变化也不会挤。
- 理由：原版列表控件自带滚动/剪裁，是「任何窗口尺寸不重叠」最省事也最像原版的解法；把 y 全部改成由 `height` 推导，是根治重叠的唯一办法（继续微调魔法数字只会在另一个窗口尺寸上再撞一次）。
- 后果：
  - 窗口尺寸从 240px 到 4K 都不重叠；矮窗口下列表变小甚至消失，但按钮和状态行始终可见。
  - `ModList` 里包了一层 `void fill(List<Row>)`：`AbstractSelectionList.addEntry` 是 **protected**，子类外调不到（首轮编译就撞在 `JoinCheckScreen.java:320: 错误: addEntry(E) 在 AbstractSelectionList 中是 protected 访问控制`）。
  - 可选模组的「我不要 / 恢复」按钮是行内自绘的 56×14 小控件（记录 `toggleX/toggleY/toggleW/toggleH` 供 `toggleHit` 判定），因为 `ObjectSelectionList.Entry` 不支持往里塞真正的 `Button` 子控件。

## ADR-020 GUI 类签名一律以 mapped_official jar 为准

- 状态：已采纳（2026-11-06）
- 背景：本次要写大量 GUI 代码，但 `C:\CodeSpace\Java\forgedocs`（Forge 官方文档本地副本，70 篇）与 `minecraft-forge-mod` 技能的 `references/api/forge-modern-api.md`（190 行）**都不含任何 GUI 类签名**（grep `ObjectSelectionList` 零命中）。
- 决策：GUI 相关签名一律用 `javap` 反查项目真实编译期 jar：
  `C:\Users\FYyjc\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.2.0_mapped_official_1.20.1\forge-1.20.1-47.2.0_mapped_official_1.20.1.jar`
  （项目 build.gradle 用 FG `[6.0.16,6.2)` + official mappings 1.20.1，这个 jar 就是编译期真实类型来源。）
- 理由：凭记忆写 GUI 签名必错（`addEntry` 的 protected 可见性、`render` 的 10 参签名、`fill` 的 6/7 参重载都踩过）；javap 是零成本、零猜测的权威来源。
- 后果：查 GUI 签名先 javap 这个 jar，不要再翻 forgedocs / 技能 references（它们本来就不覆盖 GUI）。

## ADR-021 构建一律走 mc_gradle，不直接 shell gradlew

- 状态：已采纳（2026-11-06）
- 背景：本机 `bash` 跑 `.bat` 会挂起；直接 `pwsh` 跑 `gradlew.bat` 也会把输出缓冲到进程结束才返回（本次构建 mcdev-probe 时表现为「一直 running、零输出」）。
- 决策：本项目所有 Gradle 任务一律通过 `mc_gradle` 工具执行（`projectDir` + `task` + `args`），不手写 `gradlew.bat` 调用。
- 理由：`mc_gradle` 有超时杀进程树、head/tail 截断、非零退出可读渲染，且输出实时回传。
- 后果：`status-matrix.md` 的 B 表里命令写成 `.\gradlew.bat ...` 只是给人看的等价写法，实际执行走 `mc_gradle`。

## ADR-022 验证只走纯 JVM 测试，不启动游戏做真机验收

- 状态：已采纳（2026-11-07，用户明确要求）
- 背景：之前为了验收进服检查界面，尝试过用 `mcdev-probe`（`C:\CodeSpace\minecraft-mod-dev-main\lab\mcdev-probe`）的客户端 HTTP 探针做截图/点击自动化。结果：该工程依赖 `org.ow2.asm:asm-commons:9.9.1` 本地缓存没有、`forge_version=47.4.28` 也没缓存，构建一直失败。另外要澄清：那张桌面截图其实是 PowerShell `System.Drawing.CopyFromScreen` 截的，不是任何插件提供的。用户原话：「截图和点击，真机测试是哪个插件，工具告诉你们的，**不上真机测试**」。
- 决策：
  1. 不再启动 `runClient` / `runServer` 做 GUI 验收，也不再尝试 mcdev-probe。
  2. 「各种同步情况」由 `src/test/java/com/ftysync/client/SyncScenarioTest.java` 的 13 项纯 JVM 端到端测试覆盖（进程内真 `HttpSyncServer` + `@TempDir` 游戏目录，走真实 `PackClient` → `LocalScanner` → `DiffEngine` → `SyncEngine` 链路）。
  3. 状态表里「界面外观」「点击流程」这类只能真机确认的项，一律标「未验证」，不谎报。
- 理由：纯 JVM 测试覆盖的是「服务器内容变 → 客户端怎么判断 → 下载/更新/清理成什么样」这条真正的逻辑主线，且能在任何机器/CI 上重复跑；GUI 外观与鼠标点击属于人工体验，不值得为此维护一套探针。
- 后果：
  - 允许「逻辑已验证 / 外观未验证」双状态并存，见 `docs/status-matrix.md` §G 末尾的「未覆盖」表。
  - 必须真机才能确认的问题（比如界面到底还重不重叠），交给用户自己开一次游戏看，不由 AI 声称已验证。

## ADR-023 测试数量以 XML 报告求和为准

- 状态：已采纳（2026-11-07）
- 背景：本次曾把 Gradle 控制台摘要误读成「119 PASSED / 120 个方法」，进而在 `HANDOVER.md`、`agent.md`、`status-matrix.md` 里写下错误数字；后来对 `build/test-results/test/*.xml` 求和，实际是 130 个方法（加 `SyncScenarioTest` 的 13 个后为 143）。原文档里写的「130 tests」其实是对的。2026-11-07 又新增 `CheckLayoutTest` 5 项，当前为 **148 个方法 / 147 通过 / 1 skip / 23 个 XML**。
- 决策：任何「当前有多少测试」的数字，一律用 `build/test-results/test/*.xml` 里 `tests` / `failures` / `skipped` 属性求和得出；不凭控制台摘要、不凭记忆。
- 理由：Gradle 的测试输出会因过滤（`--tests`）和 UP-TO-DATE 跳过而只显示子集，控制台摘要极易误读；XML 是这次运行的真实记录。
- 后果：状态表 B 表已注明「数字以 `build/test-results/test/*.xml` 求和为准，别看控制台摘要」。
