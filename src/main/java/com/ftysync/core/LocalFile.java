package com.ftysync.core;

/**
 * 本地扫描到的一个受管文件（M3 由扫描器产出，M1 用于比对测试）。
 *
 * @param path   相对游戏目录路径
 * @param sha256 文件内容 SHA256
 * @param size   字节数
 */
public record LocalFile(String path, String sha256, long size) {

    public LocalFile {
        path = PathGuard.normalize(path);
        sha256 = Hash.normalize(sha256);
        if (size < 0) {
            throw new IllegalArgumentException("文件大小不能为负: " + size);
        }
    }
}
