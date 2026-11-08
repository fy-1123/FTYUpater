package com.ftysync.client;

import com.ftysync.FTYUpater;
import com.ftysync.core.LocalState;
import com.ftysync.core.StateStore;
import com.ftysync.helper.HelperResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 客户端启动时的待办提示（M5-2）。
 *
 * <p>做两件事，且<b>只做一次</b>（第一次客户端 tick 时）：</p>
 * <ol>
 *   <li>读 {@code helper-result.jsonl}，把上次退出时 helper 干了什么告诉玩家；</li>
 *   <li>若 {@code pending.json} 还有内容，顺手拉起 {@link HelperLauncher}——
 *       它自己会等本进程退出，所以现在拉起是安全的。</li>
 * </ol>
 *
 * <p>为什么用显式 {@code MinecraftForge.EVENT_BUS.register} 而不是
 * {@code @EventBusSubscriber}：后者在 Forge 1.20.1 的 mapped jar 里由
 * {@code javap} 查不到（被拆到别的 jar），注解解析路径不确定；显式注册与现有
 * {@code JoinProbe} 的做法一致，行为可预期。</p>
 *
 * <p>本类引用 Minecraft 类，<b>只能在客户端侧加载</b>：由
 * {@code FTYUpater} 通过 {@code DistExecutor.runWhenOn(Dist.CLIENT, ...)} 间接调用，
 * 保证专用服务端不会触发类加载。</p>
 */
public final class ClientStartup {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);

    /** 只检查一次；`ClientTickEvent` 每 tick 都会来。 */
    private boolean checked;

    private ClientStartup() {
    }

    /**
     * 客户端侧入口：挂上事件总线。
     *
     * <p>必须由 {@code DistExecutor} 包一层再调用，避免专用服务端加载本类。</p>
     */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ClientStartup());
        LOGGER.debug("FTYUpater 客户端启动检查已挂上");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (checked || event.phase != TickEvent.Phase.END) {
            return;
        }
        checked = true;
        try {
            checkOnce();
        } catch (RuntimeException e) {
            // 提示失败绝不能影响进游戏
            LOGGER.warn("FTYUpater 启动检查出错: {}", e.toString());
        }
    }

    /**
     * 真正的检查逻辑（拆出来便于阅读与将来单测）。
     */
    private void checkOnce() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Path gameDir = minecraft.gameDirectory.toPath();

        // M6-2：先把 v1 的文本配置迁进来（幂等，迁过就什么都不做）
        migrateFromV1(gameDir);

        int pending = HelperLauncher.countPending(gameDir);
        Optional<HelperResult> result = HelperLauncher.readResult(gameDir);

        Optional<PendingNotice.Notice> notice = PendingNotice.compose(pending, result.orElse(null));
        if (notice.isPresent()) {
            PendingNotice.Notice n = notice.get();
            show(n);
            LOGGER.info("FTYUpater 启动提示：{} —— {}", n.title(), n.body());
        }

        // 有结果就先收走：提示已经弹了（即便弹失败，日志也记了），
        // 下次不该再重复打扰玩家。
        if (result.isPresent()) {
            HelperLauncher.consume(gameDir);
        }

        if (notice.map(PendingNotice.Notice::launchHelper).orElse(pending > 0)) {
            HelperLauncher.launch(gameDir);
        }
    }

    /**
     * v1 → v2 迁移：把旧文本配置合并进 {@code state.json} / {@code pending.json} / {@code servers.json}。
     *
     * <p>失败绝不能影响进游戏，所以整个方法吞掉异常只记日志。</p>
     */
    private static void migrateFromV1(Path gameDir) {
        try {
            Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            V1Migration migration = new V1Migration(configDir, gameDir);
            if (!migration.hasV1Files()) {
                return;
            }
            V1Migration.Report report = migration.migrate();
            if (!report.didAnything()) {
                LOGGER.info("FTYUpater {}", report.describe());
                return;
            }
            SyncPaths paths = new SyncPaths(gameDir);

            // 忽略列表 → state.ignored（v2 用相对路径，v1 只有文件名）
            if (!report.ignored().isEmpty()) {
                StateStore store = new StateStore(paths.stateFile());
                LocalState state = store.load();
                Set<String> ignored = new LinkedHashSet<>(state.ignored());
                ignored.addAll(report.ignored());
                state.setIgnored(ignored);
                store.save(state);
            }

            // 待删除 → pending 队列
            if (!report.pending().isEmpty()) {
                new PendingQueue(paths.pendingFile()).append(report.pending());
            }

            // 服务器记忆 → servers.json
            if (!report.servers().isEmpty()) {
                ServerSourceCache cache = new ServerSourceCache(paths.serversFile());
                report.servers().forEach(cache::put);
            }

            LOGGER.info("FTYUpater {}", report.describe());
            show(new PendingNotice.Notice("已从旧版本迁移", report.describe(),
                    PendingNotice.Kind.INFO, false));
        } catch (RuntimeException e) {
            LOGGER.warn("FTYUpater v1 迁移失败: {}", e.toString());
        }
    }

    /**
     * 弹一条系统 Toast。
     *
     * <p>用 {@code PERIODIC_NOTIFICATION} 令牌：它的显示时长最长（5 秒），
     * 而且不会和「世界备份」「资源包加载失败」等原版提示互相顶掉。</p>
     */
    private static void show(PendingNotice.Notice notice) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getToasts() == null) {
            return;
        }
        SystemToast.add(
                minecraft.getToasts(),
                SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal("FTYUpater · " + notice.title()),
                Component.literal(notice.body()));
    }
}
