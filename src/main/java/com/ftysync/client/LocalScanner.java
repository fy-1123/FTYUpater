package com.ftysync.client;

import com.ftysync.core.Hash;
import com.ftysync.core.LocalFile;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import com.ftysync.core.PathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 本地扫描：把「清单声明的路径 + mods/ 里的多余 jar + 手工 .disabled」整理成 DiffEngine 的输入。
 *
 * <p>受管范围（见 docs/protocol.md §6）：</p>
 * <ul>
 *   <li>清单里声明的任何路径：存在则算 present（含 config/resourcepacks/shaderpacks 等）；</li>
 *   <li>{@code mods/} 是镜像目录：清单外的 *.jar 也算 present，交给 DiffEngine 决定 DELETE；</li>
 *   <li>清单路径旁的 {@code <path>.disabled} 记录为「玩家手工禁用」。</li>
 * </ul>
 */
public final class LocalScanner {

    private LocalScanner() {
    }

    /** 扫描结果。 */
    public record ScanResult(Map<String, LocalFile> present, Set<String> manualDisabled) {
        public ScanResult {
            present = present == null ? Map.of() : Map.copyOf(present);
            manualDisabled = manualDisabled == null ? Set.of() : Set.copyOf(manualDisabled);
        }
    }

    /**
     * 扫描游戏目录。
     *
     * @param gameDir  游戏目录（路径都相对它）
     * @param manifest 服务器清单（决定受管文件）
     * @throws IOException 读取文件失败
     */
    public static ScanResult scan(Path gameDir, PackManifest manifest) throws IOException {
        Map<String, LocalFile> present = new LinkedHashMap<>();
        Set<String> manualDisabled = new LinkedHashSet<>();

        for (PackEntry entry : manifest.entries().values()) {
            String path = entry.path();
            Path disabled = PathGuard.resolve(gameDir, path + ".disabled");
            if (Files.isRegularFile(disabled)) {
                manualDisabled.add(path);
                continue;
            }
            Path file = PathGuard.resolve(gameDir, path);
            if (Files.isRegularFile(file)) {
                present.put(path, new LocalFile(path, Hash.sha256(file), Files.size(file)));
            }
        }

        Path modsDir = gameDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            List<Path> jars;
            try (Stream<Path> stream = Files.list(modsDir)) {
                jars = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .toList();
            }
            for (Path jar : jars) {
                String relative = "mods/" + jar.getFileName();
                if (present.containsKey(relative)) {
                    continue;
                }
                present.put(relative, new LocalFile(relative, Hash.sha256(jar), Files.size(jar)));
            }
        }

        return new ScanResult(present, manualDisabled);
    }
}
