package com.ftysync.server;

import com.ftysync.core.Hash;
import com.ftysync.core.PackEntry;
import com.ftysync.core.PathGuard;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 扫描结果：一个待发布文件。
 *
 * @param path   相对游戏目录的路径（POSIX '/'），如 {@code mods/jei.jar}
 * @param source 服务器上的实际文件
 * @param sha256 文件内容 SHA256
 * @param size   字节数
 * @param origin 来源（服务器 mods / 客户端专属目录）
 * @param role   必需 / 可选
 */
public record ScannedFile(String path, Path source, String sha256, long size,
                          PackEntry.Origin origin, PackEntry.Role role) {

    public ScannedFile {
        path = PathGuard.normalize(path);
        sha256 = Hash.normalize(sha256);
        if (size < 0) {
            throw new IllegalArgumentException("文件大小不能为负: " + size);
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(role, "role");
    }

    /** 转为协议清单条目。 */
    public PackEntry toEntry() {
        return new PackEntry(path, sha256, size, origin, role);
    }
}
