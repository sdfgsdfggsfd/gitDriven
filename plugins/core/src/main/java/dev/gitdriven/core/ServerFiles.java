package dev.gitdriven.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What the repo's {@code server/} folder may put on the server. Everything in it is copied
 * over the server root, so a merged PR could otherwise drop a ready-made jar or a script on
 * the machine, rewrite which repository Core follows, or make someone op. Core checks the
 * folder before building; a commit that breaks these rules is not deployed.
 */
final class ServerFiles {
    /** Config and data formats only: nothing the server, a plugin or a shell would execute. */
    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("yml", "yaml", "json", "properties", "toml", "conf", "cfg", "txt", "png");

    /** The only file allowed in plugins/Core/. Repository and build settings stay on the server. */
    static final String CORE_RESTART_FILE = "plugins/Core/restart.yml";

    /** Game data at the server root: who is op, whitelisted or banned isn't decided by a merge. */
    private static final Set<String> GAME_DATA =
            Set.of("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "usercache.json");

    /** server.properties keys holding secrets: a value from a public repo is remote console access. */
    private static final List<String> SECRET_PROPERTIES = List.of("rcon.password", "management-server-secret");

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /** Files to copy (relative, "/" separated) and every rule broken; deploy only if {@link #ok()}. */
    record Scan(List<String> files, List<String> problems) {
        boolean ok() {
            return problems.isEmpty();
        }
    }

    private ServerFiles() {
    }

    static Scan scan(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (!Files.exists(dir, NO_FOLLOW)) {
            return new Scan(files, problems);
        }
        if (!Files.isDirectory(dir, NO_FOLLOW)) {
            problems.add(dir.getFileName() + ": must be a plain folder");
            return new Scan(files, problems);
        }
        try (Stream<Path> walk = Files.walk(dir)) { // never follows symbolic links
            for (Path p : walk.toList()) {
                if (p.equals(dir) || Files.isDirectory(p, NO_FOLLOW)) {
                    continue;
                }
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (isDocFile(rel)) {
                    continue;
                }
                String why = Files.isRegularFile(p, NO_FOLLOW) ? problem(rel) : "symbolic links are not allowed";
                if (why == null) {
                    why = contentProblem(rel, p);
                }
                if (why == null) {
                    files.add(rel);
                } else {
                    problems.add(rel + ": " + why);
                }
            }
        }
        return new Scan(files, problems);
    }

    /** README.md and .gitkeep files document the folder; they don't go to the server. */
    static boolean isDocFile(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.equals(".gitkeep") || name.equalsIgnoreCase("README.md");
    }

    /** Why this path can't go to the server, or null if it can. Case-insensitive, like Windows. */
    static String problem(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        for (String part : parts) {
            // Hidden names could reach .git-driven/; the rest are Windows aliases of other
            // names ("Core." is "Core", "CORE~1" can be too).
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".") || part.endsWith(" ")
                    || part.contains("~") || part.contains(":") || part.contains("\\")) {
                return "unusual file name (hidden or ambiguous)";
            }
        }
        String name = parts[parts.length - 1];
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return "only config files go to the server (." + String.join(", .", new TreeSet<>(ALLOWED_EXTENSIONS))
                    + "); no jars or scripts";
        }
        if (parts.length == 1 && GAME_DATA.contains(name)) {
            return "ops, whitelist and bans are not managed by the repository";
        }
        if (lower.startsWith("plugins/core/") && !lower.equals(CORE_RESTART_FILE.toLowerCase(Locale.ROOT))) {
            return "for Core only " + CORE_RESTART_FILE + " is allowed (repository and build settings stay on the server)";
        }
        return null;
    }

    private static String contentProblem(String rel, Path file) throws IOException {
        if (!rel.equalsIgnoreCase("server.properties")) {
            return null;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IllegalArgumentException e) { // malformed \\u escape
            return "unreadable: " + e.getMessage();
        }
        for (String key : SECRET_PROPERTIES) {
            if (!props.getProperty(key, "").isBlank()) {
                return key + " must stay empty here (the repository is public; set it on the server)";
            }
        }
        return null;
    }
}
