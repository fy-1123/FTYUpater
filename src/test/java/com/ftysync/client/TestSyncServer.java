package com.ftysync.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用本地 HTTP 服务，模拟服务端 `GET /api/v1/files/&lt;sha256&gt;`。
 * 可配置「前 N 次返回错误内容」用来测重试。
 */
final class TestSyncServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, Integer> failRemaining = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    private TestSyncServer(HttpServer server) {
        this.server = server;
    }

    static TestSyncServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        TestSyncServer s = new TestSyncServer(server);
        server.createContext("/api/v1/files", s::handle);
        server.start();
        return s;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    TestSyncServer put(String sha256, byte[] data) {
        files.put(sha256, data);
        return this;
    }

    TestSyncServer failTimes(String sha256, int times) {
        failRemaining.put(sha256, times);
        return this;
    }

    int requests() {
        return requests.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String sha = path.substring(path.lastIndexOf('/') + 1);
        byte[] data = files.get(sha);
        byte[] body;
        int status;
        int remaining = failRemaining.getOrDefault(sha, 0);
        if (remaining > 0) {
            failRemaining.put(sha, remaining - 1);
            body = "garbage-body".getBytes(StandardCharsets.UTF_8);
            status = 200;
        } else if (data == null) {
            body = "{\"ok\":false,\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);
            status = 404;
        } else {
            body = data;
            status = 200;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
