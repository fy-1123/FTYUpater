package com.ftysync.client.gui;

import com.ftysync.FTYUpater;
import com.ftysync.client.SyncPaths;
import com.ftysync.client.TrashBrowser;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 回收站界面（M6-1）：列出 {@code ftyupater/trash/} 里每次清理的时间戳目录，挑一个还原。
 *
 * <p>「还原」是非破坏性的：目标位置已经有同名文件就跳过，绝不覆盖玩家现有内容。
 * 还原采用「移回去」而不是「复制」，所以文件离开回收站后对应的时间戳目录会变空，
 * 由 {@link TrashBrowser#pruneEmpty()} 收尾——否则回收站会越攒越多。</p>
 *
 * <p>布局与 {@link JoinCheckScreen} / {@link RollbackScreen} 共用 {@link CheckLayout}。</p>
 */
public final class TrashScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);

    private static final int MARGIN = CheckLayout.MARGIN;
    private static final int BUTTON_H = CheckLayout.BUTTON_H;
    private static final int ROW_H = CheckLayout.ROW_H;

    private enum Phase {
        LOADING, READY, WORKING, DONE
    }

    private final Screen parent;

    private Phase phase = Phase.LOADING;
    private String statusLine = "正在读取回收站…";
    private String reportLine = "";
    private boolean reportOk = true;
    private List<TrashBrowser.Entry> entries = List.of();
    private TrashBrowser.Entry selected;
    private EntryList list;

    public TrashScreen(Screen parent) {
        super(Component.literal("回收站"));
        this.parent = parent;
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

    // ---------------------------------------------------------------- 加载

    private void startLoading() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        CompletableFuture.supplyAsync(() -> loadEntries(gameDir))
                .whenComplete((result, error) -> minecraft.execute(() -> onLoaded(result, error)));
    }

    private static List<TrashBrowser.Entry> loadEntries(Path gameDir) {
        return new TrashBrowser(gameDir).list();
    }

    private void onLoaded(List<TrashBrowser.Entry> result, Throwable error) {
        if (error != null) {
            LOGGER.warn("FTYUpater 读取回收站失败: {}", error.toString());
            this.entries = List.of();
            this.statusLine = "读取回收站失败";
        } else {
            this.entries = result == null ? List.of() : result;
            this.statusLine = entries.isEmpty()
                    ? "回收站是空的"
                    : "选中一次清理记录，然后点「还原」";
        }
        this.selected = entries.isEmpty() ? null : entries.get(0);
        this.phase = Phase.READY;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- 还原

    private void restore() {
        if (selected == null) {
            return;
        }
        this.phase = Phase.WORKING;
        this.statusLine = "正在还原 " + selected.timestamp() + "…";
        rebuildWidgets();

        Path gameDir = FMLPaths.GAMEDIR.get();
        String timestamp = selected.timestamp();
        CompletableFuture.supplyAsync(() -> {
            TrashBrowser browser = new TrashBrowser(gameDir);
            TrashBrowser.RestoreResult result = browser.restore(timestamp);
            int pruned = browser.pruneEmpty();
            return new Outcome(result, pruned);
        }).whenComplete((result, error) -> minecraft.execute(() -> onRestored(result, error)));
    }

    private record Outcome(TrashBrowser.RestoreResult result, int pruned) {
    }

    private void onRestored(Outcome outcome, Throwable error) {
        this.phase = Phase.DONE;
        if (error != null) {
            this.reportOk = false;
            this.reportLine = "还原失败：" + error;
            LOGGER.warn("FTYUpater 还原回收站失败", error);
        } else if (outcome == null) {
            this.reportOk = false;
            this.reportLine = "还原没有返回结果";
        } else {
            this.reportOk = outcome.result().ok();
            StringBuilder sb = new StringBuilder(outcome.result().describe());
            if (outcome.pruned() > 0) {
                sb.append(" · 清掉 ").append(outcome.pruned()).append(" 个空目录");
            }
            if (!outcome.result().errors().isEmpty()) {
                sb.append(" · ").append(outcome.result().errors().get(0));
            }
            this.reportLine = sb.toString();
        }
        this.statusLine = reportOk ? "还原完成，重启游戏后生效" : "还原未全部成功";
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
        EntryList created = new EntryList(minecraft, this.width, this.height,
                metrics.listTop(), metrics.listBottom(), font);
        created.fill(entries, selected);
        created.setScrollAmount(scroll);
        this.list = addRenderableWidget(created);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && list != null && phase == Phase.READY) {
            for (EntryRow row : list.children()) {
                if (row.hit(mouseX, mouseY)) {
                    this.selected = row.entry();
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
            addButtonRow(y, List.of(new Action("返回", this::closeToParent)));
            return;
        }
        List<Action> actions = new ArrayList<>();
        if (selected != null) {
            actions.add(new Action("还原这次清理", this::restore));
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
            guiGraphics.drawCenteredString(font, Component.literal("还原不会覆盖已有文件"),
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

    private String truncate(String text) {
        int max = Math.max(20, this.width - MARGIN * 2);
        if (font.width(text) <= max) {
            return text;
        }
        String cut = font.plainSubstrByWidth(text, max - font.width("…"));
        return cut + "…";
    }

    // ---------------------------------------------------------------- 列表控件

    private static final class EntryList extends ObjectSelectionList<EntryRow> {

        EntryList(Minecraft minecraft, int width, int height, int top, int bottom, Font font) {
            super(minecraft, width, height, top, bottom, ROW_H);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }

        @Override
        public int getRowWidth() {
            return Math.max(40, this.width - MARGIN * 2 - 12);
        }

        void fill(List<TrashBrowser.Entry> entries, TrashBrowser.Entry selected) {
            clearEntries();
            for (TrashBrowser.Entry entry : entries) {
                addEntry(new EntryRow(entry, entry.equals(selected)));
            }
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 8;
        }
    }

    /** 一行清理记录。点整行选中。 */
    private static final class EntryRow extends ObjectSelectionList.Entry<EntryRow> {

        private final TrashBrowser.Entry entry;
        private final boolean selected;
        private int rowTop;
        private int rowLeft;
        private int rowWidth;
        private int rowHeight;

        EntryRow(TrashBrowser.Entry entry, boolean selected) {
            this.entry = entry;
            this.selected = selected;
        }

        TrashBrowser.Entry entry() {
            return entry;
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
            guiGraphics.drawString(font, mark + entry.describe(), left + 4, textY, 0xE0E0E0, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.describe());
        }
    }
}
