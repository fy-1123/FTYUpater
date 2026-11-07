package com.ftysync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSourceCacheTest {

    @TempDir
    Path tmp;

    @Test
    void unknownKeyIsEmpty() {
        ServerSourceCache cache = new ServerSourceCache(tmp.resolve("servers.json"));
        assertTrue(cache.get("mc.example.com:25565").isEmpty());
    }

    @Test
    void putPersistsAcrossInstances() {
        Path file = tmp.resolve("servers.json");
        new ServerSourceCache(file).put("mc.example.com:25565", "http://mc.example.com:8081");

        assertEquals(Optional.of("http://mc.example.com:8081"),
                new ServerSourceCache(file).get("mc.example.com:25565"));
    }

    @Test
    void putOverwritesPreviousValue() {
        Path file = tmp.resolve("servers.json");
        new ServerSourceCache(file).put("a", "http://one");
        new ServerSourceCache(file).put("a", "http://two");

        assertEquals(Optional.of("http://two"), new ServerSourceCache(file).get("a"));
    }

    @Test
    void corruptFileDegradesToEmpty() throws Exception {
        Path file = tmp.resolve("servers.json");
        Files.writeString(file, "{not valid json");

        assertTrue(new ServerSourceCache(file).get("a").isEmpty());
    }
}
