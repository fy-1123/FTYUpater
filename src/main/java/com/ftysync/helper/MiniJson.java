package com.ftysync.helper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读取器：只支持「扁平对象」与「扁平对象数组」，值必须是字符串、数字、布尔或 null。
 *
 * <p>为什么不用 Gson：{@code ApplyHelper} 是独立 JVM，classpath 只有模组 jar，不能假设
 * Minecraft 自带的 Gson 还在。这里要读的两种文件（{@code pending.json} 与
 * {@code helper-result.jsonl}）结构都极简单，手写解析器足够且可单测。</p>
 *
 * <p>不支持嵌套对象/数组作为值——遇到就抛 {@link IllegalArgumentException}，
 * 避免悄悄解析错。</p>
 */
public final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /**
     * 解析「对象数组」：{@code [{"a":"1"},{"a":"2"}]}。
     *
     * @param json JSON 文本
     * @return 每个元素的键值表（值统一转成字符串，null 存为 null）
     */
    public static List<Map<String, String>> parseArrayOfObjects(String json) {
        MiniJson parser = new MiniJson(json == null ? "" : json);
        List<Map<String, String>> result = new ArrayList<>();
        parser.skipWhitespace();
        if (parser.peek() != '[') {
            throw parser.error("期望 '[' 开头");
        }
        parser.pos++;
        parser.skipWhitespace();
        if (parser.peek() == ']') {
            parser.pos++;
            parser.expectEnd();
            return result;
        }
        while (true) {
            parser.skipWhitespace();
            result.add(parser.parseObject());
            parser.skipWhitespace();
            char c = parser.next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw parser.error("对象之间期望 ',' 或 ']'，收到 '" + c + "'");
            }
        }
        parser.expectEnd();
        return result;
    }

    /**
     * 解析单个扁平对象：{@code {"a":"1"}}。
     *
     * @param json JSON 文本
     * @return 键值表（值统一转成字符串，null 存为 null）
     */
    public static Map<String, String> parseObject(String json) {
        MiniJson parser = new MiniJson(json == null ? "" : json);
        parser.skipWhitespace();
        Map<String, String> object = parser.parseObject();
        parser.expectEnd();
        return object;
    }

    private Map<String, String> parseObject() {
        skipWhitespace();
        if (peek() != '{') {
            throw error("期望 '{' 开头");
        }
        pos++;
        Map<String, String> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            if (next() != ':') {
                throw error("键后面期望 ':'");
            }
            skipWhitespace();
            object.put(key, parseValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw error("键值对之间期望 ',' 或 '}'，收到 '" + c + "'");
            }
        }
    }

    private String parseValue() {
        char c = peek();
        if (c == '"') {
            return parseString();
        }
        if (c == '{' || c == '[') {
            throw error("不支持嵌套对象/数组作为值");
        }
        int start = pos;
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
                break;
            }
            pos++;
        }
        String raw = text.substring(start, pos);
        if (raw.isEmpty()) {
            throw error("缺少值");
        }
        if ("null".equals(raw)) {
            return null;
        }
        return raw;
    }

    private String parseString() {
        if (next() != '"') {
            throw error("期望字符串");
        }
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("字符串未闭合");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("转义未完成");
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("\\u 转义不完整");
                    }
                    String hex = text.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw error("\\u 转义不是合法十六进制: " + hex);
                    }
                }
                default -> throw error("不认识的转义 \\" + esc);
            }
        }
    }

    private void expectEnd() {
        skipWhitespace();
        if (pos < text.length()) {
            throw error("对象结束后还有多余内容");
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("内容意外结束");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + "（位置 " + pos + "）");
    }
}
