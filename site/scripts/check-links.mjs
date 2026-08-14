/**
 * Every internal link and asset reference in `dist/`, resolved against the files actually emitted.
 *
 * This exists because nothing else catches a broken internal link. The home page's link into the
 * theming guide was a 404 on nine of the ten locale pages (built with `localePath`, which prefixes
 * a locale onto a guide that has none), and the build was perfectly happy: Astro emits whatever
 * href a component hands it, and the one page anybody had opened was the one where it worked.
 *
 * What it checks, and why each is here rather than assumed:
 *
 *  - `href` on links, so a route that moved or never existed fails the build;
 *  - `src` and `srcset` on images and sources, because a guide can name a capture by hand;
 *  - `data-src`, which is where a film's URL waits until a reader presses play. Without this the
 *    one reference that is invisible until clicked is also the one nothing verifies.
 *
 * What it does NOT check: fragments (`#anchor`) against the ids on the target page, and anything
 * off this site. The first is worth adding the day a heading rename breaks a deep link; the second
 * is a network call and a flaky gate.
 *
 * Run: `pnpm check:links` after a build, and it runs in the PR gate. Reads `SITE_BASE` the same way
 * the build does, so a base-path build is checked with its prefix stripped rather than reported as
 * one broken link per href.
 */
import { readFile, readdir, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const DIST = path.join(SITE_DIR, "dist");
const BASE = (() => {
  const raw = process.env.SITE_BASE ?? "/";
  return raw.endsWith("/") ? raw : `${raw}/`;
})();

/** Schemes and shapes that are somebody else's problem. */
const EXTERNAL = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i;

async function main() {
  if (!existsSync(DIST)) {
    fail(`no build at ${path.relative(SITE_DIR, DIST)}\n  run: pnpm build`);
  }
  const pages = [];
  for await (const file of filesUnder(DIST)) {
    if (file.endsWith(".html")) pages.push(file);
  }
  if (pages.length === 0) {
    fail("the build produced no HTML, so refusing to report zero broken links from nothing");
  }

  // Deduplicated by target: the Javadoc tree alone carries tens of thousands of references, most
  // of them to the same few hundred pages, and a report that lists each occurrence is a report
  // nobody reads to the end. One example source per broken target is enough to find it.
  const broken = new Map();
  let checked = 0;
  for (const page of pages) {
    const html = await readFile(page, "utf8");
    for (const reference of references(html)) {
      const target = resolve(reference, page);
      if (target === null) continue;
      checked += 1;
      if (typeof target === "object") {
        const key = `outside-base:${target.outsideBase}`;
        if (!broken.has(key)) {
          broken.set(key, {
            reference,
            page: path.relative(DIST, page),
            wanted: `a path under ${BASE}; this one starts outside it`,
          });
        }
        continue;
      }
      if (!(await exists(target))) {
        if (!broken.has(target)) {
          broken.set(target, {
            reference,
            page: path.relative(DIST, page),
            wanted: path.relative(DIST, target),
          });
        }
      }
    }
  }

  if (broken.size > 0) {
    console.error(
      `check-links: ${broken.size} broken target(s) across ${pages.length} page(s)\n`,
    );
    for (const { reference, page, wanted } of broken.values()) {
      console.error(`  ${reference}`);
      console.error(`    wanted ${wanted}`);
      console.error(`    from   ${page}\n`);
    }
    process.exit(1);
  }
  console.log(
    `check-links: ${checked} internal reference(s) across ${pages.length} page(s), none broken`,
  );
}

/** Every internal reference in one page, as written. */
function* references(html) {
  const attributes = /(?:href|src|data-src)="([^"]*)"/g;
  for (const [, value] of html.matchAll(attributes)) {
    yield value;
  }
  // `srcset` holds several candidates with a density or width descriptor each.
  const srcsets = /srcset="([^"]*)"/g;
  for (const [, value] of html.matchAll(srcsets)) {
    for (const candidate of value.split(",")) {
      const url = candidate.trim().split(/\s+/)[0];
      if (url) yield url;
    }
  }
}

/**
 * The file a reference points at, or null when it is not this site's to answer for.
 *
 * A directory URL resolves to its `index.html`, which is what `trailingSlash: "always"` means on
 * disk. Checking the directory itself would pass for a directory that holds no page.
 */
function resolve(reference, page) {
  if (!reference || reference.startsWith("#") || EXTERNAL.test(reference)) return null;
  const [withoutFragment] = reference.split("#");
  const [pathname] = withoutFragment.split("?");
  if (!pathname) return null;

  let target;
  if (pathname.startsWith("/")) {
    // Absolute against the site root, so the configured base is a prefix and not a directory.
    if (BASE !== "/" && !pathname.startsWith(BASE)) {
      // Root-absolute and outside the configured base: on a project path that link leaves the
      // site. Reported by its own name rather than as a missing file, because the fix is the
      // href or the base, not a page somebody forgot to write.
      return { outsideBase: pathname };
    }
    target = path.join(DIST, decodeURIComponent(pathname.slice(BASE.length - 1)));
  } else {
    target = path.resolve(path.dirname(page), decodeURIComponent(pathname));
  }
  return pathname.endsWith("/") ? path.join(target, "index.html") : target;
}

async function exists(target) {
  if (!existsSync(target)) return false;
  const info = await stat(target);
  // A bare path that happens to be a directory still needs a page in it.
  return info.isDirectory() ? existsSync(path.join(target, "index.html")) : true;
}

async function* filesUnder(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* filesUnder(full);
    } else if (entry.isFile()) {
      yield full;
    }
  }
}

function fail(message) {
  console.error(`check-links: ${message}`);
  process.exit(1);
}

await main();
