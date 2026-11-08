package com.ftysync.client;

import com.ftysync.core.PackManifest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 拉取服务器清单：{@code GET <baseUrl>/api/v1/pack}（见 docs/protocol.md §3）。
 *
 * <p>M5 起支持指定代际：{@code GET /api/v1/pack?generation=<id>}，
 * 用于回退到上一代（服务器会一直保留最近 N 代，见 {@code retainGenerations} 配置）。</p>
 */
public final class PackClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PackClient() {
    }

    /**
     * 拉取当前代际的清单。
     *
     * @param baseUrl 同步源根地址（http/https，可带尾随 '/'）
     * @return 解析成功的清单；网络失败、状态码非 200、JSON 非法时为空
     */
    public static Optional<PackManifest> fetchManifest(String baseUrl) {
        return fetchManifest(baseUrl, null);
    }

    /**
     * 拉取指定代际的清单。
     *
     * @param baseUrl    同步源根地址
     * @param generation 目标代际；null / 空串表示「当前代际」
     * @return 解析成功的清单；代际不存在或已过保留期时为空
     */
    public static Optional<PackManifest> fetchManifest(String baseUrl, String generation) {
        String url = packUrl(baseUrl, generation);
        if (url == null) {
            return Optional.empty();
        }
        return get(url).flatMap(body -> {
            try {
                return Optional.of(PackManifest.parse(body));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * 拉取服务器保留的代际列表（{@code GET /api/v1/generations}）。
     *
     * <p>顺序与服务器一致：<b>最新在前</b>。</p>
     *
     * @param baseUrl 同步源根地址
     * @return 代际列表；网络失败或解析失败时为空
     */
    public static Optional<java.util.List<ServerGeneration>> fetchGenerations(String baseUrl) {
        String root = normalize(baseUrl);
        if (root == null) {
            return Optional.empty();
        }
        return get(root + "/api/v1/generations").flatMap(body -> {
            try {
                return Optional.of(ServerGeneration.parseList(body));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** 拼 {@code /api/v1/pack[?generation=]} 的完整地址；参数非法返回 null。 */
    private static String packUrl(String baseUrl, String generation) {
        String root = normalize(baseUrl);
        if (root == null) {
            return null;
        }
        if (generation == null || generation.isBlank()) {
            return root + "/api/v1/pack";
        }
        return root + "/api/v1/pack?generation="
                + URLEncoder.encode(generation.trim(), StandardCharsets.UTF_8);
    }

    /** 去掉尾随 '/'；空/空白返回 null。 */
    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /** 发一个 GET，200 才返回正文。 */
    private static Optional<String> get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
