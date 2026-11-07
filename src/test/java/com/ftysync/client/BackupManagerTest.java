package com.ftysync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    @TempDir
    Path gameDir;

    @Test
    void backupCopiesFileAndKeepsOriginal() throws Exception {
        Path target = gameDir.resolve("config/a.cfg");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "v1", StandardCharsets.UTF_8);

        Path backup = new BackupManager(gameDir).backup("g1", "config/a.cfg");

        assertEquals(gameDir.resolve("ftyupater/backup/g1/config/a.cfg"), backup);
        assertEquals("v1", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.exists(target), "备份不移动原文件");
    }

    @Test
    void backupMissingFileReturnsNull() throws Exception {
        assertNull(new BackupManager(gameDir).backup("g1", "config/missing.cfg"));
    }

    @Test
    void trashMovesFile() throws Exception {
        Path target = gameDir.resolve("mods/x.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "junk");

        Path trashed = new BackupManager(gameDir).trash("t1", "mods/x.jar");

        assertEquals(gameDir.resolve("ftyupater/trash/t1/mods/x.jar"), trashed);
        assertArrayEquals("junk".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(trashed));
        assertFalse(Files.exists(target), "回收站是移动而不是复制");
    }

    @Test
    void trashMissingFileReturnsNull() throws Exception {
        assertNull(new BackupManager(gameDir).trash("t1", "mods/missing.jar"));
    }
}
