package dev.gitdriven.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Local clone of the repository, driven through the git CLI. It's a mirror: it never
 * gets local commits, it only moves forward (or back) to the remote's commit.
 */
final class GitRepo {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_LINES = 5_000;

    private final Path dir;
    private final ProcessRunner runner;
    private final Logger log;

    GitRepo(Path dir, ProcessRunner runner, Logger log) {
        this.dir = dir;
        this.runner = runner;
        this.log = log;
    }

    Path dir() {
        return dir;
    }

    /**
     * Clones if it doesn't exist yet and makes sure the remote points at the configured URL.
     * A clone that git can't even open (corrupted, half-deleted) is thrown away and cloned again.
     */
    void ensureClone(String url, String branch) throws IOException, InterruptedException {
        if (Files.isDirectory(dir.resolve(".git"))) {
            clearStaleLocks();
            try {
                git("remote", "set-url", "origin", url);
                return;
            } catch (IOException e) {
                log.warning("The clone in " + dir + " is broken; cloning again. " + firstLine(e.getMessage()));
            }
        }
        DeployState.deleteRecursively(dir); // leftover from a clone that failed halfway
        Files.createDirectories(dir.getParent());
        exec(dir.getParent(), "git", "clone", "--branch", branch, "--single-branch", url, dir.toString());
    }

    /** Fetches the branch and returns the SHA of its tip on the remote. */
    String fetch(String branch) throws IOException, InterruptedException {
        String ref = "refs/remotes/origin/" + branch;
        git("fetch", "--quiet", "--prune", "origin", "+refs/heads/" + branch + ":" + ref);
        return git("rev-parse", "--verify", ref + "^{commit}").trim();
    }

    /** Makes the working tree exactly match the commit (discards any leftovers). */
    void checkout(String sha) throws IOException, InterruptedException {
        git("checkout", "--quiet", "--force", "--detach", sha);
        git("clean", "-fd", "--quiet"); // keeps ignored files (build cache)
    }

    boolean hasCommit(String sha) throws IOException, InterruptedException {
        return runner.run(dir, List.of("git", "cat-file", "-e", sha + "^{commit}"), TIMEOUT, Map.of(), 1, null).ok();
    }

    List<String> changedFiles(String from, String to) throws IOException, InterruptedException {
        return lines(git("diff", "--name-only", from, to));
    }

    /** One line per commit: "abc1234 PR title (#12) — author". */
    List<String> log(String fromExclusive, String to, int max) throws IOException, InterruptedException {
        // ASCII-only format (%x09 = tab): under a C/POSIX locale (e.g. systemd without LANG) the
        // JVM can't pass non-ASCII arguments, and a literal "—" would reach git as "?".
        List<String> args = new ArrayList<>(List.of(
                "log", "--first-parent", "--format=%h%x09%an%x09%s", "-n", String.valueOf(max)));
        args.add(fromExclusive == null ? to : fromExclusive + ".." + to);
        return lines(git(args.toArray(String[]::new))).stream()
                .map(GitRepo::formatLogLine)
                .toList();
    }

    static String formatLogLine(String raw) {
        String[] f = raw.split("\t", 3); // the subject goes last: it may contain tabs itself
        return f.length < 3 ? raw : f[0] + " " + f[2].strip() + " — " + f[1].strip();
    }

    /**
     * Removes lock files left behind by a git process that was killed (server shutdown in the
     * middle of a fetch/checkout). Without this, every later git command fails with
     * "index.lock: File exists". Safe: only Core uses this clone, and one check at a time.
     */
    private void clearStaleLocks() throws IOException {
        Path gitDir = dir.resolve(".git");
        List<Path> locks = new ArrayList<>();
        try (Stream<Path> top = Files.list(gitDir)) {
            top.filter(GitRepo::isLock).forEach(locks::add);
        }
        Path refs = gitDir.resolve("refs");
        if (Files.isDirectory(refs)) {
            try (Stream<Path> walk = Files.walk(refs)) {
                walk.filter(GitRepo::isLock).forEach(locks::add);
            }
        }
        for (Path lock : locks) {
            Files.deleteIfExists(lock);
            log.info("Removed stale git lock " + gitDir.relativize(lock));
        }
    }

    private static boolean isLock(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".lock");
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String line = s.lines().findFirst().orElse("").strip();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        return exec(dir, cmd.toArray(String[]::new));
    }

    private String exec(Path workDir, String... cmd) throws IOException, InterruptedException {
        ProcessRunner.Result r = runner.run(workDir, List.of(cmd), TIMEOUT, Map.of(), MAX_LINES, null);
        if (!r.ok()) {
            // First line = short reason (shown in /core status); then the full command and output.
            String reason = r.output().lines().map(String::strip).filter(l -> !l.isEmpty())
                    .reduce((first, last) -> last).orElse("no output");
            throw new IOException(cmd[0] + " " + cmd[1] + " failed (exit " + r.exitCode() + "): " + reason
                    + "\n" + String.join(" ", cmd) + "\n" + r.output());
        }
        return r.output();
    }

    private static List<String> lines(String out) {
        return out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
