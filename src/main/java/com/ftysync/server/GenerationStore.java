package com.ftysync.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 代际清单文件存储：{@code <storeDir>/generations/<generation>/manifest.json}。
 */
public final class GenerationStore {

    private final Path generationsDir;

    public GenerationStore(Path storeDir) {
        this.generationsDir = storeDir.resolve("generations");
    }

    public Path generationsDir() {
        return generationsDir;
    }

    /** 代际 ID 只允许字母/数字/点/下划线/短横线，防止 journal 被篡改时路径穿越。 */
    public static String requireValidGeneration(String generation) {
        if (generation == null || generation.isBlank()
                || generation.contains("/") || generation.contains("\\")
                || generation.equals(".") || generation.equals("..")) {
            throw new IllegalArgumentException("非法的代际 ID: " + generation);
        }
        return generation;
    }

    public Path manifestFile(String generation) {
        return generationsDir.resolve(requireValidGeneration(generation)).resolve("manifest.json");
    }

    public boolean exists(String generation) {
        return Files.isRegularFile(manifestFile(generation));
    }

    public Optional<String> readManifest(String generation) throws IOException {
        Path file = manifestFile(generation);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    public void writeManifest(String generation, String json) throws IOException {
        Path file = manifestFile(generation);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("manifest.json.tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(String generation) throws IOException {
        Path dir = manifestFile(generation).getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
