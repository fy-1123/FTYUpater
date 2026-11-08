package com.ftysync.client;

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
 * {@link TrashBrowser} 的测试（M6-1）：回收站的列出与还原。
 */
class TrashBrowserTest {

    @TempDir
    Path gameDir;

    private Path trashFile(String timestamp, String relative, String content) throws IOException {
        Path file = new SyncPaths(gameDir).trashDir(timestamp).resolve(relative);
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

    // ------------------------------------------------------------ 列出

    @Test
    void emptyWhenNoTrashDir() {
        assertTrue(new TrashBrowser(gameDir).list().isEmpty());
        assertTrue(new TrashBrowser(gameDir).files("20261108-120000").isEmpty());
    }

    @Test
    void listsEntriesNewestFirst() throws IOException {
        trashFile("20261107-120000", "mods/a.jar", "a");
        trashFile("20261108-120000", "mods/b.jar", "bb");
        List<TrashBrowser.Entry> entries = new TrashBrowser(gameDir).list();
        assertEquals(2, entries.size());
        assertEquals("20261108-120000", entries.get(0).timestamp());
        assertEquals("20261107-120000", entries.get(1).timestamp());
        assertEquals(1, entries.get(0).files());
        assertEquals(2, entries.get(0).bytes());
    }

    @Test
    void listsFilesRecursively() throws IOException {
        trashFile("20261108-120000", "mods/a.jar", "a");
        trashFile("20261108-120000", "config/deep/x.toml", "x");
        List<String> files = new TrashBrowser(gameDir).files("20261108-120000");
        assertEquals(List.of("config/deep/x.toml", "mods/a.jar"), files);
    }

    @Test
    void entryDescribeMentionsCountAndSize() {
        TrashBrowser.Entry entry = new TrashBrowser.Entry("20261108-120000", 3, 2048);
        assertTrue(entry.describe().contains("3 个文件"), entry.describe());
        assertTrue(entry.describe().contains("KB"), entry.describe());
    }

    @Test
    void entryRejectsBlankTimestamp() {
        try {
            new TrashBrowser.Entry(" ", 1, 1);
            throw new AssertionError("应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timestamp"), expected.getMessage());
        }
    }

    // ------------------------------------------------------------ 还原

    @Test
    void restoresFilesBackIntoGameDir() throws IOException {
        trashFile("20261108-120000", "mods/a.jar", "CONTENT");
        TrashBrowser.RestoreResult result = new TrashBrowser(gameDir).restore("20261108-120000");
        assertTrue(result.ok(), result.errors().toString());
        assertEquals(1, result.restored());
        assertEquals("CONTENT", Files.readString(gameDir.resolve("mods/a.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void neverOverwritesExistingTarget() throws IOException {
        gameFile("mods/a.jar", "MINE");
        trashFile("20261108-120000", "mods/a.jar", "TRASHED");
        TrashBrowser.RestoreResult result = new TrashBrowser(gameDir).restore("20261108-120000");
        assertEquals(0, result.restored());
        assertEquals(1, result.skipped());
        assertEquals("MINE", Files.readString(gameDir.resolve("mods/a.jar"), StandardCharsets.UTF_8),
                "玩家自己放的同名文件不能被回收站冲掉");
    }

    @Test
    void missingEntryReportsError() {
        TrashBrowser.RestoreResult result = new TrashBrowser(gameDir).restore("nope");
        assertFalse(result.ok());
        assertTrue(result.errors().get(0).contains("回收站条目不存在"), result.errors().get(0));
    }

    @Test
    void createsMissingTargetDirectories() throws IOException {
        trashFile("20261108-120000", "shaderpacks/new/pack.zip", "ZIP");
        TrashBrowser.RestoreResult result = new TrashBrowser(gameDir).restore("20261108-120000");
        assertTrue(result.ok(), result.errors().toString());
        assertTrue(Files.isRegularFile(gameDir.resolve("shaderpacks/new/pack.zip")));
    }

    @Test
    void pruneEmptyRemovesFullyRestoredEntries() throws IOException {
        trashFile("20261108-120000", "mods/a.jar", "A");
        TrashBrowser browser = new TrashBrowser(gameDir);
        browser.restore("20261108-120000");
        assertEquals(1, browser.pruneEmpty(), "全部还原成功的时间戳目录应被清掉");
        assertTrue(browser.list().isEmpty());
    }

    @Test
    void pruneEmptyKeepsEntriesWithFilesLeft() throws IOException {
        trashFile("20261108-120000", "mods/a.jar", "A");
        gameFile("mods/a.jar", "MINE");
        TrashBrowser browser = new TrashBrowser(gameDir);
        browser.restore("20261108-120000");
        assertEquals(0, browser.pruneEmpty(), "被跳过的文件还在回收站里，目录不能删");
        assertEquals(1, browser.list().size());
    }

    @Test
    void restoreResultDescribeMentionsSkipped() {
        TrashBrowser.RestoreResult result = new TrashBrowser.RestoreResult(2, 1, List.of());
        assertTrue(result.describe().contains("跳过 1"), result.describe());
        assertTrue(result.ok());
    }

    @Test
    void restoreResultIsNotOkWhenErrorsExist() {
        TrashBrowser.RestoreResult result = new TrashBrowser.RestoreResult(0, 0, List.of("boom"));
        assertFalse(result.ok());
        assertTrue(result.describe().contains("失败 1"), result.describe());
    }
}
