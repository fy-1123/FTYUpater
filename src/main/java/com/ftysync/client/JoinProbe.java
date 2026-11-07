package com.ftysync.client;

import com.ftysync.FTYUpater;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.ServerStatusPing;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * 进服前探测：对目标服务器做一次 status ping，读出它公布的同步广告，转成
 * {@link JoinDecision.ProbeOutcome}。
 *
 * <p>超时（约 4 秒）或 ping 失败时返回「没有 FTYUpater」，即放行——绝不能因为探测失败
 * 把玩家拦在服务器外面。</p>
 */
public final class JoinProbe {

    private static final Logger LOGGER = LogManager.getLogger(FTYUpater.MODID);
    private static final int TIMEOUT_TICKS = 80;

    private JoinProbe() {
    }

    /** 探测完成回调，在客户端主线程上执行。 */
    public interface Callback {
        void completed(JoinDecision.ProbeOutcome outcome);
    }

    public static void start(ServerData serverData, Callback callback) {
        if (serverData == null || serverData.ip == null) {
            callback.completed(JoinDecision.ProbeOutcome.fromMarker(null));
            return;
        }
        ServerAddress address = ServerAddress.parseString(serverData.ip);
        StatusPinger pinger;
        try {
            pinger = new StatusPinger(address.getHost(), address.getPort());
        } catch (RuntimeException e) {
            LOGGER.warn("FTYUpater 无法向 {} 发起状态探测: {}", serverData.ip, e.toString());
            callback.completed(JoinDecision.ProbeOutcome.fromMarker(null));
            return;
        }
        new Instance(pinger, callback, serverData.ip);
    }

    private static JoinDecision.ProbeOutcome outcomeOf(ServerStatus status) {
        if (status == null) {
            return JoinDecision.ProbeOutcome.fromMarker(null);
        }
        Optional<ServerStatusPing> forge = status.forgeData();
        if (forge.isEmpty()) {
            return JoinDecision.ProbeOutcome.fromMarker(null);
        }
        ServerStatusPing ping = forge.get();
        if (ping.isTruncated()) {
            // mods 表被截断时不能相信缺失项，按「没有同步源」处理，避免误判成没装模组
            return JoinDecision.ProbeOutcome.fromMarker(null);
        }
        return JoinDecision.ProbeOutcome.fromMarker(ping.mods().get(FTYUpater.MODID));
    }

    private static final class Instance {

        private final StatusPinger pinger;
        private final Callback callback;
        private final String address;
        private int ticks;
        private boolean done;

        private Instance(StatusPinger pinger, Callback callback, String address) {
            this.pinger = pinger;
            this.callback = callback;
            this.address = address;
            MinecraftForge.EVENT_BUS.register(this);
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || done) {
                return;
            }
            ticks++;
            try {
                pinger.tick();
            } catch (RuntimeException e) {
                finish(null);
                return;
            }
            if (pinger.status().isPresent()) {
                finish(pinger.status().get());
                return;
            }
            if (pinger.failure().isPresent()) {
                LOGGER.debug("FTYUpater 状态探测 {} 失败: {}", address, pinger.failure().get().getString());
                finish(null);
                return;
            }
            if (ticks >= TIMEOUT_TICKS) {
                LOGGER.debug("FTYUpater 状态探测 {} 超时，放行", address);
                finish(null);
            }
        }

        private void finish(ServerStatus status) {
            if (done) {
                return;
            }
            done = true;
            MinecraftForge.EVENT_BUS.unregister(this);
            pinger.close();
            callback.completed(outcomeOf(status));
        }
    }
}
