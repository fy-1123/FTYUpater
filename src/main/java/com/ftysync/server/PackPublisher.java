package com.ftysync.server;

import com.ftysync.core.Hash;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 扫描 → 存储对象 → 生成新代际 → 追加 journal。
 *
 * <p>内容不变则不新开代际（代际 ID 由时间 + 内容摘要组成）；内容变化时 parent 指向上一代，
 * 并清理超出保留数量的旧代际目录（journal 只追加，历史记录不丢）。</p>
 */
public final class PackPublisher {

    private static final DateTimeFormatter GENERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** 发布结果。 */
    public record PublishResult(PackManifest manifest, boolean newGeneration) {
    }

    private final ContentScanner scanner;
    private final ContentStore store;
    private final GenerationStore generations;
    private final GenerationJournal journal;
    private final String packId;
    private final String minecraft;
    private final String loader;
    private final int retainGenerations;

    public PackPublisher(ContentScanner scanner, ContentStore store, GenerationStore generations,
                         GenerationJournal journal, String packId, String minecraft, String loader,
                         int retainGenerations) {
        this.scanner = scanner;
        this.store = store;
        this.generations = generations;
        this.journal = journal;
        this.packId = packId;
        this.minecraft = minecraft;
        this.loader = loader;
        this.retainGenerations = Math.max(1, retainGenerations);
    }

    public PublishResult publish() throws IOException {
        List<ScannedFile> scanned = scanner.scan();
        String contentHash = contentHash(scanned);

        Optional<GenerationJournal.Entry> head = journal.head();
        if (head.isPresent() && contentHash.equals(head.get().contentHash())) {
            PackManifest current = readManifest(head.get().generation());
            if (current != null) {
                return new PublishResult(current, false);
            }
            // 清单文件缺失/损坏：走重建流程，生成新代际
        }

        for (ScannedFile file : scanned) {
            store.put(file.source(), file.sha256());
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String parent = head.map(GenerationJournal.Entry::generation).orElse(null);
        String generation = GENERATION_TIME.format(now) + "-" + contentHash.substring(0, 8);
        String createdAt = DateTimeFormatter.ISO_INSTANT.format(now);
        List<PackEntry> entries = scanned.stream().map(ScannedFile::toEntry).toList();
        PackManifest manifest = new PackManifest(packId, generation, parent, createdAt,
                minecraft, loader, entries);

        // 先写清单再写 journal：/pack 永远不会指向不存在的清单
        generations.writeManifest(generation, manifest.toJson());
        journal.append(new GenerationJournal.Entry(generation, parent, contentHash, createdAt, entries.size()));
        prune(journal.entries());
        return new PublishResult(manifest, true);
    }

    private PackManifest readManifest(String generation) {
        try {
            return generations.readManifest(generation)
                    .map(PackManifest::parse)
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void prune(List<GenerationJournal.Entry> entries) {
        int keepFrom = Math.max(0, entries.size() - retainGenerations);
        for (int i = 0; i < keepFrom; i++) {
            try {
                generations.delete(entries.get(i).generation());
            } catch (IOException ignored) {
                // 清理失败不影响发布；下次发布会再试
            }
        }
    }

    /** 内容摘要：路径 + 哈希 + 大小 + 来源 + 角色，排序后 SHA256。 */
    static String contentHash(List<ScannedFile> files) {
        StringBuilder canonical = new StringBuilder();
        for (ScannedFile file : files) {
            canonical.append(file.path()).append('\t')
                    .append(file.sha256()).append('\t')
                    .append(file.size()).append('\t')
                    .append(file.origin().name()).append('\t')
                    .append(file.role().name()).append('\n');
        }
        return Hash.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
}
