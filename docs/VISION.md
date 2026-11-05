# Project vision

## The idea

A Minecraft server where **the code is the server**. There's no "configure it in the
panel", no admin uploading plugins over FTP. If something exists on the server, it
exists in this repository, and anyone can propose changing it.

Want a new command? A crazy mechanic? A weekend event? Open a PR. If it's approved,
minutes later you join the game and it's there, with your name in the changelog.

The goal is **fun**: playing together, building together, coding together. It's not a
serious production server, it doesn't need 99.99% uptime, and it's fine if something
breaks now and then. It broke? Revert it and keep playing.

## Principles

1. **The repository is the truth.** Nothing is done by hand on the server. Changed
   something directly there? It will be overwritten on the next deploy.
2. **From merge to game with nobody in between.** Approved = live. Core handles the rest.
3. **Open to everyone, approved by a few.** Anyone can open a PR; a small group of
   trusted approvers gives the OK. That's the only gate.
4. **Fun > perfection.** Better a simple feature today than a perfect one never.
   Experiment. If it didn't turn out well, reverting is one click.
5. **Small and often.** One topic per PR. Easy to review, easy to undo.
6. **Everything in the open.** Every update shows up in chat, in `/changelog` and in the
   daily recap.
7. **AI agents are welcome.** Use whatever you want to write code, as long as it passes
   CI and a human approves it. That's what [AGENTS.md](../AGENTS.md) is for.

## Who's who

| Role | What they do |
|---|---|
| **Players** | Play, suggest ideas (issues or in chat), report bugs. |
| **Contributors** | Anyone who opens a PR. No need to be an experienced developer. |
| **Approvers** | Review and approve PRs (listed in `CODEOWNERS`). Approving = publishing. |
| **Core team** | Approvers for the sensitive areas: Core, the build and the automation. |
| **Operator** | Whoever hosts the machine. Installs once and then barely needs to touch it. |

The repository owner is [@sdfgsdfggsfd](https://github.com/sdfgsdfggsfd), currently the
approver and the Core team.

## A day on the server

- **3:00 PM** — someone in chat: "I wish I could sit on stairs".
- **3:20 PM** — it becomes an issue. Someone picks it up, asks their agent, tests it on a local server.
- **4:00 PM** — PR opened: "Add /sit on stairs". CI builds it and passes.
- **4:30 PM** — an approver reviews it, reads the "how to test" and approves. Auto-merge.
- **4:31 PM** — Core sees the new commit, builds it, announces in chat: "update ready".
- **4:41 PM** — nobody merged anything else in 10 min: 60 s countdown, restart.
- **4:42 PM** — everyone comes back and sees "What's new: Add /sit on stairs — someone".
- **00:00 UTC** — the `recap-…` Release comes out with all the day's PRs and who contributed.

## Scope

**In:**

- Java (Folia) plugins in `plugins/`, built on the server itself.
- Server and plugin configuration in `server/`.
- Automation: CI, auto-merge, daily recap, in-game notices.

**Out, for now** (ideas for the future, PRs welcome):

- Versioning the world/map.
- Updating the Folia jar automatically.
- Automatic rollback if the server doesn't come up.
- A GitHub webhook instead of polling.

## About security, without drama

The approvers are trusted people, so the risk is low, and it's fine if someone gets OP
from a funny PR. But one thing is real: the build runs **on the server machine**, so
approved code runs with the permissions of the hosting user. That's why the operator
runs the server isolated (dedicated user or container) and the approvers take a closer
look at PRs that touch the build and scripts.
More in [OPERATIONS.md](OPERATIONS.md#security).

## How to know it's working

- New people opening their first PR.
- A daily recap with something new almost every day.
- An idea from chat becoming a feature the same day.
- Nobody needed to log into the server machine all week.
