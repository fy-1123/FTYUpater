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
import java.util.ArrayList;
import java.util.List;

/**
 * pending/pending.json 读写。损坏时降级为空列表（并把坏文件改名留证）。
 */
public final class PendingQueue {
    private static final Type LIST_TYPE = new TypeToken<List<PendingOp>>() { }.getType();

    private final Path file;

    public PendingQueue(Path file) {
        this.file = file;
    }

    public List<PendingOp> load() {
        if (file == null || !Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<PendingOp> operations = Json.GSON.fromJson(json, LIST_TYPE);
            return operations == null ? new ArrayList<>() : new ArrayList<>(operations);
        } catch (IOException | RuntimeException e) {
            backupCorrupt();
            return new ArrayList<>();
        }
    }

    public void save(List<PendingOp> operations) {
        List<PendingOp> ops = operations == null ? List.of() : operations;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(new ArrayList<>(ops), LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void append(List<PendingOp> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        List<PendingOp> all = load();
        all.addAll(operations);
        save(all);
    }

    public Path file() {
        return file;
    }

    private void backupCorrupt() {
        try {
            if (file != null && Files.isRegularFile(file)) {
                Files.move(file,
                        file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
