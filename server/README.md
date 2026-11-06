# server/

Everything in here is copied **over the server root** on every update (`README.md` and
`.gitkeep` files are not). Use it to version configuration:

```
server/
├── bukkit.yml
├── config/paper-global.yml
└── plugins/
    ├── Core/restart.yml        (only the restart policy, see docs/MAINTAINERS.md)
    └── Playground/config.yml
```

Rules:

- The repo file **always wins**: changes made directly on the server are overwritten on the next deploy.
- Deleting a file from here does **not** delete it from the server.
- Never put secrets here (RCON password, tokens, webhooks). The repository is public.
- Don't version game data (worlds, `ops.json`, `usercache.json`, `whitelist.json`).

Core checks this folder before every build and **refuses the whole commit** (the server
stays on the current version, admins are notified) if it has:

- anything other than config files: only `.yml .yaml .json .properties .toml .conf .cfg
  .txt .png` (no jars, no scripts);
- hidden files or folders (`.something`), symbolic links, or ambiguous names (`~`, `:`, a
  name ending in `.` or a space);
- anything in `plugins/Core/` other than `restart.yml` (Core's `config.yml` stays on the server);
- `ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json` or `usercache.json` at the root;
- a `server.properties` with `rcon.password` or `management-server-secret` set.
