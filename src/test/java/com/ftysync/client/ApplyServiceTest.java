package com.ftysync.client;

import com.ftysync.core.DiffResult;
import com.ftysync.core.Hash;
import com.ftysync.core.PackEntry;
import com.ftysync.core.SyncAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyServiceTest {

    @TempDir
    Path gameDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path staging(String sha, byte[] data) throws Exception {
        Path dir = new SyncPaths(gameDir).stagingDir("g1");
        Files.createDirectories(dir);
        Path file = dir.resolve(sha);
        Files.write(file, data);
        return file;
    }

    private static SyncAction action(SyncAction.Action action, String path, String from, String to, PackEntry.Role role) {
        return new SyncAction(path, action, from, to, role);
    }

    @Test
    void installsMissingFile() throws Exception {
        byte[] data = bytes("new-file");
        String sha = Hash.sha256(data);
        Path staged = staging(sha, data);
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.INSTALL_REQUIRED, "mods/a.jar", null, sha, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> false).apply("g1", diff, Map.of(sha, staged));

        assertArrayEquals(data, Files.readAllBytes(gameDir.resolve("mods/a.jar")));
        assertEquals(List.of("mods/a.jar"), result.applied());
        assertTrue(result.pending().isEmpty());
        assertTrue(result.failed().isEmpty(), result.errors()::toString);
        assertFalse(result.requiresRestart());
    }

    @Test
    void updateBacksUpExistingFile() throws Exception {
        Path target = gameDir.resolve("mods/a.jar");
        Files.createDirectories(target.getParent());
        byte[] oldData = bytes("old");
        Files.write(target, oldData);
        byte[] newData = bytes("new");
        String newSha = Hash.sha256(newData);
        Path staged = staging(newSha, newData);
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.UPDATE, "mods/a.jar", Hash.sha256(oldData), newSha, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> false).apply("g1", diff, Map.of(newSha, staged));

        assertArrayEquals(newData, Files.readAllBytes(target));
        assertArrayEquals(oldData, Files.readAllBytes(gameDir.resolve("ftyupater/backup/g1/mods/a.jar")));
        assertEquals(List.of("mods/a.jar"), result.applied());
    }

    @Test
    void deleteMovesToTrash() throws Exception {
        Path target = gameDir.resolve("mods/extra.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "extra");
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.DELETE, "mods/extra.jar", Hash.sha256("extra".getBytes()), null, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> false).apply("g1", diff, Map.of());

        assertFalse(Files.exists(target));
        assertEquals(List.of("mods/extra.jar"), result.deleted());
        try (var walk = Files.walk(gameDir.resolve("ftyupater/trash"))) {
            assertTrue(walk.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().equals("extra.jar")));
        }
    }

    @Test
    void lockedReplaceGoesToPending() throws Exception {
        Path target = gameDir.resolve("mods/a.jar");
        Files.createDirectories(target.getParent());
        byte[] oldData = bytes("old");
        Files.write(target, oldData);
        byte[] newData = bytes("new");
        String newSha = Hash.sha256(newData);
        Path staged = staging(newSha, newData);
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.UPDATE, "mods/a.jar", Hash.sha256(oldData), newSha, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> true).apply("g1", diff, Map.of(newSha, staged));

        assertArrayEquals(oldData, Files.readAllBytes(target), "被锁定时不能动目标文件");
        assertEquals(1, result.pending().size());
        assertEquals(PendingOp.Operation.REPLACE, result.pending().get(0).operation());
        assertEquals("mods/a.jar", result.pending().get(0).path());
        assertEquals(newSha, result.pending().get(0).sha256());
        assertTrue(result.requiresRestart());
        assertEquals(List.of(), result.applied());
    }

    @Test
    void lockedDeleteGoesToPending() throws Exception {
        Path target = gameDir.resolve("mods/old.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.DELETE, "mods/old.jar", Hash.sha256("old".getBytes()), null, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> true).apply("g1", diff, Map.of());

        assertTrue(Files.exists(target));
        assertEquals(1, result.pending().size());
        assertEquals(PendingOp.Operation.DELETE, result.pending().get(0).operation());
        assertNull(result.pending().get(0).sha256());
    }

    @Test
    void duplicateContentShaAppliesToEveryPath() throws Exception {
        // 服务器清单里可能出现多个路径内容完全相同（同一 sha256），暂存文件只有一份。
        byte[] data = bytes("shared-content");
        String sha = Hash.sha256(data);
        Path staged = staging(sha, data);
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.INSTALL_REQUIRED, "mods/a.jar", null, sha, PackEntry.Role.REQUIRED),
                action(SyncAction.Action.INSTALL_REQUIRED, "mods/b.jar", null, sha, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> false).apply("g1", diff, Map.of(sha, staged));

        assertArrayEquals(data, Files.readAllBytes(gameDir.resolve("mods/a.jar")));
        assertArrayEquals(data, Files.readAllBytes(gameDir.resolve("mods/b.jar")));
        assertEquals(List.of("mods/a.jar", "mods/b.jar"), result.applied());
        assertTrue(result.failed().isEmpty(), result.errors()::toString);
    }

    @Test
    void missingStagedFileIsFailureNotCrash() throws Exception {
        String sha = Hash.sha256("ghost".getBytes());
        DiffResult diff = new DiffResult(List.of(
                action(SyncAction.Action.INSTALL_REQUIRED, "mods/ghost.jar", null, sha, PackEntry.Role.REQUIRED)));

        ApplyService.ApplyResult result = new ApplyService(gameDir, p -> false).apply("g1", diff, Map.of());

        assertEquals(List.of("mods/ghost.jar"), result.failed());
        assertFalse(Files.exists(gameDir.resolve("mods/ghost.jar")));
    }
}
