package com.ftysync.helper;

import com.ftysync.client.BackupManager;
import com.ftysync.client.LockProbe;
import com.ftysync.client.PendingOp;
import com.ftysync.client.SyncPaths;
import com.ftysync.core.Hash;
import com.ftysync.core.PathGuard;

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
import java.util.stream.Stream;

/**
 * 应用 {@code pending.json} 里「重启后才能做」的操作。
 *
 * <p>运行在独立 JVM（{@link ApplyHelper}），此时游戏进程已经退出，原本被占用的 jar
 * 应该可以替换/删除了。逻辑与 {@link com.ftysync.client.ApplyService} 保持一致：
 * 替换前先备份、删除进回收站、失败的操作留在 pending 里等下次重试。</p>
 *
 * <p><b>只用 JDK</b>：不引用 Gson，也不引用任何 Minecraft/Forge 类。读取
 * {@code pending.json} 用 {@link MiniJson}。</p>
 */
public final class PendingApplier {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PendingApplier() {
    }

    /** 应用结果。 */
    public record Report(List<String> applied,
                         List<String> deleted,
                         List<String> failed,
                         List<String> errors,
                         List<PendingOp> remaining) {
        public Report {
            applied = applied == null ? List.of() : List.copyOf(applied);
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
            failed = failed == null ? List.of() : List.copyOf(failed);
            errors = errors == null ? List.of() : List.copyOf(errors);
            remaining = remaining == null ? List.of() : List.copyOf(remaining);
        }
    }

    /**
     * 读 pending.json。
     *
     * <p>文件不存在或格式不认识时返回空表——helper 绝不能因为读不懂就崩，
     * 那会把玩家的同步卡死在半路。</p>
     *
     * @param pendingFile {@code ftyupater/pending/pending.json}
     */
    public static List<PendingOp> readPending(Path pendingFile) {
        if (pendingFile == null || !Files.isRegularFile(pendingFile)) {
            return List.of();
        }
        String json;
        try {
            json = Files.readString(pendingFile);
        } catch (IOException e) {
            return List.of();
        }
        List<Map<String, String>> rows;
        try {
            rows = MiniJson.parseArrayOfObjects(json);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        List<PendingOp> operations = new ArrayList<>();
        for (Map<String, String> row : rows) {
            PendingOp op = toPendingOp(row);
            if (op != null) {
                operations.add(op);
            }
        }
        return List.copyOf(operations);
    }

    /**
     * 把操作序列化回 pending.json 的格式（供 helper 写回「剩下的」）。
     *
     * @param operations 剩余操作
     * @return JSON 文本（与 Gson 的 pretty 输出结构兼容，客户端仍可用 PendingQueue 读）
     */
    public static String writePending(List<PendingOp> operations) {
        List<PendingOp> ops = operations == null ? List.of() : operations;
        StringBuilder sb = new StringBuilder("[");
        if (!ops.isEmpty()) {
            sb.append('\n');
        }
        for (int i = 0; i < ops.size(); i++) {
            PendingOp op = ops.get(i);
            sb.append("  {\n");
            sb.append("    \"operation\": \"").append(op.operation().name()).append("\",\n");
            sb.append("    \"path\": \"").append(escape(op.path())).append("\",\n");
            sb.append("    \"sha256\": ").append(op.sha256() == null ? "null" : "\"" + op.sha256() + "\"").append(",\n");
            sb.append("    \"addedAt\": ").append(op.addedAt() == null ? "null" : "\"" + escape(op.addedAt()) + "\"").append('\n');
            sb.append("  }");
            if (i < ops.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 应用全部操作。
     *
     * @param gameDir   游戏目录
     * @param operations pending 操作
     * @param lockProbe 占用探测（null 用生产实现）
     */
    public static Report apply(Path gameDir, List<PendingOp> operations, LockProbe lockProbe) {
        List<PendingOp> ops = operations == null ? List.of() : operations;
        List<String> applied = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<PendingOp> remaining = new ArrayList<>();
        if (ops.isEmpty()) {
            return new Report(applied, deleted, failed, errors, remaining);
        }

        LockProbe probe = lockProbe == null ? LockProbe.moveProbe() : lockProbe;
        SyncPaths paths = new SyncPaths(gameDir);
        BackupManager backups = new BackupManager(gameDir);
        String trashTime = STAMP.format(Instant.now().atZone(ZoneOffset.UTC));
        String backupGeneration = "helper-" + trashTime;

        for (PendingOp op : ops) {
            try {
                switch (op.operation()) {
                    case REPLACE -> {
                        String error = replace(gameDir, paths, backups, backupGeneration, op, probe);
                        if (error == null) {
                            applied.add(op.path());
                        } else if (error.equals(LOCKED)) {
                            remaining.add(op);
                        } else {
                            failed.add(op.path());
                            errors.add(op.path() + ": " + error);
                        }
                    }
                    case DELETE -> {
                        String error = delete(gameDir, backups, trashTime, op, probe);
                        if (error == null) {
                            deleted.add(op.path());
                        } else if (error.equals(LOCKED)) {
                            remaining.add(op);
                        } else {
                            failed.add(op.path());
                            errors.add(op.path() + ": " + error);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                failed.add(op.path());
                errors.add(op.path() + ": " + describe(e));
            }
        }
        return new Report(applied, deleted, failed, errors, remaining);
    }

    /** 目标仍被占用时返回这个哨兵值，表示「不是失败，留到下次」。 */
    private static final String LOCKED = "\u0000locked";

    private static String replace(Path gameDir, SyncPaths paths, BackupManager backups,
                                  String backupGeneration, PendingOp op, LockProbe probe) throws IOException {
        Path target = PathGuard.resolve(gameDir, op.path());
        if (Files.exists(target) && probe.isLocked(target)) {
            return LOCKED;
        }
        Path staged = findStaged(paths, op.sha256());
        if (staged == null) {
            return "暂存文件不存在（staging 里找不到 " + op.sha256() + "），请重新同步";
        }
        String actual = Hash.sha256(staged);
        if (!actual.equals(op.sha256())) {
            return "暂存文件哈希不符：期望 " + op.sha256() + "，实际 " + actual;
        }
        if (Files.exists(target)) {
            backups.backup(backupGeneration, op.path());
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        copyReplace(staged, target);
        return null;
    }

    private static String delete(Path gameDir, BackupManager backups, String trashTime,
                                 PendingOp op, LockProbe probe) throws IOException {
        Path target = PathGuard.resolve(gameDir, op.path());
        if (!Files.exists(target)) {
            // 已经被删掉了，算成功（幂等）
            return null;
        }
        if (probe.isLocked(target)) {
            return LOCKED;
        }
        backups.trash(trashTime, op.path());
        return null;
    }

    /**
     * 在 {@code staging/} 下找哈希对应的暂存文件。
     *
     * <p>pending.json 不记录代际，所以按哈希在任意代际目录里找——同一个哈希的内容
     * 就是同一份文件，来自哪一代都无所谓。</p>
     */
    static Path findStaged(SyncPaths paths, String sha256) throws IOException {
        if (sha256 == null) {
            return null;
        }
        Path stagingRoot = paths.root().resolve("staging");
        if (!Files.isDirectory(stagingRoot)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(stagingRoot, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(sha256))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void copyReplace(Path source, Path target) throws IOException {
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

    private static PendingOp toPendingOp(Map<String, String> row) {
        String operation = row.get("operation");
        String path = row.get("path");
        String sha256 = row.get("sha256");
        String addedAt = row.get("addedAt");
        if (operation == null || path == null) {
            return null;
        }
        try {
            PendingOp.Operation kind = PendingOp.Operation.valueOf(operation.trim());
            return new PendingOp(kind, path, "null".equals(sha256) ? null : sha256, addedAt);
        } catch (IllegalArgumentException e) {
            // 不认识的 operation 或非法路径：跳过这一条，不让整个文件作废
            return null;
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
