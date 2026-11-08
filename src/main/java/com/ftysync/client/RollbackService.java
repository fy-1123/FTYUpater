package com.ftysync.client;

import com.ftysync.core.LocalState;
import com.ftysync.core.PathGuard;
import com.ftysync.core.StateStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 回退的执行（M5-3）。
 *
 * <p><b>备份的语义要记清楚</b>：{@code ftyupater/backup/<G>/} 里存的是
 * 「同步到代际 G <i>之前</i>」的文件内容（{@link BackupManager#backup} 在覆盖前先复制）。
 * 所以当前代际是 {@code C} 时，{@code backup/C/} 就是 {@code C} 的上一代内容——
 * 这正好对应 {@code state.json} 里的 {@code previousGeneration}。</p>
 *
 * <p>回退有两条路，能力不同：</p>
 * <ul>
 *   <li><b>走服务器</b>（{@link #rollbackFromServer}）：拉目标代际的清单再同步一遍。
 *       文件齐、哈希可校验、新增的文件也会被正确清掉。需要服务器还保留那一代。</li>
 *   <li><b>走本地备份</b>（{@link #restoreFromBackup}）：把 {@code backup/<G>/} 里的文件复制回去。
 *       完全离线，但<b>只能还原被覆盖过的文件</b>；那一代之后新增、而备份里没有的文件无法删除
 *       （不知道该删哪些）。所以本地回退是兜底，不是首选。</li>
 * </ul>
 */
public final class RollbackService {

    /**
     * 回退结果。
     *
     * @param targetGeneration 回退到的代际
     * @param restored         成功还原的文件数
     * @param failed           失败的文件数
     * @param errors           失败原因
     * @param incomplete       true 表示这是本地兜底回退，可能有新增文件没被清掉
     */
    public record RollbackResult(String targetGeneration, int restored, int failed,
                                 List<String> errors, boolean incomplete) {

        public RollbackResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            if (restored < 0) {
                restored = 0;
            }
            if (failed < 0) {
                failed = 0;
            }
        }

        public boolean ok() {
            // 两个条件都要看：failed 是「按文件计的失败」，errors 里还可能有目录级/写状态级的错误
            return failed == 0 && errors.isEmpty();
        }

        /** 给玩家看的一行摘要。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("已还原 ").append(restored).append(" 个文件");
            if (failed > 0) {
                sb.append(" · 失败 ").append(failed).append(" 个");
            }
            if (incomplete) {
                sb.append(" · 本地兜底：可能残留新增文件");
            }
            return sb.toString();
        }
    }

    private RollbackService() {
    }

    /**
     * 列出本地备份里有哪些代际（{@code backup/} 下的目录名），最近的在前。
     *
     * @param gameDir 游戏目录
     * @return 代际 id 列表；没有备份时为空表
     */
    public static List<String> listLocalBackups(Path gameDir) {
        Path backupRoot = new SyncPaths(gameDir).root().resolve("backup");
        if (!Files.isDirectory(backupRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 数一数某个备份代际里有多少文件。
     *
     * @param gameDir     游戏目录
     * @param generation  备份代际
     * @return 文件数；目录不存在时为 0
     */
    public static int countBackupFiles(Path gameDir, String generation) {
        Path dir = new SyncPaths(gameDir).backupDir(generation);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 用本地备份还原（离线兜底）。
     *
     * <p>把 {@code backup/<backupGeneration>/} 下的每个文件复制回游戏目录对应位置，
     * 然后把 {@code state.json} 的代际改回 {@code targetGeneration}。</p>
     *
     * <p><b>限制</b>：只覆盖/还原备份里存在的文件。同步到当前代际时<i>新增</i>的文件
     * 不在备份里，因此无法判断该不该删——这些文件会留下，结果标记 {@code incomplete}。
     * 要干净地回退，请走服务器（{@link #rollbackFromServer} 的等价路径：用老清单重新同步）。</p>
     *
     * @param gameDir          游戏目录
     * @param backupGeneration 用哪个备份目录（通常是当前代际）
     * @param targetGeneration 回退到的代际（写回 state.json）
     * @return 结果
     */
    public static RollbackResult restoreFromBackup(Path gameDir, String backupGeneration,
                                                   String targetGeneration) {
        SyncPaths paths = new SyncPaths(gameDir);
        Path backupDir = paths.backupDir(backupGeneration);
        if (!Files.isDirectory(backupDir)) {
            return new RollbackResult(targetGeneration, 0, 1,
                    List.of("备份目录不存在: " + backupGeneration), true);
        }
        int restored = 0;
        List<String> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(backupDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path source : files) {
                String relative = backupDir.relativize(source).toString().replace('\\', '/');
                try {
                    Path target = PathGuard.resolve(gameDir, relative);
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    copyReplace(source, target);
                    restored++;
                } catch (IOException | RuntimeException e) {
                    errors.add(relative + ": " + describe(e));
                }
            }
        } catch (IOException e) {
            errors.add("遍历备份目录失败: " + describe(e));
        }

        // 代际回退：把 state 的代际改回目标，并把「上一代」设成刚才所在的代际，
        // 这样玩家再点一次回退就能反悔回来。
        try {
            StateStore store = new StateStore(paths.stateFile());
            LocalState state = store.load();
            String wasAt = state.generation();
            state.setPreviousGeneration(wasAt);
            state.setGeneration(targetGeneration);
            store.save(state);
        } catch (RuntimeException e) {
            errors.add("更新 state.json 失败: " + describe(e));
        }

        return new RollbackResult(targetGeneration, restored, errors.size(),
                List.copyOf(errors), true);
    }

    /**
     * 走服务器回退：拉目标代际的清单。
     *
     * <p>真正执行同步的是 {@link SyncEngine}——这里只负责拿到「老清单」并把它交给调用方。
     * 之所以拆开，是因为下载/应用是异步的、要在界面里显示进度，而拉清单是同步的一步。</p>
     *
     * @param baseUrl    同步源
     * @param generation 目标代际
     * @return 目标代际的清单；服务器已剪掉那一代时为空
     */
    public static Optional<com.ftysync.core.PackManifest> fetchTargetManifest(String baseUrl,
                                                                             String generation) {
        return PackClient.fetchManifest(baseUrl, generation);
    }

    /**
     * 复制并替换目标文件（先写临时文件再原子改名，避免半截文件）。
     *
     * <p>为什么是复制而不是移动：备份要留着，玩家可能还要回退到更早。</p>
     */
    private static void copyReplace(Path source, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".rb-" + System.nanoTime() + ".part");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
