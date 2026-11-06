package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** A bad value in config.yml must fall back to the default, never stop Core from enabling. */
class CoreConfigTest {
    private final List<String> warnings = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    CoreConfigTest() {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** The server's config.yml on top of the defaults bundled in the jar, like getConfig(). */
    private CoreConfig load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        c.setDefaults(defaults);
        return CoreConfig.load(c, log);
    }

    @Test
    void bundledDefaults() throws Exception {
        CoreConfig c = load("");
        assertEquals("https://github.com/sdfgsdfggsfd/gitDriven.git", c.repoUrl());
        assertEquals("main", c.branch());
        assertEquals(Duration.ofSeconds(60), c.pollInterval());
        assertEquals(CoreConfig.DEFAULT_BUILD_ARGS, c.buildArgs());
        assertEquals(CoreConfig.RestartMode.DEBOUNCE, c.restartMode());
        assertEquals(LocalTime.MIDNIGHT, c.dailyTimeUtc());
        assertTrue(c.ignorePaths().matches("site/index.html"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void invalidValuesFallBack() throws Exception {
        CoreConfig c = load("""
                repository:
                  branch: "  "
                  poll-interval-seconds: 1
                build:
                  args: []
                restart:
                  mode: hourly
                  daily-time-utc: "25:99"
                  countdown-seconds: -5
                """);
        assertEquals("main", c.branch());
        assertEquals(Duration.ofSeconds(15), c.pollInterval());
        assertEquals(CoreConfig.DEFAULT_BUILD_ARGS, c.buildArgs());
        assertEquals(CoreConfig.RestartMode.DEBOUNCE, c.restartMode());
        assertEquals(LocalTime.MIDNIGHT, c.dailyTimeUtc());
        assertEquals(0, c.countdownSeconds());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void restartValuesAreCapped() throws Exception {
        CoreConfig c = load("restart: {debounce-minutes: 999999, countdown-seconds: 999999}");
        assertEquals(Duration.ofDays(1), c.debounce());
        assertEquals(CoreConfig.MAX_COUNTDOWN_SECONDS, c.countdownSeconds());
    }

    /** restart.yml (deployable from the repo) only touches the restart section. */
    @Test
    void restartFileCannotChangeRepositoryOrBuild() throws Exception {
        YamlConfiguration server = new YamlConfiguration();
        server.loadFromString("repository: {url: \"https://example.com/mine.git\"}\nrestart: {countdown-seconds: 30}\n");
        YamlConfiguration restartFile = new YamlConfiguration();
        restartFile.loadFromString("""
                mode: daily
                daily-time-utc: "04:00"
                repository: {url: "https://example.com/evil.git"}
                build: {args: ["evil"]}
                """);
        CoreConfig.applyRestartFile(server, restartFile, log);
        CoreConfig c = load(server.saveToString());

        assertEquals("https://example.com/mine.git", c.repoUrl());
        assertEquals(CoreConfig.DEFAULT_BUILD_ARGS, c.buildArgs());
        assertEquals(CoreConfig.RestartMode.DAILY, c.restartMode());
        assertEquals(LocalTime.of(4, 0), c.dailyTimeUtc());
        assertEquals(30, c.countdownSeconds());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    /** "**.md" also matches .md files a plugin packs into its jar: those still need a build. */
    @Test
    void filesThatReachTheServerAreNeverIgnored() throws Exception {
        CoreConfig c = load("");
        assertTrue(c.onlyIgnored(List.of("README.md", "docs/OPERATIONS.md", "site/index.html", ".github/x.yml")));
        assertFalse(c.onlyIgnored(List.of("README.md", "plugins/guide/src/main/resources/book.md")));
        assertFalse(c.onlyIgnored(List.of("plugins/guide/README.md")));
        assertFalse(c.onlyIgnored(List.of("server/plugins/Guide/notes.md")));
        assertTrue(load("build: {server-files-dir: \"server/\"}").onlyIgnored(List.of("serverless.md")));
        assertFalse(load("build: {server-files-dir: \"server/\"}").onlyIgnored(List.of("server/a.md")));
    }

    @Test
    void modeIsCaseInsensitive() throws Exception {
        assertEquals(CoreConfig.RestartMode.DAILY, load("restart: {mode: \" Daily \"}").restartMode());
    }

    @Test
    void timesWrittenWithoutQuotes() throws Exception {
        // YAML 1.1 turns these into base-60 integers (750 and 4980).
        assertEquals(LocalTime.of(12, 30), load("restart:\n  daily-time-utc: 12:30\n").dailyTimeUtc());
        assertEquals(LocalTime.of(1, 23), load("restart:\n  daily-time-utc: 1:23:00\n").dailyTimeUtc());
        assertEquals(LocalTime.of(4, 0), load("restart:\n  daily-time-utc: \"4:00\"\n").dailyTimeUtc());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
