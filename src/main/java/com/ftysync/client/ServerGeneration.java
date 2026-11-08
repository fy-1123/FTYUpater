package com.ftysync.client;

import com.ftysync.core.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器保留的一代（{@code GET /api/v1/generations} 里的一项）。
 *
 * @param generation 代际 id（形如 {@code 20261021-143332-4784ccb4}）
 * @param createdAt  创建时间（ISO-8601）
 * @param files      该代际的文件数
 */
public record ServerGeneration(String generation, String createdAt, int files) {

    public ServerGeneration {
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException("代际 id 不能为空");
        }
        generation = generation.trim();
        createdAt = createdAt == null ? "" : createdAt.trim();
        if (files < 0) {
            files = 0;
        }
    }

    /**
     * 解析 {@code {"schema":2,"generations":[{...}]}}。
     *
     * <p>格式不对直接抛 {@link IllegalArgumentException}——调用方（{@link PackClient}）
     * 会把它变成 {@code Optional.empty()}。</p>
     *
     * @param json 响应正文
     * @return 代际列表，顺序与服务器一致（最新在前）
     */
    public static List<ServerGeneration> parseList(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("代际列表为空");
        }
        Response response = Json.GSON.fromJson(json, Response.class);
        if (response == null || response.generations == null) {
            throw new IllegalArgumentException("代际列表格式不对");
        }
        List<ServerGeneration> list = new ArrayList<>(response.generations.size());
        for (Item item : response.generations) {
            if (item == null || item.generation == null || item.generation.isBlank()) {
                // 单条坏掉不影响其它：跳过
                continue;
            }
            list.add(new ServerGeneration(item.generation, item.createdAt, item.files));
        }
        return List.copyOf(list);
    }

    /** Gson 用的外壳。 */
    private static final class Response {
        private List<Item> generations;
    }

    /** Gson 用的单项。 */
    private static final class Item {
        private String generation;
        private String createdAt;
        private int files;
    }
}
