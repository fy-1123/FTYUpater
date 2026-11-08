package com.ftysync.client;

import com.ftysync.core.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * v1 → v2 的配置/状态迁移（M6-2）。
 *
 * <p>v1 把状态摊在 {@code config/} 下的一堆文本文件里，v2 收进 {@code ftyupater/} 的 JSON。
 * 老玩家升级后不该丢掉「我忽略过哪些模组」「哪些待删除」「上次用的同步源」，所以启动时读一次旧的、
 * 写进新的，然后把旧文件改名成 {@code .migrated} 留证（不删除，出问题还能查）。</p>
 *
 * <p>v1 文件（都在 {@code config/} 下）：</p>
 * <ul>
 *   <li>{@code ftyupater-ignore.txt} —— 每行一个**小写**文件名，如 {@code someclientmod.jar}</li>
 *   <li>{@code ftyupater-pending-deletions.txt} —— 每行一个待删除文件名</li>
 *   <li>{@code ftyupater-last-server.txt} —— 每行 {@code host:port=同步源URL}</li>
 * </ul>
 *
 * <p>迁移是**幂等**的：旧文件改名后不再存在，第二次启动什么都不做。</p>
 */
public final class V1Migration {

    /** 迁移做了什么。 */
    public record Report(boolean ran, Set<String> ignored, List<PendingOp> pending, Map<String, String> servers) {
        public Report {
            ignored = ignored == null ? Set.of() : Set.copyOf(ignored);
            pending = pending == null ? List.of() : List.copyOf(pending);
            servers = servers == null ? Map.of() : Map.copyOf(servers);
        }

        public static Report none() {
            return new Report(false, Set.of(), List.of(), Map.of());
        }

        public boolean didAnything() {
            return ran && (!ignored.isEmpty() || !pending.isEmpty() || !servers.isEmpty());
        }

        /** 给日志/界面看的一句话。 */
        public String describe() {
            if (!ran) {
                return "没有需要迁移的 v1 数据";
            }
            StringBuilder sb = new StringBuilder("已从 v1 迁移");
            if (!ignored.isEmpty()) {
                sb.append(" · 忽略 ").append(ignored.size());
            }
            if (!pending.isEmpty()) {
                sb.append(" · 待删除 ").append(pending.size());
            }
            if (!servers.isEmpty()) {
                sb.append(" · 服务器记忆 ").append(servers.size());
            }
            return sb.toString();
        }
    }

    static final String IGNORE_FILE = "ftyupater-ignore.txt";
    static final String PENDING_FILE = "ftyupater-pending-deletions.txt";
    static final String SERVERS_FILE = "ftyupater-last-server.txt";
    private static final String MIGRATED_SUFFIX = ".migrated";

    private final Path configDir;
    private final Path gameDir;

    /**
     * @param configDir v1 文件所在目录（通常是 {@code config/}）
     * @param gameDir   游戏目录（用来把裸文件名匹配到真实的 {@code mods/xxx.jar} 大小写）
     */
    public V1Migration(Path configDir, Path gameDir) {
        this.configDir = configDir;
        this.gameDir = gameDir;
    }

    /** 有没有 v1 遗留文件（不读内容，只看存在性）。 */
    public boolean hasV1Files() {
        return Files.isRegularFile(configDir.resolve(IGNORE_FILE))
                || Files.isRegularFile(configDir.resolve(PENDING_FILE))
                || Files.isRegularFile(configDir.resolve(SERVERS_FILE));
    }

    /**
     * 执行迁移：读旧文件，返回「应该合并进 v2 状态的东西」，并把旧文件改名。
     *
     * <p>本方法**不写** v2 状态——合并交给调用方，方便单测与回滚。</p>
     */
    public Report migrate() {
        if (!hasV1Files()) {
            return Report.none();
        }
        Set<String> ignored = new LinkedHashSet<>();
        List<PendingOp> pending = new ArrayList<>();
        Map<String, String> servers = new LinkedHashMap<>();

        readLines(configDir.resolve(IGNORE_FILE)).forEach(name -> {
            String path = toModsPath(name);
            if (path != null) {
                ignored.add(path);
            }
        });
        String now = java.time.Instant.now().toString();
        readLines(configDir.resolve(PENDING_FILE)).forEach(name -> {
            String path = toModsPath(name);
            if (path != null) {
                pending.add(new PendingOp(PendingOp.Operation.DELETE, path, null, now));
            }
        });
        readLines(configDir.resolve(SERVERS_FILE)).forEach(line -> {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return;
            }
            String host = line.substring(0, eq).trim();
            String url = line.substring(eq + 1).trim();
            if (!host.isEmpty() && !url.isEmpty()) {
                servers.put(host.toLowerCase(Locale.ROOT), url);
            }
        });

        markMigrated(configDir.resolve(IGNORE_FILE));
        markMigrated(configDir.resolve(PENDING_FILE));
        markMigrated(configDir.resolve(SERVERS_FILE));
        return new Report(true, ignored, pending, servers);
    }

    /**
     * 把 v1 的裸文件名变成 v2 的 {@code mods/} 相对路径，尽量保住磁盘上的真实大小写。
     *
     * @return 相对路径；名字非法（空、含路径分隔符、被 PathGuard 拒绝）时返回 null
     */
    String toModsPath(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            return null;
        }
        String actual = matchRealName(name);
        try {
            return PathGuard.normalize("mods/" + actual);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 在 {@code mods/} 里按大小写不敏感匹配真实文件名；匹配不到就用传进来的名字。 */
    private String matchRealName(String lowerName) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return lowerName;
        }
        try (Stream<Path> stream = Files.list(mods)) {
            Optional<String> match = stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.equalsIgnoreCase(lowerName))
                    .findFirst();
            return match.orElse(lowerName);
        } catch (IOException e) {
            return lowerName;
        }
    }

    private static List<String> readLines(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    result.add(trimmed);
                }
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 旧文件改名成 {@code xxx.migrated}（不删除，留证）。 */
    private static void markMigrated(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Path target = file.resolveSibling(file.getFileName() + MIGRATED_SUFFIX);
            Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 改名失败也无所谓：下次启动会重新读一遍并覆盖同样的值（幂等）
        }
    }
}
