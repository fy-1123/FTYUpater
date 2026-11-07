package com.ftysync.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 发布/同步黑名单：支持 '*'（任意字符）与 '?'（单个字符）。
 *
 * <p>匹配规则：</p>
 * <ul>
 *   <li>含 '/' 的模式匹配<b>整条相对路径</b>，如 {@code mods/服务端专用.jar}；</li>
 *   <li>不含 '/' 的模式匹配<b>文件名</b>（最后一段），如 {@code *.server.jar}；</li>
 *   <li>不区分大小写（与 Windows 文件系统一致，避免玩家大小写写错漏排除）。</li>
 * </ul>
 */
public final class Blacklist {

    private final List<String> patterns;
    private final List<Pattern> pathPatterns;
    private final List<Pattern> namePatterns;

    private Blacklist(List<String> patterns, List<Pattern> pathPatterns, List<Pattern> namePatterns) {
        this.patterns = List.copyOf(patterns);
        this.pathPatterns = List.copyOf(pathPatterns);
        this.namePatterns = List.copyOf(namePatterns);
    }

    /** 空/空白/重复模式会被忽略；不会抛异常。 */
    public static Blacklist of(Collection<String> rawPatterns) {
        List<String> patterns = new ArrayList<>();
        List<Pattern> pathPatterns = new ArrayList<>();
        List<Pattern> namePatterns = new ArrayList<>();
        if (rawPatterns != null) {
            for (String raw : rawPatterns) {
                if (raw == null) {
                    continue;
                }
                String pattern = raw.trim();
                if (pattern.isEmpty() || patterns.contains(pattern)) {
                    continue;
                }
                patterns.add(pattern);
                Pattern compiled = compileGlob(pattern);
                if (pattern.indexOf('/') >= 0) {
                    pathPatterns.add(compiled);
                } else {
                    namePatterns.add(compiled);
                }
            }
        }
        return new Blacklist(patterns, pathPatterns, namePatterns);
    }

    /** 空黑名单。 */
    public static Blacklist empty() {
        return of(List.of());
    }

    /** 路径是否命中黑名单。 */
    public boolean matches(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        for (Pattern pattern : pathPatterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        for (Pattern pattern : namePatterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 原始模式（已去空白/去重，按配置顺序）。 */
    public List<String> patterns() {
        return patterns;
    }

    private static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    @Override
    public String toString() {
        return "Blacklist" + patterns.toString().toLowerCase(Locale.ROOT);
    }
}
