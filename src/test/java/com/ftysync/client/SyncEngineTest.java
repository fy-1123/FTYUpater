package com.ftysync.client;

import com.ftysync.core.DiffEngine;
import com.ftysync.core.DiffResult;
import com.ftysync.core.Hash;
import com.ftysync.core.LocalFile;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEngineTest {

    @TempDir
    Path gameDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] data) {
        return Hash.sha256(data);
    }

    private StateStore stateStore() {
        return new StateStore(new SyncPaths(gameDir).stateFile());
    }

    private static PackManifest manifest(String generation, List<PackEntry> entries) {
        return new PackManifest("fty", generation, null, "2026-01-01T00:00:00Z", "1.20.1", "forge-47.4.0", entries);
    }

    private static PackEntry required(String path, byte[] data) {
        return new PackEntry(path, sha(data), data.length, PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED);
    }

    private static PackEntry optional(String path, byte[] data) {
        return new PackEntry(path, sha(data), data.length, PackEntry.Origin.CLIENTPACK, PackEntry.Role.OPTIONAL);
    }

    private static DiffResult diff(PackManifest manifest, Map<String, LocalFile> present, LocalState state) {
        return DiffEngine.diff(manifest, present, Set.of(), state.optOut(), state.ignored(), Set.of(), Map.of());
    }

    @Test
    void installsRequiredAndOptional() throws Exception {
        byte[] a = bytes("mod-a-content");
        byte[] b = bytes("config-b");
        PackManifest m = manifest("g1", List.of(required("mods/a.jar", a), optional("config/b.cfg", b)));
        try (TestSyncServer server = TestSyncServer.start()) {
            server.put(sha(a), a).put(sha(b), b);
            SyncEngine engine = new SyncEngine(server.baseUrl(), gameDir, stateStore());

            SyncEngine.SyncReport report = engine.sync(m, diff(m, Map.of(), new LocalState()));

            assertEquals(2, report.installed().size(), report.errors()::toString);
            assertArrayEquals(a, Files.readAllBytes(gameDir.resolve("mods/a.jar")));
            assertArrayEquals(b, Files.readAllBytes(gameDir.resolve("config/b.cfg")));
            assertFalse(report.requiresRestart());
            assertEquals(2, server.requests());
            LocalState saved = stateStore().load();
            assertEquals("g1", saved.generation());
            assertEquals(2, saved.entries().size());
            assertFalse(Files.exists(new SyncPaths(gameDir).stagingDir("g1")), "同步完成后暂存目录应清理");
        }
    }

    @Test
    void optOutOptionalIsNeverRequested() throws Exception {
        byte[] b = bytes("config-b");
        PackManifest m = manifest("g1", List.of(optional("config/b.cfg", b)));
        LocalState state = new LocalState();
        state.setOptOut(Set.of("config/b.cfg"));
        try (TestSyncServer server = TestSyncServer.start()) {
            server.put(sha(b), b);

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore()).sync(m, diff(m, Map.of(), state));

            assertEquals(0, server.requests(), "opt-out 的条目不应被下载");
            assertTrue(report.installed().isEmpty());
            assertTrue(report.failed().isEmpty());
            assertFalse(Files.exists(gameDir.resolve("config/b.cfg")));
        }
    }

    @Test
    void updateBacksUpOldVersion() throws Exception {
        byte[] oldData = bytes("old");
        byte[] newData = bytes("new");
        Path target = gameDir.resolve("mods/a.jar");
        Files.createDirectories(target.getParent());
        Files.write(target, oldData);
        LocalState state = new LocalState();
        state.setGeneration("g0");
        state.markInstalled("mods/a.jar", sha(oldData), oldData.length);
        PackManifest m = manifest("g1", List.of(required("mods/a.jar", newData)));
        LocalFile local = new LocalFile("mods/a.jar", sha(oldData), oldData.length);
        try (TestSyncServer server = TestSyncServer.start()) {
            server.put(sha(newData), newData);

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore())
                    .sync(m, diff(m, Map.of("mods/a.jar", local), state));

            assertEquals(List.of("mods/a.jar"), report.updated(), report.errors()::toString);
            assertArrayEquals(newData, Files.readAllBytes(target));
            assertArrayEquals(oldData, Files.readAllBytes(gameDir.resolve("ftyupater/backup/g1/mods/a.jar")));
        }
    }

    @Test
    void deletesExtraFileToTrash() throws Exception {
        byte[] extra = bytes("extra");
        Path target = gameDir.resolve("mods/extra.jar");
        Files.createDirectories(target.getParent());
        Files.write(target, extra);
        LocalState state = new LocalState();
        state.markInstalled("mods/extra.jar", sha(extra), extra.length);
        PackManifest m = manifest("g1", List.of());
        LocalFile local = new LocalFile("mods/extra.jar", sha(extra), extra.length);
        try (TestSyncServer server = TestSyncServer.start()) {

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore())
                    .sync(m, diff(m, Map.of("mods/extra.jar", local), state));

            assertEquals(List.of("mods/extra.jar"), report.deleted(), report.errors()::toString);
            assertFalse(Files.exists(target));
            try (var walk = Files.walk(gameDir.resolve("ftyupater/trash"))) {
                assertTrue(walk.filter(Files::isRegularFile)
                        .anyMatch(p -> p.getFileName().toString().equals("extra.jar")));
            }
        }
    }

    @Test
    void retriesTransientFailureThenInstalls() throws Exception {
        byte[] data = bytes("retry-content");
        String sha = sha(data);
        PackManifest m = manifest("g1", List.of(required("mods/r.jar", data)));
        try (TestSyncServer server = TestSyncServer.start()) {
            server.put(sha, data).failTimes(sha, 2);

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore())
                    .sync(m, diff(m, Map.of(), new LocalState()));

            assertEquals(List.of("mods/r.jar"), report.installed(), report.errors()::toString);
            assertArrayEquals(data, Files.readAllBytes(gameDir.resolve("mods/r.jar")));
            assertEquals(3, server.requests());
        }
    }

    @Test
    void hashMismatchAfterRetriesIsFailure() throws Exception {
        byte[] data = bytes("bad-content");
        String sha = sha(data);
        PackManifest m = manifest("g1", List.of(required("mods/bad.jar", data)));
        try (TestSyncServer server = TestSyncServer.start()) {
            server.put(sha, data).failTimes(sha, 100);

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore())
                    .sync(m, diff(m, Map.of(), new LocalState()));

            assertEquals(List.of("mods/bad.jar"), report.failed(), report.errors()::toString);
            assertFalse(Files.exists(gameDir.resolve("mods/bad.jar")));
            assertEquals(3, server.requests(), "应重试 3 次");
        }
    }

    @Test
    void insufficientDiskSpaceAbortsBeforeDownloading() throws Exception {
        PackEntry huge = new PackEntry("mods/huge.jar", "0".repeat(64), Long.MAX_VALUE / 2,
                PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED);
        PackManifest m = manifest("g1", List.of(huge));
        try (TestSyncServer server = TestSyncServer.start()) {

            SyncEngine.SyncReport report = new SyncEngine(server.baseUrl(), gameDir, stateStore())
                    .sync(m, diff(m, Map.of(), new LocalState()));

            assertEquals(List.of("mods/huge.jar"), report.failed(), report.errors()::toString);
            assertTrue(report.errors().stream().anyMatch(e -> e.contains("磁盘")), report.errors()::toString);
            assertEquals(0, server.requests());
            assertFalse(Files.exists(gameDir.resolve("mods/huge.jar")));
        }
    }
}
