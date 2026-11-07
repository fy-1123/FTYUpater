package com.ftysync;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 服务端与客户端的 Forge 配置定义。
 *
 * <p>服务端写入 {@code ftyupater-server.toml}，客户端写入 {@code ftyupater-client.toml}。
 * 键说明见 docs/protocol.md 与 README.md。</p>
 */
public final class ModConfig {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        var sp = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER_SPEC = sp.getRight();
        SERVER = sp.getLeft();

        var cp = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT_SPEC = cp.getRight();
        CLIENT = cp.getLeft();
    }

    private ModConfig() {}

    /** 服务端内置 HTTP 同步服务的设置。 */
    public static final class Server {
        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.ConfigValue<Integer> httpPort;
        public final ForgeConfigSpec.ConfigValue<String> bindAddress;
        public final ForgeConfigSpec.ConfigValue<String> syncFolder;
        public final ForgeConfigSpec.BooleanValue advertiseSyncSource;
        public final ForgeConfigSpec.ConfigValue<String> publicSyncUrl;
        public final ForgeConfigSpec.ConfigValue<String> clientpackFolder;
        public final ForgeConfigSpec.ConfigValue<Integer> retainGenerations;
        public final ForgeConfigSpec.ConfigValue<Integer> rescanIntervalSeconds;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> blacklist;
        public final ForgeConfigSpec.ConfigValue<String> packId;

        Server(ForgeConfigSpec.Builder b) {
            b.push("server");
            enabled = b.comment("是否启动内置的 HTTP 同步服务。")
                    .define("enabled", true);
            httpPort = b.comment("同步服务的 TCP 端口。")
                    .defineInRange("httpPort", 8081, 1, 65535);
            bindAddress = b.comment("监听地址。填 0.0.0.0 表示所有网卡（局域网可访问）。")
                    .define("bindAddress", "0.0.0.0");
            syncFolder = b.comment(
                    "存放「完整客户端清单」的专用目录。相对路径按服务器根目录解析。\n"
                            + "只给客户端用的性能模组可以放这里，即使服务端自己加载不了。")
                    .define("syncFolder", "ftyupdater");
            advertiseSyncSource = b.comment(
                    "进服时是否告诉客户端本服的同步源地址（用于自动检查、以及填写客户端空着的 syncUrl）。\n"
                            + "关掉就保持沉默。")
                    .define("advertiseSyncSource", true);
            publicSyncUrl = b.comment(
                    "下发给客户端的公网基地址，例如 'http://mc.example.com:8081'。\n"
                            + "留空则按 httpPort 与客户端连进来的地址自动推导。")
                    .define("publicSyncUrl", "");
            clientpackFolder = b.comment(
                    "存放「仅客户端文件」的目录，按游戏目录结构摆放：\n"
                            + "mods/、config/、resourcepacks/、shaderpacks/……路径按服务器根目录解析。\n"
                            + "这里找到的文件一律按「可选」发布，玩家可以逐个不要。\n"
                            + "mods/ 下与服务器 mods 目录同名的文件，一律以服务器目录为准。")
                    .define("clientpackFolder", "clientpack");
            retainGenerations = b.comment(
                    "保留多少代已发布内容用于一键回退（1-20）。")
                    .defineInRange("retainGenerations", 2, 1, 20);
            rescanIntervalSeconds = b.comment(
                    "多久重新扫描一次服务器 mods/ 与 clientpack/ 目录。")
                    .defineInRange("rescanIntervalSeconds", 10, 2, 3600);
            blacklist = b.comment(
                    "永不发布的通配符规则。含 '/' 的规则匹配整个相对路径（如 'mods/server-only.jar'）；\n"
                            + "不含 '/' 的规则只匹配文件名（如 '*.server.jar'）。不区分大小写。")
                    .defineList("blacklist", List.<String>of(), o -> o instanceof String);
            packId = b.comment(
                    "本服清单的固定 id。改了它，客户端已有的同步状态会失效。")
                    .define("packId", "fty");
            b.pop();
        }
    }

    /** 客户端同步逻辑的设置。 */
    public static final class Client {
        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.ConfigValue<String> syncUrl;
        public final ForgeConfigSpec.BooleanValue joinCheckEnabled;
        public final ForgeConfigSpec.BooleanValue autoContinueIfInSync;

        Client(ForgeConfigSpec.Builder b) {
            b.push("client");
            enabled = b.comment("是否启用客户端模组同步。")
                    .define("enabled", true);
            syncUrl = b.comment(
                    "服务器 HTTP 同步服务的基地址，例如 'http://192.168.1.100:8081'，结尾不要斜杠。\n"
                            + "留空则由 FTYUpater 在进服时自动填写。")
                    .define("syncUrl", "");
            joinCheckEnabled = b.comment(
                    "拦截每一次多人连接并显示「进服前检查」界面（探测服务器、拉清单、显示差异）。\n"
                            + "关掉则直接连接、不做任何检查。")
                    .define("joinCheckEnabled", true);
            autoContinueIfInSync = b.comment(
                    "本地文件已经与服务器一致时，跳过检查界面直接进服。")
                    .define("autoContinueIfInSync", true);
            b.pop();
        }
    }
}
