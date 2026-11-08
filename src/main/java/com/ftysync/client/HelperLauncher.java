package com.ftysync.client;

import com.ftysync.FTYUpater;
import com.ftysync.helper.HelperResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 拉起 / 回收独立 JVM 的 {@code ApplyHelper}（见 ADR-008）。
 *
 * <p>客户端在检测到 {@code pending.json} 非空时调用 {@link #launch(Path)}：helper 会等
 * 当前进程退出后再动手，所以现在拉起是安全的。下次启动时用 {@link #readResult(Path)}
 * 读结果并提示玩家。</p>
 */
public final class HelperLauncher {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);

    /** 结果文件相对 {@code ftyupater/} 的路径。 */
    private static final String RESULT_NAME = "helper-result.jsonl";

    private HelperLauncher() {
    }

    /** 结果文件路径：{@code ftyupater/pending/helper-result.jsonl}。 */
    public static Path resultFile(Path gameDir) {
        return new SyncPaths(gameDir).root().resolve("pending").resolve(RESULT_NAME);
    }

    /**
     * 拉起 helper 进程。
     *
     * <p>命令：{@code <java> -cp <模组jar或classes目录> com.ftysync.helper.ApplyHelper <gameDir> <本进程PID>}。</p>
     *
     * @param gameDir 游戏目录
     * @return 拉起成功返回进程 PID；失败（找不到自身位置/无法启动）返回空
     */
    public static Optional<Long> launch(Path gameDir) {
        Path self = selfLocation();
        if (self == null) {
            LOGGER.warn("FTYUpater 找不到自身 jar 位置，无法拉起 ApplyHelper");
            return Optional.empty();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        if (!Files.isExecutable(java)) {
            LOGGER.warn("FTYUpater 找不到 java 可执行文件: {}", java);
            return Optional.empty();
        }
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(self.toString());
        command.add("com.ftysync.helper.ApplyHelper");
        command.add(gameDir.toAbsolutePath().toString());
        command.add(Long.toString(ProcessHandle.current().pid()));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOGGER.info("FTYUpater 已拉起 ApplyHelper（pid {}），将在游戏退出后应用 {} 项待办",
                    process.pid(), countPending(gameDir));
            return Optional.of(process.pid());
        } catch (IOException e) {
            LOGGER.warn("FTYUpater 拉起 ApplyHelper 失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 读 helper 结果。
     *
     * <p>读到之后<b>不删除</b>文件：由调用方在提示过玩家后调 {@link #consume(Path)}，
     * 这样即便提示失败也不会丢信息。</p>
     *
     * @param gameDir 游戏目录
     * @return 结果；没有结果文件或解析失败时为空
     */
    public static Optional<HelperResult> readResult(Path gameDir) {
        Path file = resultFile(gameDir);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(HelperResult.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("FTYUpater 读取 ApplyHelper 结果失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 删除结果文件（表示已告知玩家）。
     *
     * @param gameDir 游戏目录
     */
    public static void consume(Path gameDir) {
        try {
            Files.deleteIfExists(resultFile(gameDir));
        } catch (IOException e) {
            LOGGER.debug("FTYUpater 删除 ApplyHelper 结果失败: {}", e.toString());
        }
    }

    /** pending.json 里还有几条。 */
    public static int countPending(Path gameDir) {
        return new PendingQueue(new SyncPaths(gameDir).pendingFile()).load().size();
    }

    /**
     * 定位本模组的 classpath 条目（生产环境是 jar，dev 环境是 classes 目录）。
     *
     * @return 路径；无法确定时为 null
     */
    private static Path selfLocation() {
        try {
            var source = HelperLauncher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
