package com.ftysync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 比对引擎：清单 × 本地现状 → 动作列表（纯函数，无 IO）。
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    /**
     * 计算差异。
     *
     * @param manifest        服务器清单
     * @param present         本地受管文件（path → 文件）；不含 .disabled 文件
     * @param manualDisabled  被玩家重命名为 .disabled 的清单路径
     * @param optOut          玩家勾掉的可选路径
     * @param ignored         玩家标记忽略的路径
     * @param protectedPaths   受保护路径（如 FTYUpater 自身 jar），不参与清理
     * @param pendingTargets  已排队等待重启的目标（path → 目标 sha256）
     * @return 动作列表（每个路径最多一条）
     */
    public static DiffResult diff(PackManifest manifest,
                                  Map<String, LocalFile> present,
                                  Set<String> manualDisabled,
                                  Set<String> optOut,
                                  Set<String> ignored,
                                  Set<String> protectedPaths,
                                  Map<String, String> pendingTargets) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, LocalFile> localFiles = present == null ? Map.of() : present;
        Set<String> disabled = manualDisabled == null ? Set.of() : manualDisabled;
        Set<String> optedOut = optOut == null ? Set.of() : optOut;
        Set<String> ignoredPaths = ignored == null ? Set.of() : ignored;
        Set<String> protectedSet = protectedPaths == null ? Set.of() : protectedPaths;
        Map<String, String> pending = pendingTargets == null ? Map.of() : pendingTargets;

        List<SyncAction> actions = new ArrayList<>();
        for (PackEntry entry : manifest.entries().values()) {
            String path = entry.path();
            PackEntry.Role role = entry.role();

            // 可选条目才允许 opt-out / 忽略；required 一律忽略这些标记（否则无法进服）
            if (role == PackEntry.Role.OPTIONAL && ignoredPaths.contains(path)) {
                actions.add(new SyncAction(path, SyncAction.Action.IGNORED, null, null, role));
                continue;
            }
            if (role == PackEntry.Role.OPTIONAL && optedOut.contains(path)) {
                actions.add(new SyncAction(path, SyncAction.Action.OPT_OUT, null, null, role));
                continue;
            }

            LocalFile file = localFiles.get(path);
            if (disabled.contains(path)) {
                actions.add(new SyncAction(path, SyncAction.Action.MANUAL_DISABLED,
                        file == null ? null : file.sha256(), entry.sha256(), role));
                continue;
            }
            if (file == null) {
                if (entry.sha256().equals(pending.get(path))) {
                    actions.add(new SyncAction(path, SyncAction.Action.PENDING, null, entry.sha256(), role));
                } else {
                    actions.add(new SyncAction(path,
                            role == PackEntry.Role.REQUIRED
                                    ? SyncAction.Action.INSTALL_REQUIRED
                                    : SyncAction.Action.INSTALL_OPTIONAL,
                            null, entry.sha256(), role));
                }
                continue;
            }
            if (file.sha256().equals(entry.sha256())) {
                actions.add(new SyncAction(path, SyncAction.Action.KEEP, file.sha256(), entry.sha256(), role));
                continue;
            }
            if (entry.sha256().equals(pending.get(path))) {
                actions.add(new SyncAction(path, SyncAction.Action.PENDING, file.sha256(), entry.sha256(), role));
            } else {
                actions.add(new SyncAction(path, SyncAction.Action.UPDATE, file.sha256(), entry.sha256(), role));
            }
        }

        // 镜像清理：受管目录里有、清单里没有的文件
        for (LocalFile file : localFiles.values()) {
            if (manifest.entry(file.path()).isPresent()) {
                continue;
            }
            if (protectedSet.contains(file.path())) {
                actions.add(new SyncAction(file.path(), SyncAction.Action.KEEP, file.sha256(), null, null));
            } else {
                actions.add(new SyncAction(file.path(), SyncAction.Action.DELETE, file.sha256(), null, null));
            }
        }
        return new DiffResult(actions);
    }
}
