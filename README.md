# gitDriven

A Minecraft server **built by the people who play on it**. Everything that runs on it
(plugins, mechanics, configs) lives in this public repository. Open a PR, get it
approved: a few minutes later it's on the server. No manual deploys, no admin in between.

> **Website:** [sdfgsdfggsfd.github.io/gitDriven](https://sdfgsdfggsfd.github.io/gitDriven/) · **Discord:** [discord.gg/TaWZaVSdhr](https://discord.gg/TaWZaVSdhr) · **IP:** coming soon · **Version:** Folia 26.3 · **Changelog:** [`recap-*` releases](https://github.com/sdfgsdfggsfd/gitDriven/releases) and `/changelog` in-game

## How it works

```
 you ── PR ──▶ GitHub ── approval + green CI ──▶ auto-merge (squash) into main
                                                          │
                                                          ▼  git fetch every 60s
                                            ┌──── Core (plugin on the server) ───┐
                                            │ checkout → gradlew build → staging │
                                            └──────────────┬─────────────────────┘
                                                           ▼
                        chat notice → restart → start script applies → server is back
                                                           │
                            00:00 UTC: the day's recap is published as a Release
```

- **Core** (`plugins/core`) is the plugin that pulls the repo, builds it and prepares the update.
- The **restart** happens 10 min after the last merge (batching merges that land close
  together), or right away if the server is empty. It can be switched to "immediate"
  or "only at midnight UTC".
- A commit that only touches docs or the website triggers no build and no restart.
- Build broke? The server stays on the current version and the admins are notified.

Details in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Contribute in 5 steps

1. Fork and clone. You need **Java 25** and **git**.
2. Create your feature in `plugins/playground/` (small) or in a new plugin in `plugins/<name>/`.
3. Run `./gradlew build` and `sh scripts/check-folia.sh`.
4. Open the PR. **The title becomes the in-game changelog line**, so write it for players.
5. Once an approver gives the OK, merge and deploy are automatic.

Full guide: [CONTRIBUTING.md](CONTRIBUTING.md). Using an AI agent? It should read
[AGENTS.md](AGENTS.md) (Claude Code reads it on its own via [CLAUDE.md](CLAUDE.md)).

## Layout

```
plugins/core/        the updater (protected)
plugins/playground/  community plugin
plugins/<name>/      every folder with a build.gradle.kts is a plugin
server/              versioned configs → server root
scripts/             start.sh / start.ps1 and the Folia lint
docs/                vision, architecture, operations, maintainers
site/                the website (GitHub Pages, no build step)
```

## Documentation

| Document | For |
|---|---|
| [docs/VISION.md](docs/VISION.md) | Everyone: the goal and the ground rules |
| [CONTRIBUTING.md](CONTRIBUTING.md) | People who want to contribute |
| [AGENTS.md](AGENTS.md) | AI agents (and humans who like straight rules) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | People who want to understand the pipeline |
| [docs/MAINTAINERS.md](docs/MAINTAINERS.md) | Approvers |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Whoever hosts the server |

## License

[MIT](LICENSE).
