package com.ftysync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationJournalTest {

    @TempDir
    Path tmp;

    @Test
    void appendAndReadRoundTrip() throws IOException {
        GenerationJournal journal = new GenerationJournal(tmp.resolve("journal.jsonl"));

        assertTrue(journal.entries().isEmpty());
        assertTrue(journal.head().isEmpty());

        journal.append(new GenerationJournal.Entry("g1", null, "h1", "2026-01-01T00:00:00Z", 2));
        journal.append(new GenerationJournal.Entry("g2", "g1", "h2", "2026-01-01T00:01:00Z", 3));

        List<GenerationJournal.Entry> entries = journal.entries();
        assertEquals(2, entries.size());
        assertEquals("g2", journal.head().orElseThrow().generation());
        assertEquals("g1", entries.get(0).generation());
        assertEquals("g1", entries.get(1).parentGeneration());
    }

    @Test
    void corruptLinesAreSkippedInsteadOfFatal() throws IOException {
        Path file = tmp.resolve("journal.jsonl");
        Files.writeString(file, "not json at all\n\n"
                + "{\"generation\":\"g1\",\"contentHash\":\"h1\",\"createdAt\":\"t\",\"files\":1}\n",
                StandardCharsets.UTF_8);
        GenerationJournal journal = new GenerationJournal(file);

        List<GenerationJournal.Entry> entries = journal.entries();

        assertEquals(1, entries.size());
        assertEquals("g1", entries.get(0).generation());
        assertEquals(Optional.of("g1"), journal.head().map(GenerationJournal.Entry::generation));
    }

    @Test
    void journalFileIsCreatedOnFirstAppend() throws IOException {
        Path file = tmp.resolve("nested/dir/journal.jsonl");
        GenerationJournal journal = new GenerationJournal(file);

        journal.append(new GenerationJournal.Entry("g1", null, "h1", "t", 0));

        assertTrue(Files.exists(file));
    }
}
