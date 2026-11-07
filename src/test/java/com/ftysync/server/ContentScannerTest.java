package com.ftysync.server;

import com.ftysync.core.Blacklist;
import com.ftysync.core.Hash;
import com.ftysync.core.PackEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentScannerTest {

    @TempDir
    Path gameDir;

    private void write(String relativePath, String content) throws IOException {
        Path file = gameDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private List<ScannedFile> scan(Blacklist blacklist, Set<String> excluded) throws IOException {
        return new ContentScanner(gameDir, blacklist, excluded).scan();
    }

    @Test
    void serverModsBecomeRequiredEntries() throws IOException {
        write("mods/a.jar", "AAA");
        write("mods/readme.txt", "not a mod");
        write("mods/b.jar.disabled", "disabled by admin");

        List<ScannedFile> files = scan(Blacklist.empty(), Set.of());

        assertEquals(1, files.size());
        ScannedFile file = files.get(0);
        assertEquals("mods/a.jar", file.path());
        assertEquals(PackEntry.Origin.SERVER_MODS, file.origin());
        assertEquals(PackEntry.Role.REQUIRED, file.role());
        assertEquals(Hash.sha256("AAA".getBytes(StandardCharsets.UTF_8)), file.sha256());
        assertEquals(3L, file.size());
        assertEquals(gameDir.resolve("mods/a.jar"), file.source());
    }

    @Test
    void clientpackIsScannedRecursivelyAsOptional() throws IOException {
        write("mods/a.jar", "A");
        write("clientpack/mods/opt.jar", "OPT");
        write("clientpack/config/mod.toml", "CFG");
        write("clientpack/resourcepacks/pack.zip", "RP");
        write("clientpack/shaderpacks/s.zip", "SH");

        List<ScannedFile> files = scan(Blacklist.empty(), Set.of());

        assertEquals(5, files.size());
        for (ScannedFile file : files) {
            if (file.path().startsWith("mods/") && file.path().equals("mods/a.jar")) {
                assertEquals(PackEntry.Origin.SERVER_MODS, file.origin());
            } else {
                assertEquals(PackEntry.Origin.CLIENTPACK, file.origin());
                assertEquals(PackEntry.Role.OPTIONAL, file.role());
            }
        }
        assertTrue(files.stream().anyMatch(f -> f.path().equals("config/mod.toml")));
        assertTrue(files.stream().anyMatch(f -> f.path().equals("resourcepacks/pack.zip")));
        assertTrue(files.stream().anyMatch(f -> f.path().equals("shaderpacks/s.zip")));
    }

    @Test
    void filesAreSortedByPath() throws IOException {
        write("mods/b.jar", "B");
        write("mods/a.jar", "A");
        write("clientpack/config/x.toml", "X");

        List<String> paths = scan(Blacklist.empty(), Set.of()).stream().map(ScannedFile::path).toList();

        assertEquals(List.of("config/x.toml", "mods/a.jar", "mods/b.jar"), paths);
    }

    @Test
    void blacklistAndOwnJarAreExcluded() throws IOException {
        write("mods/keep.jar", "K");
        write("mods/server-only.jar", "S");
        write("clientpack/mods/own.jar", "O");

        List<ScannedFile> files = scan(Blacklist.of(List.of("server-only.jar")), Set.of("own.jar"));

        assertEquals(List.of("mods/keep.jar"), files.stream().map(ScannedFile::path).toList());
    }

    @Test
    void clientpackCannotOverrideServerModOfSamePath() throws IOException {
        write("mods/a.jar", "SERVER");
        write("clientpack/mods/a.jar", "CLIENT");

        List<ScannedFile> files = scan(Blacklist.empty(), Set.of());

        assertEquals(1, files.size());
        assertEquals(PackEntry.Origin.SERVER_MODS, files.get(0).origin());
        assertEquals(Hash.sha256("SERVER".getBytes(StandardCharsets.UTF_8)), files.get(0).sha256());
    }

    @Test
    void missingDirectoriesYieldEmptyResult() throws IOException {
        assertTrue(scan(Blacklist.empty(), Set.of()).isEmpty());
    }

    @Test
    void hiddenAndTempFilesAreSkipped() throws IOException {
        write("clientpack/.hidden", "h");
        write("clientpack/mods/x.jar.part", "p");
        write("clientpack/config/c.toml", "c");

        List<String> paths = scan(Blacklist.empty(), Set.of()).stream().map(ScannedFile::path).toList();

        assertEquals(List.of("config/c.toml"), paths);
    }
}
