package com.ftysync.client;

import com.ftysync.core.DiffResult;
import com.ftysync.core.Hash;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import com.ftysync.core.SyncAction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 客户端同步引擎：按同步计划「下载 → 校验 → 暂存 → 应用」，并更新 state.json / pending.json。
 * 下载并发 4，每个文件最多尝试 3 次；哈希不符视为失败，绝不把未校验的文件放到目标位置。
 */
public final class SyncEngine {
    private static final int CONCURRENCY = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final long DISK_MARGIN = 64L * 1024 * 1024;

    private final String baseUrl;
    private final Path gameDir;
    private final StateStore stateStore;
    private final HttpClient httpClient;

    public SyncEngine(String baseUrl, Path gameDir, StateStore stateStore) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.gameDir = gameDir;
        this.stateStore = stateStore == null ? new StateStore(new SyncPaths(gameDir).stateFile()) : stateStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public SyncReport sync(PackManifest manifest, DiffResult diff) {
        Objects.requireNonNull(manifest, "manifest");
        String generation = manifest.generation();
        SyncPaths paths = new SyncPaths(gameDir);
        List<String> installed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<PendingOp> pending = new ArrayList<>();

        List<SyncAction> downloadActions = diff == null ? List.of() : diff.actions().stream()
                .filter(SyncEngine::needsDownload)
                .toList();

        long needed = 0;
        for (SyncAction action : downloadActions) {
            needed += manifest.entry(action.path()).map(PackEntry::size).orElse(0L);
        }
        try {
            long usable = Files.getFileStore(gameDir).getUsableSpace();
            if (usable < needed + DISK_MARGIN) {
                String message = "磁盘空间不足：需要约 " + needed + " 字节，剩余 " + usable + " 字节";
                errors.add(message);
                for (SyncAction action : downloadActions) {
                    failed.add(action.path());
                    errors.add(action.path() + ": " + message);
                }
                return new SyncReport(generation, installed, updated, deleted, pending, failed, errors);
            }
        } catch (IOException e) {
            errors.add("无法检查磁盘空间: " + describe(e));
        }

        Path stagingDir = paths.stagingDir(generation);
        deleteTree(stagingDir);
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            String message = "无法创建暂存目录: " + describe(e);
            errors.add(message);
            for (SyncAction action : downloadActions) {
                failed.add(action.path());
            }
            return new SyncReport(generation, installed, updated, deleted, pending, failed, errors);
        }

        Map<String, Path> staged = new ConcurrentHashMap<>();
        Map<String, String> downloadErrors = new ConcurrentHashMap<>();
        Set<String> shas = new LinkedHashSet<>();
        for (SyncAction action : downloadActions) {
            shas.add(action.toSha256());
        }
        downloadAll(shas, stagingDir, staged, downloadErrors, errors);

        for (SyncAction action : downloadActions) {
            String error = downloadErrors.get(action.toSha256());
            if (error != null) {
                failed.add(action.path());
                errors.add(action.path() + ": " + error);
            }
        }

        List<SyncAction> applicable = new ArrayList<>();
        if (diff != null) {
            for (SyncAction action : diff.actions()) {
                if (!needsDownload(action) || staged.containsKey(action.toSha256())) {
                    applicable.add(action);
                }
            }
        }
        ApplyService.ApplyResult appliedResult = new ApplyService(gameDir, LockProbe.moveProbe())
                .apply(generation, new DiffResult(applicable), staged);
        failed.addAll(appliedResult.failed());
        errors.addAll(appliedResult.errors());
        pending.addAll(appliedResult.pending());
        deleted.addAll(appliedResult.deleted());

        Set<String> updatedPaths = new HashSet<>();
        for (SyncAction action : downloadActions) {
            if (action.action() == SyncAction.Action.UPDATE) {
                updatedPaths.add(action.path());
            }
        }
        LocalState state = stateStore.load();
        for (String path : appliedResult.applied()) {
            if (updatedPaths.contains(path)) {
                updated.add(path);
            } else {
                installed.add(path);
            }
            manifest.entry(path).ifPresent(entry -> state.markInstalled(path, entry.sha256(), entry.size()));
        }
        for (String path : appliedResult.deleted()) {
            state.removeInstalled(path);
        }
        state.advanceGeneration(generation);
        state.setLastSync(Instant.now().toString());
        try {
            stateStore.save(state);
        } catch (RuntimeException e) {
            errors.add("保存 state.json 失败: " + describe(e));
        }

        if (!pending.isEmpty()) {
            try {
                new PendingQueue(paths.pendingFile()).append(pending);
            } catch (RuntimeException e) {
                errors.add("保存 pending.json 失败: " + describe(e));
            }
        } else {
            deleteTree(stagingDir);
        }
        appendLog(paths, generation, installed.size(), updated.size(), deleted.size(), pending.size(), failed.size());
        return new SyncReport(generation, installed, updated, deleted, pending, failed, errors);
    }

    private void downloadAll(Set<String> shas, Path stagingDir, Map<String, Path> staged,
                             Map<String, String> downloadErrors, List<String> errors) {
        if (shas.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(CONCURRENCY, shas.size()), runnable -> {
            Thread thread = new Thread(runnable, "ftyupater-download");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String sha : shas) {
                futures.add(pool.submit(() -> {
                    try {
                        staged.put(sha, download(sha, stagingDir));
                    } catch (Exception e) {
                        downloadErrors.put(sha, describe(e));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add("同步被中断");
        } catch (ExecutionException e) {
            errors.add("下载线程异常: " + describe(e));
        } finally {
            pool.shutdownNow();
        }
    }

    private Path download(String sha, Path stagingDir) throws Exception {
        Path target = stagingDir.resolve(sha);
        Path part = stagingDir.resolve(sha + ".part");
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(part);
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/files/" + sha))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                String actual = Hash.sha256(part);
                if (!actual.equals(sha)) {
                    throw new IOException("哈希不符：期望 " + sha + "，实际 " + actual);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (Exception e) {
                last = e;
                Files.deleteIfExists(part);
                Thread.sleep(100L * attempt);
            }
        }
        throw new IOException("下载失败: " + (last == null ? "未知" : describe(last)), last);
    }

    private static boolean needsDownload(SyncAction action) {
        return switch (action.action()) {
            case INSTALL_REQUIRED, INSTALL_OPTIONAL, UPDATE -> true;
            default -> false;
        };
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static void appendLog(SyncPaths paths, String generation, int installed, int updated,
                                  int deleted, int pending, int failed) {
        try {
            Path file = paths.logFile();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = "[" + Instant.now() + "] generation=" + generation
                    + " installed=" + installed
                    + " updated=" + updated
                    + " deleted=" + deleted
                    + " pending=" + pending
                    + " failed=" + failed
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志失败不影响同步
        }
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public record SyncReport(String generation,
                             List<String> installed,
                             List<String> updated,
                             List<String> deleted,
                             List<PendingOp> pending,
                             List<String> failed,
                             List<String> errors) {
        public SyncReport {
            installed = installed == null ? List.of() : List.copyOf(installed);
            updated = updated == null ? List.of() : List.copyOf(updated);
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
            pending = pending == null ? List.of() : List.copyOf(pending);
            failed = failed == null ? List.of() : List.copyOf(failed);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean requiresRestart() {
            return !pending.isEmpty();
        }

        public boolean ok() {
            return failed.isEmpty();
        }
    }
}
