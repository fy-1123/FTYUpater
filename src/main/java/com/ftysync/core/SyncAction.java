package com.ftysync.core;

/**
 * 比对结果中的一项动作（一个路径一条）。
 *
 * @param path       相对游戏目录路径
 * @param action     动作类型
 * @param fromSha256 本地现有哈希（无则 null）
 * @param toSha256   目标哈希（删除/忽略类为 null）
 * @param role       清单中的角色；清单外文件（DELETE）为 null
 */
public record SyncAction(String path, Action action, String fromSha256, String toSha256, PackEntry.Role role) {

    public SyncAction {
        path = PathGuard.normalize(path);
    }

    /** 单个条目的处理动作。 */
    public enum Action {
        /** 已一致，无需操作。 */
        KEEP,
        /** 必需文件缺失，需要下载。 */
        INSTALL_REQUIRED,
        /** 可选文件缺失，未 opt-out 需要下载。 */
        INSTALL_OPTIONAL,
        /** 已存在但哈希不同，需要替换。 */
        UPDATE,
        /** 清单外文件，镜像清理（进回收站）。 */
        DELETE,
        /** 玩家勾掉的可选条目：完全排除，不计入统计。 */
        OPT_OUT,
        /** 玩家标记忽略的条目：完全排除，不计入统计。 */
        IGNORED,
        /** 文件被重命名为 .disabled：视为未安装，需玩家决定。 */
        MANUAL_DISABLED,
        /** 已排队、等待重启应用。 */
        PENDING
    }
}
