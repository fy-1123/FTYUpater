package com.ftysync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPathsTest {

    @TempDir
    Path gameDir;

    @Test
    void resolvesStandardLayout() {
        SyncPaths paths = new SyncPaths(gameDir);

        assertEquals(gameDir.resolve("ftyupater"), paths.root());
        assertEquals(gameDir.resolve("ftyupater/state.json"), paths.stateFile());
        assertEquals(gameDir.resolve("ftyupater/servers.json"), paths.serversFile());
        assertEquals(gameDir.resolve("ftyupater/pending/pending.json"), paths.pendingFile());
        assertEquals(gameDir.resolve("ftyupater/staging/g1"), paths.stagingDir("g1"));
        assertEquals(gameDir.resolve("ftyupater/backup/g1"), paths.backupDir("g1"));
        assertEquals(gameDir.resolve("ftyupater/trash/t1"), paths.trashDir("t1"));
        assertEquals(gameDir.resolve("ftyupater/logs/sync.log"), paths.logFile());
    }
}
