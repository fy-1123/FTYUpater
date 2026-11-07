package com.ftysync.client;

import com.ftysync.core.Hash;

/**
 * 一条「需要重启后才能应用」的操作（pending/pending.json 的元素）。
 */
public record PendingOp(Operation operation, String path, String sha256, String addedAt) {

    public enum Operation {
        /** 用暂存文件替换目标（目标已被游戏加载/锁定）。 */
        REPLACE,
        /** 删除目标（目标已被游戏加载/锁定）。 */
        DELETE
    }

    public PendingOp {
        if (operation == null) {
            throw new IllegalArgumentException("pending 操作类型不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("pending 路径不能为空");
        }
        sha256 = sha256 == null ? null : Hash.normalize(sha256);
        if (operation == Operation.REPLACE && sha256 == null) {
            throw new IllegalArgumentException("替换操作必须带目标 SHA256");
        }
    }
}
