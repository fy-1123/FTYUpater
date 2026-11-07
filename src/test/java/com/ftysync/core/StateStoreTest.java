package com.ftysync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    private static final String SHA = Hash.sha256("state".getBytes(StandardCharsets.UTF_8));

    @Test
    void missingFileYieldsEmptyState(@TempDir Path dir) {
        StateStore store = new StateStore(dir.resolve("state.json"));
        LocalState state = store.load();
        assertEquals(LocalState.SCHEMA, state.schema());
        assertNull(state.generation());
        assertTrue(state.entries().isEmpty());
        assertTrue(state.optOut().isEmpty());
        assertTrue(state.ignored().isEmpty());
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) {
        StateStore store = new StateStore(dir.resolve("state.json"));
        LocalState state = new LocalState();
        state.setGeneration("g-20261021");
        state.setLastSync("2026-10-21T12:00:00Z");
        state.optOut().add("clientpack/mods/opt.jar");
        state.ignored().add("clientpack/config/x.json");
        state.markInstalled("mods/a.jar", SHA, 42);
        store.save(state);

        LocalState loaded = store.load();
        assertEquals("g-20261021", loaded.generation());
        assertEquals("2026-10-21T12:00:00Z", loaded.lastSync());
        assertTrue(loaded.optOut().contains("clientpack/mods/opt.jar"));
        assertTrue(loaded.ignored().contains("clientpack/config/x.json"));
        assertEquals(SHA, loaded.entries().get("mods/a.jar").sha256());
        assertEquals(42, loaded.entries().get("mods/a.jar").size());
    }

    @Test
    void corruptFileIsBackedUpAndRecovers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "这不是 JSON");
        StateStore store = new StateStore(file);

        LocalState state = store.load();
        assertNull(state.generation());
        assertTrue(Files.isDirectory(dir) && backupExists(dir), "应生成 .corrupt-* 备份");
        assertTrue(Files.exists(file) == false, "损坏文件应被移走，重新生成空状态");
    }

    @Test
    void wrongSchemaIsTreatedAsCorrupt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "{\"schema\":1,\"generation\":\"old\"}");
        StateStore store = new StateStore(file);

        LocalState state = store.load();
        assertNull(state.generation());
        assertTrue(backupExists(dir));
    }

    @Test
    void invalidEntryHashIsTreatedAsCorrupt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "{\"schema\":2,\"entries\":{\"mods/a.jar\":{\"sha256\":\"bad\",\"size\":1}}}");
        StateStore store = new StateStore(file);

        LocalState state = store.load();
        assertTrue(state.entries().isEmpty());
        assertTrue(backupExists(dir));
    }

    @Test
    void saveLeavesNoTempFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        StateStore store = new StateStore(file);
        store.save(new LocalState());

        try (Stream<Path> stream = Files.list(dir)) {
            assertEquals(1, stream.count());
        }
        assertTrue(Files.exists(file));
    }

    private static boolean backupExists(Path dir) throws Exception {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().contains(".corrupt-"));
        }
    }
}
