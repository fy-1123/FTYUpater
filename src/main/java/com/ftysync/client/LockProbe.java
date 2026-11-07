package com.ftysync.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 判断目标文件当前是否「被游戏占用、不能立刻替换/删除」。
 * 生产实现用「尝试改名再改回」探测（Windows 上被占用会抛 IOException）；测试可注入假实现。
 */
@FunctionalInterface
public interface LockProbe {

    boolean isLocked(Path file);

    /** 生产用探测：尝试把文件在同目录内改名，再改回。 */
    static LockProbe moveProbe() {
        return file -> {
            if (file == null || !Files.exists(file)) {
                return false;
            }
            Path probe = file.resolveSibling(file.getFileName() + ".ftyprobe-" + System.nanoTime());
            try {
                Files.move(file, probe);
            } catch (IOException e) {
                return true;
            }
            try {
                Files.move(probe, file);
                return false;
            } catch (IOException e) {
                // 改不回去（极罕见）：尽力恢复
                try {
                    Files.move(probe, file);
                } catch (IOException ignored) {
                    // 交给上层报错
                }
                return true;
            }
        };
    }
}
