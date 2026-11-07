package com.ftysync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTest {

    /** NIST 标准样例 "abc" 的 SHA256。 */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void sha256OfBytesMatchesKnownValue() {
        assertEquals(SHA256_ABC, Hash.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256OfFileMatchesBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.bin");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(SHA256_ABC, Hash.sha256(file));
    }

    @Test
    void normalizeAcceptsUppercaseAndWhitespace() {
        assertEquals(SHA256_ABC, Hash.normalize("  " + SHA256_ABC.toUpperCase() + " "));
    }

    @Test
    void normalizeRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> Hash.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> Hash.normalize("abc"));
        assertThrows(IllegalArgumentException.class, () -> Hash.normalize("z".repeat(64)));
    }

    @Test
    void isSha256ChecksLengthAndCharset() {
        assertTrue(Hash.isSha256(SHA256_ABC));
        assertTrue(Hash.isSha256(SHA256_ABC.toUpperCase()));
        assertFalse(Hash.isSha256(null));
        assertFalse(Hash.isSha256("abc"));
        assertFalse(Hash.isSha256("g".repeat(64)));
    }

    @Test
    void streamHashingDoesNotDependOnFileSize(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.bin");
        byte[] data = new byte[200 * 1024 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        Files.write(file, data);
        assertEquals(Hash.sha256(data), Hash.sha256(file));
    }
}
