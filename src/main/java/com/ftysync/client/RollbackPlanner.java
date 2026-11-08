package com.ftysync.client;

import com.ftysync.core.LocalState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 回退候选的计算（M5-3，纯逻辑，可单测）。
 *
 * <p>回退有两个来源，优先级不同：</p>
 * <ol>
 *   <li><b>本地备份</b>：{@code ftyupater/backup/<代际>/} 里存着「同步到那一代之前」的文件。
 *       不依赖服务器，服务器把老代际剪掉了也能用。</li>
 *   <li><b>服务器历史</b>：{@code GET /api/v1/generations} 里还在保留期内的代际。
 *       需要联网，但文件齐、哈希可校验。</li>
 * </ol>
 *
 * <p>同一个代际两边都有时只列一条，标记为「本地 + 服务器」，回退时优先走本地。</p>
 */
public final class RollbackPlanner {

    /** 回退候选的来源。 */
    public enum Source {
        /** 只有本地备份。 */
        LOCAL,
        /** 只有服务器历史清单。 */
        SERVER,
        /** 两边都有。 */
        BOTH
    }

    /**
     * 一个回退候选。
     *
     * @param generation 代际 id
     * @param source     来源
     * @param createdAt  创建时间（服务器给的；本地备份可能为空串）
     * @param files      文件数（服务器给的；本地备份按实际文件数）
     * @param isPrevious 是否是「刚退下来的那一代」（state.json 里的 previousGeneration）
     */
    public record Candidate(String generation, Source source, String createdAt, int files,
                            boolean isPrevious) {

        public Candidate {
            if (generation == null || generation.isBlank()) {
                throw new IllegalArgumentException("代际 id 不能为空");
            }
            createdAt = createdAt == null ? "" : createdAt;
            if (files < 0) {
                files = 0;
            }
        }

        /** 能否离线回退（本地有备份）。 */
        public boolean offline() {
            return source == Source.LOCAL || source == Source.BOTH;
        }

        /** 给玩家看的一行摘要。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(generation);
            if (isPrevious) {
                sb.append("（刚退下来的）");
            }
            sb.append(" · ").append(files).append(" 个文件");
            if (source == Source.LOCAL) {
                sb.append(" · 本地备份");
            } else if (source == Source.SERVER) {
                sb.append(" · 服务器");
            } else {
                sb.append(" · 本地备份 + 服务器");
            }
            return sb.toString();
        }
    }

    private RollbackPlanner() {
    }

    /**
     * 算出可回退的候选列表。
     *
     * @param state             本地状态（用它的 generation / previousGeneration）
     * @param localBackups      本地备份里有哪些代际（{@code backup/} 下的目录名）
     * @param serverGenerations 服务器保留的代际；拉不到时传空表
     * @return 候选列表，最近的在前
     */
    public static List<Candidate> candidates(LocalState state,
                                             List<String> localBackups,
                                             List<ServerGeneration> serverGenerations) {
        String current = state == null ? null : state.generation();
        String previous = state == null ? null : state.previousGeneration();
        List<String> local = localBackups == null ? List.of() : localBackups;
        List<ServerGeneration> remote = serverGenerations == null ? List.of() : serverGenerations;

        // 用 LinkedHashMap 去重，同时保留「服务器顺序（最新在前）」这个主序
        Map<String, Candidate> merged = new LinkedHashMap<>();

        // 先按服务器顺序铺一遍
        for (ServerGeneration gen : remote) {
            String id = gen.generation();
            if (id.equals(current)) {
                continue;
            }
            boolean inLocal = local.contains(id);
            merged.put(id, new Candidate(id, inLocal ? Source.BOTH : Source.SERVER,
                    gen.createdAt(), gen.files(), id.equals(previous)));
        }
        // 再补上只在本地有的（服务器已剪掉的）
        for (String id : local) {
            if (id.equals(current) || merged.containsKey(id)) {
                continue;
            }
            merged.put(id, new Candidate(id, Source.LOCAL, "", 0, id.equals(previous)));
        }

        List<Candidate> result = new ArrayList<>(merged.values());
        // 「刚退下来的」提到最前：玩家点回退，十有八九是想反悔上一次更新
        result.sort((a, b) -> Boolean.compare(b.isPrevious(), a.isPrevious()));
        return List.copyOf(result);
    }

    /**
     * 默认回退目标：优先「刚退下来的那一代」，否则取第一个候选。
     *
     * @return 没有可回退的候选时为空
     */
    public static Optional<Candidate> defaultTarget(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().filter(Candidate::isPrevious).findFirst()
                .or(() -> Optional.of(candidates.get(0)));
    }
}
