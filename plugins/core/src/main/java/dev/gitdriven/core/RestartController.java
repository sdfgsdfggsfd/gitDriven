package dev.gitdriven.core;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Decides WHEN to restart to apply an update that has already been built (staging/READY).
 * An async tick every second evaluates the configured mode and drives the countdown.
 * The restart itself is just a {@code Bukkit.shutdown()}: applying the files and starting
 * the server again is the start script's job (scripts/start.sh or start.ps1). Without the
 * script ({@code supervised == false}) nothing would start the server again, so Core never
 * shuts it down: the update waits until someone restarts it with the script.
 */
final class RestartController {
    private static final int[] ANNOUNCE_AT = {600, 300, 120, 60, 30, 15, 10, 5, 4, 3, 2, 1};

    private final Plugin plugin;
    private final CoreConfig config;
    private final Messages messages;
    private final boolean supervised;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private volatile String pendingCommit;
    private volatile List<String> pendingChanges = List.of();
    private volatile Instant debounceDeadline = Instant.MAX;
    private volatile Instant nextDaily;
    private volatile Instant restartAt;   // != null => countdown in progress
    private volatile boolean manual;      // the countdown came from /core restart
    private volatile boolean held;        // /core cancel: hold until the next update or /core restart
    private int lastAnnounced = Integer.MAX_VALUE;
    private ScheduledTask task;

    RestartController(Plugin plugin, CoreConfig config, Messages messages, boolean supervised) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.supervised = supervised;
    }

    boolean supervised() {
        return supervised;
    }

    void start() {
        nextDaily = nextDaily(Instant.now(), config.dailyTimeUtc());
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> tick(), 1, 1, TimeUnit.SECONDS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    /** A change to staging/ that must not overlap with a restart. */
    @FunctionalInterface
    interface StagingChange {
        void run() throws IOException;
    }

    /**
     * A new update was built: {@code publish} puts it in staging/. It runs under the same lock
     * as the countdown, so the restart never lands while staging/ is being swapped (the start
     * script would find nothing to apply and players would be kicked for nothing). If the
     * countdown of an older update is running, the restart applies this newer one.
     *
     * @return false if a restart is already under way: staging/ is left alone and the commit is
     *     built again after the restart
     */
    synchronized boolean onStaged(String commit, List<String> changes, StagingChange publish) throws IOException {
        if (shuttingDown.get()) {
            return false;
        }
        publish.run();
        pendingCommit = commit;
        pendingChanges = changes;
        held = false;
        debounceDeadline = Instant.now().plus(config.debounce());

        if (!supervised) {
            plugin.getLogger().warning("Update " + Messages.shortSha(commit) + " is ready, but this server was not "
                    + "started by scripts/start.sh or start.ps1: nothing would start it again, so Core won't restart "
                    + "it. Stop the server and start it with the script to apply the update.");
            messages.notifyAdmins("<yellow>Update <white><sha></white> is ready, but the server wasn't started by "
                    + "the start script, so it won't restart on its own.</yellow> <gray>Stop it and start it with "
                    + "scripts/start.sh (or start.ps1) to apply it.</gray>", "sha", Messages.shortSha(commit));
            return true;
        }

        String when = switch (config.restartMode()) {
            case IMMEDIATE -> "in " + config.countdownSeconds() + "s";
            case DEBOUNCE -> config.debounce().isZero()
                    ? "in " + config.countdownSeconds() + "s"
                    : "in ~" + config.debounce().toMinutes() + " min (unless another merge lands)";
            case DAILY -> "at " + config.dailyTimeUtc() + " UTC";
        };
        if (restartAt == null) {
            messages.broadcast("<green>Update <white><sha></white> ready</green> <gray>(<n> change(s)). "
                            + "Restart <when>. See <white>/changelog</white>.</gray>",
                    "sha", Messages.shortSha(commit), "n", String.valueOf(changes.size()), "when", when);
        }
        return true;
    }

    /**
     * The update waiting in staging/ is no longer needed (e.g. a revert left the remote equal to
     * what's already running): {@code discard} removes it, under the countdown's lock.
     *
     * @return false if a restart is already under way (it applies the staging as it is)
     */
    synchronized boolean clearPending(StagingChange discard) throws IOException {
        if (shuttingDown.get()) {
            return false;
        }
        discard.run();
        pendingCommit = null;
        pendingChanges = List.of();
        // An admin's /core restart stays: they asked for a restart, not for an update.
        if (restartAt != null && !manual && !shuttingDown.get()) {
            restartAt = null;
            messages.broadcast("<yellow>Restart cancelled: there's nothing left to apply.</yellow>");
        }
        return true;
    }

    /**
     * /core restart [seconds] — works even without a pending update.
     *
     * @return false without the start script (the server would just stay off)
     */
    synchronized boolean restartIn(int seconds) {
        if (!supervised) {
            return false;
        }
        held = false;
        beginCountdown(Instant.now().plusSeconds(Math.max(0, seconds)));
        manual = true;
        return true;
    }

    /** /core cancel */
    synchronized boolean cancel() {
        if (restartAt == null || shuttingDown.get()) {
            return false;
        }
        restartAt = null;
        held = true;
        messages.broadcast("<yellow>Restart postponed by an admin.</yellow>");
        return true;
    }

    String pendingCommit() {
        return pendingCommit;
    }

    List<String> pendingChanges() {
        return pendingChanges;
    }

    Instant restartAt() {
        return restartAt;
    }

    boolean held() {
        return held;
    }

    String describeSchedule() {
        if (restartAt != null) {
            return "restarting in " + Duration.between(Instant.now(), restartAt).toSeconds() + "s";
        }
        if (pendingCommit == null) {
            return "nothing pending";
        }
        if (!supervised) {
            return "pending, waiting for a restart with the start script";
        }
        if (held) {
            return "pending, postponed by an admin";
        }
        return switch (config.restartMode()) {
            case IMMEDIATE -> "pending (immediate)";
            case DEBOUNCE -> "pending, restart at " + debounceDeadline.atZone(ZoneOffset.UTC).toLocalTime().withNano(0) + " UTC";
            case DAILY -> "pending, restart at " + nextDaily.atZone(ZoneOffset.UTC).toLocalTime() + " UTC";
        };
    }

    private synchronized void tick() {
        Instant now = Instant.now();
        if (restartAt != null) {
            countdown(now);
            return;
        }
        if (!now.isBefore(nextDaily)) {
            nextDaily = nextDaily(now, config.dailyTimeUtc());
        }
        if (pendingCommit == null || held || !supervised) {
            return;
        }
        if (config.applyWhenEmpty() && Bukkit.getOnlinePlayers().isEmpty()) {
            beginCountdown(now);
            return;
        }
        switch (config.restartMode()) {
            case IMMEDIATE -> beginCountdown(now.plusSeconds(config.countdownSeconds()));
            case DEBOUNCE -> {
                if (!now.isBefore(debounceDeadline)) {
                    beginCountdown(now.plusSeconds(config.countdownSeconds()));
                }
            }
            case DAILY -> {
                // Start the countdown early so the restart lands exactly on the scheduled time.
                if (!now.isBefore(nextDaily.minusSeconds(config.countdownSeconds()))) {
                    beginCountdown(nextDaily);
                }
            }
        }
    }

    private void beginCountdown(Instant at) {
        restartAt = at;
        manual = false;
        long secs = Math.max(0, Duration.between(Instant.now(), at).toSeconds());
        lastAnnounced = bucket(secs);
        if (secs > 0) {
            String what = pendingCommit != null
                    ? " to apply <white>" + Messages.shortSha(pendingCommit) + "</white>"
                    : "";
            messages.broadcast("<gold>Server restarts in <white><s>s</white>" + what + ".</gold>",
                    "s", String.valueOf(secs));
        }
    }

    private void countdown(Instant now) {
        long remaining = Duration.between(now, restartAt).toSeconds();
        if (remaining <= 0) {
            shutdown();
            return;
        }
        int b = bucket(remaining);
        if (b < lastAnnounced) {
            lastAnnounced = b;
            messages.broadcast("<gold>Restarting in <white><s>s</white>…</gold>", "s", String.valueOf(remaining));
        }
    }

    /** Smallest announcement mark >= remaining (marks in ANNOUNCE_AT, descending). */
    private static int bucket(long remaining) {
        int b = Integer.MAX_VALUE;
        for (int t : ANNOUNCE_AT) {
            if (remaining <= t) {
                b = t;
            }
        }
        return b;
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        restartAt = null;
        plugin.getLogger().info("Restarting to apply " + Messages.shortSha(pendingCommit));
        Component kick = Messages.fmt("<green>Updating the server!</green> <gray>Back in a moment.</gray>");
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            // On Folia each player belongs to a region: kick through the entity's scheduler.
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getScheduler().run(plugin, t -> p.kick(kick), null);
            }
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> Bukkit.shutdown(), 20L);
        });
    }

    static Instant nextDaily(Instant now, LocalTime time) {
        ZonedDateTime z = now.atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = z.toLocalDate().atTime(time).atZone(ZoneOffset.UTC);
        if (!candidate.isAfter(z)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }
}
