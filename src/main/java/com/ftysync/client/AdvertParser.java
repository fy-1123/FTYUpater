package com.ftysync.client;

import java.util.Optional;

/**
 * 解析/生成服务器广告字符串（{@code v2|<baseUrl>}），纯函数、无 Minecraft 依赖。
 *
 * <p>服务器把广告放在自己模组的 Forge DisplayTest 版本字符串里（见 docs/protocol.md §4）。
 * 本类只认「当前协议版本」的广告；其它值或垃圾值一律视为「没有可用同步源」。</p>
 */
public final class AdvertParser {

    /** 当前协议版本。 */
    public static final int VERSION = 2;

    /** 广告前缀。 */
    public static final String PREFIX = "v" + VERSION + "|";

    private AdvertParser() {
    }

    /**
     * 解析服务器版本字符串。
     *
     * @return 合法广告；不是广告、版本不符或地址非法时返回 {@link Optional#empty()}
     */
    public static Optional<Advert> parse(String marker) {
        if (marker == null || marker.isBlank() || !marker.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String baseUrl = marker.substring(PREFIX.length());
        if (baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Advert(VERSION, baseUrl));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 生成广告字符串。
     *
     * @throws IllegalArgumentException 地址非法
     */
    public static String format(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("同步地址不能为空");
        }
        return new Advert(VERSION, baseUrl).marker();
    }
}
