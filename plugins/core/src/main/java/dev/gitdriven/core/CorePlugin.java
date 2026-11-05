package dev.gitdriven.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Core plugin: keeps the server in sync with the repository.
 * Overview in docs/ARCHITECTURE.md.
 */
public final class CorePlugin extends JavaPlugin implements Listener {
    private CoreConfig config;
    private DeployState state;
    private RestartController restart;
    private UpdateService updates; // null if the repo isn't configured
    private NamespacedKey seenKey;
    // Read once at boot: this is what's running until the next restart.
    private String bootCommit;
    private List<String> bootChanges = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = CoreConfig.load(getConfig());
        // The working directory is the server root (the start scripts guarantee that).
        state = new DeployState(Path.of("").toAbsolutePath().resolve(".git-driven"));
        seenKey = new NamespacedKey(this, "seen-commit");
        bootCommit = state.deployedCommit().orElse(null);
        bootChanges = state.lastChanges();

        Messages messages = new Messages(this);
        restart = new RestartController(this, config, messages);
        restart.start();

        CoreCommands commands = new CoreCommands(this);
        for (String name : List.of("core", "changelog")) {
            Objects.requireNonNull(getCommand(name)).setExecutor(commands);
            Objects.requireNonNull(getCommand(name)).setTabCompleter(commands);
        }
        getServer().getPluginManager().registerEvents(this, this);

        state.stagedCommit().ifPresent(sha -> getLogger().severe(
                "There is a built update (" + Messages.shortSha(sha) + ") that was not applied at boot. "
                        + "The server must be started with scripts/start.sh or scripts/start.ps1 — "
                        + "without them Core can't apply updates."));

        if (config.isPlaceholderRepo()) {
            getLogger().warning("repository.url is not configured in plugins/Core/config.yml — updater disabled.");
            return;
        }
        updates = new UpdateService(config, state, new ProcessRunner(), restart, messages, getLogger());
        long period = config.pollInterval().toSeconds();
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> updates.requestCheck(false), 15, period, TimeUnit.SECONDS);
        getLogger().info("Watching " + config.repoUrl() + " (" + config.branch() + ") every " + period + "s. "
                + "In production: " + Messages.shortSha(bootCommit));
    }

    @Override
    public void onDisable() {
        if (restart != null) {
            restart.stop();
        }
        if (updates != null) {
            updates.shutdown();
        }
        Bukkit.getAsyncScheduler().cancelTasks(this);
    }

    /** Shows what's new once per update to each player. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (bootCommit == null || bootChanges.isEmpty()) {
            return;
        }
        var pdc = player.getPersistentDataContainer();
        if (bootCommit.equals(pdc.get(seenKey, PersistentDataType.STRING))) {
            return;
        }
        pdc.set(seenKey, PersistentDataType.STRING, bootCommit);
        Messages.send(player, "<green>What's new in the last update:</green>");
        Messages.sendChanges(player, bootChanges, 5);
        Messages.send(player, "<gray>Full list: <white>/changelog</white></gray>");
    }

    CoreConfig config() {
        return config;
    }

    DeployState state() {
        return state;
    }

    RestartController restart() {
        return restart;
    }

    UpdateService updates() {
        return updates;
    }
}
