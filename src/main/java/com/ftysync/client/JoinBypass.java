package com.ftysync.client;

/**
 * 记录「下一次 ConnectScreen.startConnecting 由我们自己放行、不要再被拦截」。
 *
 * <p>状态放在普通类里而不是 mixin 里：Mixin 要求混入类中的非注入器方法必须是 private，
 * 所以 mixin 只调用 {@link #consume()}，放行由检查界面调用 {@link #next()}。</p>
 */
public final class JoinBypass {

    private static final ThreadLocal<Boolean> NEXT = new ThreadLocal<>();

    private JoinBypass() {
    }

    /** 标记：下一次 startConnecting 直接放行。 */
    public static void next() {
        NEXT.set(Boolean.TRUE);
    }

    /** 读取并清除「下一次放行」标记。 */
    public static boolean consume() {
        if (Boolean.TRUE.equals(NEXT.get())) {
            NEXT.remove();
            return true;
        }
        return false;
    }
}
