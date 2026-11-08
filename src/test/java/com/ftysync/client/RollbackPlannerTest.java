package com.ftysync.client;

import com.ftysync.core.LocalState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RollbackPlanner} 的纯逻辑测试（M5-3）。
 */
class RollbackPlannerTest {

    private static LocalState stateAt(String current, String previous) {
        LocalState state = new LocalState();
        state.setGeneration(current);
        state.setPreviousGeneration(previous);
        return state;
    }

    private static ServerGeneration gen(String id, int files) {
        return new ServerGeneration(id, "2026-11-0" + Math.min(9, files) + "T10:00:00Z", files);
    }

    // ------------------------------------------------------------ 候选合并

    @Test
    void noCandidatesWhenNothingToGoBackTo() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g3", null), List.of(), List.of());
        assertTrue(list.isEmpty());
    }

    @Test
    void currentGenerationIsNeverACandidate() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g3", "g2"), List.of("g3", "g2"), List.of(gen("g3", 5), gen("g2", 4)));
        assertTrue(list.stream().noneMatch(c -> c.generation().equals("g3")),
                "当前代际不该出现在回退列表里");
        assertEquals(1, list.size());
        assertEquals("g2", list.get(0).generation());
    }

    @Test
    void mergesLocalAndServerIntoBoth() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g3", "g2"), List.of("g2"), List.of(gen("g2", 4)));
        assertEquals(1, list.size(), "同一代际两边都有时只列一条");
        assertEquals(RollbackPlanner.Source.BOTH, list.get(0).source());
        assertTrue(list.get(0).offline(), "有本地备份就能离线回退");
    }

    @Test
    void serverOnlyCandidateIsNotOffline() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g3", null), List.of(), List.of(gen("g2", 4)));
        assertEquals(RollbackPlanner.Source.SERVER, list.get(0).source());
        assertFalse(list.get(0).offline());
    }

    @Test
    void localOnlyCandidateSurvivesServerPruning() {
        // 服务器只保留最近 2 代，老代际被剪掉了，但本地还留着备份
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g5", "g4"), List.of("g4", "g2"), List.of(gen("g4", 4)));
        assertEquals(2, list.size(), "本地独有的老代际也要列出来");
        RollbackPlanner.Candidate g2 = list.stream()
                .filter(c -> c.generation().equals("g2")).findFirst().orElseThrow();
        assertEquals(RollbackPlanner.Source.LOCAL, g2.source());
        assertTrue(g2.offline());
    }

    // ------------------------------------------------------------ 排序

    @Test
    void previousGenerationComesFirst() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g5", "g4"), List.of("g4", "g3"), List.of(gen("g4", 4), gen("g3", 3)));
        assertEquals("g4", list.get(0).generation(), "刚退下来的那代要排最前");
        assertTrue(list.get(0).isPrevious());
    }

    @Test
    void previousIsFlaggedEvenWhenServerOrderDiffers() {
        // 服务器顺序是 g4, g3；previous 是 g3 —— 也要把 g3 提上来
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g5", "g3"), List.of(), List.of(gen("g4", 4), gen("g3", 3)));
        assertEquals("g3", list.get(0).generation());
        assertTrue(list.get(0).isPrevious());
        assertFalse(list.get(1).isPrevious());
    }

    // ------------------------------------------------------------ 默认目标

    @Test
    void defaultTargetPrefersPrevious() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g5", "g4"), List.of("g4", "g3"), List.of());
        Optional<RollbackPlanner.Candidate> target = RollbackPlanner.defaultTarget(list);
        assertTrue(target.isPresent());
        assertEquals("g4", target.get().generation());
    }

    @Test
    void defaultTargetFallsBackToFirst() {
        List<RollbackPlanner.Candidate> list = RollbackPlanner.candidates(
                stateAt("g5", null), List.of(), List.of(gen("g4", 4), gen("g3", 3)));
        assertEquals("g4", RollbackPlanner.defaultTarget(list).orElseThrow().generation());
    }

    @Test
    void defaultTargetEmptyWhenNoCandidates() {
        assertTrue(RollbackPlanner.defaultTarget(List.of()).isEmpty());
        assertTrue(RollbackPlanner.defaultTarget(null).isEmpty());
    }

    // ------------------------------------------------------------ 边界

    @Test
    void nullInputsAreTolerated() {
        assertTrue(RollbackPlanner.candidates(null, null, null).isEmpty());
    }

    @Test
    void candidateRejectsBlankGeneration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RollbackPlanner.Candidate("  ", RollbackPlanner.Source.LOCAL, "", 0, false));
    }

    @Test
    void describeMentionsSourceAndCount() {
        RollbackPlanner.Candidate c = new RollbackPlanner.Candidate(
                "g4", RollbackPlanner.Source.BOTH, "", 7, true);
        String text = c.describe();
        assertTrue(text.contains("g4"), text);
        assertTrue(text.contains("7"), text);
        assertTrue(text.contains("本地备份"), text);
        assertTrue(text.contains("服务器"), text);
    }
}
