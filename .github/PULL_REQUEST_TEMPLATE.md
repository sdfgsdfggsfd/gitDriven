<!-- The title becomes the in-game changelog line. Be clear: "Add /sit", "Fix wolf spawning". -->

## What changes on the server

<!-- In one or two sentences, from a player's point of view. -->

## How to test in-game

<!-- Steps for the approver to check once they join. -->
1.

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] `sh scripts/check-folia.sh` passes (no `Bukkit.getScheduler()`)
- [ ] New plugin? It has `folia-supported: true` in `plugin.yml`
- [ ] No passwords, tokens or personal data in the code/configs
- [ ] One topic per PR

<!-- Used an AI agent (Claude Code, Codex, Copilot…)? Say which one here. No problem at all, it just helps reviewers. -->
