package com.ftysync.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvertParserTest {

    @Test
    void formatThenParseRoundTrips() {
        String marker = AdvertParser.format("http://127.0.0.1:8081/");
        assertEquals("v2|http://127.0.0.1:8081", marker);

        Advert advert = AdvertParser.parse(marker).orElseThrow();
        assertEquals(2, advert.version());
        assertEquals("http://127.0.0.1:8081", advert.baseUrl());
        assertEquals(marker, advert.marker());
    }

    @Test
    void formatAcceptsHttps() {
        assertEquals("v2|https://sync.example.com", AdvertParser.format("https://sync.example.com"));
    }

    @Test
    void formatRejectsInvalidUrls() {
        for (String bad : new String[]{null, "", "   ", "ftp://x", "http://", "http:///x",
                "http://a b", "http://x|y", "127.0.0.1:8081"}) {
            assertThrows(IllegalArgumentException.class, () -> AdvertParser.format(bad), String.valueOf(bad));
        }
    }

    @Test
    void parseRejectsNonAdvertValues() {
        for (String bad : new String[]{null, "", "   ", "IGNORESERVERONLY", "v3|http://x",
                "v2|", "v2|ftp://x", "v2|http://", "http://x", "1.0.0", "v2"}) {
            assertTrue(AdvertParser.parse(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void parseRejectsSameVersionWithBadUrl() {
        assertTrue(AdvertParser.parse("v2|not-a-url").isEmpty());
    }
}
