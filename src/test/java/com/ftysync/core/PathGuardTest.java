package com.ftysync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @Test
    void acceptsNormalRelativePaths() {
        for (String path : new String[]{
                "mods/foo.jar",
                "config/x/y.toml",
                "resourcepacks/整合包资源.zip",
                "mods/my mod.jar",
                "shaderpacks/a-b_c.v1.zip"
        }) {
            assertEquals(path, PathGuard.normalize(path));
        }
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        String[] attacks = {
                "../evil",
                "mods/../../evil",
                "mods/../evil",
                "/etc/passwd",
                "C:/Windows/system32",
                "C:evil",
                "mods\\evil",
        };
        for (String attack : attacks) {
            assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize(attack), attack);
        }
    }

    @Test
    void rejectsEmptyAndDotSegments() {
        for (String bad : new String[]{"", ".", "mods/./x", "mods//x", "mods/"}) {
            assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize(bad), bad);
        }
    }

    @Test
    void rejectsControlCharactersAndInvalidChars() {
        for (String bad : new String[]{"mods/a\u0001b.jar", "mods/a:b", "mods/a|b", "mods/a*b", "mods/a?b", "mods/a<b"}) {
            assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize(bad), bad);
        }
    }

    @Test
    void rejectsWindowsReservedNamesAndTrailingDots() {
        for (String bad : new String[]{"mods/CON", "mods/con.txt", "mods/NUL", "mods/x.", "mods/x "}) {
            assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize(bad), bad);
        }
    }

    @Test
    void rejectsSurroundingWhitespace() {
        assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize(" mods/a.jar"));
        assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.normalize("mods/a.jar "));
    }

    @Test
    void resolveStaysInsideRoot(@TempDir Path root) {
        Path resolved = PathGuard.resolve(root, "mods/a.jar");
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()));
        assertTrue(resolved.endsWith(Path.of("mods", "a.jar")));
        assertThrows(PathGuard.PathGuardException.class, () -> PathGuard.resolve(root, "../outside"));
    }

    @Test
    void pathGuardExceptionIsRuntimeException() {
        assertFalse(IllegalAccessException.class.isAssignableFrom(PathGuard.PathGuardException.class));
        assertTrue(RuntimeException.class.isAssignableFrom(PathGuard.PathGuardException.class));
    }
}
