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

### One-time repository setup

1. In **Settings → Pages → Build and deployment**, select **GitHub Actions** as the source.
2. In **Settings → Environments → github-pages**, check **Deployment branches and tags**.
   If restricted, choose **Selected branches and tags** and add a **Tag** rule matching
   `v*`. A rule allowing only the `main` branch does not allow tag deployments. If the
   environment does not exist yet, create it with this name and rule.
3. Ensure GitHub Actions is enabled for the repository and the workflow's official
   `actions/*` actions are permitted. No personal access token or custom secret is needed;
   the deploy job uses GitHub's built-in token and OIDC permissions.

See GitHub's [Pages source configuration](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)
and [environment rules](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

### Publish an update

Commit and push the documentation changes to `main`, then tag that commit. For example,
use `v0.1.0` if that is the release you want to publish and the tag is not already in use:

```sh
git switch main
git pull --ff-only origin main
git tag -a v0.1.0 -m "Kavach v0.1.0"
git push origin v0.1.0
```

Open **Actions → Documentation** and watch the tag's build and deploy jobs. After a
successful deployment, visit https://bloxbean.github.io/kavach/ . Later updates use a new
`v*` tag; do not move an existing release tag. The tag selects the exact repository
snapshot to build, including the committed white-paper PDF and figures.

### Trigger behavior

- **Push a `v*` tag:** build, validate and deploy the tagged snapshot.
- **Pull request touching documentation:** build and validate only.
- **Push to `main`:** no documentation deployment.
- There is no manual deployment trigger. Retry a failed tag run from Actions after fixing
  repository settings, or publish a new tag if the source needs changes.

This deploys one current site, not separate versioned sites. Deployments share a concurrency
lock; wait for one release deployment to finish before pushing the next. A tag version does
not automatically change the white paper's document version or protocol schema versions.

JuLC uses a similar tag-driven process with `dv*` tags and branch-based publication. Kavach
uses `v*` tags and GitHub's Pages artifact/deployment actions, so select **GitHub Actions**
as its publishing source; no `gh-pages` branch or custom domain is required.
