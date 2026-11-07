package com.ftysync.client.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面布局的「不重叠」保证（纯整数运算，不需要启动游戏）。
 *
 * <p>用户反馈过「界面有重叠」，所以这里把各种窗口尺寸都跑一遍，逐条断言：
 * 文字不压文字、列表不压按钮、按钮不出屏。这样以后谁改布局，只要跑测试就能知道有没有挤在一起。</p>
 */
class CheckLayoutTest {

    /** 常见窗口宽度（含 Minecraft 的常见 GUI 缩放结果）。 */
    private static final int[] WIDTHS = {160, 320, 427, 640, 854, 1280, 1920, 2560, 3840};

    /** 从很矮到很高都试一遍。 */
    private static final int MIN_HEIGHT = 40;
    private static final int MAX_HEIGHT = 2160;

    @Test
    @DisplayName("任何窗口尺寸下：文字、列表、按钮三者都不重叠")
    void nothingOverlapsAtAnySize() {
        for (int width : WIDTHS) {
            for (int height = MIN_HEIGHT; height <= MAX_HEIGHT; height++) {
                CheckLayout.Metrics m = CheckLayout.of(width, height);
                String where = "（" + width + "x" + height + "）";

                // 1. 按钮行必须在窗口内
                assertTrue(m.buttonY() >= 0, "按钮 y 不能为负" + where);
                assertTrue(m.buttonY() + CheckLayout.BUTTON_H <= Math.max(height, CheckLayout.BUTTON_H + CheckLayout.MARGIN),
                        "按钮不能超出窗口下边" + where);

                // 2. 列表不能盖住按钮
                assertTrue(m.listBottom() <= m.buttonY(), "列表下边界不能超过按钮行" + where);

                // 3. 列表若显示，必须装得下至少一行，且顶部在底部之上
                if (m.listVisible()) {
                    assertTrue(m.listTop() < m.listBottom(), "列表上边界必须小于下边界" + where);
                    assertTrue(m.listBottom() - m.listTop() >= CheckLayout.ROW_H,
                            "显示列表时至少要装得下 " + CheckLayout.ROW_H + "px" + where);
                }

                // 4. 文字不压文字
                if (m.showSubtitle()) {
                    assertTrue(m.showTitle(), "画服务器行时标题也应当在" + where);
                    assertTrue(m.titleY() + CheckLayout.LINE_H < m.subtitleY(),
                            "标题和服务器行不能贴在一起" + where);
                }
                if (m.showStatus()) {
                    assertTrue(m.showSubtitle(), "画状态行时服务器行也应当在" + where);
                    assertTrue(m.subtitleY() + CheckLayout.LINE_H < m.statusY(),
                            "服务器行和状态行不能贴在一起" + where);
                }

                // 5. 最下面那行文字不能压住按钮
                assertTrue(m.textBottom() <= m.buttonY(), "文字不能压住按钮" + where);

                // 6. 列表若显示，它的顶部不能压住上面的文字
                if (m.listVisible()) {
                    assertTrue(m.listTop() >= m.textBottom(), "列表不能压住上面的文字" + where);
                }
            }
        }
    }

    @Test
    @DisplayName("窗口够高时：标题、服务器行、状态行、列表、按钮全都显示")
    void tallWindowShowsEverything() {
        CheckLayout.Metrics m = CheckLayout.of(1280, 720);

        assertTrue(m.showTitle());
        assertTrue(m.showSubtitle());
        assertTrue(m.showStatus());
        assertTrue(m.listVisible());
        assertEquals(12, m.titleY());
        assertEquals(CheckLayout.LIST_PREFERRED_TOP, m.listTop(), "高窗口下列表从偏好的 56px 开始");
        assertEquals(720 - CheckLayout.MARGIN - CheckLayout.BUTTON_H, m.buttonY());
    }

    @Test
    @DisplayName("窗口很矮时：先砍状态行，再砍服务器行，最后连标题也不画，但按钮永远在")
    void shortWindowShrinksFromTop() {
        CheckLayout.Metrics roomy = CheckLayout.of(854, 300);
        assertTrue(roomy.showStatus());

        CheckLayout.Metrics medium = CheckLayout.of(854, 150);
        assertFalse(medium.showStatus(), "150px 高不该画状态行");
        assertTrue(medium.showSubtitle(), "150px 还能画服务器行");
        assertTrue(medium.buttonY() > 0);

        CheckLayout.Metrics small = CheckLayout.of(854, 90);
        assertFalse(small.showStatus());
        assertFalse(small.showSubtitle(), "90px 高不该画服务器行");
        assertTrue(small.showTitle());
        assertTrue(small.buttonY() > 0);

        CheckLayout.Metrics tiny = CheckLayout.of(854, 50);
        assertFalse(tiny.showTitle(), "50px 高连标题都不画，只留按钮");
        assertEquals(0, tiny.textBottom());
        assertTrue(tiny.buttonY() + CheckLayout.BUTTON_H <= 50);
    }

    @Test
    @DisplayName("按钮行水平居中、不出左右边界")
    void buttonRowStaysInsideScreen() {
        for (int width : WIDTHS) {
            for (int count = 1; count <= 3; count++) {
                int rowWidth = CheckLayout.buttonRowWidth(width, count);
                int startX = CheckLayout.buttonStartX(width, count);
                String where = "（宽度 " + width + "，" + count + " 个按钮）";

                assertTrue(rowWidth <= width, "按钮行总宽不能超过屏幕" + where);
                assertTrue(startX >= 0, "起始 x 不能为负" + where);
                assertTrue(startX + rowWidth <= width, "按钮行不能超出右边" + where);
                // 居中：左右留白之差不超过 1px（整数除法会差 1）
                assertTrue(Math.abs(startX - (width - rowWidth - startX)) <= 1, "按钮行应当水平居中" + where);
            }
        }
    }

    @Test
    @DisplayName("按钮不会在宽屏上被拉成长条（最宽 150）")
    void buttonsAreNotTooWide() {
        assertEquals(CheckLayout.MAX_BUTTON_W, CheckLayout.buttonWidth(3840, 3));
        assertEquals(CheckLayout.MAX_BUTTON_W, CheckLayout.buttonWidth(3840, 1));
        assertTrue(CheckLayout.buttonWidth(320, 3) < CheckLayout.MAX_BUTTON_W);
    }
}
