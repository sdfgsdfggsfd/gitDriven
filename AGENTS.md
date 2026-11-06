# AGENTS.md

Instructions for AI agents (Claude Code, Codex, Copilot, Cursor…) and for humans who
like straight rules. Read the whole thing before touching anything.

## The project in 30 seconds

- A **Folia 26.3** Minecraft server whose entire content lives in this repository.
- Approved PR → auto-merge (squash) into `main` → the **Core** plugin, running on the
  server, does a `git fetch`, builds everything with Gradle and restarts the server
  with the new version.
- In other words: **whatever you merge runs in production minutes later, with nobody in between.**
  The CI check is the only automatic safety net. If it doesn't build, it doesn't get in.
- Details: [docs/VISION.md](docs/VISION.md) (why) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how).

## Repository map

```
plugins/
  core/          git-driven updater (protected area, see below)
  playground/    community plugin: the default place for small features
  <name>/        any folder with a build.gradle.kts becomes a plugin automatically
server/          versioned configs, copied over the server root on deploy
scripts/         start.sh / start.ps1 (server loop) and check-folia.sh (lint)
docs/            vision, architecture, operations, maintainer guide
site/            the public website (static HTML/CSS/JS, published to GitHub Pages)
build.gradle.kts conventions for every plugin (Java 25, Folia API, jar name)
gradle.properties Folia API version
```

## Commands

| What | Command |
|---|---|
| Build + test everything (same as CI) | `./gradlew build collectServerPlugins` |
| Folia lint (same as CI) | `sh scripts/check-folia.sh` |
| A single plugin | `./gradlew :plugins:playground:build` |
| Copy plugins to a local server in `run/` | `./gradlew installLocal` |

On Windows use `gradlew.bat`. Requires Java 25 (Gradle downloads it if missing).
**Before opening a PR, the first two commands must pass.**

## Rule #1: this is Folia, not Paper

Folia splits the world into regions, each with its own thread. There is no
"main thread". Code that works on Paper can deadlock or break here.

**Forbidden** (CI rejects it):

- `Bukkit.getScheduler()`, `BukkitRunnable`, `BukkitScheduler` — they throw on Folia.
- `plugin.yml` without `folia-supported: true` — Folia won't even load the plugin.

**Use the right scheduler:**

```java
// Touching a player/entity (follows the entity across regions)
player.getScheduler().run(plugin, task -> player.setHealth(20), null);
player.getScheduler().runDelayed(plugin, task -> ..., null, 20L); // 20 ticks = 1s

// Touching blocks/a location in the world
Bukkit.getRegionScheduler().execute(plugin, location, () -> location.getBlock().setType(Material.STONE));

// Global stuff (world time, weather, broadcasts)
Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcast(msg));

// I/O, HTTP, heavy math — off the game threads
Bukkit.getAsyncScheduler().runNow(plugin, task -> ...);
```

**More threading rules:**

- An event runs on the thread of the region that owns the event's entity/block. From
  there you can touch that entity/block directly. For **another** entity or location,
  schedule on its scheduler.
- When in doubt: `Bukkit.isOwnedByCurrentRegion(entity)` / `Bukkit.isOwnedByCurrentRegion(location)`.
- Teleporting is `entity.teleportAsync(location)`. Synchronous `teleport()` fails across regions.
- Don't keep shared mutable state without synchronization: use `ConcurrentHashMap` & co.
- Never block a region thread (network, disk, `Thread.sleep`). Go to the async scheduler.
- Commands: a player's command runs on the player's region, a console command on the global thread.

## How to add things

**Small feature** (a command, an event, a mechanic): go to `plugins/playground/`.

1. Create a new class in `dev.gitdriven.playground` (use `HelloCommand.java` as a template).
2. Register it with **one line** in `PlaygroundPlugin#onEnable`, in the marked block.
3. New command? Declare it in `src/main/resources/plugin.yml` (with a permission).

**New plugin** (something big, with its own identity):

1. Create `plugins/<lowercase-name>/build.gradle.kts` (it can contain just a comment).
2. `src/main/resources/plugin.yml`:
   ```yaml
   name: PluginName
   version: "${version}"
   main: dev.gitdriven.<name>.PluginNamePlugin
   api-version: "${apiVersion}"
   folia-supported: true
   ```
3. Main class in `src/main/java/dev/gitdriven/<name>/`.
4. Done: `settings.gradle.kts` finds the folder on its own, and the jar comes out as `<name>.jar`.

**Dependencies:** prefer libraries that Paper downloads by itself, declared in `plugin.yml`
(`libraries: ["com.example:lib:1.2.3"]`, Maven Central only) and as `compileOnly` in the
plugin's `build.gradle.kts`. Shading (Shadow) only if unavoidable — and the final jar
must still be named `<name>.jar` and be the output of the `jar` task.

**Configs:** default values go in the `config.yml` inside the jar (`saveDefaultConfig()`).
To pin a value on the server, version it in `server/plugins/<PluginName>/config.yml`.
Only config files are allowed there (no jars or scripts, nothing in `plugins/Core/` but
`restart.yml`); Core refuses the commit otherwise. See [server/README.md](server/README.md).

## Protected areas

Changes to these paths require approval from the Core team (`.github/CODEOWNERS`).
Don't touch them unless the task is specifically about them:

- `plugins/core/` — if Core breaks, the server stops updating itself.
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew*` — they run on the server machine.
- `scripts/`, `.github/` — deploy and CI infrastructure.

Never change the Gradle Wrapper or the Folia API version "in passing" in a feature PR.

## Code conventions

- Java 25. **Everything in English**: identifiers, comments, docs and player-facing messages.
- Package `dev.gitdriven.<plugin>`. One class per feature.
- Messages with Adventure/MiniMessage (bundled with the server). Text coming from a player
  goes in with `Placeholder.unparsed(...)`, never concatenated into the MiniMessage string.
- No `System.out`: use `getLogger()`.
- Tests are welcome (JUnit 5 is already set up in every plugin, `src/test/java`).
- Nothing heavy per tick. If something must always run, think about the interval and how
  many players there are.

## Pull requests

- **One topic per PR.** Small is better: faster to review, easier to revert.
- **The title becomes the in-game changelog** (`/changelog`) and the daily recap. Write it
  for players: "Add /sit on stairs", not "refactor SitHandler".
- Fill in the template: what changes and **how to test it in-game**.
- Say in the PR that you used an agent, and which one. Not a problem, it helps reviewers.
- Don't open a PR you haven't run `./gradlew build` on locally.

## Limits

- No secrets in the repo (tokens, passwords, webhooks). It's public.
- No backdoors, hidden commands or silently granted permissions. Anything that gives
  someone power must be written in the PR description.
- No network calls to unknown hosts, no running external processes, no deleting files
  outside the plugin's own data folder. (Core is the exception, and it's protected.)
- Nothing that takes the server down for everyone on purpose (lag machines, infinite loops).
  Pranks yes, sabotage no.

## If you are an agent

- Read the code you're about to change before changing it. Follow the existing patterns.
- Run `./gradlew build collectServerPlugins` and `sh scripts/check-folia.sh` before saying you're done.
- Don't invent APIs: the reference is the Folia/Paper API at the version in `gradle.properties`.
  Javadoc: <https://jd.papermc.io/folia/>.
- If the task requires touching a protected area, say so explicitly in the PR.
