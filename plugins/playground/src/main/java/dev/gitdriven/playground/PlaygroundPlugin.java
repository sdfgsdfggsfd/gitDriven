package dev.gitdriven.playground;

import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the community plugin. Each feature lives in its own class and is
 * registered here with one line — that avoids merge conflicts.
 */
public final class PlaygroundPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // --- register your feature below (one line, in alphabetical order) ---
        Objects.requireNonNull(getCommand("hello")).setExecutor(new HelloCommand(this));
    }
}
