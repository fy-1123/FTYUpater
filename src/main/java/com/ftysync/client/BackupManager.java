package com.ftysync.client;

import com.ftysync.core.PathGuard;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 备份（backup/&lt;generation&gt;）与回收站（trash/&lt;timestamp&gt;）。
 * 备份是复制（原文件继续被替换），回收站是移动（原文件消失）。
 */
public final class BackupManager {
    private final Path gameDir;
    private final SyncPaths paths;

    public BackupManager(Path gameDir) {
        this.gameDir = gameDir;
        this.paths = new SyncPaths(gameDir);
    }

    /**
     * 把目标文件复制一份到 backup/&lt;generation&gt;/&lt;relativePath&gt;（目标本身不动）。
     *
     * @return 备份文件路径；目标不存在时返回 null
     */
    public Path backup(String generation, String relativePath) throws IOException {
        Path source = PathGuard.resolve(gameDir, relativePath);
        if (!Files.isRegularFile(source)) {
            return null;
        }
        Path dest = PathGuard.resolve(paths.backupDir(generation), relativePath);
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }

    /**
     * 把目标文件移动到 trash/&lt;timestamp&gt;/&lt;relativePath&gt;。
     *
     * @return 回收站文件路径；目标不存在时返回 null
     */
    public Path trash(String timestamp, String relativePath) throws IOException {
        Path source = PathGuard.resolve(gameDir, relativePath);
        if (!Files.exists(source)) {
            return null;
        }
        Path dest = PathGuard.resolve(paths.trashDir(timestamp), relativePath);
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        try {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source);
        }
        return dest;
    }

    public SyncPaths paths() {
        return paths;
    }

    public Path gameDir() {
        return gameDir;
    }
}
