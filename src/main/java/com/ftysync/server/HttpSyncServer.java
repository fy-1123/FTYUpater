package com.ftysync.server;

import com.ftysync.core.Hash;
import com.ftysync.core.Json;
import com.ftysync.core.PackManifest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v2 同步 HTTP 服务（JDK 内置 HttpServer，无第三方依赖）。
 *
 * <p>路由见 docs/protocol.md 第 2 节：</p>
 * <pre>
 * GET /api/v1/health
 * GET /api/v1/pack[?generation=&lt;id&gt;]
 * GET /api/v1/generations
 * GET /api/v1/files/&lt;sha256&gt;
 * </pre>
 */
public final class HttpSyncServer {

    public static final String MOD_ID = "ftyupater";
    public static final int SCHEMA = 2;

    private static final String API_PREFIX = "/api/v1";
    private static final String FILES_PREFIX = API_PREFIX + "/files/";

    private final int port;
    private final String bindAddress;
    private final SyncPackRepository repository;
    private final String modVersion;

    private HttpServer server;
    private ExecutorService executor;
    private volatile int boundPort = -1;

    public HttpSyncServer(int port, String bindAddress, SyncPackRepository repository, String modVersion) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.repository = repository;
        this.modVersion = modVersion;
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("HTTP 服务已启动");
        }
        InetSocketAddress address = bindAddress == null || bindAddress.isBlank()
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
        server = HttpServer.create(address, 0);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "ftyupater-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(API_PREFIX, this::handle);
        server.start();
        boundPort = server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        boundPort = -1;
    }

    /** 实际监听端口（构造时传 0 表示随机端口，测试用）。 */
    public int port() {
        if (boundPort <= 0) {
            throw new IllegalStateException("HTTP 服务未启动");
        }
        return boundPort;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "method_not_allowed", "只支持 GET");
                return;
            }
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (!path.startsWith(API_PREFIX)) {
                sendError(exchange, 404, "not_found", "未知路径: " + path);
            } else if (path.equals(API_PREFIX + "/health")) {
                handleHealth(exchange);
            } else if (path.equals(API_PREFIX + "/pack")) {
                handlePack(exchange, uri);
            } else if (path.equals(API_PREFIX + "/generations")) {
                handleGenerations(exchange);
            } else if (path.startsWith(FILES_PREFIX)) {
                handleFile(exchange, path.substring(FILES_PREFIX.length()));
            } else {
                sendError(exchange, 404, "not_found", "未知路径: " + path);
            }
        } catch (RuntimeException e) {
            sendError(exchange, 500, "internal", "服务器内部错误: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("mod", MOD_ID);
        json.addProperty("schema", SCHEMA);
        json.addProperty("modVersion", modVersion);
        Optional<PackManifest> manifest = repository.currentManifest();
        json.addProperty("generation", manifest.map(PackManifest::generation).orElse(null));
        sendJson(exchange, 200, json);
    }

    private void handlePack(HttpExchange exchange, URI uri) throws IOException {
        String generation = queryParam(uri, "generation");
        Optional<PackManifest> manifest = generation == null || generation.isBlank()
                ? repository.currentManifest()
                : repository.manifest(generation);
        if (manifest.isEmpty()) {
            if (generation == null || generation.isBlank()) {
                sendError(exchange, 404, "not_found", "服务器尚未发布任何代际");
            } else {
                sendError(exchange, 404, "not_found", "代际不存在或已过保留期: " + generation);
            }
            return;
        }
        sendBody(exchange, 200, "application/json; charset=UTF-8",
                manifest.get().toJson().getBytes(StandardCharsets.UTF_8));
    }

    private void handleGenerations(HttpExchange exchange) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        JsonArray array = new JsonArray();
        for (GenerationJournal.Entry entry : repository.retainedGenerations()) {
            JsonObject item = new JsonObject();
            item.addProperty("generation", entry.generation());
            item.addProperty("createdAt", entry.createdAt());
            item.addProperty("files", entry.files());
            array.add(item);
        }
        json.add("generations", array);
        sendJson(exchange, 200, json);
    }

    private void handleFile(HttpExchange exchange, String rawSha) throws IOException {
        String sha = URLDecoder.decode(rawSha, StandardCharsets.UTF_8);
        if (!Hash.isSha256(sha)) {
            sendError(exchange, 400, "bad_request", "非法的文件哈希: " + sha);
            return;
        }
        Optional<Path> object = repository.objectPath(sha);
        if (object.isEmpty() || !Files.isRegularFile(object.get())) {
            sendError(exchange, 404, "not_found", "文件不存在: " + sha);
            return;
        }
        Path file = object.get();
        long size = Files.size(file);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("ETag", "\"" + sha + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
        if (size > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        sendBody(exchange, status, "application/json; charset=UTF-8",
                Json.COMPACT.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange exchange, int status, String error, String message) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", error);
        json.addProperty("message", message);
        sendJson(exchange, status, json);
    }

    private static void sendBody(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String value = eq < 0 ? "" : part.substring(eq + 1);
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
