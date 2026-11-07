package com.ftysync.client;

import com.ftysync.core.Json;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 「服务器地址 → 同步源」记忆（servers.json）。文件损坏时降级为空，不影响游戏。
 */
public final class ServerSourceCache {
    private static final int SCHEMA = 2;
    private static final Type DTO_TYPE = new TypeToken<Dto>() { }.getType();

    private final Path file;

    public ServerSourceCache(Path file) {
        this.file = file;
    }

    public Optional<String> get(String serverKey) {
        if (serverKey == null || serverKey.isBlank()) {
            return Optional.empty();
        }
        Entry entry = servers().get(serverKey);
        if (entry == null || entry.source() == null || entry.source().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(entry.source());
    }

    public void put(String serverKey, String source) {
        if (serverKey == null || serverKey.isBlank() || source == null || source.isBlank()) {
            return;
        }
        Map<String, Entry> servers = new LinkedHashMap<>(servers());
        servers.put(serverKey, new Entry(source, Instant.now().toString()));
        save(new Dto(SCHEMA, servers));
    }

    public Path file() {
        return file;
    }

    private Map<String, Entry> servers() {
        if (file == null || !Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Dto dto = Json.GSON.fromJson(json, DTO_TYPE);
            if (dto == null || dto.schema() != SCHEMA || dto.servers() == null) {
                return new LinkedHashMap<>();
            }
            Map<String, Entry> result = new LinkedHashMap<>();
            dto.servers().forEach((key, value) -> {
                if (key != null && value != null && value.source() != null) {
                    result.put(key, value);
                }
            });
            return result;
        } catch (IOException | RuntimeException e) {
            backupCorrupt();
            return new LinkedHashMap<>();
        }
    }

    private void save(Dto dto) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(dto), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void backupCorrupt() {
        try {
            if (file != null && Files.isRegularFile(file)) {
                Files.move(file,
                        file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 损坏文件备份失败不影响主流程
        }
    }

    private record Dto(int schema, Map<String, Entry> servers) {
    }

    private record Entry(String source, String updatedAt) {
    }
}
