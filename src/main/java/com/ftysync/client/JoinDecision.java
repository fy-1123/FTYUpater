package com.ftysync.client;

import com.ftysync.core.DiffResult;

/**
 * 进服前决策（纯函数，便于单测）：探测结果 + 差异 → 玩家看到什么。
 *
 * <p>规则（docs/plan.md M4 / protocol.md §4）：</p>
 * <ul>
 *   <li>服务器没有 FTYUpater / 玩家关了检查 → 直接继续；</li>
 *   <li>服务器有模组但没配同步地址、或清单拉取失败 → 提示后允许继续（不能坑玩家进不去）；</li>
 *   <li>完全一致 → 自动继续（可配置成也弹界面）；</li>
 *   <li>有差异 → 弹检查界面；只有 required 缺失/更新或需要清理时才「必须同步」。</li>
 * </ul>
 */
public final class JoinDecision {

    /** 决策动作。 */
    public enum Action {
        /** 不打扰，直接连接。 */
        AUTO_CONTINUE,
        /** 弹检查界面。 */
        SHOW_SCREEN,
        /** 弹提示但允许继续（无同步源/清单拉取失败）。 */
        CONTINUE_WITH_NOTICE
    }

    /**
     * 决策结果。
     *
     * @param action    动作
     * @param blocking  是否「必须同步才能进服」（SHOW_SCREEN 时用）
     * @param reasonKey 语言文件 key 后缀，如 {@code server_no_mod}
     */
    public record Decision(Action action, boolean blocking, String reasonKey) {
    }

    /** 探测结果类型。 */
    public enum ProbeKind {
        /** 服务器没有 FTYUpater 模组。 */
        NO_FTYUPATER,
        /** 服务器有模组，但没有同步地址（广告为空/IGNORESERVERONLY/无法解析）。 */
        HAS_NO_SOURCE,
        /** 拿到可用广告。 */
        HAS_SOURCE
    }

    /** 探测结果。 */
    public record ProbeOutcome(ProbeKind kind, Advert advert) {
        public ProbeOutcome {
            if (kind == null) {
                throw new IllegalArgumentException("kind 不能为空");
            }
            if (kind == ProbeKind.HAS_SOURCE && advert == null) {
                throw new IllegalArgumentException("HAS_SOURCE 必须带广告");
            }
        }

        /**
         * 从 Forge DisplayTest 的远程版本字符串解析探测结果。
         *
         * @param remoteMarker 服务器广告字符串；服务器没有我们的模组时为 null
         */
        public static ProbeOutcome fromMarker(String remoteMarker) {
            if (remoteMarker == null || remoteMarker.isBlank()) {
                return new ProbeOutcome(ProbeKind.NO_FTYUPATER, null);
            }
            return AdvertParser.parse(remoteMarker)
                    .map(advert -> new ProbeOutcome(ProbeKind.HAS_SOURCE, advert))
                    .orElseGet(() -> new ProbeOutcome(ProbeKind.HAS_NO_SOURCE, null));
        }
    }

    private JoinDecision() {
    }

    /**
     * 决策。
     *
     * @param checkEnabled         客户端是否启用进服前检查
     * @param probe                探测结果
     * @param diff                 差异；清单拉取/比对失败时为 null
     * @param autoContinueIfInSync 完全一致时是否自动继续（否则也弹界面）
     */
    public static Decision decide(boolean checkEnabled,
                                  ProbeOutcome probe,
                                  DiffResult diff,
                                  boolean autoContinueIfInSync) {
        if (!checkEnabled) {
            return new Decision(Action.AUTO_CONTINUE, false, "check_disabled");
        }
        switch (probe.kind()) {
            case NO_FTYUPATER -> {
                return new Decision(Action.AUTO_CONTINUE, false, "server_no_mod");
            }
            case HAS_NO_SOURCE -> {
                return new Decision(Action.CONTINUE_WITH_NOTICE, false, "no_source");
            }
            case HAS_SOURCE -> {
                // 继续往下判断差异
            }
        }
        if (diff == null) {
            return new Decision(Action.CONTINUE_WITH_NOTICE, false, "fetch_failed");
        }
        if (diff.isInSync()) {
            return autoContinueIfInSync
                    ? new Decision(Action.AUTO_CONTINUE, false, "in_sync")
                    : new Decision(Action.SHOW_SCREEN, false, "in_sync");
        }
        return new Decision(Action.SHOW_SCREEN, diff.blocksJoin(), "diff");
    }
}
