/**
 * The `/api/` half of the sitemap.
 *
 * The API reference is a static tree under `public/api/`, staged there by
 * `build-api.mjs` and never routed by Astro, so `@astrojs/sitemap` cannot discover it in
 * the build. `astro.config.ts` calls this and hands the result to the integration as
 * `customPages`.
 *
 * Enumeration happens when the Astro config loads, which in the pipeline
 * (`pnpm build` runs the generators, then `astro build`) is after `build-api.mjs` has
 * rebuilt the tree: what is listed is exactly what is about to be published. Do not move
 * this to an earlier step "to cache it": enumerating before the tree is regenerated is
 * how a sitemap ends up describing the previous deploy.
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const API_DIR = path.resolve(fileURLToPath(new URL("../public/api", import.meta.url)));

// Javadoc writes forwarding stubs (its IndexRedirectWriter) alongside real pages. A
// sitemap entry that answers with a redirect is its own Search Console complaint, so the
// stubs stay out; "just list every .html" would put them back.
const REDIRECT_MARKER = 'content="javadoc/IndexRedirectWriter"';

/**
 * Absolute URLs for every published API page, sorted so consecutive builds emit the
 * sitemap identically.
 *
 * Returns `[]` when the tree has not been generated: tolerable under `astro dev`, wrong
 * for a deploy. The caller decides which it is; `astro.config.ts` refuses the CI case,
 * following its own SITE_URL rule.
 *
 * @param {string} site the deployment origin (`SITE_URL`, or the localhost fallback)
 * @param {string} base the path the site is served under (`SITE_BASE`)
 * @returns {string[]}
 */
export function apiSitemapUrls(site, base) {
  if (!existsSync(API_DIR)) return [];
  const urls = [];
  for (const file of htmlFiles(API_DIR)) {
    if (readFileSync(file, "utf8").includes(REDIRECT_MARKER)) continue;
    const rel = path.relative(API_DIR, file).split(path.sep).join("/");
    urls.push(new URL(path.posix.join(base, "api", rel), site).href);
  }
  return urls.sort();
}

function* htmlFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* htmlFiles(full);
    else if (entry.name.endsWith(".html")) yield full;
  }
}
