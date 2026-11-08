package com.ftysync.client;

import com.ftysync.helper.HelperResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingNotice} 的纯逻辑测试（M5-2）。
 *
 * <p>这些测试不需要 Minecraft，也不需要真跑 helper：只验「什么情况说什么话」。</p>
 */
class PendingNoticeTest {

    // ------------------------------------------------------------ 无事可说

    @Test
    void nothingToSayWhenClean() {
        assertTrue(PendingNotice.compose(0, null).isEmpty(), "没有待办、没跑过 helper → 不打扰");
    }

    @Test
    void nothingToSayWhenHelperRanButDidNothing() {
        HelperResult empty = HelperResult.empty("2026-11-08T10:00:00Z");
        assertTrue(PendingNotice.compose(0, empty).isEmpty(), "helper 跑过但没事做 → 不打扰");
    }

    // ------------------------------------------------------------ 只有待办

    @Test
    void warnsAboutPendingAndAsksForHelper() {
        Optional<PendingNotice.Notice> notice = PendingNotice.compose(3, null);
        assertTrue(notice.isPresent());
        PendingNotice.Notice n = notice.get();
        assertTrue(n.title().contains("3"), "标题要说清几项：" + n.title());
        assertEquals(PendingNotice.Kind.WARNING, n.kind());
        assertTrue(n.launchHelper(), "有待办就该拉起 helper");
    }

    @Test
    void negativePendingIsTreatedAsZero() {
        assertTrue(PendingNotice.compose(-5, null).isEmpty());
    }

    // ------------------------------------------------------------ helper 成功

    @Test
    void reportsSuccess() {
        HelperResult result = new HelperResult("2026-11-08T10:00:00Z",
                List.of("mods/a.jar", "mods/b.jar"), List.of("mods/old.jar"),
                List.of(), List.of(), 0);
        Optional<PendingNotice.Notice> notice = PendingNotice.compose(0, result);
        assertTrue(notice.isPresent());
        PendingNotice.Notice n = notice.get();
        assertEquals(PendingNotice.Kind.INFO, n.kind(), "全成功 → 只是告知，不用警告");
        assertTrue(n.body().contains("2"), "替换数量：" + n.body());
        assertTrue(n.body().contains("1"), "删除数量：" + n.body());
        assertFalse(n.launchHelper(), "没有待办就不必再拉起 helper");
    }

    @Test
    void successPlusPendingStaysWarning() {
        HelperResult result = new HelperResult("2026-11-08T10:00:00Z",
                List.of("mods/a.jar"), List.of(),
                List.of(), List.of(), 2);
        PendingNotice.Notice n = PendingNotice.compose(2, result).orElseThrow();
        assertEquals(PendingNotice.Kind.WARNING, n.kind(), "还有待办 → 仍需警告");
        assertTrue(n.launchHelper());
        assertTrue(n.body().contains("2"), "要提到剩余项：" + n.body());
    }

    // ------------------------------------------------------------ helper 失败

    @Test
    void failureBeatsEverything() {
        HelperResult result = new HelperResult("2026-11-08T10:00:00Z",
                List.of("mods/a.jar"), List.of(),
                List.of("mods/locked.jar"), List.of("mods/locked.jar: 文件被占用"), 1);
        PendingNotice.Notice n = PendingNotice.compose(1, result).orElseThrow();
        assertEquals(PendingNotice.Kind.WARNING, n.kind());
        assertTrue(n.title().contains("没全部成功"), "标题：" + n.title());
        assertTrue(n.body().contains("1"), "失败数量：" + n.body());
        assertTrue(n.body().contains("文件被占用"), "要带第一条原因：" + n.body());
        assertTrue(n.launchHelper(), "失败且有待办 → 重试");
    }

    @Test
    void failureWithoutErrorsStillComposes() {
        HelperResult result = new HelperResult("2026-11-08T10:00:00Z",
                List.of(), List.of(), List.of("mods/x.jar"), List.of(), 0);
        PendingNotice.Notice n = PendingNotice.compose(0, result).orElseThrow();
        assertEquals(PendingNotice.Kind.WARNING, n.kind());
        assertFalse(n.body().contains("· null"), "没有错误原因时不该出现 null：" + n.body());
    }

    @Test
    void longErrorIsTruncated() {
        String longPath = "mods/very/deep/nested/directory/structure/with/a/really/long/name.jar: 哈希不符";
        HelperResult result = new HelperResult("2026-11-08T10:00:00Z",
                List.of(), List.of(), List.of("x"), List.of(longPath), 0);
        PendingNotice.Notice n = PendingNotice.compose(0, result).orElseThrow();
        assertTrue(n.body().length() < 200, "正文不该无限长：" + n.body().length());
        assertTrue(n.body().endsWith("…"), "截断要有省略号：" + n.body());
    }

    @Test
    void noticeFieldsAreNeverNull() {
        // 所有列表都是 null：record 的紧凑构造器会把它们换成空表，
        // 所以「有失败」必须靠 failed 里真有内容来表达。
        HelperResult result = new HelperResult(null, null, null, List.of("mods/x.jar"), null, 0);
        PendingNotice.Notice n = PendingNotice.compose(0, result).orElseThrow();
        assertTrue(n.title() != null && n.body() != null);
        assertTrue(n.body().contains("1"), "失败数量：" + n.body());
    }

    @Test
    void allNullResultIsQuiet() {
        // 全 null 且没有失败 → 无事可说（这是正确行为，不是漏提示）
        HelperResult result = new HelperResult(null, null, null, null, null, 0);
        assertTrue(PendingNotice.compose(0, result).isEmpty());
    }
}
