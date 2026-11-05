package dev.gitdriven.core;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** Messages and broadcasts. Text coming from git always goes in as plain text (no tags). */
final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize("<gradient:#7CFC00:#00BFFF><b>Core</b></gradient> <dark_gray>»</dark_gray> ");

    private final Plugin plugin;

    Messages(Plugin plugin) {
        this.plugin = plugin;
    }

    static Component fmt(String miniMessage, String... keyValues) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            resolvers.add(Placeholder.unparsed(keyValues[i], keyValues[i + 1]));
        }
        return PREFIX.append(MM.deserialize(miniMessage, TagResolver.resolver(resolvers)));
    }

    static void send(CommandSender to, String miniMessage, String... keyValues) {
        to.sendMessage(fmt(miniMessage, keyValues));
    }

    /** List of commits, one line each, capped at {@code max}. */
    static void sendChanges(CommandSender to, List<String> changes, int max) {
        changes.stream().limit(max).forEach(line ->
                to.sendMessage(Component.text("  • ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(line, NamedTextColor.GRAY))));
        if (changes.size() > max) {
            to.sendMessage(Component.text("  … and " + (changes.size() - max) + " more", NamedTextColor.DARK_GRAY));
        }
    }

    /** To every player + the console (via Folia's global thread). */
    void broadcast(String miniMessage, String... keyValues) {
        Component msg = fmt(miniMessage, keyValues);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcast(msg));
    }

    /** Only to players with core.admin + the console. */
    void notifyAdmins(String miniMessage, String... keyValues) {
        Component msg = fmt(miniMessage, keyValues);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcast(msg, "core.admin"));
    }

    static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }
}
