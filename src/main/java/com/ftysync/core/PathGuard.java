package com.ftysync.core;

import java.nio.file.Path;
import java.util.Set;

/**
 * 相对路径校验（安全边界之一）。
 *
 * <p>清单和本地状态里的路径都必须先过这里，才能用于读写文件，防止路径穿越和
 * Windows 下的危险文件名（盘符、保留设备名、非法字符、尾随点/空格）。</p>
 *
 * <p>统一使用 POSIX 风格 '/' 分隔；返回值就是规范化后的相对路径。</p>
 */
public final class PathGuard {

    /** Windows 保留设备名（不区分大小写，含扩展名形式，如 CON.txt）。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private static final String INVALID_CHARS = "<>:\"|?*";
    private static final int MAX_SEGMENT_LENGTH = 255;

    private PathGuard() {
    }

    /**
     * 校验并规范化相对路径。
     *
     * @param relativePath 待校验路径（POSIX '/' 分隔）
     * @return 规范化后的相对路径（内容不变，仅校验）
     * @throws PathGuardException 路径非法
     */
    public static String normalize(String relativePath) {
        if (relativePath == null) {
            throw new PathGuardException("路径为空");
        }
        if (relativePath.isEmpty()) {
            throw new PathGuardException("路径为空");
        }
        if (!relativePath.equals(relativePath.trim())) {
            throw new PathGuardException("路径首尾不能有空白: " + relativePath);
        }
        if (relativePath.indexOf('\\') >= 0) {
            throw new PathGuardException("路径必须用 '/' 分隔: " + relativePath);
        }
        if (relativePath.startsWith("/")) {
            throw new PathGuardException("不允许绝对路径: " + relativePath);
        }
        for (int i = 0; i < relativePath.length(); i++) {
            char c = relativePath.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new PathGuardException("路径含控制字符: " + relativePath);
            }
        }
        if (relativePath.length() >= 2 && relativePath.charAt(1) == ':') {
            throw new PathGuardException("不允许盘符: " + relativePath);
        }
        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new PathGuardException("路径含空路径段: " + relativePath);
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new PathGuardException("路径不允许包含 '.' 或 '..': " + relativePath);
            }
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                throw new PathGuardException("路径段过长: " + relativePath);
            }
            for (int i = 0; i < segment.length(); i++) {
                if (INVALID_CHARS.indexOf(segment.charAt(i)) >= 0) {
                    throw new PathGuardException("路径含非法字符 '" + segment.charAt(i) + "': " + relativePath);
                }
            }
            char last = segment.charAt(segment.length() - 1);
            if (last == '.' || last == ' ') {
                throw new PathGuardException("路径段不能以 '.' 或空格结尾: " + relativePath);
            }
            if (isReservedName(segment)) {
                throw new PathGuardException("路径含 Windows 保留名: " + relativePath);
            }
        }
        return relativePath;
    }

    /**
     * 把已校验的相对路径解析到根目录下，并再次确认结果仍在根目录内。
     *
     * @param root         根目录（游戏目录）
     * @param relativePath 相对路径
     * @return 绝对路径
     * @throws PathGuardException 非法或越界
     */
    public static Path resolve(Path root, String relativePath) {
        String normalized = normalize(relativePath);
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new PathGuardException("路径越界: " + relativePath);
        }
        return resolved;
    }

    private static boolean isReservedName(String segment) {
        int dot = segment.indexOf('.');
        String base = (dot >= 0 ? segment.substring(0, dot) : segment).toLowerCase(java.util.Locale.ROOT);
        return RESERVED_NAMES.contains(base);
    }

    /** 路径非法。 */
    public static class PathGuardException extends RuntimeException {
        public PathGuardException(String message) {
            super(message);
        }
    }
}
