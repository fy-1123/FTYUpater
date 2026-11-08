package com.ftysync.helper;

import com.ftysync.client.LockProbe;
import com.ftysync.client.PendingOp;
import com.ftysync.client.SyncPaths;
import com.ftysync.core.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PendingApplier}：退出后应用 pending 的真实文件操作。 */
class PendingApplierTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @TempDir
    Path gameDir;

    private Path stage(String sha, byte[] content) throws IOException {
        Path dir = new SyncPaths(gameDir).stagingDir("g1");
        Files.createDirectories(dir);
        Path file = dir.resolve(sha);
        Files.write(file, content);
        return file;
    }

    private String realSha(byte[] content) {
        return Hash.sha256(content);
    }

    @Test
    void replaceCopiesStagedFileIntoPlace() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("a.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");

        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", sha, "t")),
                file -> false);

        assertEquals(List.of("mods/a.jar"), report.applied());
        assertTrue(report.remaining().isEmpty());
        assertTrue(report.failed().isEmpty());
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void replaceBacksUpPreviousVersion() throws IOException {
        byte[] content = "new".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("a.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");

        PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", sha, "t")),
                file -> false);

        Path backupRoot = new SyncPaths(gameDir).root().resolve("backup");
        assertTrue(Files.isDirectory(backupRoot), "备份目录应存在");
        try (var stream = Files.walk(backupRoot)) {
            Path backedUp = stream.filter(Files::isRegularFile).findFirst().orElse(null);
            assertTrue(backedUp != null, "应有一份旧版本备份");
            assertEquals("old", Files.readString(backedUp));
        }
    }

    @Test
    void lockedTargetStaysPending() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("a.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");

        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", sha, "t")),
                file -> true);

        assertTrue(report.applied().isEmpty());
        assertEquals(1, report.remaining().size());
        assertTrue(report.failed().isEmpty(), "锁定不算失败");
        assertEquals("old", Files.readString(target), "锁定时不能动文件");
    }

    @Test
    void missingStagedFileIsFailure() {
        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", SHA_A, "t")),
                file -> false);
        assertTrue(report.applied().isEmpty());
        assertTrue(report.remaining().isEmpty());
        assertEquals(List.of("mods/a.jar"), report.failed());
        assertTrue(report.errors().get(0).contains("暂存文件不存在"));
    }

    @Test
    void hashMismatchIsFailure() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        stage(SHA_A, content);
        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", SHA_A, "t")),
                file -> false);
        assertEquals(List.of("mods/a.jar"), report.failed());
        assertTrue(report.errors().get(0).contains("哈希不符"));
    }

    @Test
    void deleteMovesFileToTrash() throws IOException {
        Path target = gameDir.resolve("mods").resolve("gone.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "bye");

        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.DELETE, "mods/gone.jar", null, "t")),
                file -> false);

        assertEquals(List.of("mods/gone.jar"), report.deleted());
        assertFalse(Files.exists(target));
        Path trashRoot = new SyncPaths(gameDir).root().resolve("trash");
        try (var stream = Files.walk(trashRoot)) {
            assertTrue(stream.anyMatch(Files::isRegularFile), "删除的文件应进回收站");
        }
    }

    @Test
    void deleteIsIdempotentWhenAlreadyGone() {
        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.DELETE, "mods/never.jar", null, "t")),
                file -> false);
        assertEquals(List.of("mods/never.jar"), report.deleted());
        assertTrue(report.failed().isEmpty());
    }

    @Test
    void lockedDeleteStaysPending() throws IOException {
        Path target = gameDir.resolve("mods").resolve("locked.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "still here");

        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.DELETE, "mods/locked.jar", null, "t")),
                file -> true);

        assertEquals(1, report.remaining().size());
        assertTrue(Files.exists(target));
    }

    @Test
    void emptyOperationsDoNothing() {
        PendingApplier.Report report = PendingApplier.apply(gameDir, List.of(), null);
        assertTrue(report.applied().isEmpty());
        assertTrue(report.deleted().isEmpty());
        assertTrue(report.failed().isEmpty());
        assertTrue(report.remaining().isEmpty());
    }

    @Test
    void readPendingHandlesMissingFile() {
        assertTrue(PendingApplier.readPending(gameDir.resolve("nope.json")).isEmpty());
        assertTrue(PendingApplier.readPending(null).isEmpty());
    }

    @Test
    void readPendingSkipsUnknownOperation() throws IOException {
        Path file = new SyncPaths(gameDir).pendingFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "["
                + "{\"operation\":\"REPLACE\",\"path\":\"mods/a.jar\",\"sha256\":\"" + SHA_A + "\",\"addedAt\":\"t\"},"
                + "{\"operation\":\"WAT\",\"path\":\"mods/b.jar\"},"
                + "{\"path\":\"no-operation.jar\"}"
                + "]");
        List<PendingOp> ops = PendingApplier.readPending(file);
        assertEquals(1, ops.size());
        assertEquals("mods/a.jar", ops.get(0).path());
    }

    @Test
    void readPendingToleratesCorruptJson() throws IOException {
        Path file = new SyncPaths(gameDir).pendingFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not an array");
        assertTrue(PendingApplier.readPending(file).isEmpty());
    }

    @Test
    void findStagedSearchesAllGenerations() throws IOException {
        byte[] content = "deep".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        Path dir = new SyncPaths(gameDir).stagingDir("some-old-generation");
        Files.createDirectories(dir);
        Files.write(dir.resolve(sha), content);

        Path found = PendingApplier.findStaged(new SyncPaths(gameDir), sha);
        assertTrue(found != null, "应能在任意代际目录里找到暂存文件");
        assertEquals(sha, found.getFileName().toString());
    }

    @Test
    void findStagedReturnsNullWhenNothingStaged() throws IOException {
        assertNull(PendingApplier.findStaged(new SyncPaths(gameDir), SHA_B));
        assertNull(PendingApplier.findStaged(new SyncPaths(gameDir), null));
    }

    @Test
    void runWritesResultAndClearsPending() throws IOException {
        byte[] content = "v2".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("a.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "v1");

        Path pendingFile = new SyncPaths(gameDir).pendingFile();
        Files.createDirectories(pendingFile.getParent());
        Files.writeString(pendingFile, PendingApplier.writePending(List.of(
                new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", sha, "t"))));

        int code = ApplyHelper.run(gameDir);

        assertEquals(0, code);
        assertEquals("v2", Files.readString(target));
        assertFalse(Files.exists(pendingFile), "全部应用后 pending.json 应删除");
        HelperResult result = HelperResult.parse(Files.readString(
                HelperLauncherPaths.resultFile(gameDir)));
        assertEquals(List.of("mods/a.jar"), result.applied());
        assertEquals(0, result.remaining());
    }

    @Test
    void runKeepsRemainingPending() throws IOException {
        byte[] content = "v2".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("a.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "v1");

        Path pendingFile = new SyncPaths(gameDir).pendingFile();
        Files.createDirectories(pendingFile.getParent());
        Files.writeString(pendingFile, PendingApplier.writePending(List.of(
                new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", sha, "t"))));

        // 用一个「永远锁定」的探针跑一遍 apply，模拟 helper 时机太早的情况
        PendingApplier.Report report = PendingApplier.apply(gameDir,
                PendingApplier.readPending(pendingFile), file -> true);
        assertEquals(1, report.remaining().size());
        assertEquals("v1", Files.readString(target));
    }

    @Test
    void runWithNoPendingWritesEmptyResult() throws IOException {
        int code = ApplyHelper.run(gameDir);
        assertEquals(0, code);
        HelperResult result = HelperResult.parse(Files.readString(
                HelperLauncherPaths.resultFile(gameDir)));
        assertFalse(result.didAnything());
        assertEquals(0, result.remaining());
    }

    @Test
    void applyIsNoOpWhenProbeIsNull() throws IOException {
        byte[] content = "z".getBytes(StandardCharsets.UTF_8);
        String sha = realSha(content);
        stage(sha, content);
        Path target = gameDir.resolve("mods").resolve("z.jar");
        Files.createDirectories(target.getParent());

        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/z.jar", sha, "t")), null);
        assertEquals(List.of("mods/z.jar"), report.applied());
    }

    @Test
    void rejectPathTraversal() {
        PendingApplier.Report report = PendingApplier.apply(gameDir,
                List.of(new PendingOp(PendingOp.Operation.DELETE, "mods/../../evil.jar", null, "t")),
                file -> false);
        assertEquals(1, report.failed().size());
    }

    /** 结果文件路径（与 HelperLauncher 保持一致，测试里不依赖 Minecraft 类）。 */
    private static final class HelperLauncherPaths {
        static Path resultFile(Path gameDir) {
            return new SyncPaths(gameDir).root().resolve("pending").resolve("helper-result.jsonl");
        }
    }
}
