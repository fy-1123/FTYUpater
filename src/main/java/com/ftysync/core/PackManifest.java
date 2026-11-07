package com.ftysync.core;

import com.google.gson.JsonParseException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 同步清单（协议 v2，JSON 结构见 {@code docs/protocol.md} 2.2）。
 *
 * <p>{@code generation} 由服务端生成：内容不变则不变，内容变化才变。</p>
 */
public final class PackManifest {

    /** 协议版本；不匹配即拒绝同步。 */
    public static final int SCHEMA = 2;

    private final String packId;
    private final String generation;
    private final String parentGeneration;
    private final String createdAt;
    private final String minecraft;
    private final String loader;
    private final Map<String, PackEntry> entries;

    public PackManifest(String packId, String generation, String parentGeneration, String createdAt,
                        String minecraft, String loader, List<PackEntry> entries) {
        if (packId == null || packId.isBlank()) {
            throw new ManifestException("清单缺少 packId");
        }
        if (generation == null || generation.isBlank()) {
            throw new ManifestException("清单缺少 generation");
        }
        this.packId = packId;
        this.generation = generation;
        this.parentGeneration = parentGeneration;
        this.createdAt = createdAt;
        this.minecraft = minecraft;
        this.loader = loader;
        Map<String, PackEntry> map = new LinkedHashMap<>();
        if (entries != null) {
            for (PackEntry entry : entries) {
                if (map.putIfAbsent(entry.path(), entry) != null) {
                    throw new ManifestException("清单含重复路径: " + entry.path());
                }
            }
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    public String packId() {
        return packId;
    }

    public String generation() {
        return generation;
    }

    public Optional<String> parentGeneration() {
        return Optional.ofNullable(parentGeneration);
    }

    public Optional<String> createdAt() {
        return Optional.ofNullable(createdAt);
    }

    public Optional<String> minecraft() {
        return Optional.ofNullable(minecraft);
    }

    public Optional<String> loader() {
        return Optional.ofNullable(loader);
    }

    /** 按清单顺序的条目，path → 条目。 */
    public Map<String, PackEntry> entries() {
        return entries;
    }

    public Optional<PackEntry> entry(String path) {
        return Optional.ofNullable(entries.get(path));
    }

    public int size() {
        return entries.size();
    }

    /** 序列化为协议 JSON。 */
    public String toJson() {
        Dto dto = new Dto(SCHEMA, packId, generation, parentGeneration, createdAt, minecraft, loader,
                List.copyOf(entries.values()));
        return Json.GSON.toJson(dto);
    }

    /**
     * 解析并校验协议 JSON。
     *
     * @throws ManifestException schema 不匹配、缺字段、重复路径、非法路径/哈希
     */
    public static PackManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ManifestException("清单内容为空");
        }
        Dto dto;
        try {
            dto = Json.GSON.fromJson(json, Dto.class);
        } catch (RuntimeException e) {
            // Gson 会把 record 构造器抛出的校验异常包成 RuntimeException，这里统一转为清单异常
            if (e instanceof ManifestException manifestException) {
                throw manifestException;
            }
            throw new ManifestException("清单 JSON 解析失败: " + e.getMessage(), e);
        }
        if (dto == null) {
            throw new ManifestException("清单 JSON 为空");
        }
        if (dto.schema() != SCHEMA) {
            throw new ManifestException("清单协议版本不匹配: 收到 " + dto.schema() + "，客户端只支持 " + SCHEMA);
        }
        if (dto.files() == null) {
            throw new ManifestException("清单缺少 files 列表");
        }
        List<PackEntry> entries = new java.util.ArrayList<>(dto.files().size());
        for (PackEntry entry : dto.files()) {
            if (entry == null) {
                throw new ManifestException("清单 files 含空条目");
            }
            try {
                // PackEntry 的构造器已完成 path/sha256/size 校验
                entries.add(new PackEntry(entry.path(), entry.sha256(), entry.size(), entry.origin(), entry.role()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ManifestException("清单条目非法: " + e.getMessage(), e);
            }
        }
        try {
            return new PackManifest(dto.packId(), dto.generation(), dto.parentGeneration(),
                    dto.createdAt(), dto.minecraft(), dto.loader(), entries);
        } catch (IllegalArgumentException e) {
            throw new ManifestException("清单非法: " + e.getMessage(), e);
        }
    }

    private record Dto(int schema, String packId, String generation, String parentGeneration,
                       String createdAt, String minecraft, String loader, List<PackEntry> files) {
    }
}
