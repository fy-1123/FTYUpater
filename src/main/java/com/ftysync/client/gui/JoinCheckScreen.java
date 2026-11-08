package com.ftysync.client.gui;

import com.ftysync.FTYUpater;
import com.ftysync.ModConfig;
import com.ftysync.client.JoinBypass;
import com.ftysync.client.JoinDecision;
import com.ftysync.client.JoinProbe;
import com.ftysync.client.LocalScanner;
import com.ftysync.client.PackClient;
import com.ftysync.client.PendingOp;
import com.ftysync.client.PendingQueue;
import com.ftysync.client.RollbackDetect;
import com.ftysync.client.ServerSourceCache;
import com.ftysync.client.SyncEngine;
import com.ftysync.client.SyncPaths;
import com.ftysync.core.DiffEngine;
import com.ftysync.core.DiffResult;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import com.ftysync.core.SyncAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 进服前检查界面（v2 唯一入口）：探测同步源 → 拉清单 → 算差异 → 让玩家决定。
 *
 * <p>阶段：CHECKING（探测/拉清单）→ READY（列出差异与按钮）→ SYNCING → DONE。</p>
 *
 * <p>布局全部按当前窗口尺寸现算，任何窗口大小都不重叠：
 * 标题/服务器行/状态行固定贴顶，按钮行固定贴底，中间的差异列表吃掉剩余空间
 * （用原版 {@link ObjectSelectionList}，自带滚动条与剪裁）。</p>
 */
public final class JoinCheckScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);

    // 布局常量与算法都在 CheckLayout 里（纯整数运算，可单测）；这里只用它的结果。
    private static final int MARGIN = CheckLayout.MARGIN;
    private static final int BUTTON_H = CheckLayout.BUTTON_H;
    private static final int ROW_H = CheckLayout.ROW_H;

    private static final int TOGGLE_W = 56;
    private static final int TOGGLE_H = 14;

    private enum Phase {
        CHECKING, READY, SYNCING, DONE
    }

    private final Screen parent;
    private final ServerData serverData;
    private final ServerAddress address;

    private Phase phase = Phase.CHECKING;
    private boolean probeStarted;
    private JoinDecision.ProbeOutcome outcome;
    private String sourceUrl;
    private PackManifest manifest;
    private DiffResult diff;
    private LocalScanner.ScanResult scan;
    private Set<String> protectedSet = Set.of();
    private JoinDecision.Decision decision;
    private RollbackDetect.Result rollback = new RollbackDetect.Result(RollbackDetect.Kind.UNKNOWN, null, null);
    private String statusLine = "正在检查服务器模组…";
    private String reportLine = "";
    private boolean reportOk = true;
    private ModList list;

    public JoinCheckScreen(Screen parent, ServerData serverData, ServerAddress address) {
        super(Component.literal("服务器模组检查"));
        this.parent = parent;
        this.serverData = serverData;
        this.address = address;
    }

    @Override
    protected void init() {
        clearWidgets();
        this.list = null;
        if (!probeStarted) {
            probeStarted = true;
            startProbe();
        }
        if (phase == Phase.READY || phase == Phase.DONE) {
            buildList();
        }
        addButtonsForPhase();
    }

    // ---------------------------------------------------------------- 探测

    private void startProbe() {
        JoinProbe.start(serverData, this::onProbe);
    }

    private void onProbe(JoinDecision.ProbeOutcome probeOutcome) {
        this.outcome = probeOutcome;
        String url = null;
        if (probeOutcome.kind() == JoinDecision.ProbeKind.HAS_SOURCE && probeOutcome.advert() != null) {
            url = probeOutcome.advert().baseUrl();
        }
        if (url == null || url.isBlank()) {
            url = ModConfig.CLIENT.syncUrl.get();
        }
        if (url == null || url.isBlank()) {
            if (probeOutcome.kind() == JoinDecision.ProbeKind.NO_FTYUPATER) {
                continueToServer();
                return;
            }
            becomeNotice("服务器没有公布同步地址");
            return;
        }
        this.sourceUrl = url;
        rememberSource(url);
        final String fetchUrl = url;
        CompletableFuture.supplyAsync(() -> PackClient.fetchManifest(fetchUrl))
                .whenComplete((result, error) -> minecraft.execute(() -> onManifest(result, error)));
    }

    private void onManifest(Optional<PackManifest> result, Throwable error) {
        if (error != null) {
            LOGGER.warn("FTYUpater 拉取清单失败: {}", error.toString());
            becomeNotice("无法连接同步服务器");
            return;
        }
        if (result.isEmpty()) {
            becomeNotice("服务器没有返回同步清单");
            return;
        }
        this.manifest = result.get();
        computeDiff();
    }

    private void computeDiff() {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            SyncPaths paths = new SyncPaths(gameDir);
            this.protectedSet = protectedPaths(gameDir);
            this.scan = LocalScanner.scan(gameDir, manifest);
            LocalState state = new StateStore(paths.stateFile()).load();
            this.diff = DiffEngine.diff(manifest, scan.present(), scan.manualDisabled(),
                    state.optOut(), state.ignored(), protectedSet, pendingTargets(paths));
            this.decision = JoinDecision.decide(true, outcome, diff, ModConfig.CLIENT.autoContinueIfInSync.get());
            if (decision.action() == JoinDecision.Action.AUTO_CONTINUE) {
                continueToServer();
                return;
            }
            if (decision.reasonKey().equals("fetch_failed") || decision.reasonKey().equals("no_source")) {
                becomeNotice(decision.reasonKey().equals("no_source") ? "服务器没有公布同步地址" : "拉取服务器模组失败");
                return;
            }
            // M5-5：服务器管理员把内容回滚了，要明说——否则玩家会以为是「没变化」
            this.rollback = RollbackDetect.compare(state, manifest);
            if (rollback.serverRolledBack()) {
                this.statusLine = rollback.describe();
            }
            this.phase = Phase.READY;
            rebuildWidgets();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("FTYUpater 计算本地模组差异失败: {}", e.toString());
            becomeNotice("读取本地模组失败");
        }
    }

    /** 只重算差异（文件没变，变的是 opt-out 集合）。 */
    private void recomputeDiff(LocalState state) {
        if (manifest == null || scan == null) {
            return;
        }
        try {
            SyncPaths paths = new SyncPaths(FMLPaths.GAMEDIR.get());
            this.diff = DiffEngine.diff(manifest, scan.present(), scan.manualDisabled(),
                    state.optOut(), state.ignored(), protectedSet, pendingTargets(paths));
        } catch (RuntimeException e) {
            LOGGER.warn("FTYUpater 重算差异失败: {}", e.toString());
        }
    }

    private static Map<String, String> pendingTargets(SyncPaths paths) {
        Map<String, String> targets = new HashMap<>();
        List<PendingOp> pending = new PendingQueue(paths.pendingFile()).load();
        for (PendingOp op : pending) {
            if (op.sha256() != null) {
                targets.put(op.path(), op.sha256());
            }
        }
        return targets;
    }

    private void becomeNotice(String message) {
        this.statusLine = message;
        this.decision = new JoinDecision.Decision(JoinDecision.Action.CONTINUE_WITH_NOTICE, false, "notice");
        this.phase = Phase.READY;
        rebuildWidgets();
    }

    private void rememberSource(String url) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            new ServerSourceCache(new SyncPaths(gameDir).serversFile()).put(serverData.ip, url);
        } catch (RuntimeException e) {
            LOGGER.debug("FTYUpater 记录同步源失败: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 同步

    private void startSync() {
        if (manifest == null || diff == null || sourceUrl == null) {
            return;
        }
        this.phase = Phase.SYNCING;
        this.statusLine = "正在同步，请稍候…";
        rebuildWidgets();
        Path gameDir = FMLPaths.GAMEDIR.get();
        SyncPaths paths = new SyncPaths(gameDir);
        SyncEngine engine = new SyncEngine(sourceUrl, gameDir, new StateStore(paths.stateFile()));
        CompletableFuture.supplyAsync(() -> engine.sync(manifest, diff))
                .whenComplete((report, error) -> minecraft.execute(() -> onSynced(report, error)));
    }

    private void onSynced(SyncEngine.SyncReport report, Throwable error) {
        if (error != null || report == null) {
            this.reportOk = false;
            this.reportLine = "同步失败：" + (error == null ? "未知错误" : error.toString());
            LOGGER.warn("FTYUpater 同步失败", error);
        } else {
            this.reportOk = report.ok();
            StringBuilder sb = new StringBuilder();
            sb.append("安装 ").append(report.installed().size())
                    .append(" · 更新 ").append(report.updated().size())
                    .append(" · 清理 ").append(report.deleted().size());
            if (!report.pending().isEmpty()) {
                sb.append(" · 排队 ").append(report.pending().size()).append("（重启后生效）");
            }
            if (!report.failed().isEmpty()) {
                sb.append(" · 失败 ").append(report.failed().size());
            }
            this.reportLine = sb.toString();
        }
        this.phase = Phase.DONE;
        this.statusLine = reportOk ? "同步完成，请重启游戏后再进入服务器" : "同步未全部成功";
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 放行

    private void continueToServer() {
        if (sourceUrl != null) {
            rememberSource(sourceUrl);
        }
        JoinBypass.next();
        ConnectScreen.startConnecting(parent == null ? new TitleScreen() : parent, minecraft, address, serverData, false);
    }

    private void closeToParent() {
        minecraft.setScreen(parent == null ? new TitleScreen() : parent);
    }

    /** 打开版本回退界面（M5-4）：返回时回到本界面，不丢当前检查状态。 */
    private void openRollback() {
        minecraft.setScreen(new RollbackScreen(this, sourceUrl));
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    // ---------------------------------------------------------------- 布局

    /** 当前窗口的布局（每次按 width/height 现算，不缓存，窗口一改就跟着变）。 */
    private CheckLayout.Metrics layout() {
        return CheckLayout.of(this.width, this.height);
    }

    private int buttonY() {
        return layout().buttonY();
    }

    private int listBottom() {
        return layout().listBottom();
    }

    private int listTop() {
        return layout().listTop();
    }

    // ---------------------------------------------------------------- 差异列表

    private void buildList() {
        CheckLayout.Metrics metrics = layout();
        if (!metrics.listVisible()) {
            return;
        }
        double scroll = list == null ? 0.0 : list.getScrollAmount();
        ModList created = new ModList(minecraft, this.width, this.height,
                metrics.listTop(), metrics.listBottom(), font);
        created.fill(rows());
        created.setScrollAmount(scroll);
        this.list = addRenderableWidget(created);
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (diff == null) {
            if (decision != null) {
                rows.add(new Row(font, decision.reasonKey(), Row.Kind.INFO, null, false));
            }
            return rows;
        }
        List<SyncAction> actions = diff.actions().stream()
                .filter(action -> action.action() != SyncAction.Action.KEEP
                        && action.action() != SyncAction.Action.IGNORED)
                .sorted((a, b) -> Integer.compare(rank(a.action()), rank(b.action())))
                .toList();
        if (actions.isEmpty()) {
            rows.add(new Row(font, "本地文件与服务器一致", Row.Kind.INFO, null, false));
            return rows;
        }
        for (SyncAction action : actions) {
            boolean optional = action.role() == PackEntry.Role.OPTIONAL;
            rows.add(new Row(font, action.path(), kindOf(action.action()), action.role(),
                    optional && action.action() == SyncAction.Action.OPT_OUT));
        }
        return rows;
    }

    private static int rank(SyncAction.Action action) {
        return switch (action) {
            case INSTALL_REQUIRED -> 0;
            case UPDATE -> 1;
            case MANUAL_DISABLED -> 2;
            case PENDING -> 3;
            case DELETE -> 4;
            case INSTALL_OPTIONAL -> 5;
            case OPT_OUT -> 6;
            default -> 7;
        };
    }

    private static Row.Kind kindOf(SyncAction.Action action) {
        return switch (action) {
            case INSTALL_REQUIRED -> Row.Kind.INSTALL_REQUIRED;
            case INSTALL_OPTIONAL -> Row.Kind.INSTALL_OPTIONAL;
            case UPDATE -> Row.Kind.UPDATE;
            case DELETE -> Row.Kind.DELETE;
            case PENDING -> Row.Kind.PENDING;
            case MANUAL_DISABLED -> Row.Kind.MANUAL_DISABLED;
            case OPT_OUT -> Row.Kind.OPT_OUT;
            default -> Row.Kind.INFO;
        };
    }

    /** 点「我不要 / 恢复」：翻转该可选路径的 opt-out 并写回 state.json。 */
    private boolean toggleOptOut(Row row) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            SyncPaths paths = new SyncPaths(gameDir);
            StateStore store = new StateStore(paths.stateFile());
            LocalState state = store.load();
            Set<String> optOut = new LinkedHashSet<>(state.optOut());
            if (!optOut.remove(row.path())) {
                optOut.add(row.path());
            }
            state.setOptOut(optOut);
            store.save(state);
            recomputeDiff(state);
            rebuildWidgets();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("FTYUpater 保存 opt-out 失败: {}", e.toString());
            return true;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && list != null) {
            for (Row row : list.children()) {
                if (row.toggleHit(mouseX, mouseY)) {
                    return toggleOptOut(row);
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- 按钮

    private void addButtonsForPhase() {
        int y = buttonY();
        if (phase == Phase.CHECKING) {
            addButtonRow(y, List.of(new Action("取消", this::closeToParent)));
            return;
        }
        if (phase == Phase.SYNCING) {
            return;
        }
        if (phase == Phase.DONE) {
            addButtonRow(y, List.of(new Action("返回", this::closeToParent)));
            return;
        }
        boolean notice = decision != null && decision.action() == JoinDecision.Action.CONTINUE_WITH_NOTICE;
        boolean canSync = !notice && diff != null && diff.requiresSync();
        List<Action> actions = new ArrayList<>();
        if (canSync) {
            String label = diff.blocksJoin() ? "一键同步（必须）" : "一键同步";
            actions.add(new Action(label, this::startSync));
        }
        actions.add(new Action(notice ? "仍然进服" : "进入服务器", this::continueToServer));
        if (!notice) {
            actions.add(new Action("版本回退", this::openRollback));
        }
        actions.add(new Action("取消", this::closeToParent));
        addButtonRow(y, actions);
    }

    private record Action(String label, Runnable onPress) {
    }

    private void addButtonRow(int y, List<Action> actions) {
        int count = actions.size();
        int width = CheckLayout.buttonWidth(this.width, count);
        int gap = count > 1 ? CheckLayout.BUTTON_GAP : 0;
        int x = CheckLayout.buttonStartX(this.width, count);
        for (Action action : actions) {
            addRenderableWidget(Button.builder(Component.literal(action.label()), b -> action.onPress().run())
                    .bounds(x, y, width, BUTTON_H).build());
            x += width + gap;
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        CheckLayout.Metrics metrics = layout();
        int cx = this.width / 2;
        if (metrics.showTitle()) {
            guiGraphics.drawCenteredString(font, this.title, cx, metrics.titleY(), 0xFFFFFF);
        }
        if (metrics.showSubtitle()) {
            guiGraphics.drawCenteredString(font, serverLine(), cx, metrics.subtitleY(), 0xA0A0A0);
        }
        if (metrics.showStatus()) {
            guiGraphics.drawCenteredString(font, Component.literal(truncate(statusLine)),
                    cx, metrics.statusY(), 0xFFFFFF);
        }
        if (phase == Phase.DONE && !reportLine.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal(truncate(reportLine)),
                    cx, metrics.buttonY() - 11, reportOk ? 0x80FF80 : 0xFF8080);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private Component serverLine() {
        return Component.literal("服务器：" + serverData.name + "（" + serverData.ip + "）");
    }

    /** 按当前宽度裁剪，超长补省略号。 */
    private String truncate(String text) {
        int max = Math.max(20, this.width - MARGIN * 2);
        if (font.width(text) <= max) {
            return text;
        }
        String cut = font.plainSubstrByWidth(text, max - font.width("…"));
        return cut + "…";
    }

    // ---------------------------------------------------------------- 受保护路径

    private static Set<String> protectedPaths(Path gameDir) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            Path location = Path.of(JoinCheckScreen.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                paths.add("mods/" + location.getFileName());
            }
        } catch (Exception ignored) {
            // 开发环境取不到代码位置时忽略
        }
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (Stream<Path> stream = Files.list(mods)) {
                paths.addAll(stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(FTYUpater.MODID))
                        .map(name -> "mods/" + name)
                        .collect(Collectors.toList()));
            } catch (IOException ignored) {
                // 列目录失败：不追加保护项
            }
        }
        return paths;
    }

    // ---------------------------------------------------------------- 列表控件

    private static final class ModList extends ObjectSelectionList<Row> {

        ModList(Minecraft minecraft, int width, int height, int top, int bottom, Font font) {
            super(minecraft, width, height, top, bottom, ROW_H);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }

        @Override
        public int getRowWidth() {
            return Math.max(40, this.width - MARGIN * 2 - 12);
        }

        /** 一次性装入所有行（{@code addEntry} 是 protected，只能在自己的子类里调）。 */
        void fill(List<Row> rows) {
            clearEntries();
            for (Row row : rows) {
                addEntry(row);
            }
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 8;
        }
    }

    /** 一行差异。{@code Kind} 决定文案与颜色；可选条目右侧带「我不要 / 恢复」小按钮。 */
    private static final class Row extends ObjectSelectionList.Entry<Row> {

        enum Kind {
            INSTALL_REQUIRED("必需安装", 0xFFD070),
            INSTALL_OPTIONAL("可选安装", 0x80D0FF),
            UPDATE("更新", 0xFFD070),
            DELETE("多余清理", 0xFF8080),
            PENDING("待重启生效", 0xFFE080),
            MANUAL_DISABLED("手工禁用", 0xFFE080),
            OPT_OUT("已选择不要", 0x909090),
            INFO("提示", 0xA0A0A0);

            final String label;
            final int color;

            Kind(String label, int color) {
                this.label = label;
                this.color = color;
            }
        }

        private final Font font;
        private final String path;
        private final Kind kind;
        private final PackEntry.Role role;
        private final boolean optedOut;
        private int toggleX;
        private int toggleY;
        private int toggleW;
        private int toggleH;

        Row(Font font, String path, Kind kind, PackEntry.Role role, boolean optedOut) {
            this.font = font;
            this.path = path;
            this.kind = kind;
            this.role = role;
            this.optedOut = optedOut;
        }

        String path() {
            return path;
        }

        boolean toggleHit(double mouseX, double mouseY) {
            return toggleW > 0
                    && mouseX >= toggleX && mouseX < toggleX + toggleW
                    && mouseY >= toggleY && mouseY < toggleY + toggleH;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            int textY = top + (height - this.font.lineHeight) / 2;
            String tag = kind.label;
            guiGraphics.drawString(this.font, tag, left, textY, kind.color, false);
            int tagWidth = this.font.width(tag) + 6;

            boolean showToggle = role == PackEntry.Role.OPTIONAL;
            int reserved = showToggle ? TOGGLE_W + 8 : 0;
            int pathWidth = Math.max(10, width - tagWidth - reserved);
            String shown = path;
            if (this.font.width(shown) > pathWidth) {
                shown = this.font.plainSubstrByWidth(shown, pathWidth - this.font.width("…")) + "…";
            }
            guiGraphics.drawString(this.font, shown, left + tagWidth, textY, 0xE0E0E0, false);

            if (showToggle) {
                this.toggleW = TOGGLE_W;
                this.toggleH = TOGGLE_H;
                this.toggleX = left + width - TOGGLE_W - 2;
                this.toggleY = top + (height - TOGGLE_H) / 2;
                boolean over = mouseX >= toggleX && mouseX < toggleX + toggleW
                        && mouseY >= toggleY && mouseY < toggleY + toggleH;
                int fill = over ? 0x60FFFFFF : 0x30FFFFFF;
                guiGraphics.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, fill);
                guiGraphics.fill(toggleX, toggleY, toggleX + toggleW, toggleY + 1, 0x80FFFFFF);
                guiGraphics.fill(toggleX, toggleY + toggleH - 1, toggleX + toggleW, toggleY + toggleH, 0x80FFFFFF);
                guiGraphics.fill(toggleX, toggleY, toggleX + 1, toggleY + toggleH, 0x80FFFFFF);
                guiGraphics.fill(toggleX + toggleW - 1, toggleY, toggleX + toggleW, toggleY + toggleH, 0x80FFFFFF);
                String label = optedOut ? "恢复" : "我不要";
                guiGraphics.drawCenteredString(this.font, Component.literal(label),
                        toggleX + toggleW / 2, toggleY + (TOGGLE_H - this.font.lineHeight) / 2, 0xFFFFFF);
            } else {
                this.toggleW = 0;
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(kind.label + "：" + path);
        }
    }
}
