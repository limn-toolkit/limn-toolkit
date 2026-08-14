/**
 * Builds `/docs/`, the guide, out of `src/guides/`.
 *
 * The guide is written for someone building an application with Limn, so it is authored
 * here rather than mirrored out of the repository: `docs/` in the repository is written for
 * people changing the toolkit, and the two audiences want opposite things from the same
 * subject. Nothing in this directory is a copy of a repository document.
 *
 * What this step adds beyond copying:
 *
 *  - **Code comes from compiled sources.** A page writes `{% snippet guide:form %}` and gets
 *    the text of that `// #region` marker out of a real Java file, by the same mechanism the
 *    component gallery uses. A page that names a region no source carries **fails the
 *    build**, so a renamed method is caught here rather than shipped as an empty block.
 *  - **Screenshots come from the capture run.** `{% shot form %}` expands to the picture the
 *    toolkit rendered of that example, in both palettes, swapped with the theme.
 *
 * Run: `pnpm sync:docs`, and after `pnpm build:gallery`, which is what writes the snippet
 * and screenshot manifests this reads.
 */
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const GUIDE_DIR = path.join(SITE_DIR, "src/guides");
const COLLECTION_DIR = path.join(SITE_DIR, "src/content/docs");
// Starlight's route for an entry IS its path inside the collection, so the extra `docs`
// segment is what mounts the guide at /docs/ and leaves / to the home page.
const OUT_DIR = path.join(COLLECTION_DIR, "docs");
const SNIPPETS = path.join(SITE_DIR, "src/generated/snippets.json");
const SHOWCASE = path.join(SITE_DIR, "src/generated/showcase.json");

async function main() {
  // The whole collection, not just this generator's subtree: anything left behind in it
  // becomes a live Starlight route, and a leftover set from an earlier layout publishes
  // every page twice, at two URLs, each claiming to be canonical.
  await rm(COLLECTION_DIR, { recursive: true, force: true });
  await mkdir(OUT_DIR, { recursive: true });

  const snippets = await readJson(SNIPPETS, "snippet manifest");
  const shots = new Map(
    (await readJson(SHOWCASE, "showcase manifest")).entries.map((entry) => [entry.id, entry]),
  );

  const files = (await readdir(GUIDE_DIR)).filter((name) => name.endsWith(".md")).sort();
  if (files.length === 0) {
    fail(`no guide pages in ${path.relative(SITE_DIR, GUIDE_DIR)}`);
  }

  const problems = [];
  for (const name of files) {
    const source = await readFile(path.join(GUIDE_DIR, name), "utf8");
    const route = name === "index.md" ? "index" : name.replace(/\.md$/, "");
    const body = expand(source, name, snippets, shots, problems);
    await writeFile(path.join(OUT_DIR, `${route}.md`), body, "utf8");
  }

  if (problems.length > 0) {
    console.error(`\nsync-docs: ${problems.length} problem(s)\n`);
    for (const problem of problems) console.error(`  ${problem}`);
    process.exit(1);
  }
  console.log(`sync-docs: ${files.length} guide page(s) → ${path.relative(SITE_DIR, OUT_DIR)}`);
}

async function readJson(file, what) {
  if (!existsSync(file)) {
    fail(`no ${what} at ${file}\n  run: pnpm build:gallery`);
  }
  return JSON.parse(await readFile(file, "utf8"));
}

/**
 * Expands the two markers. Both are line-level: a marker inside a code fence is left alone,
 * because a page that documents the marker syntax has to be able to show it.
 */
function expand(source, file, snippets, shots, problems) {
  let inFence = false;
  return source
    .split("\n")
    .map((line) => {
      if (/^\s*```/.test(line)) {
        inFence = !inFence;
        return line;
      }
      if (inFence) return line;

      const snippet = line.match(/^\s*\{%\s*snippet\s+(\S+)\s*%\}\s*$/);
      if (snippet) {
        const text = snippets[snippet[1]];
        if (text === undefined) {
          problems.push(
            `${file}: no region '${snippet[1]}' in any source file. Regions are marked ` +
              "with `// #region <name>` … `// #endregion`",
          );
          return line;
        }
        return "```java\n" + text + "\n```";
      }

      const shot = line.match(/^\s*\{%\s*shot\s+(\S+)\s+"([^"]*)"\s*%\}\s*$/);
      if (shot) {
        const entry = shots.get(shot[1]);
        if (!entry) {
          problems.push(`${file}: no showcase capture called '${shot[1]}'`);
          return line;
        }
        return figure(entry, shot[2]);
      }
      return rebase(line);
    })
    .join("\n");
}

/**
 * The same `base` rule as Astro's own BASE_URL: SITE_BASE with a trailing slash guaranteed.
 * Hardcoding a leading `/` into anything this script emits is the edit that breaks the page
 * the day the site deploys under a project path.
 */
function siteBase() {
  const root = process.env.SITE_BASE ?? "/";
  return root.endsWith("/") ? root : `${root}/`;
}

/**
 * Prefixes a guide's root-absolute markdown links with the site's base.
 *
 * A guide writes `[Layout](/docs/layout/)`, the site's own path, which is what makes the
 * link readable in the source and correct under `astro dev`. Astro rewrites no link inside
 * markdown content, so under a project path every one of them is a 404: the reader lands on
 * `/docs/layout/` while the site lives at `/limn-toolkit/docs/layout/`. Root-served builds
 * cannot show this, so it survives any amount of local verification.
 *
 * Protocol-relative targets (`//host/path`) are left alone: they are already absolute
 * against a host, and prefixing one points it at a path on this site instead.
 */
function rebase(line) {
  return line.replace(/\]\(\/(?!\/)/g, `](${siteBase()}`);
}

/**
 * Both palettes go into the HTML and one is hidden per theme, never a script that picks
 * after hydration, which shows a dark screenshot on a white page for a frame. The classes
 * are the same ones the marketing pages' Screenshot component uses, and `starlight.css`
 * carries the one set of rules that swaps them.
 */
function figure(entry, caption) {
  const base = siteBase();
  const picture = (theme) => {
    const image = entry.images[theme];
    // AVIF source + WebP img, the same pair Screenshot.astro publishes. The gallery
    // emits no PNG, so an `<img src>` naming one points at a file that does not exist.
    return (
      `<picture class="shot__${theme}">` +
      `<source srcset="${base}gallery/${image.avif}" type="image/avif">` +
      `<img src="${base}gallery/${image.webp}" width="${image.width}" height="${image.height}" ` +
      `loading="lazy" decoding="async" alt="${escapeAttr(caption)}">` +
      "</picture>"
    );
  };
  return (
    '<figure class="guide-shot">' +
    '<div class="shot">' +
    picture("dark") +
    picture("light") +
    "</div>" +
    `<figcaption>${escapeText(caption)}</figcaption>` +
    "</figure>"
  );
}

function escapeAttr(text) {
  return text.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
}

function escapeText(text) {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;");
}

function fail(message) {
  console.error(`sync-docs: ${message}`);
  process.exit(1);
}

await main();
