package com.ftysync.client.gui;

import com.ftysync.FTYUpater;
import com.ftysync.client.LocalScanner;
import com.ftysync.client.PackClient;
import com.ftysync.client.RollbackPlanner;
import com.ftysync.client.RollbackService;
import com.ftysync.client.ServerGeneration;
import com.ftysync.client.SyncEngine;
import com.ftysync.client.SyncPaths;
import com.ftysync.core.DiffEngine;
import com.ftysync.core.DiffResult;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 版本回退界面（M5-4）：列出「可以退回去的代际」，让玩家挑一个退。
 *
 * <p>数据来源：{@link RollbackPlanner} 合并「本地备份目录」与「服务器历史清单」。
 * 选中后走 {@link #restore}：服务器还留着那一代就拉清单重新同步（最准），
 * 否则退回本地备份（离线兜底，界面会明确标注「可能不完整」）。</p>
 *
 * <p>布局与 {@link JoinCheckScreen} 共用 {@link CheckLayout}，任何窗口尺寸都不重叠。</p>
 */
public final class RollbackScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);

    private static final int MARGIN = CheckLayout.MARGIN;
    private static final int BUTTON_H = CheckLayout.BUTTON_H;
    private static final int ROW_H = CheckLayout.ROW_H;

    private enum Phase {
        LOADING, READY, WORKING, DONE
    }

    private final Screen parent;
    private final String sourceUrl;

    private Phase phase = Phase.LOADING;
    private String statusLine = "正在查找可回退的版本…";
    private String reportLine = "";
    private boolean reportOk = true;
    private List<RollbackPlanner.Candidate> candidates = List.of();
    private RollbackPlanner.Candidate selected;
    private CandidateList list;

    public RollbackScreen(Screen parent, String sourceUrl) {
        super(Component.literal("版本回退"));
        this.parent = parent;
        this.sourceUrl = sourceUrl;
    }

    @Override
    protected void init() {
        clearWidgets();
        this.list = null;
        if (phase == Phase.LOADING) {
            startLoading();
        }
        if (phase == Phase.READY || phase == Phase.DONE) {
            buildList();
        }
        addButtonsForPhase();
    }

    // ---------------------------------------------------------------- 加载候选

    private void startLoading() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        String url = sourceUrl;
        CompletableFuture.supplyAsync(() -> loadCandidates(gameDir, url))
                .whenComplete((result, error) -> minecraft.execute(() -> onLoaded(result, error)));
    }

    private static List<RollbackPlanner.Candidate> loadCandidates(Path gameDir, String url) {
        SyncPaths paths = new SyncPaths(gameDir);
        LocalState state = new StateStore(paths.stateFile()).load();
        List<String> backups = RollbackService.listLocalBackups(gameDir);
        List<ServerGeneration> servers = (url == null || url.isBlank())
                ? List.of()
                : PackClient.fetchGenerations(url).orElse(List.of());
        return RollbackPlanner.candidates(state, backups, servers);
    }

    private void onLoaded(List<RollbackPlanner.Candidate> result, Throwable error) {
        if (error != null) {
            LOGGER.warn("FTYUpater 读取可回退版本失败: {}", error.toString());
            this.candidates = List.of();
            this.statusLine = "读取可回退版本失败";
        } else {
            this.candidates = result == null ? List.of() : result;
            this.statusLine = candidates.isEmpty()
                    ? "没有可以退回的版本"
                    : "选中一个版本，然后点「退回到这个版本」";
        }
        this.selected = RollbackPlanner.defaultTarget(candidates).orElse(null);
        this.phase = Phase.READY;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 回退

    private void restore() {
        if (selected == null) {
            return;
        }
        this.phase = Phase.WORKING;
        this.statusLine = "正在退回到 " + shorten(selected.generation()) + "…";
        rebuildWidgets();

        Path gameDir = FMLPaths.GAMEDIR.get();
        SyncPaths paths = new SyncPaths(gameDir);
        String target = selected.generation();
        String url = sourceUrl;
        LocalState state = new StateStore(paths.stateFile()).load();
        String current = state.generation();

        CompletableFuture.supplyAsync(() -> {
            // 首选：服务器还留着那一代 → 拉它的清单重新同步，文件齐、哈希可校验
            if (url != null && !url.isBlank()) {
                Optional<PackManifest> manifest = PackClient.fetchManifest(url, target);
                if (manifest.isPresent()) {
                    return syncFromServer(gameDir, url, manifest.get());
                }
            }
            // 兜底：本地备份
            return RollbackService.restoreFromBackup(gameDir, current, target);
        }).whenComplete((result, error) -> minecraft.execute(() -> onRestored(result, error)));
    }

    private static Object syncFromServer(Path gameDir, String url, PackManifest manifest) {
        try {
            SyncPaths paths = new SyncPaths(gameDir);
            StateStore store = new StateStore(paths.stateFile());
            LocalState state = store.load();
            LocalScanner.ScanResult scan = LocalScanner.scan(gameDir, manifest);
            DiffResult diff = DiffEngine.diff(manifest, scan.present(), scan.manualDisabled(),
                    state.optOut(), state.ignored(), Set.of(), Map.of());
            SyncEngine engine = new SyncEngine(url, gameDir, store);
            return engine.sync(manifest, diff);
        } catch (IOException e) {
            return e;
        }
    }

    private void onRestored(Object result, Throwable error) {
        this.phase = Phase.DONE;
        if (error != null) {
            this.reportOk = false;
            this.reportLine = "回退失败：" + error;
            LOGGER.warn("FTYUpater 回退失败", error);
        } else if (result instanceof SyncEngine.SyncReport report) {
            this.reportOk = report.ok();
            StringBuilder sb = new StringBuilder();
            sb.append("从服务器还原 · 安装 ").append(report.installed().size())
                    .append(" · 更新 ").append(report.updated().size())
                    .append(" · 清理 ").append(report.deleted().size());
            if (!report.pending().isEmpty()) {
                sb.append(" · 排队 ").append(report.pending().size()).append("（重启后生效）");
            }
            if (!report.failed().isEmpty()) {
                sb.append(" · 失败 ").append(report.failed().size());
            }
            this.reportLine = sb.toString();
        } else if (result instanceof RollbackService.RollbackResult rollback) {
            this.reportOk = rollback.ok();
            StringBuilder sb = new StringBuilder();
            sb.append("从本地备份还原 ").append(rollback.restored()).append(" 个文件");
            if (rollback.failed() > 0) {
                sb.append(" · 失败 ").append(rollback.failed());
            }
            if (rollback.incomplete()) {
                sb.append(" · 可能不完整（新增文件无法删除）");
            }
            this.reportLine = sb.toString();
        } else if (result instanceof Exception e) {
            this.reportOk = false;
            this.reportLine = "回退失败：" + e;
        } else {
            this.reportOk = false;
            this.reportLine = "回退没有返回结果";
        }
        this.statusLine = reportOk ? "回退完成，请重启游戏后再进入服务器" : "回退未全部成功";
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 布局

    private CheckLayout.Metrics layout() {
        return CheckLayout.of(this.width, this.height);
    }

    private void buildList() {
        CheckLayout.Metrics metrics = layout();
        if (!metrics.listVisible()) {
            return;
        }
        double scroll = list == null ? 0.0 : list.getScrollAmount();
        CandidateList created = new CandidateList(minecraft, this.width, this.height,
                metrics.listTop(), metrics.listBottom(), font);
        created.fill(candidates, selected);
        created.setScrollAmount(scroll);
        this.list = addRenderableWidget(created);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && list != null && phase == Phase.READY) {
            for (CandidateRow row : list.children()) {
                if (row.hit(mouseX, mouseY)) {
                    this.selected = row.candidate();
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- 按钮

    private void addButtonsForPhase() {
        int y = layout().buttonY();
        if (phase == Phase.LOADING || phase == Phase.WORKING) {
            return;
        }
        if (phase == Phase.DONE) {
            addButtonRow(y, List.of(
                    new Action("回收站", this::openTrash),
                    new Action("返回", this::closeToParent)));
            return;
        }
        List<Action> actions = new ArrayList<>();
        if (selected != null) {
            String label = selected.offline() ? "退回到这个版本（本地备份）" : "退回到这个版本";
            actions.add(new Action(label, this::restore));
        }
        actions.add(new Action("回收站", this::openTrash));
        actions.add(new Action("取消", this::closeToParent));
        addButtonRow(y, actions);
    }

    private void openTrash() {
        minecraft.setScreen(new TrashScreen(this));
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

    private void closeToParent() {
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        closeToParent();
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
            guiGraphics.drawCenteredString(font, Component.literal("当前：" + currentLine()),
                    cx, metrics.subtitleY(), 0xA0A0A0);
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

    private String currentLine() {
        try {
            SyncPaths paths = new SyncPaths(FMLPaths.GAMEDIR.get());
            LocalState state = new StateStore(paths.stateFile()).load();
            String generation = state.generation();
            return generation == null || generation.isBlank() ? "还没同步过" : shorten(generation);
        } catch (RuntimeException e) {
            return "未知";
        }
    }

    private String truncate(String text) {
        int max = Math.max(20, this.width - MARGIN * 2);
        if (font.width(text) <= max) {
            return text;
        }
        String cut = font.plainSubstrByWidth(text, max - font.width("…"));
        return cut + "…";
    }

    private static String shorten(String generation) {
        if (generation == null || generation.isBlank()) {
            return "未知";
        }
        int dash = generation.lastIndexOf('-');
        return dash > 0 ? generation.substring(0, dash) : generation;
    }

    // ---------------------------------------------------------------- 列表控件

    private static final class CandidateList extends ObjectSelectionList<CandidateRow> {

        CandidateList(Minecraft minecraft, int width, int height, int top, int bottom, Font font) {
            super(minecraft, width, height, top, bottom, ROW_H);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }

        @Override
        public int getRowWidth() {
            return Math.max(40, this.width - MARGIN * 2 - 12);
        }

        void fill(List<RollbackPlanner.Candidate> candidates, RollbackPlanner.Candidate selected) {
            clearEntries();
            for (RollbackPlanner.Candidate candidate : candidates) {
                addEntry(new CandidateRow(candidate, candidate.equals(selected)));
            }
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 8;
        }
    }

    /** 一行候选。点整行选中。 */
    private static final class CandidateRow extends ObjectSelectionList.Entry<CandidateRow> {

        private final RollbackPlanner.Candidate candidate;
        private final boolean selected;
        private int rowTop;
        private int rowLeft;
        private int rowWidth;
        private int rowHeight;

        CandidateRow(RollbackPlanner.Candidate candidate, boolean selected) {
            this.candidate = candidate;
            this.selected = selected;
        }

        RollbackPlanner.Candidate candidate() {
            return candidate;
        }

        boolean hit(double mouseX, double mouseY) {
            return mouseX >= rowLeft && mouseX < rowLeft + rowWidth
                    && mouseY >= rowTop && mouseY < rowTop + rowHeight;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            this.rowTop = top;
            this.rowLeft = left;
            this.rowWidth = width;
            this.rowHeight = height;

            if (selected || hovered) {
                guiGraphics.fill(left, top, left + width, top + height, selected ? 0x40FFFFFF : 0x20FFFFFF);
            }
            int textY = top + (height - Minecraft.getInstance().font.lineHeight) / 2;
            Font font = Minecraft.getInstance().font;
            String mark = selected ? "▶ " : "  ";
            guiGraphics.drawString(font, mark + candidate.describe(), left + 4, textY,
                    candidate.isPrevious() ? 0xFFD070 : 0xE0E0E0, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(candidate.describe());
        }
    }
}
