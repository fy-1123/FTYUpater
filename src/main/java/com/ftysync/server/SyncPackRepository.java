package com.ftysync.server;

import com.ftysync.core.PackManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * HTTP 层只读访问清单与对象文件的接口（实现见 {@link FilePackRepository}）。
 */
public interface SyncPackRepository {

    /** 当前代际清单；没有代际时为空。 */
    Optional<PackManifest> currentManifest();

    /** 指定代际清单（仍在保留期时）。 */
    Optional<PackManifest> manifest(String generation);

    /** 保留期内的代际，新 → 旧。 */
    List<GenerationJournal.Entry> retainedGenerations();

    /** 对象文件路径（调用方用 {@code Files.exists} 兜底）。 */
    Optional<Path> objectPath(String sha256);
}
