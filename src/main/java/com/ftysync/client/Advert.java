package com.ftysync.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 服务器在自己的 Forge DisplayTest 版本字符串里携带的广告：{@code v2|<baseUrl>}。
 *
 * @param version 协议版本（当前 2）
 * @param baseUrl 同步源根地址，如 {@code http://127.0.0.1:8081}（无尾随 '/'）
 */
public record Advert(int version, String baseUrl) {

    public Advert {
        if (version <= 0) {
            throw new IllegalArgumentException("广告版本必须为正: " + version);
        }
        Objects.requireNonNull(baseUrl, "baseUrl");
        String value = baseUrl.trim();
        if (!value.equals(baseUrl)) {
            throw new IllegalArgumentException("广告地址首尾不能有空白: " + baseUrl);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.indexOf('|') >= 0 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("广告地址不能含 '|' 或空白: " + baseUrl);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("广告地址不是合法 URL: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("广告地址必须是 http(s) URL: " + baseUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("广告地址缺少主机名: " + baseUrl);
        }
        baseUrl = value;
    }

    /** 序列化为 DisplayTest 版本字符串（{@code v2|http://...}）。 */
    public String marker() {
        return "v" + version + "|" + baseUrl;
    }
}
