package com.ftysync.client;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * 自己实现的 Minecraft status ping（不依赖 {@code ServerStatusPinger}）。
 *
 * <p>为什么不用原版 pinger：原版把 Forge 的 {@code ServerStatusPing} 摘要塞进
 * {@code ServerData.forgeData} 后就丢弃了原始 mods 表，客户端拿不到我们广告的原始字符串。
 * 这里直接用 {@link Connection} 发一次 status 握手，拿到完整的 {@link ServerStatus}
 * （含 {@code forgeData().mods()}），从而精确读取本模组的广告。</p>
 *
 * <p>使用方式：构造成立后每 tick 调用一次 {@link #tick()}，直到 {@link #status()} 或
 * {@link #failure()} 有值；用完后必须 {@link #close()}。</p>
 */
public final class StatusPinger implements AutoCloseable {

    private final Connection connection;
    private final Listener listener = new Listener();
    private volatile ServerStatus status;
    private volatile Component failure;
    private volatile boolean closed;

    public StatusPinger(String host, int port) {
        this.connection = Connection.connectToServer(new InetSocketAddress(host, port), false);
        this.connection.setListener(listener);
        this.connection.send(new ClientIntentionPacket(host, port, ConnectionProtocol.STATUS));
        this.connection.send(new ServerboundStatusRequestPacket());
    }

    public Optional<ServerStatus> status() {
        return Optional.ofNullable(status);
    }

    public Optional<Component> failure() {
        return Optional.ofNullable(failure);
    }

    /** 由客户端 tick 驱动，处理网络读写。 */
    public void tick() {
        if (closed) {
            return;
        }
        try {
            connection.tick();
        } catch (RuntimeException e) {
            failure = Component.literal(describe(e));
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.disconnect(Component.literal("FTYUpater 状态探测结束"));
        } catch (RuntimeException ignored) {
            // 连接可能已经关闭
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private final class Listener implements ClientStatusPacketListener {

        @Override
        public void handleStatusResponse(ClientboundStatusResponsePacket packet) {
            status = packet.status();
        }

        @Override
        public void handlePongResponse(ClientboundPongResponsePacket packet) {
        }

        @Override
        public void onDisconnect(Component reason) {
            if (status == null && failure == null) {
                failure = reason;
            }
        }

        @Override
        public boolean isAcceptingMessages() {
            return status == null && !closed;
        }
    }
}
