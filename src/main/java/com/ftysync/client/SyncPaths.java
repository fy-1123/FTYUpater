package com.ftysync.client;

import java.nio.file.Path;

/**
 * 客户端状态目录布局（见 docs/architecture.md 3.2）。所有路径都相对游戏目录。
 */
public final class SyncPaths {
    private final Path gameDir;

    public SyncPaths(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Path root() {
        return gameDir.resolve("ftyupater");
    }

    public Path stateFile() {
        return root().resolve("state.json");
    }

    public Path serversFile() {
        return root().resolve("servers.json");
    }

    public Path pendingFile() {
        return root().resolve("pending").resolve("pending.json");
    }

    public Path stagingDir(String generation) {
        return root().resolve("staging").resolve(generation);
    }

    public Path backupDir(String generation) {
        return root().resolve("backup").resolve(generation);
    }

    public Path trashDir(String timestamp) {
        return root().resolve("trash").resolve(timestamp);
    }

    public Path logFile() {
        return root().resolve("logs").resolve("sync.log");
    }
}
