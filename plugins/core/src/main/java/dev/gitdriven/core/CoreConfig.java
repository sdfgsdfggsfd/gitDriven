package dev.gitdriven.core;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable configuration read from config.yml. */
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

    static CoreConfig load(FileConfiguration c) {
        return new CoreConfig(
                c.getString("repository.url", "").trim(),
                c.getString("repository.branch", "main").trim(),
                Duration.ofSeconds(Math.max(15, c.getLong("repository.poll-interval-seconds", 60))),
                List.copyOf(c.getStringList("build.args")),
                Duration.ofMinutes(Math.max(1, c.getLong("build.timeout-minutes", 15))),
                c.getString("build.artifacts-dir", "build/server-plugins"),
                c.getString("build.server-files-dir", "server"),
                new PathGlob(c.getStringList("build.ignore-paths")),
                RestartMode.valueOf(c.getString("restart.mode", "debounce").trim().toUpperCase(Locale.ROOT)),
                Duration.ofMinutes(Math.max(0, c.getLong("restart.debounce-minutes", 10))),
                LocalTime.parse(c.getString("restart.daily-time-utc", "00:00").trim()),
                Math.max(0, c.getInt("restart.countdown-seconds", 60)),
                c.getBoolean("restart.apply-when-empty", true));
    }

    boolean isPlaceholderRepo() {
        return repoUrl.isEmpty() || repoUrl.contains("OWNER/REPO");
    }
}
