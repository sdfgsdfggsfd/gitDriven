package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathGlobTest {
    private final PathGlob glob = new PathGlob(List.of("**.md", "docs/**", ".github/**", "LICENSE"));

    @Test
    void ignoresDocsAndCi() {
        assertTrue(glob.matches("README.md"));
        assertTrue(glob.matches("plugins/playground/NOTES.md"));
        assertTrue(glob.matches("docs/ARCHITECTURE.md"));
        assertTrue(glob.matches(".github/workflows/build.yml"));
        assertTrue(glob.matches("LICENSE"));
    }

    @Test
    void codeAndConfigStillCount() {
        assertFalse(glob.matches("plugins/core/src/main/java/dev/gitdriven/core/CorePlugin.java"));
        assertFalse(glob.matches("server/bukkit.yml"));
        assertFalse(glob.matches("build.gradle.kts"));
        assertFalse(glob.matches("LICENSE.txt"));
    }

    @Test
    void singleStarDoesNotCrossFolders() {
        PathGlob g = new PathGlob(List.of("server/*.yml"));
        assertTrue(g.matches("server/bukkit.yml"));
        assertFalse(g.matches("server/plugins/x.yml"));
    }
}
