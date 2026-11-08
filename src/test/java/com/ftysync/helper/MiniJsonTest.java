package com.ftysync.helper;

import com.ftysync.client.PendingOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MiniJson}：helper 的极简解析器（独立 JVM 里没有 Gson 可用）。 */
class MiniJsonTest {

    @Test
    void parsesFlatObject() {
        Map<String, String> row = MiniJson.parseObject(
                "{\"operation\":\"REPLACE\",\"path\":\"mods/a.jar\",\"sha256\":\"abc\",\"addedAt\":null}");
        assertEquals("REPLACE", row.get("operation"));
        assertEquals("mods/a.jar", row.get("path"));
        assertEquals("abc", row.get("sha256"));
        assertNull(row.get("addedAt"));
        assertTrue(row.containsKey("addedAt"));
    }

    @Test
    void parsesArrayOfObjects() {
        List<Map<String, String>> rows = MiniJson.parseArrayOfObjects(
                "[{\"path\":\"a\"},{\"path\":\"b\"}]");
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get("path"));
        assertEquals("b", rows.get(1).get("path"));
    }

    @Test
    void parsesEmptyArray() {
        assertTrue(MiniJson.parseArrayOfObjects("[]").isEmpty());
        assertTrue(MiniJson.parseArrayOfObjects("  [ ]  ").isEmpty());
    }

    @Test
    void parsesEmptyObject() {
        assertTrue(MiniJson.parseObject("{}").isEmpty());
    }

    @Test
    void handlesEscapes() {
        Map<String, String> row = MiniJson.parseObject(
                "{\"path\":\"a\\\\b\\\"c\",\"n\":\"x\\ny\",\"u\":\"\\u4e2d\\u6587\"}");
        assertEquals("a\\b\"c", row.get("path"));
        assertEquals("x\ny", row.get("n"));
        assertEquals("中文", row.get("u"));
    }

    @Test
    void parsesNumbersAndBooleansAsRawText() {
        Map<String, String> row = MiniJson.parseObject("{\"n\":42,\"b\":true,\"z\":null}");
        assertEquals("42", row.get("n"));
        assertEquals("true", row.get("b"));
        assertNull(row.get("z"));
    }

    @Test
    void rejectsNestedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> MiniJson.parseObject("{\"a\":{\"b\":1}}"));
        assertThrows(IllegalArgumentException.class,
                () -> MiniJson.parseObject("{\"a\":[1,2]}"));
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class,
                () -> MiniJson.parseObject("{\"a\":1} extra"));
        assertThrows(IllegalArgumentException.class,
                () -> MiniJson.parseArrayOfObjects("[{\"a\":1}] x"));
    }

    @Test
    void rejectsUnterminated() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\":1"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\":\"x"));
    }

    @Test
    void rejectsWrongRoot() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects("{}"));
    }

    @Test
    void emptyInputFails() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject(""));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects(""));
    }

    @Test
    void roundTripsHelperResultJsonl() {
        HelperResult original = new HelperResult("2026-11-08T00:00:00Z",
                List.of("mods/a.jar"), List.of("mods/old.jar"),
                List.of("mods/locked.jar"), List.of("mods/locked.jar: 仍被占用"), 1);
        String jsonl = original.toJsonl();
        HelperResult parsed = HelperResult.parse(jsonl);
        assertEquals(original, parsed);
        assertTrue(parsed.hasFailures());
        assertTrue(parsed.didAnything());
    }

    @Test
    void helperResultEmptyRoundTrip() {
        HelperResult parsed = HelperResult.parse(HelperResult.empty("t0").toJsonl());
        assertEquals("t0", parsed.finishedAt());
        assertFalse(parsed.hasFailures());
        assertFalse(parsed.didAnything());
        assertEquals(0, parsed.remaining());
    }

    @Test
    void helperResultSkipsGarbageLines() {
        String jsonl = "not json\n{\"type\":\"applied\",\"path\":\"a\"}\n{}\n{\"type\":\"weird\"}\n";
        HelperResult parsed = HelperResult.parse(jsonl);
        assertEquals(List.of("a"), parsed.applied());
    }

    @Test
    void helperResultNullForBlank() {
        assertNull(HelperResult.parse(null));
        assertNull(HelperResult.parse("   \n  "));
    }

    @Test
    void pendingRoundTrip() {
        List<PendingOp> ops = List.of(
                new PendingOp(PendingOp.Operation.REPLACE, "mods/a.jar", "0".repeat(64), "t1"),
                new PendingOp(PendingOp.Operation.DELETE, "mods/b.jar", null, "t2"));
        String json = PendingApplier.writePending(ops);
        List<Map<String, String>> rows = MiniJson.parseArrayOfObjects(json);
        assertEquals(2, rows.size());
        assertEquals("REPLACE", rows.get(0).get("operation"));
        assertEquals("0".repeat(64), rows.get(0).get("sha256"));
        assertEquals("DELETE", rows.get(1).get("operation"));
        assertNull(rows.get(1).get("sha256"));
    }

    @Test
    void writeEmptyPendingIsEmptyArray() {
        assertEquals("[]", PendingApplier.writePending(List.of()));
        assertEquals("[]", PendingApplier.writePending(null));
    }
}
