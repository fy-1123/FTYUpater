package com.ftysync;

import com.ftysync.core.Blacklist;
import com.ftysync.server.ContentScanner;
import com.ftysync.server.ContentStore;
import com.ftysync.server.FilePackRepository;
import com.ftysync.server.GenerationJournal;
import com.ftysync.server.GenerationStore;
import com.ftysync.server.HttpSyncServer;
import com.ftysync.server.PackPublisher;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 把 v2 发布管线接在 Forge 服务端生命周期上。
 *
 * <p>服务器启动 → 扫描服务器 mods/ 与 clientpack/、启动 {@code /api/v1} HTTP、按
 * {@code rescanIntervalSeconds} 周期重扫；服务器停止 → 全部关闭。配置改动需要重启服务器生效。</p>
 *
 * <p>HTTP 只读磁盘上的 journal/generations/objects，不持有扫描结果，因此发布线程与请求线程
 * 之间不需要额外同步。</p>
 */
public final class ServerSyncRuntime {

    /** 状态目录，相对服务器根目录；客户端状态在 {@code ftyupater/} 根下，服务端单独放子目录避免混淆。 */
    private static final String STORE_SUBDIR = "ftyupater/server";

    private static HttpSyncServer http;
    private static ScheduledExecutorService scheduler;
    private static PackPublisher publisher;
    private static Path storeDir;
    private static volatile boolean running;

    private ServerSyncRuntime() {
    }

    public static synchronized void start() {
        stop();
        if (!ModConfig.SERVER.enabled.get()) {
            FTYUpater.LOGGER.info("FTYUpater v2 同步服务端已在配置中禁用（server.enabled=false）。");
            return;
        }
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path clientpackDir = gameDir.resolve(ModConfig.SERVER.clientpackFolder.get());
            Blacklist blacklist = Blacklist.of(blacklistPatterns());
            ContentScanner scanner = new ContentScanner(gameDir, clientpackDir, blacklist,
                    ownJarNames(gameDir));

            storeDir = gameDir.resolve(STORE_SUBDIR);
            ContentStore store = new ContentStore(storeDir);
            GenerationStore generations = new GenerationStore(storeDir);
            GenerationJournal journal = new GenerationJournal(storeDir.resolve("journal.jsonl"));
            int retain = ModConfig.SERVER.retainGenerations.get();
            publisher = new PackPublisher(scanner, store, generations, journal,
                    ModConfig.SERVER.packId.get(), minecraftVersion(), loaderVersion(), retain);
            FilePackRepository repository = new FilePackRepository(store, generations, journal, retain);

            http = new HttpSyncServer(ModConfig.SERVER.httpPort.get(),
                    ModConfig.SERVER.bindAddress.get(), repository, modVersion());
            http.start();
            running = true;
            FTYUpater.LOGGER.info("FTYUpater v2 同步服务端已启动: {}（状态目录 {}）", httpUrl(), storeDir);
            if (ModConfig.SERVER.advertiseSyncSource.get()) {
                String advertised = ModConfig.SERVER.publicSyncUrl.get();
                if (advertised == null || advertised.isBlank()) {
                    advertised = com.ftysync.client.SyncAdvert.detectLocalUrl(http.port());
                    if (advertised == null) {
                        FTYUpater.LOGGER.warn(
                                "FTYUpater 无法自动探测局域网地址，请在 server.publicSyncUrl 里手动填写同步地址。");
                    }
                }
                com.ftysync.client.SyncAdvert.setSource(advertised);
            } else {
                com.ftysync.client.SyncAdvert.clear();
            }
        } catch (Exception e) {
            FTYUpater.LOGGER.error("FTYUpater v2 同步服务端启动失败（不影响游戏本身）", e);
            stop();
            return;
        }

        int interval = Math.max(2, ModConfig.SERVER.rescanIntervalSeconds.get());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ftyupater-publisher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(ServerSyncRuntime::publishOnce, 0, interval, TimeUnit.SECONDS);
    }

    private static void publishOnce() {
        PackPublisher current = publisher;
        if (current == null) {
            return;
        }
        try {
            PackPublisher.PublishResult result = current.publish();
            if (result.newGeneration()) {
                FTYUpater.LOGGER.info("FTYUpater 已发布新代际 {}（{} 个文件）",
                        result.manifest().generation(), result.manifest().size());
            }
        } catch (IOException | RuntimeException e) {
            FTYUpater.LOGGER.warn("FTYUpater 扫描/发布失败，将在下个周期重试: {}", e.toString());
        }
    }

    public static synchronized void stop() {
        running = false;
        com.ftysync.client.SyncAdvert.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        publisher = null;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 当前监听地址（0.0.0.0 显示为 127.0.0.1），未启动时返回占位文本。 */
    public static String httpUrl() {
        HttpSyncServer current = http;
        if (current == null) {
            return "(未启动)";
        }
        String bind = ModConfig.SERVER.bindAddress.get();
        if (bind == null || bind.isBlank() || "0.0.0.0".equals(bind)) {
            bind = "127.0.0.1";
        }
        return "http://" + bind + ":" + current.port() + "/api/v1";
    }

    private static List<String> blacklistPatterns() {
        List<String> patterns = new ArrayList<>();
        for (String pattern : ModConfig.SERVER.blacklist.get()) {
            patterns.add(pattern);
        }
        return patterns;
    }

    private static String modVersion() {
        return ModList.get().getModContainerById(FTYUpater.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("dev");
    }

    private static String minecraftVersion() {
        return ModList.get().getModContainerById("minecraft")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("1.20.1");
    }

    private static String loaderVersion() {
        return "forge-" + ModList.get().getModContainerById("forge")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /**
     * 自身 jar 绝不发布。正式环境取本类所在 jar 的文件名；开发环境（classes 目录）没有 jar，
     * 则兜底排除 mods/ 下文件名以 ftyupater 开头的 jar，避免开发时把旧版自身发出去。
     */
    private static Set<String> ownJarNames(Path gameDir) {
        Set<String> names = new HashSet<>();
        try {
            var source = ServerSyncRuntime.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(location)) {
                    names.add(location.getFileName().toString());
                }
            }
        } catch (Exception ignored) {
            // 取不到 code source 时只靠下面的兜底
        }
        Path mods = gameDir.resolve(ContentScanner.MODS_DIR);
        if (Files.isDirectory(mods)) {
            try (Stream<Path> stream = Files.list(mods)) {
                stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(FTYUpater.MODID))
                        .forEach(names::add);
            } catch (IOException ignored) {
                // 扫描失败时由 ContentScanner 自己再处理
            }
        }
        return names;
    }
}
