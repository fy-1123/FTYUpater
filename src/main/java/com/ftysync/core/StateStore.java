package com.ftysync.core;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code state.json} 的读写：原子写、损坏自动备份并降级为空状态。
 *
 * <p>状态文件损坏不应导致游戏崩溃：备份为 {@code state.json.corrupt-<时间>} 后，
 * 当作「从未同步过」处理，下次同步会重建。损坏备份留着方便排查，也避免静默丢数据。</p>
 */
public final class StateStore {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path stateFile;

    public StateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * 读取状态。文件不存在返回空状态；内容损坏时备份后返回空状态，绝不抛异常。
     */
    public LocalState load() {
        if (!Files.exists(stateFile)) {
            return new LocalState();
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            LocalState state = Json.GSON.fromJson(json, LocalState.class);
            if (state == null) {
                throw new ManifestException("状态文件内容为空");
            }
            if (state.schema() != LocalState.SCHEMA) {
                throw new ManifestException("状态文件 schema 不匹配: " + state.schema());
            }
            state.setOptOut(state.optOut());
            state.setIgnored(state.ignored());
            state.setEntries(state.entries());
            for (String path : state.optOut()) {
                PathGuard.normalize(path);
            }
            for (String path : state.ignored()) {
                PathGuard.normalize(path);
            }
            for (String path : state.entries().keySet()) {
                PathGuard.normalize(path);
            }
            return state;
        } catch (RuntimeException | IOException e) {
            // Gson 把 record 构造器异常包成 RuntimeException；任何损坏都降级为空状态
            backupCorrupt();
            return new LocalState();
        }
    }

    /** 原子写入（临时文件 + 移动），IO 失败抛 {@link UncheckedIOException} 由调用方记录日志。 */
    public void save(LocalState state) {
        LocalState value = state == null ? new LocalState() : state;
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存同步状态失败: " + stateFile, e);
        }
    }

    private void backupCorrupt() {
        try {
            if (!Files.exists(stateFile)) {
                return;
            }
            Path parent = stateFile.toAbsolutePath().getParent();
            String backupName = stateFile.getFileName() + ".corrupt-"
                    + BACKUP_TIME.format(LocalDateTime.now());
            Path backup = parent == null ? Path.of(backupName) : parent.resolve(backupName);
            Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 备份失败也不能让游戏崩溃：静默返回空状态即可
        }
    }
}
