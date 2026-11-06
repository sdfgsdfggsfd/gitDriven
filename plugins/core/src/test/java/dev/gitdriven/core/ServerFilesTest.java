package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** server/ is copied over the server root: only plain config may get through. */
class ServerFilesTest {
    @TempDir
    Path tmp;

    @Test
    void configFilesAreAllowed() {
        for (String ok : List.of("bukkit.yml", "server.properties", "config/paper-global.yml",
                "plugins/Playground/config.yml", "plugins/Some.Plugin/messages.json", "server-icon.png",
                "plugins/Core/restart.yml", "plugins/core/RESTART.YML")) {
            assertNull(ServerFiles.problem(ok), ok);
        }
    }

    @Test
    void codeAndScriptsAreRefused() {
        for (String bad : List.of("plugins/evil.jar", "start.sh", "start.ps1", "folia.jar", "run.bat",
                "plugins/Skript/scripts/x.sk", "libraries/x.class", "noextension")) {
            assertNotNull(ServerFiles.problem(bad), bad);
        }
    }

    @Test
    void coreSettingsAndGameDataAreRefused() {
        for (String bad : List.of("plugins/Core/config.yml", "plugins/core/config.yml", "plugins/Core./config.yml",
                "plugins/CORE~1/config.yml", "ops.json", "OPS.JSON", "whitelist.json", "banned-players.json",
                ".git-driven/managed-plugins.txt", "plugins/.hidden/x.yml", ".bashrc.txt")) {
            assertNotNull(ServerFiles.problem(bad), bad);
        }
        // Same names deeper down are just plugin data.
        assertNull(ServerFiles.problem("plugins/Playground/ops.json"));
    }

    @Test
    void scanListsFilesAndSkipsDocs() throws Exception {
        Path dir = tmp.resolve("server");
        write(dir.resolve("bukkit.yml"), "x: 1\n");
        write(dir.resolve("README.md"), "docs\n");
        write(dir.resolve("plugins/Playground/README.md"), "docs\n");
        write(dir.resolve("plugins/.gitkeep"), "");
        write(dir.resolve("plugins/Playground/config.yml"), "y: 2\n");

        ServerFiles.Scan scan = ServerFiles.scan(dir);
        assertTrue(scan.ok(), scan.problems().toString());
        assertEquals(List.of("bukkit.yml", "plugins/Playground/config.yml"), scan.files().stream().sorted().toList());
    }

    @Test
    void scanReportsEveryProblem() throws Exception {
        Path dir = tmp.resolve("server");
        write(dir.resolve("bukkit.yml"), "x: 1\n");
        write(dir.resolve("plugins/evil.jar"), "PK");
        write(dir.resolve("plugins/Core/config.yml"), "repository: {url: x}\n");

        ServerFiles.Scan scan = ServerFiles.scan(dir);
        assertEquals(2, scan.problems().size(), scan.problems().toString());
    }

    @Test
    void secretsInServerPropertiesAreRefused() throws Exception {
        Path dir = tmp.resolve("server");
        write(dir.resolve("server.properties"), "motd=Hi\nrcon.password=\nmanagement-server-secret=\n");
        assertTrue(ServerFiles.scan(dir).ok());

        write(dir.resolve("server.properties"), "motd=Hi\nenable-rcon=true\nrcon.password=hunter2\n");
        assertEquals(1, ServerFiles.scan(dir).problems().size());
    }

    @Test
    void missingFolderIsFine() throws Exception {
        assertTrue(ServerFiles.scan(tmp.resolve("nope")).ok());
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
