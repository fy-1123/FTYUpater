package com.ftysync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * SHA256 工具：十六进制统一小写，供清单与本地状态使用。
 */
public final class Hash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int BUFFER_SIZE = 64 * 1024;

    private Hash() {
    }

    /** 计算字节数组的 SHA256。 */
    public static String sha256(byte[] data) {
        Objects.requireNonNull(data, "data");
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", e);
        }
    }

    /** 流式计算文件的 SHA256（不把整个文件读进内存）。 */
    public static String sha256(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }

    /** 校验并规范化一个 SHA256 字符串；非法时抛 {@link IllegalArgumentException}。 */
    public static String normalize(String sha256) {
        if (sha256 == null) {
            throw new IllegalArgumentException("SHA256 不能为空");
        }
        String value = sha256.trim().toLowerCase(Locale.ROOT);
        if (value.length() != 64) {
            throw new IllegalArgumentException("SHA256 必须是 64 位十六进制，收到: " + sha256);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                throw new IllegalArgumentException("SHA256 含非十六进制字符: " + sha256);
            }
        }
        return value;
    }

    /** 是否为合法的 SHA256 字符串（不抛异常）。 */
    public static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", e);
        }
    }
}
