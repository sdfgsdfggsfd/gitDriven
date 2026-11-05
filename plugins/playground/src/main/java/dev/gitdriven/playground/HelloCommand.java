package dev.gitdriven.playground;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Example of a Folia-compatible feature: /hello sends a message and spawns particles.
 *
 * <p>On Folia there is no single main thread: each region of the world has its own. To touch
 * a player/entity use {@code entity.getScheduler()}; for blocks/locations use
 * {@code Bukkit.getRegionScheduler()}. Never use {@code Bukkit.getScheduler()}.
 */
final class HelloCommand implements CommandExecutor {
    private final Plugin plugin;

    HelloCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Hello, console!");
            return true;
        }
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<rainbow>Hello, <name>!</rainbow> <gray>This server is built by the people who play on it.</gray>",
                Placeholder.unparsed("name", player.getName())));

        // In 10 ticks, on the thread of whatever region the player is in.
        player.getScheduler().runDelayed(plugin, task ->
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 2, 0),
                        20, 0.5, 0.5, 0.5), null, 10L);
        return true;
    }
}
