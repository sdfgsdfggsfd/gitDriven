package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clone → fetch → diff/log → staging, using a real local repository (needs git on the PATH). */
class PipelineTest {
    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();
    private final Logger log = Logger.getAnonymousLogger();

    @Test
    void fetchDiffLogAndStage() throws Exception {
        Path origin = origin();
        Files.writeString(origin.resolve("README.md"), "hi\n");
        String first = commit(origin, "First commit");

        DeployState state = new DeployState(tmp.resolve("server/.git-driven"));
        GitRepo git = new GitRepo(state.repoDir(), runner, log);
        git.ensureClone(origin.toUri().toString(), "main");
        assertEquals(first, git.fetch("main"));

        // A docs-only commit + one with code.
        Files.writeString(origin.resolve("README.md"), "hi again\n");
        String docs = commit(origin, "Docs (#1)");
        Files.createDirectories(origin.resolve("server"));
        Files.writeString(origin.resolve("server/bukkit.yml"), "x: 1\n");
        Files.writeString(origin.resolve("server/README.md"), "doesn't go to the server\n");
        String code = commit(origin, "New config (#2)");

        assertEquals(code, git.fetch("main"));
        PathGlob ignore = new PathGlob(List.of("**.md"));
        assertTrue(git.changedFiles(first, docs).stream().allMatch(ignore::matches));
        assertFalse(git.changedFiles(first, code).stream().allMatch(ignore::matches));

        // Same format players see in /changelog: "<sha> <title> — <author>".
        List<String> log = git.log(first, code, 50);
        assertEquals(2, log.size());
        assertTrue(log.get(0).matches("[0-9a-f]{7,} New config \\(#2\\) — Tester"), log.get(0));
        assertTrue(log.get(1).matches("[0-9a-f]{7,} Docs \\(#1\\) — Tester"), log.get(1));

        // Simulates Gradle's output and prepares the staging.
        git.checkout(code);
        Path artifacts = Files.createDirectories(state.repoDir().resolve("build/server-plugins"));
        Files.writeString(artifacts.resolve("core.jar"), "fake");
        new Builder(config(), state, runner).stage(state.repoDir(), code, log);

        assertEquals(code, state.stagedCommit().orElseThrow());
        assertEquals(log, state.stagedChanges());
        assertTrue(Files.exists(state.stagingDir().resolve("plugins/core.jar")));
        assertTrue(Files.exists(state.stagingDir().resolve("files/bukkit.yml")));
        assertFalse(Files.exists(state.stagingDir().resolve("files/README.md")));
    }

    /** A git process killed mid-checkout (server shutdown) leaves lock files behind. */
    @Test
    void recoversFromStaleLocks() throws Exception {
        Path origin = origin();
        Files.writeString(origin.resolve("a.txt"), "1\n");
        commit(origin, "One");

        GitRepo git = new GitRepo(tmp.resolve("repo"), runner, log);
        git.ensureClone(origin.toUri().toString(), "main");
        Files.writeString(origin.resolve("a.txt"), "2\n");
        String two = commit(origin, "Two");

        Files.writeString(tmp.resolve("repo/.git/index.lock"), "");
        Files.writeString(Files.createDirectories(tmp.resolve("repo/.git/refs/remotes/origin")).resolve("main.lock"), "");
        git.ensureClone(origin.toUri().toString(), "main");
        assertEquals(two, git.fetch("main"));
        git.checkout(two);
        assertEquals("2", Files.readString(tmp.resolve("repo/a.txt")).strip());
    }

    @Test
    void reclonesABrokenClone() throws Exception {
        Path origin = origin();
        Files.writeString(origin.resolve("a.txt"), "1\n");
        String one = commit(origin, "One");

        GitRepo git = new GitRepo(tmp.resolve("repo"), runner, log);
        git.ensureClone(origin.toUri().toString(), "main");
        Files.writeString(tmp.resolve("repo/.git/config"), "[[[ not a git config");
        git.ensureClone(origin.toUri().toString(), "main");
        assertEquals(one, git.fetch("main"));
    }

    /** /core status shows the first line of the error: it must say why, not just which command. */
    @Test
    void gitErrorsStartWithTheReason() {
        GitRepo git = new GitRepo(tmp.resolve("repo"), runner, log);
        IOException e = assertThrows(IOException.class,
                () -> git.ensureClone(tmp.resolve("missing").toUri().toString(), "main"));
        String first = GitRepo.firstLine(e.getMessage());
        assertTrue(first.matches("git clone failed \\(exit \\d+\\): .*\\S.*"), first);
        assertFalse(first.endsWith("no output"), first);
    }

    @Test
    void formatsLogLines() {
        assertEquals("abc1234 Add /sit (#3) — Alex", GitRepo.formatLogLine("abc1234\tAlex\tAdd /sit (#3)"));
        assertEquals("abc1234 a\tb — Alex", GitRepo.formatLogLine("abc1234\tAlex\ta\tb"));
        assertEquals("weird", GitRepo.formatLogLine("weird"));
    }

    /** git marks pack files read-only; Windows won't delete those without help. */
    @Test
    void deletesReadOnlyFiles() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("ro/objects/pack"));
        Path pack = Files.writeString(dir.resolve("pack-1.pack"), "x");
        assertTrue(pack.toFile().setReadOnly());
        DeployState.deleteRecursively(tmp.resolve("ro"));
        assertFalse(Files.exists(tmp.resolve("ro")));
    }

    private Path origin() throws Exception {
        Path origin = Files.createDirectories(tmp.resolve("origin"));
        sh(origin, "git", "init", "-q", "-b", "main");
        sh(origin, "git", "config", "user.email", "test@example.com");
        sh(origin, "git", "config", "user.name", "Tester");
        sh(origin, "git", "config", "commit.gpgsign", "false");
        return origin;
    }

    private String commit(Path repo, String msg) throws Exception {
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "commit", "-q", "-m", msg);
        return sh(repo, "git", "rev-parse", "HEAD").strip();
    }

    private String sh(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessRunner.Result r = runner.run(dir, List.of(cmd), Duration.ofMinutes(1), Map.of(), 100, null);
        assertEquals(0, r.exitCode(), r.output());
        return r.output();
    }

    private static CoreConfig config() {
        return new CoreConfig("file://x", "main", Duration.ofSeconds(60), List.of(), Duration.ofMinutes(1),
                "build/server-plugins", "server", new PathGlob(List.of()), CoreConfig.RestartMode.DEBOUNCE,
                Duration.ofMinutes(10), LocalTime.MIDNIGHT, 60, true);
    }
}
