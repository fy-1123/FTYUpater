package com.ftysync.client.gui;

/**
 * 进服检查界面的布局计算（纯整数运算，不依赖 Minecraft，可以直接单测）。
 *
 * <p>为什么要单独抽出来：界面以前是硬编码坐标，小窗口下文字和按钮会叠在一起。
 * 现在所有纵向位置都从这里算，{@link JoinCheckScreen} 只负责按这套数值画，
 * 于是「任何窗口尺寸都不重叠」这件事可以被测试覆盖，而不是靠肉眼看一次。</p>
 *
 * <p>纵向自上而下依次是：标题 → 服务器行（够高才画）→ 状态行（够高才画）→ 差异列表 →
 * 按钮行（永远贴底）。空间不够时从下往上砍：先砍状态行，再砍服务器行，最后连标题也不画，
 * 只留列表和按钮——宁可少显示，也不让控件叠在一起。</p>
 */
public final class CheckLayout {

    /** 左右留白。 */
    public static final int MARGIN = 8;
    /** 按钮高度。 */
    public static final int BUTTON_H = 20;
    /** 列表每行高度。 */
    public static final int ROW_H = 24;
    /** 列表底部与按钮行之间的空隙。 */
    public static final int GAP_AFTER_LIST = 18;
    /** 第一行文字的 y。 */
    public static final int TITLE_Y = 12;
    /** 单行文字高度（原版 font 的 lineHeight 是 9）。 */
    public static final int LINE_H = 9;
    /** 按钮之间的横向间距。 */
    public static final int BUTTON_GAP = 6;
    /** 单个按钮最宽值，避免宽屏下按钮长得像长条。 */
    public static final int MAX_BUTTON_W = 150;
    /** 列表偏好的起始 y（内容够多时列表从这里开始）。 */
    public static final int LIST_PREFERRED_TOP = 56;

    /** 表示「这一行不画」。 */
    public static final int HIDDEN = Integer.MIN_VALUE / 4;

    private CheckLayout() {
    }

    /**
     * 一次布局的全部坐标。
     *
     * @param width        窗口宽
     * @param height       窗口高
     * @param showTitle    是否画标题
     * @param titleY       标题 y（不画时为 {@link #HIDDEN}）
     * @param showSubtitle 是否画服务器行
     * @param subtitleY    服务器行 y（不画时为 {@link #HIDDEN}）
     * @param showStatus   是否画状态行
     * @param statusY      状态行 y（不画时为 {@link #HIDDEN}）
     * @param listVisible  是否建列表（空间太小就不建）
     * @param listTop      列表上边界
     * @param listBottom   列表下边界
     * @param buttonY      按钮行 y
     */
    public record Metrics(int width,
                          int height,
                          boolean showTitle,
                          int titleY,
                          boolean showSubtitle,
                          int subtitleY,
                          boolean showStatus,
                          int statusY,
                          boolean listVisible,
                          int listTop,
                          int listBottom,
                          int buttonY) {

        /** 最下面一行文字的底边；一行都不画时为 0。 */
        public int textBottom() {
            if (showStatus) {
                return statusY + LINE_H;
            }
            if (showSubtitle) {
                return subtitleY + LINE_H;
            }
            if (showTitle) {
                return titleY + LINE_H;
            }
            return 0;
        }
    }

    /**
     * 按窗口尺寸算布局。
     *
     * @param width  窗口宽
     * @param height 窗口高
     */
    public static Metrics of(int width, int height) {
        int buttonY = Math.max(0, height - MARGIN - BUTTON_H);
        int listBottom = Math.max(0, buttonY - GAP_AFTER_LIST);

        int cursor = TITLE_Y;
        boolean showTitle = height >= 60;
        int titleY = showTitle ? cursor : HIDDEN;
        if (showTitle) {
            cursor += LINE_H + 3;
        }

        boolean showSubtitle = height >= 110;
        int subtitleY = showSubtitle ? cursor : HIDDEN;
        if (showSubtitle) {
            cursor += LINE_H + 5;
        }

        boolean showStatus = height >= 200;
        int statusY = showStatus ? cursor : HIDDEN;
        if (showStatus) {
            cursor += LINE_H + 3;
        }

        // 列表优先吃满中间空间；但至少给两行，放不下两行就干脆不建列表。
        int listTop = Math.max(cursor + 2, Math.min(LIST_PREFERRED_TOP, listBottom - ROW_H * 2));
        boolean listVisible = listBottom - listTop >= ROW_H;

        return new Metrics(width, height, showTitle, titleY, showSubtitle, subtitleY,
                showStatus, statusY, listVisible, listTop, listBottom, buttonY);
    }

    /**
     * 一行按钮里每颗的宽度。
     *
     * @param availableWidth 可用宽度（通常是窗口宽）
     * @param count          按钮个数
     */
    public static int buttonWidth(int availableWidth, int count) {
        if (count <= 0) {
            return 0;
        }
        int usable = Math.max(1, availableWidth - MARGIN * 2);
        int gap = count > 1 ? BUTTON_GAP : 0;
        int width = (usable - gap * (count - 1)) / count;
        width = Math.min(MAX_BUTTON_W, width);
        return width < 1 ? Math.max(1, usable / count) : width;
    }

    /**
     * 一行按钮的总宽度。
     *
     * @param availableWidth 可用宽度
     * @param count          按钮个数
     */
    public static int buttonRowWidth(int availableWidth, int count) {
        if (count <= 0) {
            return 0;
        }
        int gap = count > 1 ? BUTTON_GAP : 0;
        return buttonWidth(availableWidth, count) * count + gap * (count - 1);
    }

    /**
     * 一行按钮的起始 x（整行水平居中）。
     *
     * @param availableWidth 可用宽度
     * @param count          按钮个数
     */
    public static int buttonStartX(int availableWidth, int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.max(0, (availableWidth - buttonRowWidth(availableWidth, count)) / 2);
    }
}
