package com.ftysync.server;

import com.ftysync.core.Blacklist;
import com.ftysync.core.Hash;
import com.ftysync.core.PackEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 扫描服务器 {@code mods/} 与客户端专属目录 {@code clientpack/}，产出待发布文件列表。
 *
 * <p>规则（见 docs/protocol.md 第 2.2/4 节）：</p>
 * <ul>
 *   <li>服务器 {@code mods/*.jar} → {@code server-mods} + {@code required}；</li>
 *   <li>{@code clientpack/} 按游戏目录结构递归扫描 → {@code clientpack} + {@code optional}；</li>
 *   <li>黑名单命中、自身 jar、隐藏文件、*.part 一律不发布；</li>
 *   <li>同一相对路径（如两边都有 {@code mods/a.jar}）以服务器 mods 为准。</li>
 * </ul>
 */
public final class ContentScanner {

    public static final String MODS_DIR = "mods";
    public static final String CLIENTPACK_DIR = "clientpack";

    private final Path gameDir;
    private final Path clientpackDir;
    private final Blacklist blacklist;
    private final Set<String> excludedFileNames;

    public ContentScanner(Path gameDir, Blacklist blacklist, Collection<String> excludedFileNames) {
        this(gameDir, gameDir.resolve(CLIENTPACK_DIR), blacklist, excludedFileNames);
    }

    public ContentScanner(Path gameDir, Path clientpackDir, Blacklist blacklist,
                          Collection<String> excludedFileNames) {
        this.gameDir = Objects.requireNonNull(gameDir, "gameDir");
        this.clientpackDir = clientpackDir == null ? gameDir.resolve(CLIENTPACK_DIR) : clientpackDir;
        this.blacklist = blacklist == null ? Blacklist.empty() : blacklist;
        Set<String> names = new HashSet<>();
        if (excludedFileNames != null) {
            for (String name : excludedFileNames) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.excludedFileNames = Set.copyOf(names);
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path clientpackDir() {
        return clientpackDir;
    }

    /** 按路径排序的待发布列表。 */
    public List<ScannedFile> scan() throws IOException {
        Map<String, ScannedFile> files = new LinkedHashMap<>();
        scanServerMods(files);
        scanClientpack(files);
        List<ScannedFile> result = new ArrayList<>(files.values());
        result.sort(Comparator.comparing(ScannedFile::path));
        return List.copyOf(result);
    }

    private void scanServerMods(Map<String, ScannedFile> out) throws IOException {
        Path modsDir = gameDir.resolve(MODS_DIR);
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        List<Path> jars;
        try (Stream<Path> stream = Files.list(modsDir)) {
            jars = stream.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (excludedFileNames.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String path = MODS_DIR + "/" + name;
            if (blacklist.matches(path)) {
                continue;
            }
            out.putIfAbsent(path, scanned(path, jar, PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED));
        }
    }

    private void scanClientpack(Map<String, ScannedFile> out) throws IOException {
        if (!Files.isDirectory(clientpackDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(clientpackDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            String relative = clientpackDir.relativize(file).toString().replace('\\', '/');
            String fileName = file.getFileName().toString();
            if (isHidden(relative) || isTemp(fileName)
                    || excludedFileNames.contains(fileName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (blacklist.matches(relative)) {
                continue;
            }
            // 同一路径服务器 mods 优先
            out.putIfAbsent(relative, scanned(relative, file, PackEntry.Origin.CLIENTPACK, PackEntry.Role.OPTIONAL));
        }
    }

    private static ScannedFile scanned(String path, Path source, PackEntry.Origin origin, PackEntry.Role role)
            throws IOException {
        return new ScannedFile(path, source, Hash.sha256(source), Files.size(source), origin, role);
    }

    /** 任何一段以 '.' 开头的路径都视为隐藏文件，不发布。 */
    private static boolean isHidden(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTemp(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".part") || lower.endsWith(".tmp");
    }
}
