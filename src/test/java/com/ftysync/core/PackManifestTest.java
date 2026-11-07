package com.ftysync.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManifestTest {

    private static final String SHA_A = Hash.sha256("a".getBytes(StandardCharsets.UTF_8));
    private static final String SHA_B = Hash.sha256("b".getBytes(StandardCharsets.UTF_8));

    private static PackEntry entry(String path, String sha, PackEntry.Origin origin, PackEntry.Role role) {
        return new PackEntry(path, sha, 123, origin, role);
    }

    private static PackManifest sample() {
        return new PackManifest(
                "fty",
                "20261021-201800-1a2b3c4d",
                "20261020-120000-9f8e7d6c",
                "2026-10-21T12:18:00Z",
                "1.20.1",
                "forge-47.4.0",
                List.of(
                        entry("mods/jei.jar", SHA_A, PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED),
                        entry("config/sodium.json", SHA_B, PackEntry.Origin.CLIENTPACK, PackEntry.Role.OPTIONAL)
                )
        );
    }

    @Test
    void jsonRoundTripPreservesEverything() {
        PackManifest original = sample();
        PackManifest parsed = PackManifest.parse(original.toJson());

        assertEquals(original.packId(), parsed.packId());
        assertEquals(original.generation(), parsed.generation());
        assertEquals(original.parentGeneration(), parsed.parentGeneration());
        assertEquals(original.createdAt(), parsed.createdAt());
        assertEquals(original.minecraft(), parsed.minecraft());
        assertEquals(original.loader(), parsed.loader());
        assertEquals(2, parsed.size());
        assertEquals(original.entries(), parsed.entries());
        assertEquals(123, parsed.entry("mods/jei.jar").orElseThrow().size());
    }

    @Test
    void jsonUsesProtocolFieldNames() {
        String json = sample().toJson();
        assertTrue(json.contains("\"schema\": 2"));
        assertTrue(json.contains("\"server-mods\""));
        assertTrue(json.contains("\"clientpack\""));
        assertTrue(json.contains("\"required\""));
        assertTrue(json.contains("\"optional\""));
        assertTrue(json.contains("\"files\""));
    }

    @Test
    void lookupKeepsInsertionOrder() {
        PackManifest manifest = sample();
        assertEquals(List.of("mods/jei.jar", "config/sodium.json"),
                List.copyOf(manifest.entries().keySet()));
        assertTrue(manifest.entry("mods/jei.jar").isPresent());
        assertFalse(manifest.entry("mods/nope.jar").isPresent());
    }

    @Test
    void rejectsWrongSchema() {
        String json = sample().toJson().replace("\"schema\": 2", "\"schema\": 1");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }

    @Test
    void rejectsDuplicatePaths() {
        String json = sample().toJson().replace("config/sodium.json", "mods/jei.jar");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }

    @Test
    void rejectsTraversalPath() {
        String json = sample().toJson().replace("mods/jei.jar", "../evil.jar");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }

    @Test
    void rejectsBadSha256() {
        String json = sample().toJson().replace(SHA_A, "abc");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }

    @Test
    void rejectsMissingGeneration() {
        String json = sample().toJson().replace("\"generation\": \"20261021-201800-1a2b3c4d\"", "\"generation\": \"\"");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }

    @Test
    void rejectsNotJson() {
        assertThrows(ManifestException.class, () -> PackManifest.parse("这不是 JSON"));
    }

    @Test
    void rejectsManifestWithoutFiles() {
        String json = sample().toJson().replace("\"files\": [", "\"unused\": [");
        assertThrows(ManifestException.class, () -> PackManifest.parse(json));
    }
}
