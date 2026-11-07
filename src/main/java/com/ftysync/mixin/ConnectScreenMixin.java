package com.ftysync.mixin;

import com.ftysync.ModConfig;
import com.ftysync.client.JoinBypass;
import com.ftysync.client.gui.JoinCheckScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截所有「连接多人服务器」入口（服务器列表、快速加入、Quick Play）。
 *
 * <p>把原版 {@link ConnectScreen} 换成我们的检查界面：先 ping 服务器拿同步广告、拉清单、
 * 算差异，再决定自动进服还是让玩家处理。放行状态由 {@link JoinBypass} 承载
 * （Mixin 不允许混入类里出现非 private 的非注入器方法）。</p>
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    // official 映射下直接写方法名，MixinGradle 的注解处理器会生成 refmap 把它映射成 SRG；
    // dev 运行时再经 mixin.env 映射文件反查回 official 名（见 build.gradle 的 mixin 块与 --mixin.config）。
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void ftyupater$onStartConnecting(Screen parent, Minecraft mc, ServerAddress address,
                                                    ServerData data, boolean quickPlay, CallbackInfo ci) {
        if (JoinBypass.consume()) {
            return;
        }
        if (!ModConfig.CLIENT.enabled.get() || !ModConfig.CLIENT.joinCheckEnabled.get()) {
            return;
        }
        ci.cancel();
        mc.setScreen(new JoinCheckScreen(parent, data, address));
    }
}
