package com.ftysync.client;

import net.minecraftforge.network.NetworkConstants;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * 服务器 ping 广告：把本机的同步服务地址编码进 Forge DisplayTest 的版本字符串。
 *
 * <p>服务器启动时由 {@code ServerSyncRuntime} 调用 {@link #setSource(String)}；
 * 客户端在 forging list ping 时通过本模组自己的 DisplayTest 读到它（见 docs/protocol.md §4）。</p>
 *
 * <p>{@link #marker()} 的取值：</p>
 * <ul>
 *   <li>{@code v2|http://192.168.1.5:8081}：可用同步源；</li>
 *   <li>{@code IGNORESERVERONLY}（Forge 常量）：有 FTYUpater 但没有对外公布地址。</li>
 * </ul>
 */
public final class SyncAdvert {

    private static volatile String marker = NetworkConstants.IGNORESERVERONLY;

    private SyncAdvert() {
    }

    /** 设置当前广告地址；地址非法或为空时回退成「没有同步源」。 */
    public static void setSource(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            marker = NetworkConstants.IGNORESERVERONLY;
            return;
        }
        try {
            marker = AdvertParser.format(baseUrl);
        } catch (IllegalArgumentException e) {
            marker = NetworkConstants.IGNORESERVERONLY;
        }
    }

    /** 清空广告（服务器停止或关闭广播时调用）。 */
    public static void clear() {
        marker = NetworkConstants.IGNORESERVERONLY;
    }

    /** 当前 DisplayTest 版本字符串。 */
    public static String marker() {
        return marker;
    }

    /**
     * 探测本机第一个非回环、site-local 的 IPv4 地址，拼成 {@code http://ip:port}。
     *
     * @return 探测失败返回 null
     */
    public static String detectLocalUrl(int port) {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address.isLoopbackAddress() || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    if (address.getAddress().length != 4) {
                        continue;
                    }
                    return "http://" + address.getHostAddress() + ":" + port;
                }
            }
        } catch (SocketException ignored) {
            // 探测失败按「没有局域网地址」处理
        }
        return null;
    }
}
