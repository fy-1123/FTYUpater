package com.ftysync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link V1Migration} 的测试（M6-2）：v1 的文本配置迁到 v2。
 */
class V1MigrationTest {

    @TempDir
    Path gameDir;

    private Path configDir() throws IOException {
        Path dir = gameDir.resolve("config");
        Files.createDirectories(dir);
        return dir;
    }

    private void v1File(String name, String... lines) throws IOException {
        Files.write(configDir().resolve(name), List.of(lines), StandardCharsets.UTF_8);
    }

    private void modsFile(String name) throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve(name), "jar", StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ 检测

    @Test
    void reportsNothingWhenNoV1Files() throws IOException {
        V1Migration migration = new V1Migration(configDir(), gameDir);
        assertFalse(migration.hasV1Files());
        V1Migration.Report report = migration.migrate();
        assertFalse(report.ran());
        assertFalse(report.didAnything());
        assertTrue(report.describe().contains("没有需要迁移"), report.describe());
    }

    // ------------------------------------------------------------ 忽略列表

    @Test
    void migratesIgnoreList() throws IOException {
        v1File(V1Migration.IGNORE_FILE, "someclientmod.jar", "", "# 注释", "another.jar");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertTrue(report.ran());
        assertEquals(2, report.ignored().size());
        assertTrue(report.ignored().contains("mods/someclientmod.jar"));
        assertTrue(report.ignored().contains("mods/another.jar"));
    }

    @Test
    void ignoreListKeepsRealCaseFromDisk() throws IOException {
        modsFile("SomeClientMod.jar");
        v1File(V1Migration.IGNORE_FILE, "someclientmod.jar");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertTrue(report.ignored().contains("mods/SomeClientMod.jar"),
                "磁盘上的真实大小写优先：" + report.ignored());
    }

    @Test
    void ignoreListSkipsPathTraversal() throws IOException {
        v1File(V1Migration.IGNORE_FILE, "../evil.jar", "a/b.jar", "ok.jar");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertEquals(1, report.ignored().size(), report.ignored().toString());
        assertTrue(report.ignored().contains("mods/ok.jar"));
    }

    // ------------------------------------------------------------ 待删除

    @Test
    void migratesPendingDeletions() throws IOException {
        v1File(V1Migration.PENDING_FILE, "oldmod.jar");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertEquals(1, report.pending().size());
        PendingOp op = report.pending().get(0);
        assertEquals(PendingOp.Operation.DELETE, op.operation());
        assertEquals("mods/oldmod.jar", op.path());
        assertEquals(null, op.sha256(), "删除操作不带哈希");
        assertTrue(op.addedAt() != null && !op.addedAt().isBlank());
    }

    // ------------------------------------------------------------ 服务器记忆

    @Test
    void migratesRememberedServers() throws IOException {
        v1File(V1Migration.SERVERS_FILE,
                "127.0.0.1:25566=http://192.168.0.250:8081",
                "# 注释行",
                "badline",
                "MC.Example.COM=http://example.com:8081");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        Map<String, String> servers = report.servers();
        assertEquals(2, servers.size(), servers.toString());
        assertEquals("http://192.168.0.250:8081", servers.get("127.0.0.1:25566"));
        assertEquals("http://example.com:8081", servers.get("mc.example.com"), "host 统一小写");
    }

    // ------------------------------------------------------------ 幂等 / 留证

    @Test
    void renamesV1FilesSoSecondRunDoesNothing() throws IOException {
        v1File(V1Migration.IGNORE_FILE, "a.jar");
        V1Migration migration = new V1Migration(configDir(), gameDir);
        assertTrue(migration.migrate().ran());
        assertFalse(migration.hasV1Files(), "旧文件应已改名");
        assertTrue(Files.isRegularFile(configDir().resolve(V1Migration.IGNORE_FILE + ".migrated")),
                "旧文件要留证，不能直接删");
        assertFalse(migration.migrate().ran(), "第二次启动不应再迁移");
    }

    @Test
    void migratesAllThreeFilesAtOnce() throws IOException {
        v1File(V1Migration.IGNORE_FILE, "a.jar");
        v1File(V1Migration.PENDING_FILE, "b.jar");
        v1File(V1Migration.SERVERS_FILE, "h=http://x");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertTrue(report.didAnything());
        String described = report.describe();
        assertTrue(described.contains("忽略 1"), described);
        assertTrue(described.contains("待删除 1"), described);
        assertTrue(described.contains("服务器记忆 1"), described);
    }

    @Test
    void toModsPathRejectsBlankAndSeparators() throws IOException {
        V1Migration migration = new V1Migration(configDir(), gameDir);
        assertEquals(null, migration.toModsPath(null));
        assertEquals(null, migration.toModsPath("   "));
        assertEquals(null, migration.toModsPath("a/b.jar"));
        assertEquals("mods/x.jar", migration.toModsPath("x.jar"));
    }

    @Test
    void toleratesUnreadableContent() throws IOException {
        // 空文件、只有注释：不算迁移内容，但旧文件仍应被改名（避免反复尝试）
        v1File(V1Migration.IGNORE_FILE, "# 只有注释");
        V1Migration.Report report = new V1Migration(configDir(), gameDir).migrate();
        assertTrue(report.ran());
        assertFalse(report.didAnything());
    }
}
