package dev.gitdriven.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /core (admins) and /changelog (everyone). */
final class CoreCommands implements TabExecutor {
    private final CorePlugin plugin;

    CoreCommands(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("changelog")) {
            changelog(sender);
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "check" -> {
                if (plugin.updates() == null) {
                    Messages.send(sender, "<red>Set repository.url in Core's config.yml.</red>");
                } else if (plugin.updates().requestCheck(true)) {
                    Messages.send(sender, "<gray>Checking the repository…</gray>");
                } else {
                    Messages.send(sender, "<yellow>A check/build is already running.</yellow>");
                }
            }
            case "restart" -> {
                int secs = 30;
                if (args.length > 1) {
                    try {
                        secs = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        Messages.send(sender, "<red>Usage: /core restart [seconds]</red>");
                        return true;
                    }
                }
                plugin.restart().restartIn(secs);
            }
            case "cancel" -> {
                if (!plugin.restart().cancel()) {
                    Messages.send(sender, "<gray>No restart countdown in progress.</gray>");
                }
            }
            default -> Messages.send(sender, "<gray>Usage: /core <status|check|restart [seconds]|cancel></gray>");
        }
        return true;
    }

    private void status(CommandSender s) {
        DeployState st = plugin.state();
        UpdateService up = plugin.updates();
        RestartController rc = plugin.restart();
        Messages.send(s, "<gray>In production: <white><d></white> · built: <white><p></white></gray>",
                "d", Messages.shortSha(st.deployedCommit().orElse(null)),
                "p", st.stagedCommit().map(Messages::shortSha).orElse("—"));
        Messages.send(s, "<gray>Restart: <white><r></white> · mode: <white><m></white></gray>",
                "r", rc.describeSchedule(), "m", plugin.config().restartMode().name().toLowerCase());
        if (up == null) {
            Messages.send(s, "<red>Updater disabled: repository.url is not configured.</red>");
            return;
        }
        Instant last = up.lastCheck();
        Messages.send(s, "<gray>Updater: <white><st></white> · last check: <white><l></white> · remote: <white><rm></white></gray>",
                "st", up.status(),
                "l", last == null ? "never" : Duration.between(last, Instant.now()).toSeconds() + "s ago",
                "rm", Messages.shortSha(up.lastRemote()));
        if (up.lastError() != null) {
            Messages.send(s, "<red>Last error: <e></red>", "e", up.lastError());
        }
    }

    private void changelog(CommandSender s) {
        List<String> last = plugin.state().lastChanges();
        List<String> pending = plugin.restart().pendingChanges();
        if (last.isEmpty() && pending.isEmpty()) {
            Messages.send(s, "<gray>No changes recorded yet.</gray>");
            return;
        }
        if (!last.isEmpty()) {
            Messages.send(s, "<green>In the last update:</green>");
            Messages.sendChanges(s, last, 10);
        }
        if (!pending.isEmpty()) {
            Messages.send(s, "<gold>Coming in the next restart:</gold>");
            Messages.sendChanges(s, pending, 10);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("core") && args.length == 1) {
            return List.of("status", "check", "restart", "cancel").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
