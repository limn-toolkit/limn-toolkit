/**
 * The aggregate Javadoc becomes `/api/`.
 *
 * Gradle produces the tree (`./gradlew aggregateJavadoc`); this stages it under
 * `site/public/api/` and makes it belong to the site: themed and framed, never iframed:
 *
 *  - prepends the generated palette to the Javadoc stylesheet, so `/api/` restates no tone
 *    the toolkit already defines;
 *  - injects the SAME pre-paint theme script every other page uses, imported from
 *    `src/lib/theme-script.mjs` rather than copied, so `/api/` follows the reader's
 *    light/dark choice instead of being a full-brightness island at the end of every click
 *    through from `/docs/`;
 *  - puts the site's own top bar above Javadoc's, with the mark, the navigation and the
 *    theme control, so the API reference reads as part of the site;
 *  - runs the same privacy gate, from the same `public/consent.js` and the same strings.
 *
 * **The bar is injected, not wrapped.** An `/api/` page inside an iframe would keep the
 * frame's URL in the address bar: no deep link, no working back button, nothing to
 * bookmark, and a search engine indexing the inner document with no way back to the site.
 * Every page here is a real page, and the chrome is part of it.
 *
 * Beyond that, Javadoc's DOM is not ours to restructure, and this file is where that
 * boundary is enforced.
 *
 * Fails loudly rather than degrading to stale output: no Javadoc tree, no `/api/`. A
 * tree older than the newest `.java` under the module source roots is refused the same
 * way, because a stale tree is several hundred well-formed pages with entire packages
 * missing, and nothing downstream can tell.
 */
import { copyFile, cp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  NO_FLASH_SCRIPT,
  THEME_COLOR_SCRIPT,
  THEME_STORAGE_KEY,
} from "../src/lib/theme-script.mjs";
import {
  LOCALE_LINK_ATTR,
  localeContinuityScript,
} from "../src/lib/locale-continuity-script.mjs";
import {
  NAV_KEY_ATTR,
  NAV_LABEL_ATTR,
  navLanguageScript,
} from "../src/lib/nav-language-script.mjs";
import {
  PREFIXED_LOCALE_TAGS,
  SHARED_EN,
  SHARED_NAV_TRANSLATIONS,
  consentStrings,
} from "../src/i18n/shared.mjs";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const REPO_DIR = path.resolve(SITE_DIR, "..");

const JAVADOC_DIR =
  process.env.JAVADOC_DIR ?? path.join(REPO_DIR, "build/docs/aggregate-javadoc");
const PUBLIC_DIR = path.join(SITE_DIR, "public");
const OUT_DIR = path.join(PUBLIC_DIR, "api");
const BRAND_DIR = path.join(SITE_DIR, "src/brand");
const TOKENS = path.join(SITE_DIR, "src/styles/tokens.generated.css");
/** The bar's one stylesheet, shared with the marketing pages and with the guide. */
const BAR_CSS = path.join(SITE_DIR, "src/styles/bar.css");
const THEME_CSS = path.join(SITE_DIR, "src/styles/javadoc-theme.css");
const STYLESHEET_NAME = "limn-javadoc.css";
/**
 * The digest of the sources the staged tree was built from, kept beside the other generated
 * data. It is what lets the staleness check below tell a touched file from a changed one.
 */
const STALENESS = path.join(SITE_DIR, "src/generated/api-staleness.json");
const CHROME_SCRIPT_NAME = "limn-chrome.js";
/** Both variants of the delivered lockup, swapped by the same CSS the site's Logo uses. */
const LOCKUPS = ["limn-lockup-light.svg", "limn-lockup-dark.svg"];

async function main() {
  for (const [what, where] of [
    ["Javadoc tree", JAVADOC_DIR],
    ["generated palette", TOKENS],
  ]) {
    if (!existsSync(where)) {
      console.error(
        `build-api: no ${what} at ${where}\n` +
          "  run: ./gradlew aggregateJavadoc :limn-demo:exportThemeTokens",
      );
      process.exit(1);
    }
  }

  // A tree that exists can still predate the sources it documents: aggregateJavadoc runs by
  // hand and nothing invalidates its output, so without this a build happily republishes last
  // week's reference. Do not soften it into a warning: a stale `/api/` looks complete on every
  // page, so nobody would read the log.
  //
  // Mtime raises the alarm and a content digest decides it, because mtime alone deadlocks the
  // build. Gradle is content-addressed: a source that was touched and not changed leaves
  // aggregateJavadoc up to date, so the alarm fires, the remedy it prints regenerates nothing,
  // and the alarm cannot clear. When every source is byte-identical to the ones the staged tree
  // came from, that is a touch and the tree is current whatever the timestamps say.
  const newestSource = await newestFile(await moduleSourceRoots(), (f) => f.endsWith(".java"));
  if (!newestSource) {
    console.error(
      `build-api: no .java under any module's src/main/java in ${REPO_DIR}. ` +
        "Staleness cannot be checked; run this script from a full checkout",
    );
    process.exit(1);
  }
  const newestTree = await newestFile([JAVADOC_DIR], () => true);
  const digest = await sourceDigest(await moduleSourceRoots());
  const staged = existsSync(STALENESS)
    ? JSON.parse(await readFile(STALENESS, "utf8")).sources
    : null;
  if ((!newestTree || newestTree.mtimeMs < newestSource.mtimeMs) && digest !== staged) {
    const when = (entry) => new Date(entry.mtimeMs).toISOString();
    // Relative for the in-repo default, untouched for a JAVADOC_DIR override elsewhere:
    // `path.relative` across the two would print a ../.. chain nobody can paste anywhere.
    const shown = path.relative(REPO_DIR, JAVADOC_DIR);
    console.error(
      `build-api: stale Javadoc tree at ${shown.startsWith("..") ? JAVADOC_DIR : shown}` +
        `${newestTree ? ` (newest file ${when(newestTree)})` : " (empty)"}\n` +
        `  ${path.relative(REPO_DIR, newestSource.file)} is newer (${when(newestSource)})\n` +
        "  run: ./gradlew aggregateJavadoc\n" +
        "  (if that reports UP-TO-DATE, the sources changed and came back; regenerate with " +
        "--rerun-tasks)",
    );
    process.exit(1);
  }

  await rm(OUT_DIR, { recursive: true, force: true });
  await mkdir(OUT_DIR, { recursive: true });
  await cp(JAVADOC_DIR, OUT_DIR, { recursive: true });
  // Recorded only now, after the tree has actually been staged: a digest written before the copy
  // would vouch for a tree that a failure below left half-written.
  await mkdir(path.dirname(STALENESS), { recursive: true });
  await writeFile(STALENESS, `${JSON.stringify({ sources: digest }, null, 2)}\n`, "utf8");

  // Javadoc's own stylesheet imports resources/fonts/dejavu.css, a file the generator
  // never writes, so every page of the tree logged a 404 fetching it. Only an import
  // whose target is really absent is dropped: deleting the line unconditionally would
  // sever the fonts on a JDK that ships them, and writing an empty file to satisfy the
  // request would leave the tree claiming a stylesheet it does not have.
  const jdkStylesheet = path.join(OUT_DIR, "stylesheet.css");
  if (existsSync(jdkStylesheet)) {
    const css = await readFile(jdkStylesheet, "utf8");
    const repaired = css.replace(
      /^@import url\(['"]?([^'")]+)['"]?\);[^\S\n]*\n?/gm,
      (line, target) => (existsSync(path.join(OUT_DIR, target)) ? line : ""),
    );
    if (repaired !== css) await writeFile(jdkStylesheet, repaired, "utf8");
  }

  // Palette, then the shared bar, then the Javadoc overrides, in that order, because each
  // may read a custom property the one before it declares.
  await writeFile(
    path.join(OUT_DIR, STYLESHEET_NAME),
    `${await readFile(TOKENS, "utf8")}\n${await readFile(BAR_CSS, "utf8")}\n${await readFile(THEME_CSS, "utf8")}`,
    "utf8",
  );
  await writeFile(path.join(OUT_DIR, CHROME_SCRIPT_NAME), chromeScript(), "utf8");
  for (const lockup of LOCKUPS) {
    await copyFile(path.join(BRAND_DIR, lockup), path.join(OUT_DIR, lockup));
  }

  let patched = 0;
  for await (const file of htmlFiles(OUT_DIR)) {
    const html = await readFile(file, "utf8");
    if (!html.includes("<head>") || !html.includes("</head>")) continue;
    // The aggregate task pins -locale en; this is the loud failure if that ever regresses.
    // Without it, a build on a non-English machine ships hundreds of English pages whose
    // lang attribute names the machine's language, found as lang="pt" across all 404.
    const lang = html.match(/<html[^>]*\blang="([^"]*)"/)?.[1];
    if (lang && lang !== "en" && !lang.startsWith("en-")) {
      console.error(
        `build-api: ${path.relative(OUT_DIR, file)} declares lang="${lang}". Regenerate ` +
          "the Javadoc with an English locale (./gradlew aggregateJavadoc).",
      );
      process.exit(1);
    }
    // Relative, so the whole tree keeps working under any `base` and when opened
    // straight off disk. `toSite` is what turns a page four directories deep into a link
    // back to the site around it.
    const rel = (target) =>
      path.relative(path.dirname(file), target).split(path.sep).join("/") || ".";
    const href = rel(path.join(OUT_DIR, STYLESHEET_NAME));
    const toApi = rel(OUT_DIR);
    const toSite = rel(PUBLIC_DIR);

    // The script goes FIRST, because it must run before any paint. The stylesheet goes LAST:
    // it redefines the same `:root` custom properties the JDK's stylesheet declares, and
    // at equal specificity the later rule wins. Injected ahead of Javadoc's own link, the
    // whole theme silently does nothing.
    //
    // The two deferred scripts run in this order and in the document's order: the chrome
    // wires the theme control and hands over the prompt's text, then the gate reads it.
    const patchedHtml = html
      .replace(
        "<head>",
        `<head><script>${NO_FLASH_SCRIPT}</script><script>${THEME_COLOR_SCRIPT}</script>`,
      )
      .replace(
        "</head>",
        `<link rel="stylesheet" href="${href}">` +
          `<script defer src="${toApi}/${CHROME_SCRIPT_NAME}"></script>` +
          `<script defer src="${toSite}/consent.js"></script>` +
          "</head>",
      )
      // Inside `div.flex-box`, as its first child, and NOT after `<body>`.
      //
      // That box is the page's whole layout: `position: fixed`, `height: 100%`, a column
      // whose last child is the one element that scrolls. A bar placed before it leaves the
      // box at its static position (pushed down by the bar's height) while the box keeps
      // asking for the full viewport, so its scrolling child now ends below the bottom of
      // the screen and the last band of every page cannot be reached. Injected inside it,
      // the JDK's own flex column measures the bar like it measures its own header, and the
      // scroll container gets exactly what is left.
      .replace(/<div class="flex-box">/i, (match) => `${match}${bar(toApi, toSite)}`);
    // A redirect stub carries no layout and is gone before anything paints: `<body>` is a
    // link to the page it forwards to. It is the one page with no box to put the bar in,
    // and the guard below is what tells us if a page that should have one stops having it.
    const redirect = html.includes('name="generator" content="javadoc/IndexRedirectWriter"');
    if (!redirect && !patchedHtml.includes("limn-bar")) {
      console.error(
        `build-api: ${path.relative(OUT_DIR, file)} has no div.flex-box to put the bar in. ` +
          "The Javadoc layout changed, and the bar has to move with it",
      );
      process.exit(1);
    }
    await writeFile(file, patchedHtml, "utf8");
    patched += 1;
  }

  if (patched === 0) {
    console.error("build-api: no Javadoc page had a <head> to patch; check the tree");
    process.exit(1);
  }
  console.log(`build-api: ${patched} page(s) → ${path.relative(REPO_DIR, OUT_DIR)}`);
}

/**
 * The site's bar, as static markup for one page.
 *
 * The classes are `src/styles/bar.css`'s, and the structure has to match what that file
 * expects (the disclosure, its summary and its panel), because the marketing pages build
 * the same three elements from `SiteHeader.astro`. Below the breakpoint the panel becomes
 * the dropdown; there is no script in that, and `/api/` needs none.
 *
 * English, because the Javadoc is: the guide and the API reference are published in one
 * language, so this bar carries no language picker: it could not honour a choice made here
 * without throwing the reader out of the page they are reading.
 *
 * What it does carry is the way back out in the reader's language. The site-bound links
 * (the brand, Components, Showcase) are marked with the locale-continuity attribute, and
 * the chrome script rewrites them to the language stored by the site's picker. The Docs
 * link stays unmarked because the guide has no localized counterpart to send anyone to,
 * and the API link is this page.
 *
 * @param toApi relative path from this page to the root of the API tree
 * @param toSite relative path from this page to the root of the site
 */
function bar(toApi, toSite) {
  const label = (key) => escapeHtml(SHARED_EN[key]);
  // `localePath` is the link's path below the locale segment, the value the continuity
  // script matches against the href's tail, so the two must name the same place.
  // `NAV_KEY_ATTR` is what lets the script below put the word back into the reader's
  // language; the page under it stays English, which is the whole of the boundary.
  const link = (href, key, { current, localePath } = {}) =>
    `<li><a href="${href}"` +
    (current ? ' aria-current="page"' : "") +
    (localePath === undefined ? "" : ` ${LOCALE_LINK_ATTR}="${localePath}"`) +
    ` ${NAV_KEY_ATTR}="${key}"` +
    `>${label(key)}</a></li>`;

  // The delivered lockup, both variants in the markup and one hidden per theme: the same
  // arrangement as the site's Logo, and for the same reason: a script that chose after
  // hydration would show dark ink on a dark bar for a frame.
  const lockup = (variant) =>
    `<img class="limn-bar__logo limn-bar__logo--${variant}" src="${toApi}/limn-lockup-${variant}.svg"` +
    ' alt="" width="85" height="26">';

  const themeOption = (value, key) =>
    `<label class="theme-toggle__option"><input type="radio" name="limn-theme" value="${value}">` +
    `<span>${label(key)}</span></label>`;

  // `data-site` is what the chrome script turns into the privacy page's URL: the prompt's
  // text is shared by every page of the tree, but the path back to the site is not.
  return (
    `<header class="limn-bar" data-site="${toSite}">` +
    '<div class="limn-bar__inner limn-bar__inner--flush">' +
    `<a class="limn-bar__brand" href="${toSite}/" ${LOCALE_LINK_ATTR}="" aria-label="${label("site.name")}">` +
    lockup("light") +
    lockup("dark") +
    "</a>" +
    '<div class="limn-bar__menu" data-bar-menu>' +
    `<button class="limn-bar__toggle" type="button" aria-label="${label("nav.menu")}"` +
    ` ${NAV_LABEL_ATTR}="nav.menu"` +
    ' aria-expanded="false" aria-controls="limn-bar-panel" hidden>' +
    '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" ' +
    'stroke="currentColor" stroke-width="1.8" stroke-linecap="round">' +
    '<path d="M4 7h16M4 12h16M4 17h16"></path></svg>' +
    "</button>" +
    '<div class="limn-bar__panel" id="limn-bar-panel">' +
    `<nav class="limn-bar__nav" aria-label="${label("nav.primaryLabel")}"` +
    ` ${NAV_LABEL_ATTR}="nav.primaryLabel"><ul>` +
    link(`${toSite}/components/`, "nav.components", { localePath: "components/" }) +
    link(`${toSite}/showcase/`, "nav.showcase", { localePath: "showcase/" }) +
    link(`${toSite}/docs/`, "nav.docs") +
    link(`${toApi}/index.html`, "nav.api", { current: true }) +
    "</ul></nav>" +
    '<div class="limn-bar__tools">' +
    '<fieldset class="theme-toggle" data-theme-toggle>' +
    `<legend class="theme-toggle__legend">${label("theme.label")}</legend>` +
    themeOption("system", "theme.system") +
    themeOption("light", "theme.light") +
    themeOption("dark", "theme.dark") +
    "</fieldset>" +
    "</div>" +
    "</div>" +
    "</div>" +
    "</div>" +
    "</header>"
  );
}

/**
 * The one script the bar needs: the theme control, the menu's dismissal, the privacy
 * prompt's text, and the locale continuity for the bar's site-bound links.
 *
 * Written once into the tree rather than inlined into several hundred pages, and it carries
 * no strings of its own. Every word comes from `src/i18n/shared.mjs`, which is also where
 * the site's own catalog takes them from. The continuity script is likewise not written
 * here: it comes from `src/lib/locale-continuity-script.mjs`, the same builder the site's
 * English-only pages embed, so `/api/` cannot drift from how the rest of the site carries
 * a reader's language.
 *
 * Everything here is an enhancement: the bar's menu is a `<details>` and opens without a
 * script, the theme falls back to the pre-paint script's reading of the same key, and the
 * page is complete with none of it running.
 */
function chromeScript() {
  const strings = consentStrings((key) => SHARED_EN[key], "");
  return `/* Generated by site/scripts/build-api.mjs. Edit that, not this. */
(function () {
  "use strict";
  var KEY = ${JSON.stringify(THEME_STORAGE_KEY)};
  var strings = ${JSON.stringify(strings)};
  var bar = document.querySelector(".limn-bar");
  // Resolved per page: the prompt is shared, the way back to the site is not.
  strings.privacyHref = (bar ? bar.getAttribute("data-site") : ".") + "/privacy/";
  window.limnConsentStrings = strings;

  function stored() {
    try {
      var raw = localStorage.getItem(KEY);
      return raw === "light" || raw === "dark" ? raw : "system";
    } catch (error) {
      return "system";
    }
  }

  function apply(choice) {
    var dark =
      choice === "dark" ||
      (choice === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
  }

  var group = document.querySelector("[data-theme-toggle]");
  if (group) {
    var inputs = group.querySelectorAll('input[name="limn-theme"]');
    for (var i = 0; i < inputs.length; i++) {
      inputs[i].checked = inputs[i].value === stored();
      inputs[i].addEventListener("change", function (event) {
        if (!event.target.checked) return;
        var next = event.target.value;
        try {
          // "" is Starlight's spelling of "follow the system", and this key is Starlight's.
          localStorage.setItem(KEY, next === "system" ? "" : next);
        } catch (error) {
          // Storage blocked: the change still applies to this page.
        }
        apply(next);
      });
    }
  }

  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
    if (stored() === "system") apply("system");
  });

  // The bar's menu, behaving exactly as the site's own does: the markup ships open and
  // uncollapsed, and this is what opts it into collapsing below the breakpoint.
  var menu = document.querySelector("[data-bar-menu]");
  var toggle = menu && menu.querySelector(".limn-bar__toggle");
  if (menu && toggle) {
    menu.setAttribute("data-collapsible", "");
    toggle.hidden = false;

    var setOpen = function (open) {
      if (open) menu.setAttribute("data-open", "");
      else menu.removeAttribute("data-open");
      toggle.setAttribute("aria-expanded", String(open));
    };

    toggle.addEventListener("click", function () {
      setOpen(!menu.hasAttribute("data-open"));
    });

    document.addEventListener("click", function (event) {
      if (!menu.hasAttribute("data-open")) return;
      if (menu.contains(event.target)) return;
      setOpen(false);
    });

    menu.addEventListener("keydown", function (event) {
      if (event.key !== "Escape" || !menu.hasAttribute("data-open")) return;
      setOpen(false);
      toggle.focus();
    });
  }
})();
/* Locale continuity: the bar's marked links leave in the reader's stored language. */
${localeContinuityScript(PREFIXED_LOCALE_TAGS)};
/* And the bar's own words arrive in it. The reference below stays English. */
${navLanguageScript(SHARED_NAV_TRANSLATIONS, ".limn-bar__inner")};
`;
}

function escapeHtml(value) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

async function* filesUnder(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* filesUnder(full);
    else yield full;
  }
}

async function* htmlFiles(dir) {
  for await (const file of filesUnder(dir)) {
    if (file.endsWith(".html")) yield file;
  }
}

/**
 * Every module's `src/main/java`, discovered by scanning the repository's top level
 * rather than kept as a list here, because a list would go quietly wrong the day a module is
 * added or renamed, which is exactly the day the staleness check matters most.
 */
async function moduleSourceRoots() {
  const roots = [];
  for (const entry of await readdir(REPO_DIR, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const root = path.join(REPO_DIR, entry.name, "src", "main", "java");
    if (existsSync(root)) roots.push(root);
  }
  return roots;
}

/** Newest file under any of `dirs` that `wanted` accepts, as `{ file, mtimeMs }`, or null. */
/**
 * One digest over every `.java` under the module source roots, path and content both, so a file
 * that moves counts as a change. Sorted, because a directory listing's order is the filesystem's
 * business and a digest that depends on it would differ between two identical checkouts.
 */
async function sourceDigest(roots) {
  const files = [];
  for (const root of roots) {
    for await (const file of filesUnder(root)) {
      if (file.endsWith(".java")) files.push(file);
    }
  }
  files.sort();
  const hash = createHash("sha256");
  for (const file of files) {
    hash.update(path.relative(REPO_DIR, file));
    hash.update(await readFile(file));
  }
  return hash.digest("hex");
}

async function newestFile(dirs, wanted) {
  let newest = null;
  for (const dir of dirs) {
    for await (const file of filesUnder(dir)) {
      if (!wanted(file)) continue;
      const { mtimeMs } = await stat(file);
      if (!newest || mtimeMs > newest.mtimeMs) newest = { file, mtimeMs };
    }
  }
  return newest;
}

await main();
