package dev.gitdriven.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Files in {@code <server>/.git-driven/}. This is the "contract" between Core
 * (which builds and prepares) and the start scripts (which apply while the server is stopped):
 *
 * <pre>
 * repo/                 clone of the repository
 * staging/READY         exists = update ready to apply
 * staging/COMMIT        built SHA
 * staging/CHANGES.txt   new commits (one line each)
 * staging/plugins/      jars to install into plugins/
 * staging/files/        copy of the repo's server/, goes over the server root
 * staging.tmp/          next staging/ being written; renamed into place (Builder#publish)
 * ci-plugins/           jars downloaded from CI (build.source: ci)
 * deployed-commit       SHA in production (written by the script when applying)
 * last-changes.txt      CHANGES.txt of the last applied update
 * managed-plugins.txt   jars installed by Core (the script removes them before applying)
 * last-build.log        full output of the last build
 * </pre>
 */
final class DeployState {
    private final Path root;

    DeployState(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    Path repoDir() {
        return root.resolve("repo");
    }

    Path stagingDir() {
        return root.resolve("staging");
    }

    /** The next staging/, being written. Becomes staging/ in one rename. */
    Path stagingTmpDir() {
        return root.resolve("staging.tmp");
    }

    /** Jars downloaded from CI for the commit being staged (build.source: ci). */
    Path ciPluginsDir() {
        return root.resolve("ci-plugins");
    }

    /** The previous staging/ while it's being swapped out. */
    Path stagingOldDir() {
        return root.resolve("staging.old");
    }

    Path buildLog() {
        return root.resolve("last-build.log");
    }

    Optional<String> deployedCommit() {
        return readFirstLine(root.resolve("deployed-commit"));
    }

    void writeDeployedCommit(String sha) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("deployed-commit"), sha + "\n");
    }

    Optional<String> stagedCommit() {
        if (!Files.exists(stagingDir().resolve("READY"))) {
            return Optional.empty();
        }
        return readFirstLine(stagingDir().resolve("COMMIT"));
    }

    List<String> stagedChanges() {
        return stagedCommit().isPresent() ? readLines(stagingDir().resolve("CHANGES.txt")) : List.of();
    }

    List<String> lastChanges() {
        return readLines(root.resolve("last-changes.txt"));
    }

    void clearStaging() throws IOException {
        deleteRecursively(stagingDir());
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (AccessDeniedException e) {
                    // Windows refuses to delete read-only files, and git marks its pack files
                    // read-only. Clear the flag and try again.
                    if (!p.toFile().setWritable(true)) {
                        throw new UncheckedIOException(e);
                    }
                    try {
                        Files.delete(p);
                    } catch (IOException again) {
                        throw new UncheckedIOException(again);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Optional<String> readFirstLine(Path file) {
        return readLines(file).stream().findFirst();
    }

    private static List<String> readLines(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
