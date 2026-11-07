package com.ftysync.server;

import com.ftysync.core.PackManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 基于磁盘的 {@link SyncPackRepository}：读 journal + generations/ + objects/。
 */
public final class FilePackRepository implements SyncPackRepository {

    private final ContentStore store;
    private final GenerationStore generations;
    private final GenerationJournal journal;
    private final int retainGenerations;

    public FilePackRepository(ContentStore store, GenerationStore generations,
                              GenerationJournal journal, int retainGenerations) {
        this.store = store;
        this.generations = generations;
        this.journal = journal;
        this.retainGenerations = Math.max(1, retainGenerations);
    }

    @Override
    public Optional<PackManifest> currentManifest() {
        try {
            Optional<GenerationJournal.Entry> head = journal.head();
            return head.isPresent() ? readManifest(head.get().generation()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<PackManifest> manifest(String generation) {
        try {
            return readManifest(generation);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<GenerationJournal.Entry> retainedGenerations() {
        try {
            List<GenerationJournal.Entry> all = journal.entries();
            int from = Math.max(0, all.size() - retainGenerations);
            List<GenerationJournal.Entry> retained = new ArrayList<>(all.subList(from, all.size()));
            Collections.reverse(retained);
            return List.copyOf(retained);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Path> objectPath(String sha256) {
        try {
            return store.has(sha256) ? Optional.of(store.objectPath(sha256)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<PackManifest> readManifest(String generation) throws IOException {
        return generations.readManifest(generation).map(PackManifest::parse);
    }
}
