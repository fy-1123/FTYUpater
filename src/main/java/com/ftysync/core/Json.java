package com.ftysync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 统一 JSON 编解码入口。
 *
 * <p>Gson 由 Minecraft/Forge 运行环境自带（1.20.1 为 2.10.1），因此不额外引入依赖；
 * {@code core} 包只使用 Gson，不引用任何 Minecraft 类。</p>
 */
public final class Json {

    /** 全局共享实例：pretty 便于人工排查，不转义 HTML 便于中文日志/文件。 */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 单行 JSONL 用（journal.jsonl 一行一条记录，不能带换行）。 */
    public static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }
}
