package com.ftysync.server;

import com.ftysync.core.Blacklist;
import com.ftysync.core.Hash;
import com.ftysync.core.PackManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackPublisherTest {

    @TempDir
    Path gameDir;

    private Path storeDir;
    private ContentScanner scanner;
    private ContentStore store;
    private GenerationStore generations;
    private GenerationJournal journal;

    @BeforeEach
    void setUp() {
        storeDir = gameDir.resolve("ftyupater");
        scanner = new ContentScanner(gameDir, Blacklist.empty(), Set.of("own.jar"));
        store = new ContentStore(storeDir);
        generations = new GenerationStore(storeDir);
        journal = new GenerationJournal(storeDir.resolve("journal.jsonl"));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = gameDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private PackPublisher publisher(int retainGenerations) {
        return new PackPublisher(scanner, store, generations, journal,
                "fty", "1.20.1", "forge-47.4.0", retainGenerations);
    }

    @Test
    void firstPublishCreatesGenerationWithStoredObjects() throws IOException {
        write("mods/a.jar", "AAA");
        write("clientpack/config/x.toml", "X");

        PackPublisher.PublishResult result = publisher(2).publish();

        assertTrue(result.newGeneration());
        assertEquals(2, result.manifest().size());
        assertTrue(result.manifest().parentGeneration().isEmpty());
        assertEquals(1, journal.entries().size());
        assertEquals(result.manifest().generation(), journal.head().orElseThrow().generation());
        assertTrue(store.has(Hash.sha256("AAA".getBytes(StandardCharsets.UTF_8))));
        assertTrue(store.has(Hash.sha256("X".getBytes(StandardCharsets.UTF_8))));
        assertTrue(generations.exists(result.manifest().generation()));
    }

    @Test
    void manifestRoundTripsThroughProtocolJson() throws IOException {
        write("mods/a.jar", "AAA");
        write("clientpack/config/x.toml", "X");

        PackManifest manifest = publisher(2).publish().manifest();
        String json = generations.readManifest(manifest.generation()).orElseThrow();
        PackManifest parsed = PackManifest.parse(json);

        assertEquals(manifest.generation(), parsed.generation());
        assertEquals(2, parsed.size());
        assertEquals(manifest.toJson(), parsed.toJson());
    }

    @Test
    void unchangedContentDoesNotCreateNewGeneration() throws IOException {
        write("mods/a.jar", "AAA");

        PackPublisher.PublishResult first = publisher(2).publish();
        PackPublisher.PublishResult second = publisher(2).publish();

        assertTrue(first.newGeneration());
        assertFalse(second.newGeneration());
        assertEquals(first.manifest().generation(), second.manifest().generation());
        assertEquals(1, journal.entries().size());
    }

    @Test
    void changedContentCreatesChildGeneration() throws IOException {
        write("mods/a.jar", "AAA");
        PackPublisher.PublishResult first = publisher(2).publish();

        write("mods/a.jar", "BBB");
        PackPublisher.PublishResult second = publisher(2).publish();

        assertTrue(second.newGeneration());
        assertEquals(first.manifest().generation(),
                second.manifest().parentGeneration().orElseThrow());
        assertEquals(2, journal.entries().size());
    }

    @Test
    void generationsBeyondRetentionArePruned() throws IOException {
        write("mods/a.jar", "v1");
        String gen1 = publisher(2).publish().manifest().generation();
        write("mods/a.jar", "v2");
        String gen2 = publisher(2).publish().manifest().generation();
        write("mods/a.jar", "v3");
        String gen3 = publisher(2).publish().manifest().generation();

        assertFalse(generations.exists(gen1));
        assertTrue(generations.exists(gen2));
        assertTrue(generations.exists(gen3));
        assertEquals(3, journal.entries().size(), "journal 只追加，历史不丢");

        FilePackRepository repository = new FilePackRepository(store, generations, journal, 2);
        List<GenerationJournal.Entry> retained = repository.retainedGenerations();
        assertEquals(List.of(gen3, gen2), retained.stream().map(GenerationJournal.Entry::generation).toList());
    }

    @Test
    void emptyContentStillPublishesFirstGenerationOnce() throws IOException {
        PackPublisher.PublishResult first = publisher(2).publish();
        PackPublisher.PublishResult second = publisher(2).publish();

        assertTrue(first.newGeneration());
        assertEquals(0, first.manifest().size());
        assertFalse(second.newGeneration());
        assertEquals(1, journal.entries().size());
    }
}
