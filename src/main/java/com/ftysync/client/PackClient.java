package com.ftysync.client;

import com.ftysync.core.PackManifest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 拉取服务器清单：{@code GET <baseUrl>/api/v1/pack}（见 docs/protocol.md §3）。
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
     * @param baseUrl 同步源根地址（http/https，可带尾随 '/'）
     * @return 解析成功的清单；网络失败、状态码非 200、JSON 非法时为空
     */
    public static Optional<PackManifest> fetchManifest(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalized + "/api/v1/pack"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(PackManifest.parse(response.body()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
