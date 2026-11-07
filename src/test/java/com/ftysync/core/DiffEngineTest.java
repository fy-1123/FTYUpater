package com.ftysync.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ftysync.core.PackEntry.Origin.CLIENTPACK;
import static com.ftysync.core.PackEntry.Origin.SERVER_MODS;
import static com.ftysync.core.PackEntry.Role.OPTIONAL;
import static com.ftysync.core.PackEntry.Role.REQUIRED;
import static com.ftysync.core.SyncAction.Action.DELETE;
import static com.ftysync.core.SyncAction.Action.IGNORED;
import static com.ftysync.core.SyncAction.Action.INSTALL_OPTIONAL;
import static com.ftysync.core.SyncAction.Action.INSTALL_REQUIRED;
import static com.ftysync.core.SyncAction.Action.KEEP;
import static com.ftysync.core.SyncAction.Action.MANUAL_DISABLED;
import static com.ftysync.core.SyncAction.Action.OPT_OUT;
import static com.ftysync.core.SyncAction.Action.PENDING;
import static com.ftysync.core.SyncAction.Action.UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    private static final String SHA_LOCAL = Hash.sha256("local".getBytes(StandardCharsets.UTF_8));
    private static final String SHA_REMOTE = Hash.sha256("remote".getBytes(StandardCharsets.UTF_8));

    private static PackEntry entry(String path, String sha, PackEntry.Role role) {
        return new PackEntry(path, sha, 10, SERVER_MODS, role);
    }

    private static PackManifest manifest(PackEntry... entries) {
        return new PackManifest("fty", "g1", null, "2026-10-21T00:00:00Z", "1.20.1",
                "forge-47.4.0", List.of(entries));
    }

    private static LocalFile local(String path, String sha) {
        return new LocalFile(path, sha, 10);
    }

    private static DiffResult diff(PackManifest manifest, Map<String, LocalFile> present) {
        return DiffEngine.diff(manifest, present, Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    @Test
    void emptyPackAndEmptyLocalIsInSync() {
        DiffResult result = diff(manifest(), Map.of());
        assertTrue(result.isInSync());
        assertFalse(result.blocksJoin());
        assertEquals(0, result.actions().size());
    }

    @Test
    void missingRequiredIsInstallAndBlocksJoin() {
        DiffResult result = diff(manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)), Map.of());
        assertEquals(1, result.count(INSTALL_REQUIRED));
        assertFalse(result.isInSync());
        assertTrue(result.blocksJoin());
    }

    @Test
    void missingOptionalIsInstallOptionalAndDoesNotBlockJoin() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("clientpack/mods/opt.jar", SHA_REMOTE, OPTIONAL)),
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
        assertEquals(1, result.count(INSTALL_OPTIONAL));
        assertFalse(result.isInSync());
        assertFalse(result.blocksJoin());
    }

    @Test
    void matchingFileIsKeep() {
        DiffResult result = diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of("mods/a.jar", local("mods/a.jar", SHA_REMOTE)));
        assertEquals(1, result.count(KEEP));
        assertTrue(result.isInSync());
        assertFalse(result.blocksJoin());
    }

    @Test
    void outdatedRequiredIsUpdateAndBlocksJoin() {
        DiffResult result = diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of("mods/a.jar", local("mods/a.jar", SHA_LOCAL)));
        assertEquals(1, result.count(UPDATE));
        assertTrue(result.blocksJoin());
        assertEquals(SHA_LOCAL, result.of(UPDATE).get(0).fromSha256());
        assertEquals(SHA_REMOTE, result.of(UPDATE).get(0).toSha256());
    }

    @Test
    void outdatedOptionalDoesNotBlockJoin() {
        DiffResult result = diff(
                manifest(entry("clientpack/mods/opt.jar", SHA_REMOTE, OPTIONAL)),
                Map.of("clientpack/mods/opt.jar", local("clientpack/mods/opt.jar", SHA_LOCAL)));
        assertEquals(1, result.count(UPDATE));
        assertFalse(result.blocksJoin());
    }

    @Test
    void optedOutOptionalIsCompletelyExcluded() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("clientpack/mods/opt.jar", SHA_REMOTE, OPTIONAL)),
                Map.of(), Set.of(), Set.of("clientpack/mods/opt.jar"), Set.of(), Set.of(), Map.of());
        assertEquals(1, result.count(OPT_OUT));
        assertFalse(result.count(INSTALL_OPTIONAL) > 0);
        assertTrue(result.isInSync());
        assertFalse(result.blocksJoin());
    }

    @Test
    void optOutCannotSkipRequiredEntry() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of(), Set.of(), Set.of("mods/a.jar"), Set.of(), Set.of(), Map.of());
        assertEquals(1, result.count(INSTALL_REQUIRED));
        assertEquals(0, result.count(OPT_OUT));
        assertTrue(result.blocksJoin());
    }

    @Test
    void ignoredOptionalIsCompletelyExcluded() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("clientpack/config/x.json", SHA_REMOTE, OPTIONAL)),
                Map.of(), Set.of(), Set.of(), Set.of("clientpack/config/x.json"), Set.of(), Map.of());
        assertEquals(1, result.count(IGNORED));
        assertTrue(result.isInSync());
    }

    @Test
    void extraManagedFileIsDeletedAndBlocksJoin() {
        DiffResult result = diff(manifest(), Map.of("mods/extra.jar", local("mods/extra.jar", SHA_LOCAL)));
        assertEquals(1, result.count(DELETE));
        assertTrue(result.blocksJoin());
        assertEquals(SHA_LOCAL, result.of(DELETE).get(0).fromSha256());
    }

    @Test
    void protectedFileIsNeverDeleted() {
        DiffResult result = DiffEngine.diff(
                manifest(), Map.of("mods/fake.jar", local("mods/fake.jar", SHA_LOCAL)),
                Set.of(), Set.of(), Set.of(), Set.of("mods/fake.jar"), Map.of());
        assertEquals(1, result.count(KEEP));
        assertEquals(0, result.count(DELETE));
        assertFalse(result.blocksJoin());
    }

    @Test
    void manualDisabledRequiredBlocksJoin() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of(), Set.of("mods/a.jar"), Set.of(), Set.of(), Set.of(), Map.of());
        assertEquals(1, result.count(MANUAL_DISABLED));
        assertTrue(result.blocksJoin());
    }

    @Test
    void manualDisabledOptionalIsLeftAlone() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("clientpack/mods/opt.jar", SHA_REMOTE, OPTIONAL)),
                Map.of(), Set.of("clientpack/mods/opt.jar"), Set.of(), Set.of(), Set.of(), Map.of());
        assertEquals(1, result.count(MANUAL_DISABLED));
        assertFalse(result.blocksJoin());
    }

    @Test
    void pendingActionWithSameTargetIsPendingNotDuplicated() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of("mods/a.jar", local("mods/a.jar", SHA_LOCAL)),
                Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of("mods/a.jar", SHA_REMOTE));
        assertEquals(1, result.count(PENDING));
        assertEquals(0, result.count(UPDATE));
        assertTrue(result.blocksJoin());
    }

    @Test
    void pendingForDifferentTargetStillProducesNormalAction() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED)),
                Map.of("mods/a.jar", local("mods/a.jar", SHA_LOCAL)),
                Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of("mods/a.jar", SHA_LOCAL));
        assertEquals(1, result.count(UPDATE));
        assertEquals(0, result.count(PENDING));
    }

    @Test
    void everyPathGetsAtMostOneAction() {
        DiffResult result = DiffEngine.diff(
                manifest(entry("mods/a.jar", SHA_REMOTE, REQUIRED),
                        entry("clientpack/mods/opt.jar", SHA_REMOTE, OPTIONAL)),
                Map.of("mods/a.jar", local("mods/a.jar", SHA_LOCAL),
                        "mods/extra.jar", local("mods/extra.jar", SHA_LOCAL)),
                Set.of(), Set.of("clientpack/mods/opt.jar"), Set.of(), Set.of(), Map.of());
        long uniquePaths = result.actions().stream().map(SyncAction::path).distinct().count();
        assertEquals(result.actions().size(), uniquePaths);
    }
}
