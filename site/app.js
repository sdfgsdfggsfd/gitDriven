// gitDriven website: fills in the server address and the live changelog from GitHub.
// Everything that comes from GitHub goes in with textContent, never as HTML.
(() => {
  "use strict";

  const cfg = Object.assign(
    { repo: "sdfgsdfggsfd/gitDriven", address: "", version: "26.3", discord: "" },
    window.GITDRIVEN || {}
  );
  const repoUrl = `https://github.com/${cfg.repo}`;
  const api = `https://api.github.com/repos/${cfg.repo}`;
  const CACHE_MS = 5 * 60 * 1000;
  const MAX_MERGED = 8;
  const MAX_OPEN = 5;

  const $ = (id) => document.getElementById(id);

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  // ---------- Links and server entry ----------

  document.querySelectorAll("[data-gh]").forEach((a) => {
    a.href = repoUrl + a.dataset.gh;
  });

  if (cfg.discord) {
    document.querySelectorAll("[data-discord]").forEach((li) => {
      li.querySelector("a").href = cfg.discord;
      li.hidden = false;
    });
  }

  $("server-version").textContent = `Java Edition ${cfg.version}`;

  if (cfg.address) {
    const address = $("server-address");
    const button = $("copy-address");
    const status = $("copy-status");
    address.textContent = cfg.address;
    address.hidden = false;
    button.hidden = false;
    $("server-hint").hidden = false;
    $("server-soon").hidden = true;

    let reset;
    button.addEventListener("click", async () => {
      clearTimeout(reset);
      try {
        await navigator.clipboard.writeText(cfg.address);
        button.textContent = "Copied";
        status.textContent = "Address copied.";
      } catch {
        // No clipboard access: select the address so Ctrl+C works.
        const range = document.createRange();
        range.selectNodeContents(address);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = "Press Ctrl+C";
        status.textContent = "Address selected. Press Ctrl+C to copy it.";
      }
      reset = setTimeout(() => {
        button.textContent = "Copy address";
      }, 2500);
    });
  }

  // ---------- GitHub ----------

  // Kept in localStorage: fresh for 5 minutes, and if GitHub fails later (the API allows 60
  // requests per hour per visitor) the last copy is shown instead of an error.
  function cached(key) {
    try {
      const hit = JSON.parse(localStorage.getItem(key));
      if (hit && typeof hit.t === "number" && Array.isArray(hit.v)) return hit;
    } catch {
      // Storage blocked or corrupt: just fetch again.
    }
    return null;
  }

  function store(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify({ t: Date.now(), v: value }));
    } catch {
      // Storage full or blocked: the page works without it.
    }
  }

  /** Resolves to { list, stale } where stale is the age (ms) of a cached copy used after an error. */
  async function pulls(query) {
    const key = `gitdriven:v2:${cfg.repo}:${query}`;
    const hit = cached(key);
    if (hit && Date.now() - hit.t < CACHE_MS) return { list: hit.v, stale: 0 };

    try {
      const list = await fetchPulls(query);
      store(key, list);
      return { list, stale: 0 };
    } catch (error) {
      if (hit) return { list: hit.v, stale: Date.now() - hit.t, error };
      throw error;
    }
  }

  async function fetchPulls(query) {
    const res = await fetch(`${api}/pulls?${query}`, {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) {
      const error = new Error(`GitHub answered ${res.status}`);
      error.status = res.status;
      throw error;
    }
    return (await res.json()).map((pr) => ({
      number: pr.number,
      title: pr.title,
      url: pr.html_url,
      login: pr.user ? pr.user.login : "ghost",
      avatar: pr.user ? pr.user.avatar_url : "",
      profile: pr.user ? pr.user.html_url : "",
      bot: pr.user ? pr.user.type === "Bot" : false,
      draft: Boolean(pr.draft),
      mergedAt: pr.merged_at,
    }));
  }

  // ---------- Chat rendering ----------

  const log = $("chat-log");
  log.setAttribute("aria-busy", "true"); // until the changelog arrives (see show())
  const ago = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

  function relative(iso) {
    const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
    const units = [
      ["year", 31536000],
      ["month", 2592000],
      ["week", 604800],
      ["day", 86400],
      ["hour", 3600],
      ["minute", 60],
    ];
    for (const [unit, size] of units) {
      if (Math.abs(seconds) >= size) return ago.format(Math.round(seconds / size), unit);
    }
    return "just now";
  }

  /** "Core » " followed by the given parts. */
  function coreLine(...parts) {
    const p = el("p", "chat-line");
    p.append(el("span", "core", "Core"), el("span", "c-dgray", " » "), ...parts);
    return p;
  }

  function changeItem(pr, withDate) {
    const li = el("li", "chat-line");
    const link = el("a", "c-gray", `${pr.title} (#${pr.number})`);
    link.href = pr.url;
    li.append(el("span", "c-dgray", "  • "), link, el("span", "c-gray", ` — ${pr.login}`));
    if (withDate && pr.mergedAt) {
      const time = el("time", "c-date", `  ${relative(pr.mergedAt)}`);
      time.dateTime = pr.mergedAt;
      time.title = new Date(pr.mergedAt).toLocaleString();
      li.append(time);
    }
    return li;
  }

  function moreLine(count) {
    return el("li", "chat-line c-dgray", `  … and ${count} more`);
  }

  function githubLink(text, path) {
    const a = el("a", "c-aqua", text);
    a.href = repoUrl + path;
    return a;
  }

  function show(lines) {
    log.replaceChildren(...lines);
    log.querySelectorAll(".chat-line").forEach((line, i) => line.style.setProperty("--i", i));
    log.classList.add("is-live");
    log.removeAttribute("aria-busy");
  }

  function staleLine(age) {
    return coreLine(
      el("span", "c-yellow", `GitHub isn't answering right now, so this is the list from ${relative(new Date(Date.now() - age).toISOString())}. `),
      githubLink("See the latest on GitHub", "/pulls?q=is%3Apr+is%3Amerged"),
      el("span", "c-yellow", ".")
    );
  }

  function renderChangelog(merged, open, stale) {
    const lines = [];

    if (merged.length === 0 && open.length === 0) {
      lines.push(coreLine(el("span", "c-gray", "No changes recorded yet.")));
      const p = el("p", "chat-line");
      p.append(
        el("span", "c-dgray", "  • "),
        githubLink("Open the first pull request", "/blob/main/CONTRIBUTING.md")
      );
      lines.push(p);
      show(lines);
      return;
    }

    if (merged.length > 0) {
      lines.push(coreLine(el("span", "c-green", "Latest changes:")));
      const ul = el("ul", "chat-list");
      merged.slice(0, MAX_MERGED).forEach((pr) => ul.append(changeItem(pr, true)));
      lines.push(ul);
    }

    if (open.length > 0) {
      lines.push(coreLine(el("span", "c-gold", "In review, live once approved:")));
      const ul = el("ul", "chat-list");
      open.slice(0, MAX_OPEN).forEach((pr) => ul.append(changeItem(pr, false)));
      if (open.length > MAX_OPEN) ul.append(moreLine(open.length - MAX_OPEN));
      lines.push(ul);
    }

    if (stale > 0) lines.push(staleLine(stale));
    show(lines);
  }

  function renderError(error) {
    const reason =
      error.status === 403 || error.status === 429
        ? "GitHub is limiting requests right now. Try again in a few minutes, or "
        : error.status === 404
          ? "The repository isn't on GitHub yet. Once it is, "
          : "Couldn't reach GitHub. ";
    const tail = error.status === 404 ? "the changelog shows up here." : null;
    const parts = [el("span", "c-yellow", reason)];
    if (tail) {
      parts.push(el("span", "c-yellow", tail));
    } else {
      parts.push(githubLink("see the changes on GitHub", "/pulls?q=is%3Apr+is%3Amerged"), el("span", "c-yellow", "."));
    }
    show([coreLine(...parts)]);
  }

  function renderBuilders(merged) {
    const seen = new Map();
    for (const pr of merged) {
      if (!pr.bot && !seen.has(pr.login)) seen.set(pr.login, pr);
    }
    if (seen.size === 0) return;

    const faces = $("faces");
    for (const pr of [...seen.values()].slice(0, 16)) {
      const li = el("li");
      const a = el("a");
      a.href = pr.profile;
      a.title = pr.login;
      const img = el("img");
      // An 8x8 avatar scaled up with crisp pixels: everyone gets a Minecraft face.
      img.src = `${pr.avatar}${pr.avatar.includes("?") ? "&" : "?"}s=8`;
      img.alt = pr.login;
      img.width = 40;
      img.height = 40;
      img.loading = "lazy";
      a.append(img);
      li.append(a);
      faces.append(li);
    }
    $("builders").hidden = false;
  }

  Promise.all([
    pulls("state=closed&base=main&sort=updated&direction=desc&per_page=40"),
    pulls("state=open&base=main&sort=created&direction=desc&per_page=20"),
  ])
    .then(([closed, opened]) => {
      const merged = closed.list
        .filter((pr) => pr.mergedAt)
        .sort((a, b) => Date.parse(b.mergedAt) - Date.parse(a.mergedAt));
      const open = opened.list.filter((pr) => !pr.draft);
      renderChangelog(merged, open, Math.max(closed.stale, opened.stale));
      renderBuilders(merged);
    })
    .catch(renderError);
})();
