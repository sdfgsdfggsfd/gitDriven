package dev.gitdriven.core;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable configuration read from config.yml.
 *
 * <p>Loading never throws: an invalid value falls back to its default with a warning. A typo
 * merged into {@code server/plugins/Core/config.yml} must not disable Core, otherwise the
 * server would stop updating itself and the fix could never be deployed.
 */
record CoreConfig(
        String repoUrl,
        String branch,
        Duration pollInterval,
        List<String> buildArgs,
        Duration buildTimeout,
        String artifactsDir,
        String serverFilesDir,
        PathGlob ignorePaths,
        RestartMode restartMode,
        Duration debounce,
        LocalTime dailyTimeUtc,
        int countdownSeconds,
        boolean applyWhenEmpty) {

    enum RestartMode { IMMEDIATE, DEBOUNCE, DAILY }

    static final List<String> DEFAULT_BUILD_ARGS =
            List.of("build", "collectServerPlugins", "--no-daemon", "--console=plain");

    // "0:00", "00:00" and "00:00:00" are all accepted.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm[:ss]", Locale.ROOT);

    static CoreConfig load(FileConfiguration c, Logger log) {
        List<String> args = c.getStringList("build.args").stream()
                .filter(a -> a != null && !a.isBlank())
                .toList();
        if (args.isEmpty()) {
            log.warning("build.args is empty; using " + DEFAULT_BUILD_ARGS + ".");
            args = DEFAULT_BUILD_ARGS;
        }
        return new CoreConfig(
                text(c, "repository.url", ""),
                text(c, "repository.branch", "main"),
                Duration.ofSeconds(Math.max(15, c.getLong("repository.poll-interval-seconds", 60))),
                args,
                Duration.ofMinutes(Math.max(1, c.getLong("build.timeout-minutes", 15))),
                text(c, "build.artifacts-dir", "build/server-plugins"),
                text(c, "build.server-files-dir", "server"),
                new PathGlob(c.getStringList("build.ignore-paths")),
                mode(text(c, "restart.mode", "debounce"), log),
                Duration.ofMinutes(Math.max(0, c.getLong("restart.debounce-minutes", 10))),
                time(c.get("restart.daily-time-utc", "00:00"), log),
                Math.max(0, c.getInt("restart.countdown-seconds", 60)),
                c.getBoolean("restart.apply-when-empty", true));
    }

    boolean isPlaceholderRepo() {
        return repoUrl.isEmpty() || repoUrl.contains("OWNER/REPO");
    }

    /** Trimmed string; a missing or blank value becomes {@code def}. */
    private static String text(FileConfiguration c, String path, String def) {
        String value = c.getString(path);
        return value == null || value.isBlank() ? def : value.trim();
    }

    static RestartMode mode(String raw, Logger log) {
        try {
            return RestartMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("restart.mode '" + raw + "' is not valid (immediate, debounce or daily); using debounce.");
            return RestartMode.DEBOUNCE;
        }
    }

    /**
     * YAML 1.1 reads an unquoted {@code 12:30} as the base-60 integer 750 (minutes) and
     * {@code 12:30:00} as 45000 (seconds), so numbers are converted back. The two never
     * overlap: a value with seconds is at least 1:00:00 = 3600.
     */
    static LocalTime time(Object raw, Logger log) {
        try {
            if (raw instanceof Number n) {
                long v = n.longValue();
                if (v >= 0 && v < 24 * 60) {
                    return LocalTime.of((int) (v / 60), (int) (v % 60));
                }
                if (v >= 3600 && v < 24 * 3600) {
                    return LocalTime.ofSecondOfDay(v);
                }
                throw new DateTimeParseException("out of range", String.valueOf(v), 0);
            }
            return LocalTime.parse(String.valueOf(raw).trim(), TIME);
        } catch (DateTimeParseException e) {
            log.warning("restart.daily-time-utc '" + raw + "' is not a valid HH:mm time; using 00:00.");
            return LocalTime.MIDNIGHT;
        }
    }
}
