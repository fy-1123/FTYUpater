package com.ftysync.core;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * 清单中的一个文件条目。
 *
 * <p>身份 = {@code path + sha256}，不依赖文件名里的版本号（见 ADR-011）。</p>
 *
 * @param path   相对游戏目录路径（POSIX '/' 分隔）
 * @param sha256 文件内容 SHA256（64 位小写十六进制）
 * @param size   字节数
 * @param origin 来源：服务器 mods / 客户端专属目录
 * @param role   必需（缺了不能进服）或可选（玩家可关）
 */
public record PackEntry(String path, String sha256, long size, Origin origin, Role role) {

    public PackEntry {
        path = PathGuard.normalize(path);
        sha256 = Hash.normalize(sha256);
        if (size < 0) {
            throw new IllegalArgumentException("文件大小不能为负: " + size);
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(role, "role");
    }

    /** 条目来源。 */
    public enum Origin {
        @SerializedName("server-mods")
        SERVER_MODS,
        @SerializedName("clientpack")
        CLIENTPACK
    }

    /** 条目角色：required 缺失会拦截进服，optional 玩家可 opt-out。 */
    public enum Role {
        @SerializedName("required")
        REQUIRED,
        @SerializedName("optional")
        OPTIONAL
    }
}
