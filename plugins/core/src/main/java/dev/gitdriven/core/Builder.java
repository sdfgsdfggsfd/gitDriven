package dev.gitdriven.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Builds the clone with the Gradle Wrapper and prepares the staging/ folder. */
final class Builder {
    private final CoreConfig config;
    private final DeployState state;
    private final ProcessRunner runner;

    Builder(CoreConfig config, DeployState state, ProcessRunner runner) {
        this.config = config;
        this.state = state;
        this.runner = runner;
    }

    ProcessRunner.Result build(Path repoDir, String commit) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        // Relative path (the process runs inside the repo): avoids trouble with spaces on
        // Windows. "sh gradlew" works even if the executable bit gets lost.
        List<String> cmd = new ArrayList<>(windows ? List.of("cmd", "/c", ".\\gradlew.bat") : List.of("sh", "gradlew"));
        cmd.addAll(config.buildArgs());

        Map<String, String> env = Map.of(
                "JAVA_HOME", System.getProperty("java.home"), // same Java as the server
                "GIT_DRIVEN_COMMIT", commit);
        Files.createDirectories(state.root());
        return runner.run(repoDir, cmd, config.buildTimeout(), env, 30, state.buildLog());
    }

    /**
     * Copies jars + server files into staging.tmp/, READY included. Nothing the start script
     * reads changes until {@link #publish()}.
     */
    void prepare(Path repoDir, String commit, List<String> changes) throws IOException {
        Path tmp = state.stagingTmpDir();
        DeployState.deleteRecursively(tmp);
        DeployState.deleteRecursively(state.stagingOldDir());
        Path pluginsOut = Files.createDirectories(tmp.resolve("plugins"));

        Path artifacts = repoDir.resolve(config.artifactsDir());
        int jars = 0;
        if (Files.isDirectory(artifacts)) {
            try (Stream<Path> files = Files.list(artifacts)) {
                for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    Files.copy(jar, pluginsOut.resolve(jar.getFileName()));
                    jars++;
                }
            }
        }
        if (jars == 0) {
            throw new IOException("The build produced no jars in " + config.artifactsDir());
        }

        // UpdateService already refused the commit if server/ breaks the rules; checked again
        // here so nothing reaches staging/ without passing ServerFiles.
        ServerFiles.Scan scan = ServerFiles.scan(repoDir.resolve(config.serverFilesDir()));
        if (!scan.ok()) {
            throw new IOException("Files in " + config.serverFilesDir() + "/ that can't go to the server: "
                    + scan.problems());
        }
        Path filesOut = Files.createDirectories(tmp.resolve("files"));
        for (String rel : scan.files()) {
            Path dst = filesOut.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(repoDir.resolve(config.serverFilesDir()).resolve(rel), dst, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.writeString(tmp.resolve("COMMIT"), commit + "\n");
        Files.write(tmp.resolve("CHANGES.txt"), changes, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("READY"), commit + "\n");
    }

    /**
     * Swaps the prepared staging.tmp/ in as staging/ with two renames, instead of deleting the
     * old staging/ and copying the new one. Called under {@link RestartController}'s lock, so a
     * restart can't happen between the renames.
     */
    void publish() throws IOException {
        Path old = state.stagingOldDir();
        if (Files.exists(state.stagingDir())) {
            Files.move(state.stagingDir(), old);
        }
        Files.move(state.stagingTmpDir(), state.stagingDir());
        try {
            DeployState.deleteRecursively(old);
        } catch (IOException e) {
            // Only a leftover: prepare() removes it before the next update.
        }
    }
}
