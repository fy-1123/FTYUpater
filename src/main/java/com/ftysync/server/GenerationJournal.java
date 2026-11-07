package com.ftysync.server;

import com.ftysync.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 代际日志：{@code <storeDir>/journal.jsonl}，只追加不重写（见 docs/architecture.md）。
 */
public final class GenerationJournal {

    /** 一行日志 = 一个代际的摘要。 */
    public record Entry(String generation, String parentGeneration, String contentHash,
                        String createdAt, int files) {
    }

    private final Path journalFile;

    public GenerationJournal(Path journalFile) {
        this.journalFile = journalFile;
    }

    public Path journalFile() {
        return journalFile;
    }

    /** 全部代际（旧 → 新），损坏行跳过。 */
    public List<Entry> entries() throws IOException {
        if (!Files.isRegularFile(journalFile)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Entry entry = Json.GSON.fromJson(trimmed, Entry.class);
                if (entry != null && entry.generation() != null && !entry.generation().isBlank()) {
                    entries.add(entry);
                }
            } catch (RuntimeException ignored) {
                // 单行损坏不影响其它历史；损坏行保留原样便于排查
            }
        }
        return List.copyOf(entries);
    }

    /** 最新代际；没有则空。 */
    public Optional<Entry> head() throws IOException {
        List<Entry> entries = entries();
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }

    /** 追加一个代际。 */
    public void append(Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Path parent = journalFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = Json.COMPACT.toJson(entry) + System.lineSeparator();
        Files.writeString(journalFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }
}
