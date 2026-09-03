# The website

`site/` is the documentation site: Astro, published to GitHub Pages, and a *consumer* of this
repository rather than a part of its build — Gradle does not know it exists. The authoritative
description is [`docs/design/website.md`](../docs/design/website.md): the generators and what feeds
them, the invariants, the gates and the traps. This file is the door.

## A fresh clone

```bash
# From the repository root: the three Gradle tasks that feed the Node generators.
./gradlew :limn-demo:exportThemeTokens aggregateJavadoc :limn-demo:captureGallery
cd site
pnpm install --frozen-lockfile
pnpm dev
```

Node 22 or newer and pnpm 11, as `package.json` declares. `captureGallery` needs a display and a
GL context: on a headless Linux machine run it under `xvfb-run -a` with `LIBGL_ALWAYS_SOFTWARE=1`;
on macOS the task adds `-XstartOnFirstThread` itself. `pnpm build` is `dev` with `astro build` at
the end, and is what the deploy workflow runs.

## In a worktree

The captures are gitignored and slow to render, so a second checkout need not render its own:
`ln -s /path/to/the/main/clone/site/captures captures`, and copy that clone's
`src/styles/tokens.generated.css` (or run `exportThemeTokens` again).

## There is no `pnpm check`

`astro check` prompts to install `@astrojs/check` and `typescript`, and neither is among the
dependencies on purpose, so the script that ran it is gone rather than made to work. Do not add
them. The verification is `pnpm exec astro build`: it compiles every page, expands every snippet
and shot, and runs the gates [`website.md`](../docs/design/website.md) lists.
