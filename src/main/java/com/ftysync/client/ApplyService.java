package com.ftysync.client;

import com.ftysync.core.DiffResult;
import com.ftysync.core.PathGuard;
import com.ftysync.core.SyncAction;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把同步计划落到磁盘：能立即应用的立刻应用，被占用的写入 pending 等重启后应用。
 */
public final class ApplyService {
    private static final DateTimeFormatter TRASH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path gameDir;
    private final SyncPaths paths;
    private final LockProbe lockProbe;
    private final BackupManager backups;

    public ApplyService(Path gameDir, LockProbe lockProbe) {
        this.gameDir = gameDir;
        this.paths = new SyncPaths(gameDir);
        this.lockProbe = lockProbe == null ? LockProbe.moveProbe() : lockProbe;
        this.backups = new BackupManager(gameDir);
    }

    /**
     * @param generation  本次同步对应的服务器代际
     * @param diff        同步计划
     * @param stagedFiles 已下载并校验的文件：sha256 → 暂存文件路径
     */
    public ApplyResult apply(String generation, DiffResult diff, Map<String, Path> stagedFiles) {
        List<String> applied = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<PendingOp> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (diff == null) {
            return new ApplyResult(applied, deleted, pending, failed, errors);
        }

        Map<String, Path> staged = stagedFiles == null ? Map.of() : stagedFiles;
        String now = Instant.now().toString();
        String trashTime = TRASH_TIME.format(Instant.now().atZone(ZoneOffset.UTC));

        for (SyncAction action : diff.actions()) {
            String path = action.path();
            try {
                switch (action.action()) {
                    case INSTALL_REQUIRED, INSTALL_OPTIONAL, UPDATE ->
                            applyFile(generation, action, staged, now, pending, applied, failed, errors);
                    case DELETE -> applyDelete(path, trashTime, now, pending, deleted);
                    default -> {
                        // KEEP / OPT_OUT / IGNORED / MANUAL_DISABLED / PENDING：不需要落盘
                    }
                }
            } catch (IOException | RuntimeException e) {
                failed.add(path);
                errors.add(path + ": " + describe(e));
            }
        }
        return new ApplyResult(applied, deleted, pending, failed, errors);
    }

    private void applyFile(String generation, SyncAction action, Map<String, Path> staged, String now,
                           List<PendingOp> pending, List<String> applied, List<String> failed, List<String> errors) throws IOException {
        String path = action.path();
        String toSha = action.toSha256();
        if (toSha == null) {
            failed.add(path);
            errors.add(path + ": 缺少目标哈希");
            return;
        }
        Path stagedFile = staged.get(toSha);
        if (stagedFile == null || !Files.isRegularFile(stagedFile)) {
            failed.add(path);
            errors.add(path + ": 暂存文件不存在");
            return;
        }
        Path target = PathGuard.resolve(gameDir, path);
        if (Files.exists(target) && lockProbe.isLocked(target)) {
            pending.add(new PendingOp(PendingOp.Operation.REPLACE, path, toSha, now));
            return;
        }
        if (Files.exists(target)) {
            backups.backup(generation, path);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try {
            copyReplace(stagedFile, target);
        } catch (IOException e) {
            if (Files.exists(target) && lockProbe.isLocked(target)) {
                pending.add(new PendingOp(PendingOp.Operation.REPLACE, path, toSha, now));
                return;
            }
            throw e;
        }
        applied.add(path);
    }

    private void applyDelete(String path, String trashTime, String now,
                             List<PendingOp> pending, List<String> deleted) throws IOException {
        Path target = PathGuard.resolve(gameDir, path);
        if (!Files.exists(target)) {
            return;
        }
        if (lockProbe.isLocked(target)) {
            pending.add(new PendingOp(PendingOp.Operation.DELETE, path, null, now));
            return;
        }
        backups.trash(trashTime, path);
        deleted.add(path);
    }

    private static void copyReplace(Path source, Path target) throws IOException {
        // 暂存文件可能被多个目标路径共用（服务器清单里同一内容的多个副本），
        // 所以这里复制而不是移走；先写临时文件再原子改名，避免半截文件被读到。
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("目标路径没有父目录: " + target);
        }
        Path tmp = parent.resolve(target.getFileName() + ".fty-" + System.nanoTime() + ".part");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public SyncPaths paths() {
        return paths;
    }

    public record ApplyResult(List<String> applied,
                              List<String> deleted,
                              List<PendingOp> pending,
                              List<String> failed,
                              List<String> errors) {
        public ApplyResult {
            applied = applied == null ? List.of() : List.copyOf(applied);
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
            pending = pending == null ? List.of() : List.copyOf(pending);
            failed = failed == null ? List.of() : List.copyOf(failed);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean requiresRestart() {
            return !pending.isEmpty();
        }
    }
}

