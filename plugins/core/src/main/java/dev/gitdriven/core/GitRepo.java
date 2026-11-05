package dev.gitdriven.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Local clone of the repository, driven through the git CLI. It's a mirror: it never
 * gets local commits, it only moves forward (or back) to the remote's commit.
 */
final class GitRepo {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_LINES = 5_000;

    private final Path dir;
    private final ProcessRunner runner;

    GitRepo(Path dir, ProcessRunner runner) {
        this.dir = dir;
        this.runner = runner;
    }

    Path dir() {
        return dir;
    }

    /** Clones if it doesn't exist yet and makes sure the remote points at the configured URL. */
    void ensureClone(String url, String branch) throws IOException, InterruptedException {
        if (Files.isDirectory(dir.resolve(".git"))) {
            git("remote", "set-url", "origin", url);
            return;
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
        List<String> args = new ArrayList<>(List.of(
                "log", "--first-parent", "--format=%h %s — %an", "-n", String.valueOf(max)));
        args.add(fromExclusive == null ? to : fromExclusive + ".." + to);
        return lines(git(args.toArray(String[]::new)));
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
            throw new IOException(String.join(" ", cmd) + " failed (" + r.exitCode() + "):\n" + r.output());
        }
        return r.output();
    }

    private static List<String> lines(String out) {
        return out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
