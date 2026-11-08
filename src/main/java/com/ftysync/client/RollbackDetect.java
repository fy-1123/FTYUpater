package com.ftysync.client;

import com.ftysync.core.LocalState;
import com.ftysync.core.PackManifest;

/**
 * 服务器代际与本地代际的先后判断（纯函数，便于单测）。
 *
 * <p>代际 id 形如 {@code 20261021-143332-4784ccb4}（{@code yyyyMMdd-HHmmss-hash}），
 * 所以字典序比较等价于时间先后；但能用清单里的 {@code parentGeneration} 与本地
 * {@code previousGeneration} 佐证时优先用佐证，避免代际 id 换了格式就判错。</p>
 *
 * <p>用途（docs/plan.md M5-5）：服务器管理员把内容回滚后，客户端要提示「服务器退到了更早的版本」，
 * 并让玩家跟随；而不是傻乎乎地当成新版本。</p>
 */
public final class RollbackDetect {

    /** 比较结果。 */
    public enum Kind {
        /** 任一侧代际为空，无法判断。 */
        UNKNOWN,
        /** 两边是同一代。 */
        SAME,
        /** 服务器比本地新（正常更新）。 */
        SERVER_NEWER,
        /** 服务器比本地旧（管理员回退了）。 */
        SERVER_OLDER
    }

    /**
     * 判断结果。
     *
     * @param kind             结论
     * @param localGeneration  本地代际，可为 null
     * @param serverGeneration 服务器代际，可为 null
     */
    public record Result(Kind kind, String localGeneration, String serverGeneration) {

        public Result {
            if (kind == null) {
                throw new IllegalArgumentException("kind 不能为空");
            }
        }

        /** 服务器回退到更早的版本了。 */
        public boolean serverRolledBack() {
            return kind == Kind.SERVER_OLDER;
        }

        /** 服务器有新版本，值得同步。 */
        public boolean serverIsNewer() {
            return kind == Kind.SERVER_NEWER;
        }

        /** 给玩家看的一句话（界面状态行直接用它）。 */
        public String describe() {
            return switch (kind) {
                case SAME -> "本地版本与服务器一致";
                case SERVER_NEWER -> "服务器有新版本";
                case SERVER_OLDER -> "服务器已回退到更早的版本（" + shorten(serverGeneration)
                        + "），同步会把本地也退回那一版";
                case UNKNOWN -> "无法判断版本先后";
            };
        }
    }

    private RollbackDetect() {
    }

    /**
     * 用本地状态与服务器清单比较。
     *
     * @param state    本地状态；null 视为「没有本地版本」
     * @param manifest 服务器清单；null 返回 {@link Kind#UNKNOWN}
     */
    public static Result compare(LocalState state, PackManifest manifest) {
        if (manifest == null) {
            return new Result(Kind.UNKNOWN, state == null ? null : state.generation(), null);
        }
        String server = manifest.generation();
        if (state == null || state.generation() == null || state.generation().isBlank()) {
            // 本地从没同步过：对玩家而言这就是「有新内容要装」
            return new Result(server == null || server.isBlank() ? Kind.UNKNOWN : Kind.SERVER_NEWER,
                    null, server);
        }
        String local = state.generation();
        if (local.equals(server)) {
            return new Result(Kind.SAME, local, server);
        }
        // 清单说「我是从本地这一代长出来的」→ 服务器更新
        if (manifest.parentGeneration().filter(local::equals).isPresent()) {
            return new Result(Kind.SERVER_NEWER, local, server);
        }
        // 本地记得「我刚从服务器这一代退下来」→ 服务器回退
        if (server != null && server.equals(state.previousGeneration())) {
            return new Result(Kind.SERVER_OLDER, local, server);
        }
        // 清单的 parent 是本地也认识、但不是本地当前代际 → 两边隔了不止一代，退回按 id 时间序判断
        return compare(local, server);
    }

    /**
     * 只比代际 id（字典序即时间序）。
     *
     * @param localGeneration  本地代际
     * @param serverGeneration 服务器代际
     */
    public static Result compare(String localGeneration, String serverGeneration) {
        if (isBlank(localGeneration) || isBlank(serverGeneration)) {
            return new Result(Kind.UNKNOWN, localGeneration, serverGeneration);
        }
        int cmp = localGeneration.compareTo(serverGeneration);
        Kind kind = cmp == 0 ? Kind.SAME : (cmp < 0 ? Kind.SERVER_NEWER : Kind.SERVER_OLDER);
        return new Result(kind, localGeneration, serverGeneration);
    }

    /** 代际 id 太长，界面上只留时间部分。 */
    private static String shorten(String generation) {
        if (generation == null || generation.isBlank()) {
            return "未知";
        }
        int dash = generation.lastIndexOf('-');
        return dash > 0 ? generation.substring(0, dash) : generation;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
