package com.ftysync.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlacklistTest {

    @Test
    void emptyBlacklistMatchesNothing() {
        assertFalse(Blacklist.empty().matches("mods/a.jar"));
        assertFalse(Blacklist.of(null).matches("mods/a.jar"));
        assertFalse(Blacklist.of(List.of(" ", "")).matches("mods/a.jar"));
    }

    @Test
    void namePatternMatchesBasename() {
        Blacklist blacklist = Blacklist.of(List.of("*.server.jar"));
        assertTrue(blacklist.matches("mods/foo.server.jar"));
        assertTrue(blacklist.matches("foo.server.jar"));
        assertFalse(blacklist.matches("mods/foo.server.txt"));
        assertFalse(blacklist.matches("mods/foo.server.jarx"));
    }

    @Test
    void pathPatternMatchesWholePathOnly() {
        Blacklist blacklist = Blacklist.of(List.of("mods/服务端专用.jar"));
        assertTrue(blacklist.matches("mods/服务端专用.jar"));
        assertFalse(blacklist.matches("clientpack/mods/服务端专用.jar"));
    }

    @Test
    void questionMarkMatchesSingleCharacter() {
        Blacklist blacklist = Blacklist.of(List.of("a?.jar"));
        assertTrue(blacklist.matches("mods/ab.jar"));
        assertFalse(blacklist.matches("mods/abc.jar"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        Blacklist blacklist = Blacklist.of(List.of("*.SERVER.jar"));
        assertTrue(blacklist.matches("mods/foo.server.jar"));
    }

    @Test
    void regexSpecialCharactersAreEscaped() {
        Blacklist blacklist = Blacklist.of(List.of("mods/a+b.jar"));
        assertTrue(blacklist.matches("mods/a+b.jar"));
        assertFalse(blacklist.matches("mods/aab.jar"));
        assertFalse(blacklist.matches("mods/ab.jar"));
    }

    @Test
    void patternsAreDeduplicatedAndTrimmed() {
        Blacklist blacklist = Blacklist.of(List.of("*.jar", " *.jar ", "*.server.jar"));
        assertTrue(blacklist.patterns().contains("*.jar"));
        assertTrue(blacklist.patterns().contains("*.server.jar"));
        assertFalse(blacklist.patterns().contains(" *.jar "));
    }
}
