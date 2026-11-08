package com.ftysync;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(FTYUpater.MODID)
public class FTYUpater {

    public static final String MODID = "ftyupater";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public FTYUpater() {
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                ModConfig.SERVER_SPEC, "ftyupater-server.toml");
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT,
                ModConfig.CLIENT_SPEC, "ftyupater-client.toml");
        // 把同步源地址通过 Forge DisplayTest 广告给客户端（status ping 的 mods 表里会带这个字符串）。
        ModLoadingContext.get().registerDisplayTest(() -> com.ftysync.client.SyncAdvert.marker(),
                (remoteVersion, isServer) -> true);
        MinecraftForge.EVENT_BUS.register(this);
        // 客户端侧：启动时提示 pending / helper 结果。
        // 必须用 DistExecutor 包一层：ClientStartup 引用了 Minecraft 类，
        // 专用服务端直接调会 NoClassDefFoundError。runWhenOn 在服务端根本不会执行
        // 那个 Supplier，所以 ClientStartup 不会被加载。
        net.minecraftforge.fml.DistExecutor.runWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> com.ftysync.client.ClientStartup::init);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ServerSyncRuntime.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ServerSyncRuntime.stop();
    }
}
