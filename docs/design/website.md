# The website

Background for `site/`: a static site built from this repository's own sources, published to GitHub
Pages. The API reference is the Javadoc, every component picture is the demo rendering itself, and
every code sample is a `// #region` marker in a source the build compiles, so a renamed method
fails the site build rather than shipping as prose that no longer runs.

The guide under `/docs/` is the one thing authored in `site/`, and deliberately: `docs/` in this
repository is written for people changing the toolkit, `site/src/guides/` for people building an
application with it, and the two audiences want opposite things from the same subject. Nothing under
`site/src/guides/` is a copy of a repository document.

**Gradle does not know `site/` exists.** It is not in `settings.gradle.kts`, no task depends on it,
and nothing here is wired into `check`. The site is a *consumer* of this repository, so a change
that breaks it cannot break the toolkit's own gate.

## Building it

```bash
./gradlew :limn-demo:exportThemeTokens aggregateJavadoc :limn-demo:captureGallery
cd site && pnpm install && pnpm build
```

Three Gradle tasks feed the Node generators that `pnpm generate` runs, and `pnpm build` runs
`generate` first:

| Produces | From | Generator |
| --- | --- | --- |
| `src/styles/tokens.generated.css` | `Theme.limn()` / `Theme.limnLight()` | `:limn-demo:exportThemeTokens` |
| `public/api/` | one aggregate Javadoc over every module | `build-api.mjs` |
| `captures/` → `public/gallery/` | `limn.demo.site.GalleryScenes` | `:limn-demo:captureGallery` → `build-gallery.mjs` |
| `src/content/docs/` | `src/guides/`, with snippets and shots expanded | `sync-docs.mjs` |
| `public/icon-*.png` | the delivered artwork in `src/brand/` | `build-icons.mjs` |

Every one of those outputs is gitignored. Nothing in them can be recovered from git, only
regenerated. `captureGallery` needs a display and a GL context (`xvfb-run -a` with
`LIBGL_ALWAYS_SOFTWARE=1` on a headless Linux machine, `-XstartOnFirstThread` on macOS) and cannot
run in parallel with another capture.

`site/captures/` and `site/public/gallery/` are both generated and are **not** interchangeable: the
captures are the pristine input and the only copy the derivation may read.

## Invariants

- **No hostname appears anywhere.** `SITE_URL` and `SITE_BASE` come from `actions/configure-pages`
  at build time and default to localhost otherwise, so a project path and a custom domain both work
  and moving the site is a Pages setting rather than an edit. A CI build without `SITE_URL` fails
  fast, because the sitemap is generated from it and would otherwise bake in `localhost:4321`.
- **English lives at `/`, other locales under a prefix.** GitHub Pages has no server-side redirect,
  so an `/en/` root would make the bare URL a client-side bounce page, a visible flash on the one
  page that has ten seconds to work. Nothing may reintroduce that, including a locale-continuity fix.
- **Every string is an i18n key from the first line.** `t()` falls back to English and *throws* when
  a key is missing from English, so a raw key cannot reach a page (the same guarantee `I18nString`
  makes in the toolkit).
- **Adding a language is adding a catalog.** The published set, the picker, `hreflang` and the
  coverage table all derive from the map in `src/i18n/index.ts`. No page carries a list of locales,
  and a locale with no catalog is not routed at all; a URL that promises a language and serves
  English is worse than no URL.
- **The palettes are generated, never transcribed.** A hex copied by hand into CSS is a fact either
  side can change without the other noticing, and the drift shows up as screenshots slowly
  disagreeing with the page around them.
- **One definition of the theme contract.** `src/lib/theme-script.mjs` holds the storage key and the
  pre-paint script; `theme.ts` re-exports it for Astro and `build-api.mjs` imports it directly, so
  the Javadoc pages and the site cannot drift. It adopts Starlight's key *and* its vocabulary (`''`
  for auto): two scripts stamping `data-theme` from two keys cannot be kept in sync.
- **Astro's built-in `i18n` block is deliberately unused.** With it on, Starlight reads the first
  path segment as a locale and fans the English-only guide across every prefix, 681 pages where
  there should be 36. Marketing locale routing comes from `src/lib/i18n.ts` instead.
- **No code sample is typed into a page.** A guide writes `{% snippet guide:form %}` and gets the
  text of that `// #region` out of a real Java file; `{% shot form %}` expands to the picture the
  toolkit rendered of that same example. The sample and the screenshot are one program.
- **`sync-docs.mjs` clears the whole collection directory**, not just its own subtree. A leftover
  set from an earlier layout stays live as a second route set, and every page then publishes twice,
  at two URLs, each claiming to be canonical.

## Gates, and what each one caught

Each of these exists because the failure it catches was silent, and a green build reported it as
success.

- **The derivation is idempotent, asserted.** `build-gallery.mjs` once read and wrote the same path;
  with a fixed crop box that meant every build re-cropped the already-cropped file, and seven
  component cards rendered blank while seven more were silently mis-cropped. Captures now stage
  outside `public/` and a second derivation over unchanged captures must be byte-identical.
- **A stale Javadoc tree is refused**, the same way a missing one is. It once published a reference
  with `limn.components.chart` absent while a guide page taught `BarChart`: the check verified the
  tree *existed*, which is the one failure staleness is not.
- **`check-links.mjs` resolves every internal `href`, `src`, `srcset` and `data-src` in `dist/`
  against the files actually emitted.** Astro emits whatever href a component hands it: the home
  page's link into the theming guide was a 404 on nine of the ten locale pages (built with
  `localePath`, which prefixes a locale onto a guide that has none), and the build was perfectly
  happy, because the one page anybody had opened was the one where it worked. `data-src` is in the
  list because a film's URL is invisible until someone presses play.
- **A missing snippet region fails the build.** A page or a gallery entry naming a region no source
  carries exits 1 and names both, so a renamed method is caught here.
- **A promised image that was not rendered fails the build.** One shot went missing from an otherwise
  green run and was caught only by counting files.
- **The animation budget is asserted after the re-encode**, not before it (`ANIMATION_BUDGET` 400 KB,
  `SHOWCASE_ANIMATION_BUDGET` 1800 KB). A film still over budget at the lowest quality the encoder
  is willing to try fails the build rather than publishing the overrun.
- **`build-api.mjs` fails if a page declares the wrong `lang`.** The javadoc tool inherits the build
  machine's locale into `<html lang>`, which is why `aggregateJavadoc` pins `locale = "en"`.

## Traps

**In the capture harness** (`limn.demo.site.Gallery`, `GalleryScenes`):

- `Scene.bind` installs its own frame callback, replacing the driver's; the window then renders
  forever, captures nothing and never closes. Re-install after every bind, and keep the watchdog.
- `Scene`'s default background is a hard-coded tone of the generic Dark palette, not the current
  theme's. Without `setBackground(Theme.current().background)` every light capture is a dark canvas
  with light-palette ink on it.
- **Never resize the window between shots.** The resize lands asynchronously, so a scene built too
  early lays out at the previous entry's size and the capture returns the shot before it. Every
  entry is captured on one fixed canvas and trimmed afterwards, which removes the race rather than
  timing it.
- `Theme.setCurrent` and `I18n.setLocale` are process-wide. Two capture drivers in one event loop
  means one sets the palette while the other paints. One driver, one list.
- The frame loop renders as fast as it can, so wall-clock time barely advances and warmup frames do
  not settle an animation; gallery scenes step `Scene`'s injectable clock a fixed 20 ms per read.
  The performance footer is the opposite case: it latches on a once-per-second wall-clock heartbeat,
  so raising `WARMUP_FRAMES` captures the dashes no matter how high it goes.

**In the Javadoc theming** (`build-api.mjs`, `javadoc-theme.css`):

- Override Javadoc's own `:root` custom properties and nothing else. Overriding selectors produces
  dark ink on light panels, because the JDK sets backgrounds on containers those selectors do not
  reach. A future JDK renaming a variable then costs one off-palette panel rather than an unreadable
  page.
- **Injection order is load-bearing and getting it wrong is silent.** The theme script goes first in
  `<head>` (it must run before any paint) and the stylesheet goes *last*, because it redefines the
  same properties the JDK declares and at equal specificity the later rule wins. Injected ahead of
  Javadoc's own link, the whole theme applies and changes nothing.

**In the pages:**

- Root `overflow-x: clip` does not contain an oversized decoration: Chromium keeps the scrollable
  overflow area for a root clip. Make the element not overflow instead. And `window.scrollTo`
  succeeds under `overflow: hidden`, so a programmatic scroll is never evidence that the reader can
  scroll: measure `scrollWidth` against the viewport.
- A grid item's automatic minimum is its content's min-content size, so a wide code sample inside a
  card grows the card. `min-width: 0` at every link of the chain, plus `align-items: start` so one
  card's expansion stops deforming its row-mates.
- Tailwind's preflight sets `font-weight: inherit`, which silently unbolds every heading.
- Starlight also defines `/404`; `disable404Route` turns theirs off rather than shadowing it.

## Keeping the pages true to the toolkit

`site.config.json` records the commit the site's *content* was last read against, distinct from the
build commit in the footer, which moves on every build. `pnpm since:review` prints the toolkit
commits the pages have not been told about yet. Read them, update the pages that need it, then move
`contentReviewedAt` and record what was covered, **including what was read and needed no change**,
which is what makes the next review cheap.
