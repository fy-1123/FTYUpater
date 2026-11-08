package com.ftysync.client;

import com.ftysync.core.PathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 回收站（{@code ftyupater/trash/<timestamp>/}）的读取与还原（M6-1）。
 *
 * <p>同步清理掉的「多余文件」不是直接删除，而是移动进回收站；这里负责把它们列出来并还原回去。
 * 每个时间戳目录代表一次清理动作，目录里的相对路径与游戏目录一一对应。</p>
 *
 * <p>还原是**非破坏性**的：目标已存在时不覆盖，直接记为跳过（玩家自己又放了同名文件时不能悄悄冲掉）。</p>
 */
public final class TrashBrowser {

    /** 回收站里的一次清理。 */
    public record Entry(String timestamp, int files, long bytes) {
        public Entry {
            if (timestamp == null || timestamp.isBlank()) {
                throw new IllegalArgumentException("timestamp 不能为空");
            }
            files = Math.max(0, files);
            bytes = Math.max(0, bytes);
        }

        /** 给玩家看的一句话。 */
        public String describe() {
            return timestamp + " · " + files + " 个文件 · " + humanSize(bytes);
        }
    }

    /** 一次还原的结果。 */
    public record RestoreResult(int restored, int skipped, List<String> errors) {
        public RestoreResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("还原 ").append(restored).append(" 个文件");
            if (skipped > 0) {
                sb.append(" · 跳过 ").append(skipped).append("（目标已存在）");
            }
            if (!errors.isEmpty()) {
                sb.append(" · 失败 ").append(errors.size());
            }
            return sb.toString();
        }
    }

    private final Path gameDir;
    private final SyncPaths paths;

    public TrashBrowser(Path gameDir) {
        this.gameDir = gameDir;
        this.paths = new SyncPaths(gameDir);
    }

    /** 列出所有回收站条目，最新在前。 */
    public List<Entry> list() {
        Path root = paths.root().resolve("trash");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String timestamp = dir.getFileName().toString();
                Counter counter = new Counter();
                countFiles(dir, counter);
                entries.add(new Entry(timestamp, counter.files, counter.bytes));
            }
        } catch (IOException e) {
            return List.of();
        }
        entries.sort(Comparator.comparing(Entry::timestamp).reversed());
        return entries;
    }

    /** 列出某个时间戳下被清掉的文件（相对路径）。 */
    public List<String> files(String timestamp) {
        Path dir = PathGuard.resolve(paths.root().resolve("trash"), timestamp);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = dir.relativize(path).toString().replace('\\', '/');
                files.add(relative);
            });
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /**
     * 把一个时间戳目录里的文件全部还原回游戏目录。
     *
     * @param timestamp 回收站时间戳
     */
    public RestoreResult restore(String timestamp) {
        Path dir = PathGuard.resolve(paths.root().resolve("trash"), timestamp);
        if (!Files.isDirectory(dir)) {
            return new RestoreResult(0, 0, List.of("回收站条目不存在: " + timestamp));
        }
        int restored = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (String relative : files(timestamp)) {
            try {
                Path source = PathGuard.resolve(dir, relative);
                Path target = PathGuard.resolve(gameDir, relative);
                if (Files.exists(target)) {
                    skipped++;
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                // 还原 = 移回去：文件离开回收站，时间戳目录随之变空，由 pruneEmpty 收尾
                try {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    Files.deleteIfExists(source);
                }
                restored++;
            } catch (IOException | RuntimeException e) {
                errors.add(relative + "：" + e.getMessage());
            }
        }
        return new RestoreResult(restored, skipped, errors);
    }

    /** 还原成功后删掉该时间戳目录（失败的文件会留在原地，下次还能再试）。 */
    public int pruneEmpty() {
        Path root = paths.root().resolve("trash");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                if (isEmpty(dir)) {
                    try (Stream<Path> inner = Files.walk(dir)) {
                        for (Path path : inner.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                    removed++;
                }
            }
        } catch (IOException e) {
            return removed;
        }
        return removed;
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.noneMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    private static void countFiles(Path dir, Counter counter) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                counter.files++;
                try {
                    counter.bytes += Files.size(path);
                } catch (IOException ignored) {
                    // 大小取不到就不计，不影响文件数
                }
            });
        } catch (IOException ignored) {
            // 目录读不了就当空的
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static final class Counter {
        int files;
        long bytes;
    }
}
