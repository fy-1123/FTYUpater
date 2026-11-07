package com.ftysync.client;

import com.ftysync.core.Hash;
import com.ftysync.core.LocalFile;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalScannerTest {

    @TempDir
    Path gameDir;

    private Path write(String relative, String content) throws IOException {
        Path file = gameDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static PackEntry entry(Path gameDir, String relative, PackEntry.Origin origin, PackEntry.Role role)
            throws IOException {
        Path file = gameDir.resolve(relative);
        return new PackEntry(relative, Hash.sha256(file), Files.size(file), origin, role);
    }

    private static PackManifest manifest(PackEntry... entries) {
        return new PackManifest("fty", "gen-1", null, "2026-10-21T00:00:00Z",
                "1.20.1", "forge-47.4.0", List.of(entries));
    }

    @Test
    void declaredExistingFilesArePresent() throws IOException {
        write("mods/a.jar", "AAA");
        write("config/example.cfg", "CFG");
        PackManifest manifest = manifest(
                entry(gameDir, "mods/a.jar", PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED),
                entry(gameDir, "config/example.cfg", PackEntry.Origin.CLIENTPACK, PackEntry.Role.OPTIONAL));

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest);

        assertEquals(2, result.present().size());
        LocalFile a = result.present().get("mods/a.jar");
        assertEquals(Hash.sha256(gameDir.resolve("mods/a.jar")), a.sha256());
        assertEquals(3L, a.size());
        assertTrue(result.present().containsKey("config/example.cfg"));
        assertTrue(result.manualDisabled().isEmpty());
    }

    @Test
    void missingDeclaredFileIsNotPresent() throws IOException {
        write("mods/a.jar", "AAA");
        PackManifest manifest = manifest(
                entry(gameDir, "mods/a.jar", PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED),
                new PackEntry("mods/missing.jar", "0".repeat(64), 10, PackEntry.Origin.SERVER_MODS,
                        PackEntry.Role.REQUIRED));

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest);

        assertFalse(result.present().containsKey("mods/missing.jar"));
        assertTrue(result.present().containsKey("mods/a.jar"));
    }

    @Test
    void extraJarInModsIsPresentForCleanup() throws IOException {
        write("mods/extra.jar", "EXTRA");
        PackManifest manifest = manifest();

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest);

        assertTrue(result.present().containsKey("mods/extra.jar"));
        assertEquals(5L, result.present().get("mods/extra.jar").size());
    }

    @Test
    void nonJarFilesInModsAreIgnored() throws IOException {
        write("mods/notes.txt", "NOTES");
        write("mods/sub/nested.jar", "NESTED");

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest());

        assertFalse(result.present().containsKey("mods/notes.txt"));
        assertFalse(result.present().containsKey("mods/sub/nested.jar"));
    }

    @Test
    void disabledFileIsManualDisabledNotPresent() throws IOException {
        write("mods/off.jar.disabled", "OFF");
        PackManifest manifest = manifest(new PackEntry("mods/off.jar", "0".repeat(64), 1,
                PackEntry.Origin.SERVER_MODS, PackEntry.Role.REQUIRED));

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest);

        assertTrue(result.manualDisabled().contains("mods/off.jar"));
        assertFalse(result.present().containsKey("mods/off.jar"));
    }

    @Test
    void nestedClientpackPathsAreScanned() throws IOException {
        write("config/mods/deep/client.jar", "DEEP");
        PackManifest manifest = manifest(
                entry(gameDir, "config/mods/deep/client.jar", PackEntry.Origin.CLIENTPACK, PackEntry.Role.OPTIONAL));

        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest);

        assertTrue(result.present().containsKey("config/mods/deep/client.jar"));
    }

    @Test
    void missingModsDirectoryIsNotAnError() throws IOException {
        LocalScanner.ScanResult result = LocalScanner.scan(gameDir, manifest());

        assertTrue(result.present().isEmpty());
        assertTrue(result.manualDisabled().isEmpty());
        assertNull(result.present().get("mods/anything.jar"));
    }
}
