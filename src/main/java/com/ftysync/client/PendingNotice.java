package com.ftysync.client;

import com.ftysync.helper.HelperResult;

import java.util.List;
import java.util.Optional;

/**
 * 客户端启动时要给玩家的提示（M5-2）。
 *
 * <p>这个类只做「算什么话」的纯逻辑，不碰 Minecraft 的任何类，因此可以单测。
 * 真正弹提示的动作在 {@link ClientStartup} 里。</p>
 *
 * <p>信息来源有两处：</p>
 * <ol>
 *   <li>{@code ftyupater/pending/pending.json} —— 还有多少项等着重启后应用；</li>
 *   <li>{@code ftyupater/pending/helper-result.jsonl} —— 上次退出时 helper 干了什么。</li>
 * </ol>
 *
 * <p>优先级：helper 失败 &gt; helper 成功干过活 &gt; 还有待办 &gt; 什么都不说。</p>
 */
public final class PendingNotice {

    /** 提示的语气。 */
    public enum Kind {
        /** 正常告知（绿/灰）。 */
        INFO,
        /** 需要玩家注意（有失败、有待办）。 */
        WARNING
    }

    /**
     * 一条提示。
     *
     * @param title        标题（一行，尽量短）
     * @param body         正文（一行，说清数量）
     * @param kind         语气
     * @param launchHelper 是否该顺手把 ApplyHelper 拉起来
     */
    public record Notice(String title, String body, Kind kind, boolean launchHelper) {
    }

    private PendingNotice() {
    }

    /**
     * 根据 pending 数量与上次 helper 结果算出该不该提示、提示什么。
     *
     * @param pendingCount {@code pending.json} 里的操作数（&ge;0，负数按 0 处理）
     * @param result       上次 helper 的结果；没跑过为 {@code null}
     * @return 要显示的提示；无事可说时为空
     */
    public static Optional<Notice> compose(int pendingCount, HelperResult result) {
        int pending = Math.max(0, pendingCount);
        // 只要还有待办就拉起 helper：它自己会等本进程退出再动手，重复拉起是安全的，
        // 而且这正是「失败后下次启动可重试」的实现方式。
        boolean launch = pending > 0;

        if (result != null && result.hasFailures()) {
            return Optional.of(new Notice(
                    "上次更新没全部成功",
                    describeFailure(result, pending),
                    Kind.WARNING,
                    launch));
        }
        if (result != null && result.didAnything()) {
            return Optional.of(new Notice(
                    "重启后已更新",
                    describeSuccess(result, pending),
                    pending > 0 ? Kind.WARNING : Kind.INFO,
                    launch));
        }
        if (pending > 0) {
            return Optional.of(new Notice(
                    "还有 " + pending + " 项要重启后才生效",
                    "退出游戏后会自动处理，下次启动就到位了",
                    Kind.WARNING,
                    launch));
        }
        // helper 跑过但什么都没做、也没有待办 —— 没什么可打扰玩家的。
        return Optional.empty();
    }

    /**
     * 失败提示的正文：数量 + 第一条原因。
     *
     * <p>只放第一条原因：Toast 只有一行，塞多了反而看不清；完整清单在日志里。</p>
     */
    private static String describeFailure(HelperResult result, int pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("失败 ").append(result.failed().size()).append(" 项");
        int done = result.applied().size() + result.deleted().size();
        if (done > 0) {
            sb.append(" · 已生效 ").append(done).append(" 项");
        }
        if (pending > 0) {
            sb.append(" · 还有 ").append(pending).append(" 项待重试");
        }
        String first = firstError(result);
        if (!first.isEmpty()) {
            sb.append(" · ").append(first);
        }
        return sb.toString();
    }

    /** 成功提示的正文：替换 / 删除数量 + 剩余待办。 */
    private static String describeSuccess(HelperResult result, int pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("替换 ").append(result.applied().size()).append(" 项");
        if (!result.deleted().isEmpty()) {
            sb.append(" · 删除 ").append(result.deleted().size()).append(" 项");
        }
        if (pending > 0) {
            sb.append(" · 还有 ").append(pending).append(" 项待重试");
        }
        return sb.toString();
    }

    /** 取第一条错误原因；没有就返回空串。 */
    private static String firstError(HelperResult result) {
        List<String> errors = result.errors();
        if (errors.isEmpty()) {
            return "";
        }
        String first = errors.get(0);
        if (first == null) {
            return "";
        }
        first = first.trim();
        // Toast 一行放不下长路径，截断到 60 个字符（够看出是哪个文件了）。
        int limit = 60;
        if (first.length() > limit) {
            return first.substring(0, limit) + "…";
        }
        return first;
    }
}
