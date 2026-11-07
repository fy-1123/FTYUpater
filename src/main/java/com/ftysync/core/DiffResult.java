package com.ftysync.core;

import java.util.List;

/**
 * 一次比对的完整结果。
 */
public final class DiffResult {

    private final List<SyncAction> actions;

    public DiffResult(List<SyncAction> actions) {
        this.actions = List.copyOf(actions);
    }

    public List<SyncAction> actions() {
        return actions;
    }

    public long count(SyncAction.Action action) {
        return actions.stream().filter(a -> a.action() == action).count();
    }

    public List<SyncAction> of(SyncAction.Action action) {
        return actions.stream().filter(a -> a.action() == action).toList();
    }

    /** 本地与清单完全一致（只剩 KEEP / OPT_OUT / IGNORED）。 */
    public boolean isInSync() {
        return actions.stream().noneMatch(a -> a.action() != SyncAction.Action.KEEP
                && a.action() != SyncAction.Action.OPT_OUT
                && a.action() != SyncAction.Action.IGNORED);
    }

    /** 是否有需要执行的动作（包括仅 optional 的安装/更新）。 */
    public boolean requiresSync() {
        return !isInSync();
    }

    /**
     * 是否应当拦截进服（必须先生成/应用同步）。
     *
     * <p>required 的缺失/更新/禁用、清单外文件的清理都会拦截；optional 的
     * 缺失/更新/禁用只提示、不拦截（玩家可以选择不要）。</p>
     */
    public boolean blocksJoin() {
        for (SyncAction action : actions) {
            switch (action.action()) {
                case INSTALL_REQUIRED, DELETE -> {
                    return true;
                }
                case UPDATE, MANUAL_DISABLED, PENDING -> {
                    if (action.role() == PackEntry.Role.REQUIRED) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }
}
