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
