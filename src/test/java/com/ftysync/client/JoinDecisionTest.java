package com.ftysync.client;

import com.ftysync.core.DiffResult;
import com.ftysync.core.PackEntry;
import com.ftysync.core.SyncAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinDecisionTest {

    private static final String SHA = "a".repeat(64);
    private static final String SHA2 = "b".repeat(64);

    private static Advert advert() {
        return new Advert(2, "http://127.0.0.1:8081");
    }

    private static JoinDecision.ProbeOutcome source() {
        return new JoinDecision.ProbeOutcome(JoinDecision.ProbeKind.HAS_SOURCE, advert());
    }

    private static DiffResult diff(SyncAction... actions) {
        return new DiffResult(List.of(actions));
    }

    private static SyncAction keep() {
        return new SyncAction("mods/a.jar", SyncAction.Action.KEEP, SHA, SHA, PackEntry.Role.REQUIRED);
    }

    @Test
    void checkDisabledAlwaysContinues() {
        JoinDecision.Decision decision = JoinDecision.decide(false,
                new JoinDecision.ProbeOutcome(JoinDecision.ProbeKind.NO_FTYUPATER, null), null, true);
        assertEquals(JoinDecision.Action.AUTO_CONTINUE, decision.action());
        assertFalse(decision.blocking());
        assertEquals("check_disabled", decision.reasonKey());
    }

    @Test
    void serverWithoutModContinues() {
        JoinDecision.Decision decision = JoinDecision.decide(true,
                new JoinDecision.ProbeOutcome(JoinDecision.ProbeKind.NO_FTYUPATER, null), null, true);
        assertEquals(JoinDecision.Action.AUTO_CONTINUE, decision.action());
        assertEquals("server_no_mod", decision.reasonKey());
    }

    @Test
    void serverWithModButNoSourceShowsNotice() {
        JoinDecision.Decision decision = JoinDecision.decide(true,
                new JoinDecision.ProbeOutcome(JoinDecision.ProbeKind.HAS_NO_SOURCE, null), null, true);
        assertEquals(JoinDecision.Action.CONTINUE_WITH_NOTICE, decision.action());
        assertFalse(decision.blocking());
        assertEquals("no_source", decision.reasonKey());
    }

    @Test
    void fetchFailureShowsNotice() {
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), null, true);
        assertEquals(JoinDecision.Action.CONTINUE_WITH_NOTICE, decision.action());
        assertFalse(decision.blocking());
        assertEquals("fetch_failed", decision.reasonKey());
    }

    @Test
    void inSyncAutoContinues() {
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), diff(keep()), true);
        assertEquals(JoinDecision.Action.AUTO_CONTINUE, decision.action());
        assertFalse(decision.blocking());
        assertEquals("in_sync", decision.reasonKey());
    }

    @Test
    void inSyncCanShowScreenWhenAutoContinueDisabled() {
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), diff(keep()), false);
        assertEquals(JoinDecision.Action.SHOW_SCREEN, decision.action());
        assertFalse(decision.blocking());
        assertEquals("in_sync", decision.reasonKey());
    }

    @Test
    void requiredMissingBlocks() {
        SyncAction missing = new SyncAction("mods/req.jar", SyncAction.Action.INSTALL_REQUIRED,
                null, SHA2, PackEntry.Role.REQUIRED);
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), diff(missing), true);
        assertEquals(JoinDecision.Action.SHOW_SCREEN, decision.action());
        assertTrue(decision.blocking());
        assertEquals("diff", decision.reasonKey());
    }

    @Test
    void optionalOnlyDoesNotBlock() {
        SyncAction optional = new SyncAction("mods/opt.jar", SyncAction.Action.INSTALL_OPTIONAL,
                null, SHA2, PackEntry.Role.OPTIONAL);
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), diff(optional), true);
        assertEquals(JoinDecision.Action.SHOW_SCREEN, decision.action());
        assertFalse(decision.blocking());
    }

    @Test
    void extraCleanupBlocks() {
        SyncAction extra = new SyncAction("mods/extra.jar", SyncAction.Action.DELETE, SHA2, null, null);
        JoinDecision.Decision decision = JoinDecision.decide(true, source(), diff(extra), true);
        assertTrue(decision.blocking());
    }

    @Test
    void fromMarkerRecognizesServerStates() {
        assertEquals(JoinDecision.ProbeKind.NO_FTYUPATER,
                JoinDecision.ProbeOutcome.fromMarker(null).kind());
        assertEquals(JoinDecision.ProbeKind.HAS_NO_SOURCE,
                JoinDecision.ProbeOutcome.fromMarker("IGNORESERVERONLY").kind());
        assertEquals(JoinDecision.ProbeKind.HAS_NO_SOURCE,
                JoinDecision.ProbeOutcome.fromMarker("garbage").kind());
        assertEquals(JoinDecision.ProbeKind.HAS_NO_SOURCE,
                JoinDecision.ProbeOutcome.fromMarker("v3|http://x").kind());

        JoinDecision.ProbeOutcome parsed = JoinDecision.ProbeOutcome.fromMarker("v2|http://127.0.0.1:8081");
        assertEquals(JoinDecision.ProbeKind.HAS_SOURCE, parsed.kind());
        assertNotNull(parsed.advert());
        assertEquals("http://127.0.0.1:8081", parsed.advert().baseUrl());
    }
}
