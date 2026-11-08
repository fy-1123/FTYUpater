package com.ftysync.helper;

import com.ftysync.client.PendingOp;
import com.ftysync.client.SyncPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 独立 JVM 的「退出后应用器」：等游戏进程退出 → 应用 pending → 写结果文件。
 *
 * <p>为什么必须独立进程：Windows 上游戏运行时 mods/ 里的 jar 被锁住，替换/删除都失败。
 * 只能等主进程退出后，由一个残留的小进程完成收尾（ADR-008）。</p>
 *
 * <p>用法（由客户端在检测到 pending 时拉起）：</p>
 * <pre>
 * java -cp ftyupater.jar com.ftysync.helper.ApplyHelper &lt;gameDir&gt; &lt;parentPid&gt;
 * </pre>
 *
 * <p>退出码：0 = 全部成功或无事可做；1 = 有失败项（细节在 helper-result.jsonl）；
 * 2 = 参数错误。</p>
 *
 * <p><b>只用 JDK</b>：classpath 上只有模组 jar，不能假设 Gson / Minecraft 还在。</p>
 */
public final class ApplyHelper {

    /** 等父进程退出的最长时长：10 分钟。超时也照样尝试应用（可能已经能改了）。 */
    private static final Duration WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final long POLL_MILLIS = 500L;

    private ApplyHelper() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("用法: ApplyHelper <gameDir> [parentPid]");
            System.exit(2);
            return;
        }
        Path gameDir = Path.of(args[0]);
        long parentPid = parsePid(args.length > 1 ? args[1] : null);
        if (parentPid > 0) {
            waitForExit(parentPid);
        }
        int code = run(gameDir);
        System.exit(code);
    }

    /**
     * 执行一次应用（不退出 JVM，便于单测）。
     *
     * @param gameDir 游戏目录
     * @return 退出码语义：0 成功/无事，1 有失败
     */
    public static int run(Path gameDir) {
        SyncPaths paths = new SyncPaths(gameDir);
        Path pendingFile = paths.pendingFile();
        List<PendingOp> operations = PendingApplier.readPending(pendingFile);
        HelperResult result;
        try {
            if (operations.isEmpty()) {
                result = HelperResult.empty(Instant.now().toString());
            } else {
                PendingApplier.Report report = PendingApplier.apply(gameDir, operations, null);
                result = new HelperResult(
                        Instant.now().toString(),
                        report.applied(),
                        report.deleted(),
                        report.failed(),
                        report.errors(),
                        report.remaining().size());
                writePendingBack(pendingFile, report.remaining());
            }
        } catch (RuntimeException e) {
            result = new HelperResult(
                    Instant.now().toString(),
                    List.of(), List.of(),
                    List.of("<helper>"),
                    List.of("<helper>: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())),
                    operations.size());
        }
        writeResult(paths.root().resolve("pending").resolve("helper-result.jsonl"), result);
        return result.hasFailures() ? 1 : 0;
    }

    private static void writePendingBack(Path pendingFile, List<PendingOp> remaining) {
        try {
            if (remaining.isEmpty()) {
                Files.deleteIfExists(pendingFile);
                return;
            }
            Path parent = pendingFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = pendingFile.resolveSibling(pendingFile.getFileName() + ".tmp");
            Files.writeString(tmp, PendingApplier.writePending(remaining), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, pendingFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, pendingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 写不回去就保留原文件：下次启动还能重试，比丢掉操作安全
        }
    }

    private static void writeResult(Path resultFile, HelperResult result) {
        try {
            Path parent = resultFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resultFile, result.toJsonl(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 结果文件写不出来只能靠日志；不影响已完成的文件操作
            System.err.println("ApplyHelper 无法写结果文件: " + e.getMessage());
        }
    }

    private static long parsePid(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            long pid = Long.parseLong(raw.trim());
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 等指定进程退出。
     *
     * <p>用 {@link ProcessHandle} 轮询：比「按名字找 java 进程」可靠得多，不会误判同机的
     * 其它 JVM。进程已经不在时立刻返回。</p>
     */
    static void waitForExit(long pid) {
        ProcessHandle handle;
        try {
            handle = ProcessHandle.of(pid).orElse(null);
        } catch (RuntimeException e) {
            return;
        }
        if (handle == null || !handle.isAlive()) {
            return;
        }
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (handle.isAlive() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
