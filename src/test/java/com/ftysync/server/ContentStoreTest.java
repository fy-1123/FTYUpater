package com.ftysync.server;

import com.ftysync.core.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentStoreTest {

    @TempDir
    Path tmp;

    @Test
    void putCopiesIntoObjectsNamedByHash() throws IOException {
        Path source = tmp.resolve("a.jar");
        Files.writeString(source, "AAA", StandardCharsets.UTF_8);
        String sha = Hash.sha256("AAA".getBytes(StandardCharsets.UTF_8));
        ContentStore store = new ContentStore(tmp.resolve("store"));

        assertFalse(store.has(sha));
        Path stored = store.put(source, sha);

        assertTrue(store.has(sha));
        assertEquals(sha, stored.getFileName().toString());
        assertEquals("AAA", Files.readString(stored, StandardCharsets.UTF_8));
        assertEquals(sha, Hash.sha256(stored));
    }

    @Test
    void putIsIdempotentAndLeavesNoTempFiles() throws IOException {
        Path source = tmp.resolve("a.jar");
        Files.writeString(source, "AAA", StandardCharsets.UTF_8);
        String sha = Hash.sha256("AAA".getBytes(StandardCharsets.UTF_8));
        ContentStore store = new ContentStore(tmp.resolve("store"));

        store.put(source, sha);
        store.put(source, sha);

        try (Stream<Path> children = Files.list(store.objectsDir())) {
            assertEquals(1, children.count());
        }
    }

    @Test
    void objectPathRejectsInvalidSha() {
        ContentStore store = new ContentStore(tmp.resolve("store"));
        assertThrows(IllegalArgumentException.class, () -> store.objectPath("not-a-hash"));
    }
}
