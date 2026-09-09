# Kavach website

Astro + Starlight documentation, with a custom landing page, local fonts, built-in search,
light/dark documentation themes and responsive layouts. This is a static public website;
it does not deploy the wallet dashboard, backend, account data or signing services.

## Develop

Use Node **22.19.0 or later** (the locked dependency tree requires it).

```sh
cd www
npm ci
npm run dev
```

Open http://127.0.0.1:4321/kavach/ .

```sh
npm run build
npm run preview
```

The build runs Astro type/content checks, creates the search index and checks local output
links and assets under the GitHub Pages project base.

## Edit content

- Landing page: `src/pages/index.astro` and `src/styles/home.css`.
- Curated guides: `src/content/docs/guides` and `src/content/docs/concepts`.
- Reference pages and white paper: **edit the canonical repository documents**, not the
  ignored generated pages. `scripts/sync-docs.mjs` explicitly lists the imported sources,
  rewrites relative links and copies the seven white-paper diagrams and PDF.
- Rebuild the white paper using `docs/whitepaper/BUILDING.md` after changing its source so
  its PDF and SVGs match. Commit the updated publication artifacts with that change.
- Navigation and deployment URL: `astro.config.mjs`.

Only the explicit documentation allowlist and public website assets enter the build.
No backend storage, wallet state or companion secrets are copied.

## GitHub Pages

Expected URL: https://bloxbean.github.io/kavach/ . The `/kavach` base is required for this
project site. If the repository is renamed or a custom domain is introduced, update the
Astro configuration, authored links, synchronization script and link checker together.

In repository **Settings → Pages → Build and deployment**, select **GitHub Actions**.
After the workflow is committed and pushed to `main`, `.github/workflows/docs.yml` builds
and deploys the site. Pull requests build and validate without deploying. The workflow
can also be run manually. Adding these files alone does not publish the website.
