package dev.gitdriven.core;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pipeline: fetch → (docs only? mark as applied) → checkout → build → staging → notify the
 * RestartController. Runs on its own thread, one check at a time, never on a game thread.
 */
final class UpdateService {
    private static final int MAX_CHANGES = 50;

    private final CoreConfig config;
    private final DeployState state;
    private final GitRepo git;
    private final Builder builder;
    private final ProcessRunner runner;
    private final RestartController restart;
    private final Messages messages;
    private final Logger log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Core-Updater");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();

    private volatile String status = "idle";
    private volatile Instant lastCheck;
    private volatile String lastRemote;
    private volatile String failedCommit; // don't rebuild the same broken commit every minute
    private volatile String failedMessage;
    private volatile String lastError;
    private String lastLoggedError; // only touched by the worker thread

    UpdateService(CoreConfig config, DeployState state, ProcessRunner runner, RestartController restart,
            Messages messages, Logger log) {
        this.config = config;
        this.state = state;
        this.runner = runner;
        this.git = new GitRepo(state.repoDir(), runner, log);
        this.builder = new Builder(config, state, runner);
        this.restart = restart;
        this.messages = messages;
        this.log = log;
    }

    /** @return false if a check is already in progress */
    boolean requestCheck(boolean force) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        try {
            worker.execute(() -> {
                try {
                    checkOnce(force);
                    if (lastLoggedError != null) {
                        lastLoggedError = null;
                        log.info("Update checks are working again.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    onError(e);
                } finally {
                    status = "idle";
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) { // plugin shutting down
            busy.set(false);
            return false;
        }
        return true;
    }

    /**
     * Same error every minute (GitHub down, no network): log it once, not 60 times an hour.
     * git/Gradle failures are IOExceptions whose message already holds the output; anything
     * else is a bug and gets the stack trace.
     */
    private void onError(Exception e) {
        String summary = GitRepo.firstLine(e.getMessage());
        lastError = summary.isEmpty() ? e.getClass().getSimpleName() : summary;
        String key = e.getClass().getName() + ": " + e.getMessage();
        if (key.equals(lastLoggedError)) {
            return;
        }
        lastLoggedError = key;
        if (e instanceof IOException) {
            log.warning("Failed to check for updates (logged once until it changes): " + e.getMessage());
        } else {
            log.log(Level.WARNING, "Failed to check for updates", e);
        }
    }

    void shutdown() {
        worker.shutdownNow();
        runner.cancel();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkOnce(boolean force) throws Exception {
        status = "fetching";
        git.ensureClone(config.repoUrl(), config.branch());
        String remote = git.fetch(config.branch());
        lastCheck = Instant.now();
        lastRemote = remote;
        // The fetch worked: an old network error no longer applies. A failed build of this
        // same commit still does.
        lastError = remote.equals(failedCommit) ? failedMessage : null;

        String deployed = state.deployedCommit().orElse(null);
        String staged = state.stagedCommit().orElse(null);

        if (remote.equals(staged)) {
            return; // already built, waiting for the restart
        }
        if (remote.equals(deployed)) {
            if (staged != null) { // e.g. a revert/force-push back to what's already running
                state.clearStaging();
                restart.clearPending();
            }
            return;
        }
        if (remote.equals(failedCommit) && !force) {
            return;
        }

        boolean deployedKnown = deployed != null && git.hasCommit(deployed);
        if (deployedKnown) {
            List<String> files = git.changedFiles(deployed, remote);
            if (files.stream().allMatch(config.ignorePaths()::matches)) {
                log.info("Commit " + Messages.shortSha(remote) + " only touches docs/CI; no build and no restart.");
                git.checkout(remote);
                state.writeDeployedCommit(remote);
                if (staged != null) {
                    state.clearStaging();
                    restart.clearPending();
                }
                return;
            }
        }

        status = "building " + Messages.shortSha(remote);
        log.info("Building " + remote + "…");
        messages.notifyAdmins("<gray>Building <white><sha></white>…</gray>", "sha", Messages.shortSha(remote));
        git.checkout(remote);
        List<String> changes = git.log(deployedKnown ? deployed : null, remote, deployedKnown ? MAX_CHANGES : 10);

        ProcessRunner.Result result = builder.build(git.dir(), remote);
        if (!result.ok()) {
            failedCommit = remote;
            failedMessage = "build of " + Messages.shortSha(remote) + " failed (exit " + result.exitCode() + ")";
            lastError = failedMessage;
            log.warning("Build failed. The server stays on the current version. Full log: "
                    + state.buildLog() + "\n" + result.output());
            messages.notifyAdmins("<red>Build of <white><sha></white> failed.</red> <gray>The server stays on the "
                    + "current version. Log: .git-driven/last-build.log</gray>", "sha", Messages.shortSha(remote));
            return;
        }

        status = "staging " + Messages.shortSha(remote);
        builder.stage(git.dir(), remote, changes);
        failedCommit = null;
        failedMessage = null;
        lastError = null;
        log.info("Update " + Messages.shortSha(remote) + " ready in " + state.stagingDir());
        restart.onStaged(remote, changes);
    }

    String status() {
        return status;
    }

    Instant lastCheck() {
        return lastCheck;
    }

    String lastRemote() {
        return lastRemote;
    }

    String lastError() {
        return lastError;
    }
}
