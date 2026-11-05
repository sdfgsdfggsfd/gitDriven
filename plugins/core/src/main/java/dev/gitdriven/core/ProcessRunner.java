package dev.gitdriven.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external processes (git, gradle) with a timeout, keeping the last lines of output. */
final class ProcessRunner {
    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private volatile Process current;

    /**
     * @param keepLines how many trailing lines of output to keep in the result
     * @param logFile   if not null, receives the full output
     */
    Result run(Path workDir, List<String> command, Duration timeout, Map<String, String> env,
            int keepLines, Path logFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // never hang asking for a password
        pb.environment().putAll(env);

        Process process = pb.start();
        current = process;
        Deque<String> tail = new ArrayDeque<>();
        Thread reader = Thread.ofVirtual().start(() -> pump(process, tail, keepLines, logFile));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                reader.join(5_000);
                return new Result(-1, "Timed out after " + timeout.toMinutes() + " min\n" + joinTail(tail));
            }
            reader.join(5_000);
            return new Result(process.exitValue(), joinTail(tail));
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        } finally {
            current = null;
        }
    }

    /** Kills the running process (used when the plugin shuts down). */
    void cancel() {
        Process p = current;
        if (p != null) {
            kill(p);
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void pump(Process process, Deque<String> tail, int keepLines, Path logFile) {
        try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                Writer log = logFile == null ? Writer.nullWriter() : Files.newBufferedWriter(logFile)) {
            String line;
            while ((line = in.readLine()) != null) {
                log.write(line);
                log.write(System.lineSeparator());
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > keepLines) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process killed mid-read
        }
    }

    private static String joinTail(Deque<String> tail) {
        synchronized (tail) {
            return String.join("\n", tail);
        }
    }
}
