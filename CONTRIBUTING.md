# Contributing

Anyone can contribute. You don't need to be an experienced developer, and using an AI
agent is totally fine.

## Without coding

- **Idea?** Open an issue with the "Idea" template. Someone may pick it up.
- **Bug?** Use the "Bug" template, with steps to reproduce.
- **Review?** Commenting on open PRs helps the approvers.

## Environment

- **Java 25** ([Temurin](https://adoptium.net/)) and **git**.
- Editor: IntelliJ IDEA Community opens the project directly (it's Gradle). VS Code with
  the "Extension Pack for Java" works too.
- No need to install Gradle: use `./gradlew` (or `gradlew.bat` on Windows).

## Workflow

1. Fork and clone. Create a branch: `git switch -c sit-on-stairs`.
2. Write the code:
   - small feature → `plugins/playground/`
   - something big → new plugin in `plugins/<name>/`
   - config → `server/`

   Step by step and the Folia rules are in [AGENTS.md](AGENTS.md). **Read the Folia
   part**: it's where almost everyone slips up the first time.
3. Check:
   ```sh
   ./gradlew build collectServerPlugins
   sh scripts/check-folia.sh
   ```
4. Test on a local server (recommended):
   ```sh
   mkdir run && cp /path/to/folia-26.3.jar run/folia.jar
   ./gradlew installLocal          # copies the plugins to run/plugins (without Core)
   cd run && java -jar folia.jar --nogui
   ```
   The first time, accept the EULA in `run/eula.txt`. The `run/` folder is ignored by git.
5. Open the PR and fill in the template. **The title becomes the in-game changelog.**

## With an AI agent

[AGENTS.md](AGENTS.md) has everything the agent needs. Claude Code reads it on its own
(via `CLAUDE.md`); Codex, Cursor, Copilot etc. read `AGENTS.md`. Example prompts:

> Add a /sit command to the Playground that makes the player sit on the stairs they're
> looking at. Follow AGENTS.md and run the build at the end.

> Create a new "events" plugin that launches fireworks at spawn every day at 20:00
> UTC. Remember it's Folia.

Review what the agent did before opening the PR, and say in the PR that you used one.

## After the merge

| When | What |
|---|---|
| ~1 min | Core sees the commit and builds it |
| right away | chat notice: "update ready" |
| ~10 min with no other merges | 60 s countdown and restart (immediately if nobody is online) |
| on join | "What's new" with your PR |
| 00:00 UTC | your PR in the day's `recap-…` Release |

## Rules

- One topic per PR, title written for players.
- Build and lint passing.
- No secrets, no backdoors, no sabotage. Pranks are welcome.
- Be kind ([CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)).
