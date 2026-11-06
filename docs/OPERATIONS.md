# Operations (for whoever hosts)

Install once; after that the server keeps itself up to date from the repository.

## Requirements

- **Java 25** (required by Folia 26.3).
- **git** on the `PATH`.
- Linux recommended. Windows works (`start.ps1`).
- Outbound access to `github.com` (the repository and the `server-build` release, which
  redirects to `objects.githubusercontent.com`).
- Only with `build.source: local` (Gradle on the machine): + ~1 GB RAM during builds and
  access to `repo.papermc.io` and Maven Central.

## Installation

1. **Server folder.** Create it (e.g. `/srv/minecraft`), put the Folia jar in it as
   `folia.jar` (or set `SERVER_JAR`), run it once and accept Mojang's EULA in `eula.txt`.

2. **Repository URL.** `plugins/core/src/main/resources/config.yml` already points to
   `https://github.com/sdfgsdfggsfd/gitDriven.git`. If you run a fork, change it to your
   fork's URL and merge that into the repo, so every Core build points to the right place.

3. **Initial Core.** On any machine with the repo cloned:
   ```sh
   ./gradlew :plugins:core:jar
   cp plugins/core/build/libs/core.jar /srv/minecraft/plugins/core.jar
   ```
   The name must be `core.jar`, so later versions of Core replace this one.

4. **Start script.** Copy `scripts/start.sh` (or `start.ps1`) into the server folder.
   Always start the server through it: it sets `GIT_DRIVEN_LAUNCHER`, and without that
   variable Core never restarts the server (nothing would start it again). Updates are
   then built but wait, and `/core status` says so. After a change to `scripts/`, copy
   the script again (it doesn't update itself).

5. **Start it.** From inside the server folder:
   ```sh
   tmux new -s mc 'MEMORY=6G sh start.sh'
   ```
   On Windows: `powershell -ExecutionPolicy Bypass -File start.ps1 -Memory 6G`.

6. **First cycle.** Within ~15 s Core clones the repo, downloads the jars CI built for the
   tip of `main` and, with the server empty, restarts. (Nothing published yet? Run
   Actions → Server build → Run workflow on GitHub, or wait for its 10-minute schedule.) The script applies the update and the server comes back with
   all plugins. Check with `/core status` in the console.

### With systemd

```ini
# /etc/systemd/system/minecraft.service
[Unit]
Description=gitDriven Minecraft server
After=network-online.target
Wants=network-online.target

[Service]
User=minecraft
WorkingDirectory=/srv/minecraft
Environment=MEMORY=6G
ExecStart=/bin/sh /srv/minecraft/start.sh
Restart=always
TimeoutStopSec=90

[Install]
WantedBy=multi-user.target
```

The script's loop already restarts the server; `Restart=always` covers the case where
the script itself dies. There's no stdin under systemd, so use RCON (password **outside**
the repo) for the console.

## Script variables

| Variable | Default | |
|---|---|---|
| `SERVER_JAR` | `folia.jar` | server jar |
| `MEMORY` | `4G` | `-Xms` and `-Xmx` |
| `JAVA` | `java` | Java 25 binary |
| `JAVA_ARGS` | empty | extra flags (e.g. Aikar's flags) |
| `RESTART_DELAY` | `5` | seconds between stopping and starting again |

`start.sh --apply-only` (or `start.ps1 -ApplyOnly`) only applies the staging and exits.
If you use it as `ExecStartPre` and start Java directly from systemd (with
`Restart=always`), also set `Environment=GIT_DRIVEN_LAUNCHER=systemd`, so Core knows
something will start the server again.

## In-game/console commands

| Command | Who | What |
|---|---|---|
| `/core status` | `core.admin` (op) | commit in production, staging, next restart, last error |
| `/core check` | `core.admin` | checks the repo now (and retries a commit that failed) |
| `/core restart [s]` | `core.admin` | restarts in `s` seconds (default 30), with or without an update |
| `/core cancel` | `core.admin` | cancels the countdown and holds the update |
| `/changelog` | everyone | last update and what's coming next |

## Core configuration

It lives in `plugins/Core/config.yml` (the default comes from the jar) and **only on the
server**: the repository can't overwrite it (Core refuses a commit that tries). So the
repository URL, branch and build settings can only be changed on the machine, and a merge
can't send Core to another repository or break its updates.

The restart policy can be versioned: `server/plugins/Core/restart.yml` holds the keys of
the `restart:` section (without that level) and overrides them. Options are explained in
the default `config.yml` and in [ARCHITECTURE.md](ARCHITECTURE.md#restart-policy).

An invalid value (a typo in `restart.mode`, a time like `25:00`) never disables Core: it
falls back to the default and logs a warning at startup. `debounce-minutes` is capped at
one day and `countdown-seconds` at 600, so no restart setting can hold updates back
indefinitely. Check the console after changing it.

## Updating the Folia version

1. Open a PR changing `foliaApiVersion` and `minecraftApiVersion` in `gradle.properties`.
2. After the merge, when Core announces the update is ready, run
   `/core cancel` (so it doesn't restart with the old jar).
3. Stop the script (Ctrl+C), swap `folia.jar`, start it again: the script applies the
   staging and the server comes back on the new version.

## Emergencies

**Broken build on main.** Nothing to do on the machine: the server stays on the last
good version. Merge a fix or a revert. The log is in the failed `Server build` run on
GitHub (or `.git-driven/last-build.log` with `build.source: local`).

**CI down or not publishing.** `/core status` shows "waiting for the CI build", and the
admins are warned after 1 h. Check the `Server build` workflow (GitHub disables schedules
after 60 days without activity in the repository). To keep updating meanwhile, set
`build.source: local` in `plugins/Core/config.yml` and restart: Core builds on the machine
again.

**New plugin crashing the server.** Usually Paper just disables the plugin that errored
and the rest keeps working. The fix is **reverting the PR on GitHub**. If the server
doesn't even come up: stop the script, delete the guilty jar from `plugins/`, start it
again. Once the revert is merged, Core applies it normally.

**Broken Core** (no longer updates). Build Core from a good commit
(`./gradlew :plugins:core:jar`), put it in place as `plugins/core.jar`, delete
`.git-driven/staging/` and start the server. Then revert the PR that broke it.

**Update checks failing.** `/core status` shows the last error; the console logs it once
(not every minute) and says when checks work again. Core removes stale git lock files
left by a shutdown in the middle of a fetch, and clones again if its copy of the repo is
broken, so most of these fix themselves.

**Start from scratch.** Stop the server, delete `.git-driven/` and start it: Core clones
and builds everything again.

## Backups

Community plugins touch the world. Back up the worlds at least once a day (e.g. a cron
job with `tar` at 23:50 UTC, before the recap). The repository doesn't store the world.

## Security

The risk here is low and accepted (trusted approvers, a server for fun), but know what
is running:

- **Approved code runs on the machine.** The plugins run inside the server's JVM, with the
  permissions of the hosting user. The build (Gradle scripts, tests) runs in CI, not here,
  unless `build.source: local`.
- Run it as a **dedicated user without sudo**, preferably in a container or VM.
- Don't keep anything sensitive on that machine/user (SSH keys for other places, tokens).
- Secrets (RCON, webhooks) live only on the server, never in `server/` in the repo.
  Core refuses a `server.properties` with `rcon.password` or `management-server-secret` set.
- `server/` only deploys config files: Core refuses commits that put jars, scripts, hidden
  files, Core's `config.yml` or `ops.json`/whitelist/bans there (rules in
  [server/README.md](../server/README.md)).
- Approvers: extra attention on PRs that touch `*.gradle.kts`, `gradle/`, `scripts/` and `server/`.
