package com.ftysync.client;

import com.ftysync.core.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingQueueTest {

    @TempDir
    Path tmp;

    private static final String SHA = Hash.sha256("pending".getBytes());

    @Test
    void appendThenLoadRoundtrip() {
        Path file = tmp.resolve("pending/pending.json");
        PendingQueue queue = new PendingQueue(file);
        queue.append(List.of(
                new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", SHA, "2026-01-01T00:00:00Z"),
                new PendingOp(PendingOp.Operation.DELETE, "mods/b.jar", null, "2026-01-01T00:00:00Z")));

        List<PendingOp> loaded = new PendingQueue(file).load();

        assertEquals(2, loaded.size());
        assertEquals(PendingOp.Operation.REPLACE, loaded.get(0).operation());
        assertEquals("mods/a.jar", loaded.get(0).path());
        assertEquals(SHA, loaded.get(0).sha256());
        assertEquals(PendingOp.Operation.DELETE, loaded.get(1).operation());
        assertNull(loaded.get(1).sha256());
    }

    @Test
    void saveReplacesExistingContent() {
        Path file = tmp.resolve("pending/pending.json");
        PendingQueue queue = new PendingQueue(file);
        queue.save(List.of(new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", SHA, "t1")));
        queue.save(List.of(new PendingOp(PendingOp.Operation.DELETE, "mods/c.jar", null, "t2")));

        List<PendingOp> loaded = new PendingQueue(file).load();

        assertEquals(1, loaded.size());
        assertEquals("mods/c.jar", loaded.get(0).path());
    }

    @Test
    void corruptFileYieldsEmptyList() throws Exception {
        Path file = tmp.resolve("pending/pending.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not a json array");

        assertTrue(new PendingQueue(file).load().isEmpty());
    }
}
