package com.ftysync.client;

import com.ftysync.core.LocalState;
import com.ftysync.core.PackManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RollbackDetect} 的测试（M5-5）：服务器代际与本地代际的先后判断。
 */
class RollbackDetectTest {

    private static PackManifest manifest(String generation, String parent) {
        return new PackManifest("fty", generation, parent, "2026-11-08T00:00:00Z",
                "1.20.1", "forge-47.4.0", List.of());
    }

    private static LocalState stateWith(String current, String previous) {
        LocalState state = new LocalState();
        state.setGeneration(previous);
        state.advanceGeneration(current);
        return state;
    }

    // ------------------------------------------------------------ 基本比较

    @Test
    void sameGenerationIsSame() {
        RollbackDetect.Result result = RollbackDetect.compare("20261021-100000-aaa", "20261021-100000-aaa");
        assertEquals(RollbackDetect.Kind.SAME, result.kind());
        assertFalse(result.serverRolledBack());
        assertFalse(result.serverIsNewer());
    }

    @Test
    void laterGenerationIsNewer() {
        RollbackDetect.Result result = RollbackDetect.compare("20261021-100000-aaa", "20261022-100000-bbb");
        assertEquals(RollbackDetect.Kind.SERVER_NEWER, result.kind());
        assertTrue(result.serverIsNewer());
    }

    @Test
    void earlierGenerationIsOlder() {
        RollbackDetect.Result result = RollbackDetect.compare("20261022-100000-bbb", "20261021-100000-aaa");
        assertEquals(RollbackDetect.Kind.SERVER_OLDER, result.kind());
        assertTrue(result.serverRolledBack());
    }

    @Test
    void blankOrNullIsUnknown() {
        assertEquals(RollbackDetect.Kind.UNKNOWN, RollbackDetect.compare(null, "g1").kind());
        assertEquals(RollbackDetect.Kind.UNKNOWN, RollbackDetect.compare("g1", "  ").kind());
        assertEquals(RollbackDetect.Kind.UNKNOWN, RollbackDetect.compare((String) null, null).kind());
    }

    // ------------------------------------------------------------ 用 parent 佐证

    @Test
    void parentPointingAtLocalMeansServerIsNewer() {
        LocalState state = stateWith("20261021-100000-aaa", null);
        PackManifest server = manifest("20261022-100000-bbb", "20261021-100000-aaa");
        RollbackDetect.Result result = RollbackDetect.compare(state, server);
        assertEquals(RollbackDetect.Kind.SERVER_NEWER, result.kind());
    }

    @Test
    void previousGenerationMatchingServerMeansRollback() {
        // 本地从 g2 升到 g3，服务器又退回 g2
        LocalState state = stateWith("20261022-100000-bbb", "20261021-100000-aaa");
        PackManifest server = manifest("20261021-100000-aaa", null);
        RollbackDetect.Result result = RollbackDetect.compare(state, server);
        assertEquals(RollbackDetect.Kind.SERVER_OLDER, result.kind());
        assertTrue(result.describe().contains("回退"), result.describe());
    }

    @Test
    void fallsBackToIdOrderWhenParentIsUnrelated() {
        LocalState state = stateWith("20261022-100000-bbb", "20261020-000000-zzz");
        PackManifest server = manifest("20261023-100000-ccc", "20261019-000000-yyy");
        RollbackDetect.Result result = RollbackDetect.compare(state, server);
        assertEquals(RollbackDetect.Kind.SERVER_NEWER, result.kind());
    }

    // ------------------------------------------------------------ 边界

    @Test
    void noLocalGenerationMeansInstall() {
        RollbackDetect.Result result = RollbackDetect.compare(new LocalState(), manifest("20261021-100000-aaa", null));
        assertEquals(RollbackDetect.Kind.SERVER_NEWER, result.kind());
    }

    @Test
    void nullManifestIsUnknown() {
        RollbackDetect.Result result = RollbackDetect.compare(new LocalState(), null);
        assertEquals(RollbackDetect.Kind.UNKNOWN, result.kind());
        assertEquals(RollbackDetect.Kind.UNKNOWN, RollbackDetect.compare((String) null, (String) null).kind());
    }

    @Test
    void describeNeverNull() {
        for (RollbackDetect.Kind kind : RollbackDetect.Kind.values()) {
            RollbackDetect.Result result = new RollbackDetect.Result(kind, null, null);
            assertTrue(result.describe() != null && !result.describe().isBlank(), kind.name());
        }
    }

    @Test
    void resultRejectsNullKind() {
        try {
            new RollbackDetect.Result(null, "a", "b");
            throw new AssertionError("应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("kind"), expected.getMessage());
        }
    }
}
