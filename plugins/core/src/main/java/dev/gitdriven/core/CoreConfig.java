package dev.gitdriven.core;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable configuration read from config.yml (+ restart.yml).
 *
 * <p>config.yml lives only on the server: the repository can't overwrite it, so a merge can't
 * point Core at another repository or break the build settings. A merge can only change the
 * restart policy, through restart.yml. Loading never throws: an invalid value falls back to its
 * default with a warning, so a typo never stops the server from updating itself.
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
        boolean applyWhenEmpty,
        BuildSource buildSource,
        String ciUrl) {

    enum RestartMode { IMMEDIATE, DEBOUNCE, DAILY }

    /** CI = download the jars CI built ({@link CiArtifacts}); LOCAL = run Gradle on this machine. */
    enum BuildSource { CI, LOCAL }

    /** Asset that .github/workflows/server-build.yml publishes for each commit of the branch. */
    private static final Pattern GITHUB_REPO =
            Pattern.compile("https://github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?");

    static final List<String> DEFAULT_BUILD_ARGS =
            List.of("build", "collectServerPlugins", "--no-daemon", "--console=plain");

    static final List<String> RESTART_KEYS =
            List.of("mode", "debounce-minutes", "daily-time-utc", "countdown-seconds", "apply-when-empty");
    static final long MAX_DEBOUNCE_MINUTES = 24 * 60;
    static final int MAX_COUNTDOWN_SECONDS = 600;

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
        String repoUrl = text(c, "repository.url", "");
        BuildSource source = source(text(c, "build.source", "ci"), log);
        String ciUrl = "";
        if (source == BuildSource.CI) {
            ciUrl = ciUrl(text(c, "build.ci-url", ""), repoUrl, log);
            if (ciUrl.isEmpty()) {
                source = BuildSource.LOCAL;
            }
        }
        return new CoreConfig(
                repoUrl,
                text(c, "repository.branch", "main"),
                Duration.ofSeconds(Math.max(15, c.getLong("repository.poll-interval-seconds", 60))),
                args,
                Duration.ofMinutes(Math.max(1, c.getLong("build.timeout-minutes", 15))),
                text(c, "build.artifacts-dir", "build/server-plugins"),
                text(c, "build.server-files-dir", "server"),
                new PathGlob(c.getStringList("build.ignore-paths")),
                mode(text(c, "restart.mode", "debounce"), log),
                // Capped so no value can hold updates back for more than a day.
                Duration.ofMinutes(Math.clamp(c.getLong("restart.debounce-minutes", 10), 0, MAX_DEBOUNCE_MINUTES)),
                time(c.get("restart.daily-time-utc", "00:00"), log),
                Math.clamp(c.getInt("restart.countdown-seconds", 60), 0, MAX_COUNTDOWN_SECONDS),
                c.getBoolean("restart.apply-when-empty", true),
                source,
                ciUrl);
    }

    static BuildSource source(String raw, Logger log) {
        try {
            return BuildSource.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("build.source '" + raw + "' is not valid (ci or local); using ci.");
            return BuildSource.CI;
        }
    }

    /**
     * build.ci-url, or the server-build release of a GitHub repository.url.
     *
     * @return "" when there's no usable URL (the caller then builds locally)
     */
    static String ciUrl(String configured, String repoUrl, Logger log) {
        if (!configured.isEmpty()) {
            if (configured.contains("{commit}")) {
                return configured;
            }
            log.warning("build.ci-url must contain {commit} (the full SHA); ignored.");
        }
        Matcher m = GITHUB_REPO.matcher(repoUrl);
        if (m.matches()) {
            return "https://github.com/" + m.group(1) + "/" + m.group(2)
                    + "/releases/download/server-build/server-plugins-{commit}.zip";
        }
        if (!repoUrl.isEmpty() && !repoUrl.contains("OWNER/REPO")) {
            log.warning("build.source is ci, but repository.url is not a GitHub repository and build.ci-url is "
                    + "not set: building on this machine instead.");
        }
        return "";
    }

    /**
     * Applies {@code plugins/Core/restart.yml}, the only Core file a merge can deploy
     * (see {@link ServerFiles}), on top of the {@code restart} section. Its keys are those of
     * that section, without the {@code restart:} level. Anything else in it is ignored, so a
     * merge can never change which repository Core follows or how it builds.
     */
    static void applyRestartFile(ConfigurationSection target, ConfigurationSection restartFile, Logger log) {
        for (String key : restartFile.getKeys(false)) {
            if (RESTART_KEYS.contains(key)) {
                target.set("restart." + key, restartFile.get(key));
            } else {
                log.warning("restart.yml: '" + key + "' is not a restart setting (" + String.join(", ", RESTART_KEYS)
                        + "); ignored.");
            }
        }
    }

    /**
     * True if every path only touches docs/CI: no build and no restart needed.
     * {@code ignore-paths} decides, except under {@code plugins/} and the server-files folder.
     * Everything there reaches the server (packed into a jar or copied over the root), so a broad
     * pattern like {@code **.md} can't skip a file a plugin ships as a resource.
     */
    boolean onlyIgnored(List<String> paths) {
        String serverPrefix = serverFilesDir.replaceAll("/+$", "") + "/";
        return paths.stream().allMatch(p -> ignorePaths.matches(p)
                && !p.startsWith("plugins/") && !p.startsWith(serverPrefix));
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
