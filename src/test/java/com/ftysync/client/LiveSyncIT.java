package com.ftysync.client;

import com.ftysync.core.DiffEngine;
import com.ftysync.core.DiffResult;
import com.ftysync.core.Hash;
import com.ftysync.core.LocalFile;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手工集成测试：对一个已经跑起来的本地 dev 服务器（默认 127.0.0.1:8081）做真实下载/应用/清理。
 *
 * <p>服务器不可达时通过 {@link Assumptions} 自动跳过，因此常规 {@code gradlew test} 不会失败。
 * 验证方法：先 {@code .\gradlew.bat runServer}，再在另一个终端
 * {@code .\gradlew.bat test --tests "com.ftysync.client.LiveSyncIT"}。</p>
 */
class LiveSyncIT {

    private static final String BASE_URL = System.getenv().getOrDefault("FTYUPATER_LIVE_URL", "http://127.0.0.1:8081");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    void syncFromLiveDevServerAppliesDeletesAndRecordsState(@TempDir Path gameDir) throws Exception {
        assumeServerUp();
        PackManifest manifest = fetchManifest();
        assertFalse(manifest.entries().isEmpty(), "服务器清单不应为空");

        // 预置一个清单外的多余 jar（应被清理进 trash），以及一个与清单不一致的旧文件（应备份+覆盖）。
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("unwanted-extra.jar"), "extra", StandardCharsets.UTF_8);
        PackEntry overridden = manifest.entries().values().stream()
                .filter(entry -> entry.path().equals("config/example-client.cfg"))
                .findFirst().orElse(null);
        Path oldContentPath = overridden != null ? gameDir.resolve(overridden.path()) : null;
        if (oldContentPath != null) {
            Files.createDirectories(oldContentPath.getParent());
            Files.writeString(oldContentPath, "old-local-content", StandardCharsets.UTF_8);
        }

        SyncPaths paths = new SyncPaths(gameDir);
        LocalScanner.ScanResult scan = LocalScanner.scan(gameDir, manifest);
        DiffResult diff = DiffEngine.diff(manifest, scan.present(), scan.manualDisabled(),
                Set.of(), Set.of(), Set.of(), Map.of());
        assertTrue(diff.requiresSync(), "全新目录 + 多余文件应需要同步");

        StateStore stateStore = new StateStore(paths.stateFile());
        SyncEngine engine = new SyncEngine(BASE_URL, gameDir, stateStore);
        SyncEngine.SyncReport report = engine.sync(manifest, diff);

        assertTrue(report.ok(), () -> "同步应全部成功，errors=" + report.errors());
        assertTrue(report.failed().isEmpty());

        // 清单里的每个文件都应落盘且 SHA256 正确。
        for (PackEntry entry : manifest.entries().values()) {
            Path file = gameDir.resolve(entry.path());
            assertTrue(Files.isRegularFile(file), "缺少文件: " + entry.path());
            assertEquals(entry.sha256(), Hash.sha256(file), "SHA 不匹配: " + entry.path());
        }

        // 多余 jar 应被清理，且进入 trash 而不是直接删除。
        assertFalse(Files.exists(mods.resolve("unwanted-extra.jar")));
        try (Stream<Path> trash = Files.walk(paths.trashDir(findTrashTimestamp(paths)))) {
            assertTrue(trash.anyMatch(p -> p.getFileName().toString().equals("unwanted-extra.jar")),
                    "多余 jar 应进入 trash");
        }

        // 被覆盖的旧文件应进入 backup/<generation>/。
        if (oldContentPath != null) {
            Path backup = paths.backupDir(manifest.generation()).resolve(overridden.path());
            assertTrue(Files.isRegularFile(backup), "旧文件应备份到 " + backup);
            assertEquals("old-local-content", Files.readString(backup, StandardCharsets.UTF_8));
        }

        // 状态文件应记录代际与条目。
        LocalState state = stateStore.load();
        assertEquals(manifest.generation(), state.generation());
        assertEquals(manifest.size(), state.entries().size());
        assertTrue(Files.isRegularFile(paths.logFile()), "应写同步日志");

        // 再扫一遍：应与服务器一致（幂等）。
        LocalScanner.ScanResult after = LocalScanner.scan(gameDir, manifest);
        DiffResult second = DiffEngine.diff(manifest, after.present(), after.manualDisabled(),
                state.optOut(), state.ignored(), Set.of(), Map.of());
        assertTrue(second.isInSync(), () -> "同步后应一致，剩余动作=" + second.actions());
    }

    private static String findTrashTimestamp(SyncPaths paths) throws IOException {
        Path trash = paths.root().resolve("trash");
        try (Stream<Path> stream = Files.list(trash)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .max(Comparator.naturalOrder())
                    .orElseThrow(() -> new AssertionError("trash 目录为空"));
        }
    }

    private static void assumeServerUp() {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(response.statusCode() == 200,
                    "dev 服务器未运行在 " + BASE_URL + "，跳过 live 集成测试");
        } catch (Exception e) {
            Assumptions.abort("dev 服务器不可达（" + BASE_URL + "），跳过 live 集成测试: " + e);
        }
    }

    private static PackManifest fetchManifest() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v1/pack"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        PackManifest manifest = PackManifest.parse(response.body());
        assertNotNull(manifest);
        return manifest;
    }
}
