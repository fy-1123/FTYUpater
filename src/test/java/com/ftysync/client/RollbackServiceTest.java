package com.ftysync.client;

import com.ftysync.core.LocalState;
import com.ftysync.core.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RollbackService} 的测试（M5-3）：本地备份还原是真写文件，用临时目录跑。
 */
class RollbackServiceTest {

    @TempDir
    Path gameDir;

    private Path backupFile(String generation, String relative, String content) throws IOException {
        Path file = new SyncPaths(gameDir).backupDir(generation).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path gameFile(String relative, String content) throws IOException {
        Path file = gameDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String read(String relative) throws IOException {
        return Files.readString(gameDir.resolve(relative), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ 列备份

    @Test
    void listsNothingWhenNoBackupDir() {
        assertTrue(RollbackService.listLocalBackups(gameDir).isEmpty());
    }

    @Test
    void listsBackupsNewestFirst() throws IOException {
        backupFile("g2", "mods/a.jar", "a");
        backupFile("g10", "mods/b.jar", "b");
        List<String> list = RollbackService.listLocalBackups(gameDir);
        assertEquals(List.of("g2", "g10"), list, "字符串倒序（代际 id 带时间戳，倒序即最新在前）");
    }

    @Test
    void countsFilesInBackup() throws IOException {
        backupFile("g2", "mods/a.jar", "a");
        backupFile("g2", "config/x.toml", "x");
        assertEquals(2, RollbackService.countBackupFiles(gameDir, "g2"));
        assertEquals(0, RollbackService.countBackupFiles(gameDir, "nope"));
    }

    // ------------------------------------------------------------ 还原

    @Test
    void restoresFilesFromBackup() throws IOException {
        gameFile("mods/a.jar", "NEW");
        gameFile("config/x.toml", "NEW");
        backupFile("g3", "mods/a.jar", "OLD");
        backupFile("g3", "config/x.toml", "OLD");

        RollbackService.RollbackResult result =
                RollbackService.restoreFromBackup(gameDir, "g3", "g2");

        assertTrue(result.ok(), "无失败：" + result.errors());
        assertEquals(2, result.restored());
        assertEquals("OLD", read("mods/a.jar"));
        assertEquals("OLD", read("config/x.toml"));
        assertTrue(result.incomplete(), "本地兜底必须标记为不完整");
    }

    @Test
    void restoreCreatesMissingTargetDirectories() throws IOException {
        backupFile("g3", "shaderpacks/new/pack.zip", "DATA");
        RollbackService.RollbackResult result =
                RollbackService.restoreFromBackup(gameDir, "g3", "g2");
        assertTrue(result.ok(), result.errors().toString());
        assertEquals("DATA", read("shaderpacks/new/pack.zip"));
    }

    @Test
    void restoreLeavesNoPartFilesBehind() throws IOException {
        backupFile("g3", "mods/a.jar", "OLD");
        gameFile("mods/a.jar", "NEW");
        RollbackService.restoreFromBackup(gameDir, "g3", "g2");
        try (var stream = Files.list(gameDir.resolve("mods"))) {
            List<String> names = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("a.jar"), names, "临时 .part 文件必须清掉：" + names);
        }
    }

    @Test
    void missingBackupDirReportsError() {
        RollbackService.RollbackResult result =
                RollbackService.restoreFromBackup(gameDir, "nope", "g2");
        assertFalse(result.ok());
        assertTrue(result.errors().get(0).contains("备份目录不存在"), result.errors().get(0));
    }

    // ------------------------------------------------------------ 代际回退

    @Test
    void rewindsStateGenerationAndRemembersWhereWeWere() throws IOException {
        SyncPaths paths = new SyncPaths(gameDir);
        StateStore store = new StateStore(paths.stateFile());
        LocalState state = new LocalState();
        state.advanceGeneration("g2");
        state.advanceGeneration("g3");
        store.save(state);

        backupFile("g3", "mods/a.jar", "OLD");
        RollbackService.restoreFromBackup(gameDir, "g3", "g2");

        LocalState after = store.load();
        assertEquals("g2", after.generation(), "当前代际应回退到 g2");
        assertEquals("g3", after.previousGeneration(), "再点一次回退要能反悔回 g3");
    }

    @Test
    void restoreSurvivesUnreadableBackupEntry() throws IOException {
        // 备份目录里放一个目录名与文件同名的情况不现实，这里只验「单个坏文件不影响其它」
        backupFile("g3", "mods/good.jar", "GOOD");
        gameFile("mods/good.jar", "NEW");
        // 人为制造一个非法相对路径的条目：文件名带控制字符在 Windows 上建不出来，
        // 所以改用「备份里有一个目标位置被目录占住」的场景
        Files.createDirectories(gameDir.resolve("mods/bad.jar"));
        backupFile("g3", "mods/bad.jar", "BAD");

        RollbackService.RollbackResult result =
                RollbackService.restoreFromBackup(gameDir, "g3", "g2");
        assertEquals(1, result.restored(), "好文件仍应还原成功");
        assertEquals(1, result.failed(), "坏文件应记为失败");
        assertFalse(result.ok());
        assertEquals("GOOD", read("mods/good.jar"));
    }
}
