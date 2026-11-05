# Maintainer guide

Approving a PR here **is publishing to the server**: after your OK, auto-merge kicks in
and Core deploys on its own. There is no "now push it to production" step.

## GitHub setup (once)

**Settings → General → Pull Requests**
- [ ] Only **Allow squash merging** (uncheck merge commits and rebase).
      Default message: **Pull request title** — that's what shows up in `/changelog`.
- [ ] **Allow auto-merge**.
- [ ] **Automatically delete head branches**.

**Settings → Rules → Rulesets → New branch ruleset** (target: default branch `main`)
- [ ] **Restrict deletions** and **Block force pushes**.
- [ ] **Require a pull request before merging**
  - Required approvals: **1** (or more, if you want)
  - [ ] Dismiss stale pull request approvals when new commits are pushed
  - [ ] Require review from Code Owners
  - [ ] Require approval of the most recent reviewable push
- [ ] **Require status checks to pass** → add the **`build`** check.

Without "dismiss stale approvals", someone could push after the approval and
auto-merge would ship the new code unreviewed.

**Solo owner:** GitHub doesn't let you approve your own PR. While
[@sdfgsdfggsfd](https://github.com/sdfgsdfggsfd) is the only code owner, add the
**Repository admin** role to the ruleset's **Bypass list** (so you can merge your own
PRs), or add a second approver to `CODEOWNERS`.

**Settings → Actions → General**
- Fork PRs from first-time contributors require **"Approve and run"** before CI
  runs. That's normal: check that the PR doesn't touch `.github/` and approve it.

**Settings → Pages**
- [ ] Source: **GitHub Actions**. The `Website` workflow then publishes `site/` to
      <https://sdfgsdfggsfd.github.io/gitDriven/> on every merge that touches it.
      Edit `site/config.js` to set the server address once there is one.

**CODEOWNERS** — `.github/CODEOWNERS` currently lists only `@sdfgsdfggsfd`. To add
approvers, add their usernames next to it (e.g. `@alice @bob`). Teams
(`@org/team`) require a GitHub organization.

**Discord** — the channel's webhook URL never goes in the repo: it's public, and anyone
with the URL can post to the channel.
- [ ] **Settings → Webhooks → Add webhook**: an alert for every code change (pushes to
      any branch, merges included, and pull requests). Payload URL: the Discord webhook
      URL **with `/github` at the end**; content type `application/json`; events:
      **Pushes** and **Pull requests**.
- [ ] Optional secret `DISCORD_WEBHOOK` (Settings → Secrets → Actions): the daily recap
      gets posted to Discord too. Same URL, without `/github`.

## Review checklist

- [ ] **Is it fun / does it make sense for the server?** That's the main criterion.
- [ ] Does the title work as a changelog line for players?
- [ ] One topic only? If not, ask for it to be split.
- [ ] **Folia:** no `Bukkit.getScheduler()`; touches entities/blocks through the right
      scheduler; teleports with `teleportAsync`. (CI catches the basics, not everything.)
- [ ] Nothing heavy per tick, nothing that grows without bounds (lists, maps, entities).
- [ ] Touches `*.gradle.kts`, `gradle/`, `scripts/` or `.github/`? That runs **on the
      server machine**: read it line by line.
- [ ] Does it give someone permissions or power? It must be written in the description.
- [ ] No secrets, no network calls to strange hosts, no external processes.

It doesn't need to be perfect. If it's fun and doesn't break the server, approve it. If
it goes wrong, revert it.

## Reverting

On the merged PR's page: **Revert** → opens a new PR → approve it. Core applies the
revert like any other change. If the server doesn't come up, see
[OPERATIONS.md](OPERATIONS.md#emergencies).

## Changing the restart policy

Version `server/plugins/Core/config.yml` (copy the default from
`plugins/core/src/main/resources/config.yml`) and change `restart.mode`. It takes effect
from the restart that applies that PR. Options in [ARCHITECTURE.md](ARCHITECTURE.md#restart-policy).

## Daily recap

The `daily-recap.yml` workflow runs at 00:00 UTC and publishes a `recap-YYYY-MM-DD`
Release with the PRs merged the previous day. You can run it by hand at
**Actions → Daily recap → Run workflow**.
