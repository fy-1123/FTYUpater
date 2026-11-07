package com.ftysync.client;

import com.ftysync.core.Blacklist;
import com.ftysync.core.DiffEngine;
import com.ftysync.core.DiffResult;
import com.ftysync.core.LocalState;
import com.ftysync.core.PackManifest;
import com.ftysync.core.StateStore;
import com.ftysync.core.SyncAction;
import com.ftysync.server.ContentScanner;
import com.ftysync.server.ContentStore;
import com.ftysync.server.FilePackRepository;
import com.ftysync.server.GenerationJournal;
import com.ftysync.server.GenerationStore;
import com.ftysync.server.HttpSyncServer;
import com.ftysync.server.PackPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各种「同步情况」的端到端测试（纯 JVM，不启动 Minecraft）。
 *
 * <p>每个测试都起一个真的 {@link HttpSyncServer}（发布 → 落盘 → HTTP 下发），
 * 客户端侧走真实的 {@link PackClient} → {@link LocalScanner} → {@code DiffEngine}
 * → {@link SyncEngine} 链路，游戏目录用 {@code @TempDir} 模拟。</p>
 *
 * <p>这样覆盖的是「服务器内容变 → 客户端怎么判断 → 下载/更新/清理成什么样」，
 * 不依赖显卡、窗口和鼠标，可以在任何 CI 上跑。</p>
 */
class SyncScenarioTest {

    @TempDir
    Path serverDir;

    @TempDir
    Path clientDir;

    private HttpSyncServer server;
    private PackPublisher publisher;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        Path storeDir = serverDir.resolve("ftyupater");
        ContentScanner scanner = new ContentScanner(serverDir, Blacklist.empty(), Set.of("FTYUpater-2.0.0.jar"));
        ContentStore store = new ContentStore(storeDir);
        GenerationStore generations = new GenerationStore(storeDir);
        GenerationJournal journal = new GenerationJournal(storeDir.resolve("journal.jsonl"));
        publisher = new PackPublisher(scanner, store, generations, journal, "fty", "1.20.1", "forge-47.4.0", 2);
        FilePackRepository repository = new FilePackRepository(store, generations, journal, 2);
        server = new HttpSyncServer(0, "127.0.0.1", repository, "2.0.0");
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ---------- 小工具 ----------

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 服务器侧放一个文件（mods/ 或 clientpack/ 下）。 */
    private void serverFile(String relative, String content) throws IOException {
        write(serverDir, relative, content);
    }

    private PackManifest publish() throws IOException {
        return publisher.publish().manifest();
    }

    private StateStore stateStore() {
        return new StateStore(new SyncPaths(clientDir).stateFile());
    }

    /** 拉服务器最新清单（走真实 HTTP）。 */
    private PackManifest fetch() {
        return PackClient.fetchManifest(base)
                .orElseThrow(() -> new AssertionError("应该能拉到清单：" + base));
    }

    /** 按当前本地状态算差异。 */
    private DiffResult diffOf(PackManifest manifest) throws IOException {
        LocalScanner.ScanResult scan = LocalScanner.scan(clientDir, manifest);
        LocalState state = stateStore().load();
        return DiffEngine.diff(manifest, scan.present(), scan.manualDisabled(),
                state.optOut(), state.ignored(), Set.of(), Map.of());
    }

    private SyncEngine.SyncReport apply(PackManifest manifest, DiffResult diff) {
        return new SyncEngine(base, clientDir, stateStore()).sync(manifest, diff);
    }

    /** 把当前客户端完整同步到服务器最新内容。 */
    private SyncEngine.SyncReport syncNow() throws IOException {
        PackManifest manifest = fetch();
        return apply(manifest, diffOf(manifest));
    }

    /** 模拟一次 status ping：服务器广告串 → 探测结果。 */
    private static JoinDecision.ProbeOutcome probe(String marker) {
        return JoinDecision.ProbeOutcome.fromMarker(marker);
    }

    // ---------- 1. 全新客户端 ----------

    @Test
    @DisplayName("全新客户端：必需模组必装、客户端专属文件可选，同步后完全一致")
    void freshClientInstallsEverything() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        serverFile("clientpack/config/x.toml", "CONFIG-X");
        PackManifest manifest = publish();

        DiffResult before = diffOf(manifest);
        assertEquals(1, before.count(SyncAction.Action.INSTALL_REQUIRED), before.actions()::toString);
        assertEquals(1, before.count(SyncAction.Action.INSTALL_OPTIONAL), before.actions()::toString);
        assertTrue(before.blocksJoin(), "缺必需模组必须拦住进服");

        JoinDecision.Decision decision = JoinDecision.decide(true,
                probe(AdvertParser.format(base)), before, true);
        assertEquals(JoinDecision.Action.SHOW_SCREEN, decision.action());

        SyncEngine.SyncReport report = apply(manifest, before);
        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(2, report.installed().size(), report.installed()::toString);
        assertEquals("MOD-A", Files.readString(clientDir.resolve("mods/a.jar")));
        // 注意：clientpack/ 是「按游戏目录结构」的，发布时会把 clientpack/ 前缀剥掉，
        // 所以服务器上的 clientpack/config/x.toml 在客户端就是 config/x.toml。
        assertEquals("CONFIG-X", Files.readString(clientDir.resolve("config/x.toml")));
        assertFalse(report.requiresRestart(), "全新装的文件没有被占用，不需要重启");

        DiffResult after = diffOf(fetch());
        assertTrue(after.isInSync(), after.actions()::toString);
    }

    // ---------- 2. 完全一致 ----------

    @Test
    @DisplayName("客户端已一致：自动继续，不再下载任何东西")
    void inSyncClientAutoContinues() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        publish();
        syncNow();

        PackManifest manifest = fetch();
        DiffResult diff = diffOf(manifest);
        assertTrue(diff.isInSync(), diff.actions()::toString);

        JoinDecision.Decision decision = JoinDecision.decide(true,
                probe(AdvertParser.format(base)), diff, true);
        assertEquals(JoinDecision.Action.AUTO_CONTINUE, decision.action());
        assertEquals("in_sync", decision.reasonKey());

        SyncEngine.SyncReport again = apply(manifest, diff);
        assertTrue(again.installed().isEmpty());
        assertTrue(again.updated().isEmpty());
        assertTrue(again.deleted().isEmpty());
    }

    // ---------- 3. 服务器新增模组 ----------

    @Test
    @DisplayName("服务器新增模组：只补新的那一个")
    void serverAddsNewModOnlyNewOneInstalled() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        publish();
        syncNow();

        serverFile("mods/b.jar", "MOD-B");
        PackManifest manifest = publish();

        DiffResult diff = diffOf(manifest);
        assertEquals(1, diff.count(SyncAction.Action.INSTALL_REQUIRED), diff.actions()::toString);

        SyncEngine.SyncReport report = apply(manifest, diff);
        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(java.util.List.of("mods/b.jar"), report.installed());
        assertEquals("MOD-A", Files.readString(clientDir.resolve("mods/a.jar")), "老文件不该被重写");
    }

    // ---------- 4. 服务器更新模组 ----------

    @Test
    @DisplayName("服务器更新模组：覆盖新内容，旧版本进备份")
    void serverUpdatesModBacksUpOldVersion() throws Exception {
        serverFile("mods/a.jar", "OLD");
        publish();
        syncNow();

        serverFile("mods/a.jar", "NEW-CONTENT");
        PackManifest manifest = publish();

        DiffResult diff = diffOf(manifest);
        assertEquals(1, diff.count(SyncAction.Action.UPDATE), diff.actions()::toString);
        assertTrue(diff.blocksJoin(), "必需模组更新也必须先同步");

        SyncEngine.SyncReport report = apply(manifest, diff);
        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(java.util.List.of("mods/a.jar"), report.updated());
        assertEquals("NEW-CONTENT", Files.readString(clientDir.resolve("mods/a.jar")));

        Path backup = clientDir.resolve("ftyupater/backup/" + manifest.generation() + "/mods/a.jar");
        assertTrue(Files.isRegularFile(backup), "旧版本应该备份到 " + backup);
        assertEquals("OLD", Files.readString(backup));
        // 纯 JVM 里没有游戏占用 jar，所以能立即替换、不需要重启；
        // 真机上 mods/*.jar 被 JVM 锁住时才会走 pending + ApplyHelper（M5）。
        assertFalse(report.requiresRestart(), "没被占用就应该立即应用");
    }

    // ---------- 5. 客户端多余 jar ----------

    @Test
    @DisplayName("客户端多余的 jar：自动清理进回收站，不直接删掉")
    void extraClientJarGoesToTrash() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        publish();
        syncNow();

        write(clientDir, "mods/random-thing.jar", "USER-EXTRA");

        PackManifest manifest = fetch();
        DiffResult diff = diffOf(manifest);
        assertEquals(1, diff.count(SyncAction.Action.DELETE), diff.actions()::toString);
        assertTrue(diff.blocksJoin(), "多余文件也要拦住，保证和服务器完全一致");

        SyncEngine.SyncReport report = apply(manifest, diff);
        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(java.util.List.of("mods/random-thing.jar"), report.deleted());
        assertFalse(Files.exists(clientDir.resolve("mods/random-thing.jar")));

        try (var walk = Files.walk(clientDir.resolve("ftyupater/trash"))) {
            assertTrue(walk.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().equals("random-thing.jar")),
                    "被清理的文件应该能在回收站里找到");
        }
        assertTrue(diffOf(fetch()).isInSync());
    }

    // ---------- 6. 可选模组 opt-out ----------

    @Test
    @DisplayName("玩家选了「我不要」的可选模组：不下发、不报错、也不拦进服")
    void optedOutOptionalIsSkippedAndDoesNotBlock() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        serverFile("clientpack/optional.jar", "OPTIONAL");
        publish();

        LocalState state = stateStore().load();
        // clientpack/optional.jar 发布后路径是 optional.jar（clientpack 前缀会被剥掉）
        state.setOptOut(Set.of("optional.jar"));
        stateStore().save(state);

        PackManifest manifest = fetch();
        DiffResult diff = diffOf(manifest);
        assertEquals(1, diff.count(SyncAction.Action.OPT_OUT), diff.actions()::toString);
        // 此时还缺必需的 mods/a.jar，所以仍然拦进服；OPT_OUT 本身不拦（下一段验证）

        SyncEngine.SyncReport report = apply(manifest, diff);
        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(java.util.List.of("mods/a.jar"), report.installed(),
                "opt-out 的可选模组不该被下载");
        assertFalse(Files.exists(clientDir.resolve("optional.jar")));

        // 必需模组装好之后，只剩 OPT_OUT，应当算「一致」且不拦进服
        DiffResult after = diffOf(fetch());
        assertTrue(after.isInSync(), after.actions()::toString);
        assertFalse(after.blocksJoin(), "可选模组不要，不能因此进不了服");
    }

    // ---------- 7. 手工禁用必需模组 ----------

    @Test
    @DisplayName("玩家手动禁用了必需模组（.disabled）：识别出来并拦住进服")
    void manualDisabledRequiredBlocksJoin() throws Exception {
        serverFile("mods/req.jar", "REQUIRED");
        PackManifest manifest = publish();

        write(clientDir, "mods/req.jar.disabled", "REQUIRED");

        DiffResult diff = diffOf(manifest);
        assertEquals(1, diff.count(SyncAction.Action.MANUAL_DISABLED), diff.actions()::toString);
        assertTrue(diff.blocksJoin());
        assertFalse(diff.isInSync());
    }

    // ---------- 8. 服务器没装模组 ----------

    @Test
    @DisplayName("服务器没装 FTYUpater：什么都不提示，直接进服")
    void serverWithoutModAutoContinues() {
        JoinDecision.Decision decision = JoinDecision.decide(true, probe(null), null, true);

        assertEquals(JoinDecision.Action.AUTO_CONTINUE, decision.action());
        assertEquals("server_no_mod", decision.reasonKey());
        assertFalse(decision.blocking());
    }

    // ---------- 9. 服务器装了但没配同步地址 ----------

    @Test
    @DisplayName("服务器装了模组但没公布地址：提示一句，仍允许进服")
    void serverWithModButNoSourceContinuesWithNotice() {
        JoinDecision.ProbeOutcome outcome = probe("IGNORESERVERONLY");
        assertEquals(JoinDecision.ProbeKind.HAS_NO_SOURCE, outcome.kind());

        JoinDecision.Decision decision = JoinDecision.decide(true, outcome, null, true);

        assertEquals(JoinDecision.Action.CONTINUE_WITH_NOTICE, decision.action());
        assertEquals("no_source", decision.reasonKey());
        assertFalse(decision.blocking());
    }

    // ---------- 10. 拉清单失败 ----------

    @Test
    @DisplayName("同步服务连不上：提示后仍可进服，不能把玩家关在门外")
    void fetchFailureContinuesWithNotice() throws Exception {
        Optional<PackManifest> nothing = PackClient.fetchManifest("http://127.0.0.1:1");
        assertTrue(nothing.isEmpty(), "连不上应该返回空，而不是抛异常");

        JoinDecision.Decision decision = JoinDecision.decide(true,
                probe(AdvertParser.format(base)), null, true);

        assertEquals(JoinDecision.Action.CONTINUE_WITH_NOTICE, decision.action());
        assertEquals("fetch_failed", decision.reasonKey());
        assertFalse(decision.blocking());
    }

    // ---------- 11. 同一内容两个路径 ----------

    @Test
    @DisplayName("同一份内容放在两个路径：只下载一次，两处都装好")
    void sameContentTwoPathsBothInstalled() throws Exception {
        serverFile("mods/dup.jar", "SAME-BYTES");
        serverFile("clientpack/mods/dup-copy.jar", "SAME-BYTES");
        PackManifest manifest = publish();

        DiffResult diff = diffOf(manifest);
        SyncEngine.SyncReport report = apply(manifest, diff);

        assertTrue(report.ok(), report.errors()::toString);
        assertEquals(2, report.installed().size(), report.installed()::toString);
        assertArrayEquals("SAME-BYTES".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(clientDir.resolve("mods/dup.jar")));
        // 服务器上的 clientpack/mods/dup-copy.jar 在客户端就是 mods/dup-copy.jar
        assertArrayEquals("SAME-BYTES".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(clientDir.resolve("mods/dup-copy.jar")));
    }

    // ---------- 12. 幂等 ----------

    @Test
    @DisplayName("连续同步两次：第二次什么都不做")
    void secondSyncIsNoOp() throws Exception {
        serverFile("mods/a.jar", "MOD-A");
        serverFile("clientpack/config/x.toml", "CONFIG-X");
        publish();

        assertTrue(syncNow().ok());
        DiffResult second = diffOf(fetch());

        assertTrue(second.isInSync(), second.actions()::toString);
        SyncEngine.SyncReport report = apply(fetch(), second);
        assertTrue(report.installed().isEmpty());
        assertTrue(report.updated().isEmpty());
        assertTrue(report.deleted().isEmpty());
        assertTrue(report.pending().isEmpty());
    }

    // ---------- 13. 黑名单文件不发布 ----------

    @Test
    @DisplayName("服务器黑名单里的文件：清单独没有，客户端也不会被要求下载")
    void blacklistedFileIsNotPublished() throws Exception {
        server.stop();

        Path storeDir = serverDir.resolve("ftyupater2");
        ContentScanner scanner = new ContentScanner(serverDir,
                Blacklist.of(java.util.List.of("mods/secret.jar")), Set.of("FTYUpater-2.0.0.jar"));
        ContentStore store = new ContentStore(storeDir);
        GenerationStore generations = new GenerationStore(storeDir);
        GenerationJournal journal = new GenerationJournal(storeDir.resolve("journal.jsonl"));
        publisher = new PackPublisher(scanner, store, generations, journal, "fty", "1.20.1", "forge-47.4.0", 2);
        server = new HttpSyncServer(0, "127.0.0.1",
                new FilePackRepository(store, generations, journal, 2), "2.0.0");
        server.start();
        base = "http://127.0.0.1:" + server.port();

        serverFile("mods/public.jar", "PUBLIC");
        serverFile("mods/secret.jar", "SECRET");
        PackManifest manifest = publish();

        assertTrue(manifest.entry("mods/public.jar").isPresent());
        assertTrue(manifest.entry("mods/secret.jar").isEmpty(), "黑名单文件不该出现在清单里");
    }
}
