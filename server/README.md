# server/

Everything in here is copied **over the server root** on every update (this README
and `.gitkeep` files are not). Use it to version configuration:

```
server/
├── bukkit.yml
├── config/paper-global.yml
└── plugins/
    └── Playground/config.yml
```

Rules:

- The repo file **always wins**: changes made directly on the server are overwritten on the next deploy.
- Deleting a file from here does **not** delete it from the server.
- Never put secrets here (RCON password, tokens, webhooks). The repository is public.
- Don't version game data (worlds, `ops.json`, `usercache.json`, `whitelist.json`).
