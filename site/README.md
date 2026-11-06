# site/

The public website, published to GitHub Pages by `.github/workflows/pages.yml` on
every merge that touches this folder. Plain HTML, CSS and JavaScript: no build step,
no dependencies. Changes here don't rebuild or restart the Minecraft server.

| File | What |
|---|---|
| `config.js` | **Start here.** Server address, Minecraft version, Discord link, repository. |
| `index.html` | All the page content. |
| `style.css` | The look. The page is a slice of the world: sky, dirt, stone, deepslate, bedrock. |
| `app.js` | Fills in the address and the live changelog (merged and open PRs, from the GitHub API). |
| `assets/` | Pixel textures and the favicon (SVG). |

## Preview locally

Any static server works, for example:

```sh
cd site
python -m http.server 8000     # then open http://localhost:8000
```

Opening `index.html` straight from disk mostly works too, but some browsers block the
textures over `file://`.

## Notes

- The changelog calls the public GitHub API from the visitor's browser (60 requests per
  hour per visitor). Answers are kept in `localStorage` for 5 minutes; if GitHub fails
  later, the page shows the last copy it has and says how old it is. With no copy at all,
  it says GitHub is unreachable and links there.
- Text from GitHub is inserted with `textContent`, never as HTML. Keep it that way.
- Everything in English, like the rest of the repo.
