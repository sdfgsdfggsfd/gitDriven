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

    /** Copies jars + server files into staging/ and only creates READY at the very end. */
    void stage(Path repoDir, String commit, List<String> changes) throws IOException {
        Path tmp = state.stagingTmpDir();
        DeployState.deleteRecursively(tmp);
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

        Path serverFiles = repoDir.resolve(config.serverFilesDir());
        Path filesOut = Files.createDirectories(tmp.resolve("files"));
        if (Files.isDirectory(serverFiles)) {
            copyTree(serverFiles, filesOut);
        }

        Files.writeString(tmp.resolve("COMMIT"), commit + "\n");
        Files.write(tmp.resolve("CHANGES.txt"), changes, StandardCharsets.UTF_8);

        state.clearStaging();
        Files.move(tmp, state.stagingDir());
        Files.writeString(state.stagingDir().resolve("READY"), commit + "\n");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path rel = from.relativize(src);
                if (isDocFile(rel)) {
                    continue;
                }
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** README.md and .gitkeep files from server/ don't go to the server. */
    private static boolean isDocFile(Path rel) {
        String name = rel.getFileName() == null ? "" : rel.getFileName().toString();
        return name.equals(".gitkeep") || (rel.getNameCount() == 1 && name.equalsIgnoreCase("README.md"));
    }
}
