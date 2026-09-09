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

### First deployment and repository setup

Every push to `main` (including a merged pull request) builds the source snapshot, then
`peaceiris/actions-gh-pages@v4` commits the generated site to **`gh-pages`**. GitHub Pages
serves that branch. The branch contains generated files only; edit source on `main`.

1. Ensure GitHub Actions is enabled and permits `actions/*` and
   `peaceiris/actions-gh-pages@v4`. The publish job requests `contents: write` for the
   built-in `GITHUB_TOKEN`; no personal access token or custom secret is needed.
2. Push or merge the workflow and site to `main`. Wait for **Actions → Documentation**
   to finish publishing. This creates `gh-pages` if it does not exist yet.
3. In **Settings → Pages → Build and deployment**, set **Source** to
   **Deploy from a branch**, choose **gh-pages**, choose **/ (root)**, and click **Save**.
   If you previously selected **GitHub Actions**, change it to this branch-based source.
4. If the `github-pages` environment has deployment restrictions from the previous setup,
   allow the **gh-pages branch**. The Pages deployment runs from that branch; previous
   tag-based environment rules are no longer relevant.
5. Wait for GitHub's **pages build and deployment** run to finish, then visit
   https://bloxbean.github.io/kavach/ .

The publisher adds `.nojekyll` so GitHub serves Astro's generated assets directly.
See [GitHub's publishing-source instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)
and the [publishing action documentation](https://github.com/peaceiris/actions-gh-pages).

### Publish an update

Merge a pull request into `main`, or commit and push changes to `main`. No release tag is
required. Watch **Actions → Documentation**, followed by **pages build and deployment**.
The build uses that commit's source, including the committed white-paper PDF and figures.

### Trigger behavior

- **Every push to `main`:** build, validate and publish to `gh-pages`.
- **Pull request touching documentation:** build and validate only; no write permissions.
- **Push a tag (including `dv*` and `v*`):** no documentation deployment.
- There is no manual deployment trigger. Retry a failed run after fixing repository
  settings, or merge a fix if the source needs changes.

This publishes one current site, not separate versioned sites. Publishing jobs share a
concurrency lock. Tags may still mark milestones, but do not publish documentation or
change the white paper's document version or protocol schema versions. No custom domain
is configured.
