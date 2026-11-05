package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clone → fetch → diff/log → staging, using a real local repository (needs git on the PATH). */
class PipelineTest {
    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void fetchDiffLogAndStage() throws Exception {
        Path origin = Files.createDirectories(tmp.resolve("origin"));
        sh(origin, "git", "init", "-q", "-b", "main");
        sh(origin, "git", "config", "user.email", "test@example.com");
        sh(origin, "git", "config", "user.name", "Tester");
        sh(origin, "git", "config", "commit.gpgsign", "false");
        Files.writeString(origin.resolve("README.md"), "hi\n");
        String first = commit(origin, "First commit");

        DeployState state = new DeployState(tmp.resolve("server/.git-driven"));
        GitRepo git = new GitRepo(state.repoDir(), runner);
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

        List<String> log = git.log(first, code, 50);
        assertEquals(2, log.size());
        assertTrue(log.get(0).contains("New config (#2)"));

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
