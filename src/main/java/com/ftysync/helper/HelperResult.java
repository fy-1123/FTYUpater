package com.ftysync.helper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * helper 执行结果（{@code ftyupater/pending/helper-result.jsonl}）。
 *
 * <p>用 JSONL（一行一个扁平对象）而不是嵌套 JSON，是为了让 {@link MiniJson} 能直接读——
 * helper 跑在独立 JVM 里，不能依赖 Gson。</p>
 *
 * <p>客户端下次启动时读它、给玩家提示，然后把文件删掉（表示已告知）。</p>
 *
 * @param finishedAt 结束时间（ISO-8601）
 * @param applied    成功替换的路径
 * @param deleted    成功删除的路径
 * @param failed     失败的路径
 * @param errors     失败原因（与 {@code failed} 一一对应，格式 {@code path: reason}）
 * @param remaining  仍留在 pending.json 里待下次处理的操作数
 */
public record HelperResult(String finishedAt,
                           List<String> applied,
                           List<String> deleted,
                           List<String> failed,
                           List<String> errors,
                           int remaining) {

    public HelperResult {
        applied = applied == null ? List.of() : List.copyOf(applied);
        deleted = deleted == null ? List.of() : List.copyOf(deleted);
        failed = failed == null ? List.of() : List.copyOf(failed);
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (remaining < 0) {
            remaining = 0;
        }
    }

    /** 空结果（没有 pending 时 helper 也会写一条，让客户端知道「跑过了、没事做」）。 */
    public static HelperResult empty(String finishedAt) {
        return new HelperResult(finishedAt, List.of(), List.of(), List.of(), List.of(), 0);
    }

    /** 是否有失败项。 */
    public boolean hasFailures() {
        return !failed.isEmpty();
    }

    /** 是否有任何改动（用于决定要不要提示玩家）。 */
    public boolean didAnything() {
        return !applied.isEmpty() || !deleted.isEmpty();
    }

    /**
     * 序列化成 JSONL 文本。
     *
     * @return 每行一个事件；空结果也至少有一行 {@code finished}
     */
    public String toJsonl() {
        StringBuilder sb = new StringBuilder();
        line(sb, Map.of("type", "finished", "at", finishedAt == null ? "" : finishedAt));
        for (String path : applied) {
            line(sb, Map.of("type", "applied", "path", path));
        }
        for (String path : deleted) {
            line(sb, Map.of("type", "deleted", "path", path));
        }
        for (String path : failed) {
            line(sb, Map.of("type", "failed", "path", path));
        }
        for (String error : errors) {
            line(sb, Map.of("type", "error", "message", error));
        }
        line(sb, Map.of("type", "remaining", "count", Integer.toString(remaining)));
        return sb.toString();
    }

    /**
     * 解析 JSONL 文本；无法识别的行跳过（日志可能被手工编辑）。
     *
     * @param jsonl 文本；null/空返回 null
     * @return 解析结果；没有任何有效行时返回 null
     */
    public static HelperResult parse(String jsonl) {
        if (jsonl == null || jsonl.isBlank()) {
            return null;
        }
        String finishedAt = null;
        List<String> applied = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int remaining = 0;
        boolean any = false;
        for (String rawLine : jsonl.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Map<String, String> row;
            try {
                row = MiniJson.parseObject(line);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String type = row.get("type");
            if (type == null) {
                continue;
            }
            any = true;
            switch (type) {
                case "finished" -> finishedAt = row.get("at");
                case "applied" -> addIfPresent(applied, row.get("path"));
                case "deleted" -> addIfPresent(deleted, row.get("path"));
                case "failed" -> addIfPresent(failed, row.get("path"));
                case "error" -> addIfPresent(errors, row.get("message"));
                case "remaining" -> remaining = parseCount(row.get("count"));
                default -> {
                    // 未知类型：向前兼容，忽略
                }
            }
        }
        return any ? new HelperResult(finishedAt, applied, deleted, failed, errors, remaining) : null;
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void line(StringBuilder sb, Map<String, String> fields) {
        // Map.of 不保证顺序，这里固定按插入顺序重建，输出更稳定
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : new String[]{"type", "at", "path", "message", "count"}) {
            if (fields.containsKey(key)) {
                ordered.put(key, fields.get(key));
            }
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            sb.append('"').append(escape(entry.getValue())).append('"');
        }
        sb.append('}').append('\n');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
