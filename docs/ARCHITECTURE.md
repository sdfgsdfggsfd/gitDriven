# Architecture

## Overview

```
GitHub                                   Server machine
──────                                   ──────────────
PR ─▶ CI (build.yml) ─┐                  start.sh (loop)
   CODEOWNER approves ┤                     │ 1. applies .git-driven/staging/ (if any)
auto-merge.yml ───────┴▶ main ◀── fetch ─┐  │ 2. java -jar folia.jar
                                         │  ▼
daily-recap.yml (00:00 UTC)              │  Folia
  └▶ Release recap-YYYY-MM-DD            └─ Core ── checkout → gradlew build → staging/
                                              └─ RestartController ── shutdown ─▶ (back to 1)
```

Three pieces, each with one responsibility:

| Piece | Does | Doesn't |
|---|---|---|
| **GitHub** (CI, auto-merge, recap) | Makes sure only code that builds and was approved gets in | Doesn't talk to the server |
| **Core** (plugin) | Detects commits, builds, prepares the staging, decides when to restart | Doesn't swap jars while the server is running |
| **start script** | Applies the staging while the server is **stopped** and starts it again | Doesn't build anything |

## Why restart and not hot-reload

Folia doesn't support reloading plugins (`/reload` and friends break or don't even exist).
Swapping jars while the server is running is also fragile (on Windows, the files are
locked). So every update is applied in a clean restart: Core shuts the server down,
the script swaps the files and starts it again. It takes about 20–40 s.

## The Core cycle

Every `poll-interval-seconds` (60 s), on its own thread, never on a game thread:

1. **Fetch.** `git fetch` of the configured branch into `.git-driven/repo/`.
2. **Nothing new?** If the remote commit is already in production or already staged, stop.
3. **Known broken commit?** If the last build of that same commit failed, don't
   try again (the next commit will). `/core check` forces it.
4. **Docs only?** If *all* files changed since the production commit match
   `ignore-paths` (`**.md`, `docs/**`, `.github/**`, `site/**`), mark it as applied, with no build
   and no restart.
5. **Build.** `checkout` of the commit + `gradlew build collectServerPlugins --no-daemon`
   with the same Java as the server. Full output in `.git-driven/last-build.log`.
   Failed: notify everyone with `core.admin`; the server stays on the current version.
6. **Staging.** Copy `build/server-plugins/*.jar` and the `server/` folder into
   `.git-driven/staging/`, write `COMMIT`, `CHANGES.txt` and, last, `READY`.
7. **Restart.** Announce it in chat and hand over to the `RestartController` (next section).

If another merge lands while an update is waiting for the restart, Core builds the
new commit and replaces the staging. The tip of `main` is always what goes to the server.

## Restart policy

Configurable in `restart.mode`:

| Mode | When it restarts | Good for | Bad for |
|---|---|---|---|
| `immediate` | Right after each build, with a countdown | Instant feedback | Lots of restarts on busy merge days |
| **`debounce`** (default) | 10 min after the **last** merge | Batches bursts of merges into a single restart | Wait of up to ~10 min |
| `daily` | At `daily-time-utc` (00:00), if there's anything | Minimal interruption | Up to 24 h to see your change |

In every mode, if `apply-when-empty: true` and nobody is online, it applies right away.

**Why `debounce` is the default:** the fun of the project is seeing your change live
right after the merge. Restarting only at midnight kills that loop; restarting on every
merge annoys whoever is playing on a day with many PRs. Debounce sits in the middle: a
lone merge goes live in ~10 min, five merges in a row become a single restart.

**What about the "daily recap"?** It's independent from the restart: the `daily-recap.yml`
workflow publishes a Release at 00:00 UTC with all the day's PRs, in any mode. Whoever
wants the full "everything from the day applied at midnight" package switches to `daily`.

Admins control it by hand with `/core restart [seconds]` and `/core cancel` (holds the
update until the next merge or until a `/core restart`).

## The `.git-driven/` contract

At the server root (the Java working directory):

```
.git-driven/
├── repo/                 clone of the repository (a mirror, never receives commits)
├── staging/              ready update, written by Core
│   ├── plugins/*.jar
│   ├── files/            copy of the repo's server/ (without README.md and .gitkeep)
│   ├── COMMIT            SHA
│   ├── CHANGES.txt       "abc1234 PR title (#12) — author", one per line
│   └── READY             created last; without it the script ignores the staging
├── deployed-commit       SHA in production (written by the script)
├── last-changes.txt      CHANGES.txt of the last update (shown on join and in /changelog)
├── managed-plugins.txt   jars installed by Core (removed before applying the new ones)
└── last-build.log        output of the last build
```

Before every boot, if `staging/READY` exists, the script (`start.sh` / `start.ps1`):

1. Removes from `plugins/` the jars listed in `managed-plugins.txt`. Jars placed on the
   server by hand are not touched. A plugin deleted from the repo disappears from the server.
2. Copies `staging/plugins/*.jar` into `plugins/` and updates `managed-plugins.txt`.
3. Copies `staging/files/` over the server root.
4. Writes `deployed-commit` and `last-changes.txt` and deletes the staging.

If the server starts without the script, Core notices the unapplied staging, logs an
error and does **not** schedule a restart (to avoid a loop).

## Building on the server (not in CI)

Building on the machine itself keeps the system simple: no token, no artifact download,
and the server depends only on the public repository. The cost is CPU/RAM during the
build (~1 min, Gradle capped at 1 GB in `gradle.properties`, no daemon). If that gets
heavy, a natural next step is for Core to download the `server-plugins` artifact that
CI already produces.

## Core updates itself

Core is also a plugin in the repo, so a PR to `plugins/core/` updates the updater
itself. That's why this folder is protected in `CODEOWNERS`. If a broken Core gets in,
the server stops updating itself: the fix is manual (see
[OPERATIONS.md](OPERATIONS.md#emergencies)).

## Build conventions

- `settings.gradle.kts` includes every `plugins/*` folder that has a `build.gradle.kts`.
- The root `build.gradle.kts` applies to every plugin: Java 25, `folia-api` as
  `compileOnly`, JUnit 5, a jar with the fixed name `<folder>.jar`, and it expands
  `${version}` (short commit SHA) and `${apiVersion}` in `plugin.yml`.
- `collectServerPlugins` gathers the jars into `build/server-plugins/`, which is what Core
  installs. `installLocal` copies them to `run/plugins/` (without Core) for local testing.

## Known limitations / next steps

- Polling every 60 s (a webhook would be instant, but requires an open port).
- No automatic rollback if the server doesn't come up with the new version (reverting the PR fixes it).
- The Folia jar is not managed by the repo.
- `scripts/` is copied to the server by hand: changes to it don't apply on their own.
