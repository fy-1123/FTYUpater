package com.ftysync.server;

import com.ftysync.core.Hash;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * SHA256 内容寻址存储：{@code <storeDir>/objects/<sha256>}，同一内容只存一份。
 */
public final class ContentStore {

    private final Path objectsDir;

    public ContentStore(Path storeDir) {
        this.objectsDir = storeDir.resolve("objects");
    }

    public Path objectsDir() {
        return objectsDir;
    }

    public Path objectPath(String sha256) {
        return objectsDir.resolve(Hash.normalize(sha256));
    }

    public boolean has(String sha256) {
        return Files.isRegularFile(objectPath(sha256));
    }

    /** 把源文件复制进存储（已存在则跳过）；返回存储中的路径。 */
    public Path put(Path source, String sha256) throws IOException {
        Path target = objectPath(sha256);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(objectsDir);
        Path tmp = objectsDir.resolve(sha256 + ".tmp-" + Thread.currentThread().getId() + "-" + System.nanoTime());
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException parallelWinner) {
                // 另一个发布线程已写入同样内容（内容寻址，二者等价）
                Files.deleteIfExists(tmp);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }
}
