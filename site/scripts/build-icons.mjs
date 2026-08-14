/**
 * The icon set, taken from the delivered artwork in `src/brand/`.
 *
 * It copies rather than rasterises. The mark was exported at nine sizes by whoever drew it,
 * and a rasteriser re-deriving them from the SVG would quietly disagree with the artwork at
 * the small end, where hinting a 2.6-unit stroke down to 16 pixels is a decision and not an
 * arithmetic. So this picks the sizes the site actually asks for, and the ones it does not
 * ask for never become requests.
 *
 * An SVG favicon covers desktop browsers and nothing else: a phone adding the site to its
 * home screen wants a PNG at a known size, and given none it renders a screenshot of the
 * page or a grey square.
 *
 * **The `light` export is the one that ships.** In the artwork `light` and `dark` name the
 * BACKGROUND, not the theme (the `light` file carries dark ink for a light surface), and a
 * home-screen tile is composited on whatever the launcher chooses, which is usually white.
 *
 * Run: `pnpm build:icons`.
 */
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const BRAND_DIR = path.join(SITE_DIR, "src/brand");
const OUT_DIR = path.join(SITE_DIR, "public");
const TOKENS = path.join(SITE_DIR, "src/styles/tokens.generated.css");

/** The sizes the site names, and who asks for each. */
const SIZES = [
  { from: "limn-symbol-light-32.png", to: "icon-32.png", size: 32 }, // <link rel=icon>
  { from: "limn-symbol-light-180.png", to: "icon-180.png", size: 180 }, // apple-touch-icon
  { from: "limn-symbol-light-512.png", to: "icon-512.png", size: 512 }, // the manifest
];

/** Not exported at 180, because Apple's size is not a power of two. The nearest above it is used. */
const SUBSTITUTES = { "limn-symbol-light-180.png": "limn-symbol-light-256.png" };

async function main() {
  await mkdir(OUT_DIR, { recursive: true });

  for (const { from, to } of SIZES) {
    const source = pick(from);
    if (!source) {
      fail(`no artwork for ${to}: looked for ${from} in ${path.relative(SITE_DIR, BRAND_DIR)}`);
    }
    await copyFile(source, path.join(OUT_DIR, to));
  }

  // The manifest's two colours are the page's, not the mark's: they paint the browser
  // chrome around an installed page, so they come out of the generated palette.
  const tokens = await readFile(TOKENS, "utf8");
  const manifest = {
    name: "Limn",
    short_name: "Limn",
    description: "A UI toolkit for desktop Java.",
    start_url: ".",
    display: "standalone",
    background_color: tone(tokens, "--limn-background"),
    theme_color: tone(tokens, "--limn-primary"),
    icons: [
      { src: "icon-180.png", sizes: "180x180", type: "image/png" },
      { src: "icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
    ],
  };
  await writeFile(
    path.join(OUT_DIR, "site.webmanifest"),
    `${JSON.stringify(manifest, null, 2)}\n`,
    "utf8",
  );

  console.log(`build-icons: ${SIZES.length} icon(s) + manifest → public/`);
}

function pick(file) {
  const exact = path.join(BRAND_DIR, file);
  if (existsSync(exact)) return exact;
  const substitute = SUBSTITUTES[file];
  if (substitute) {
    const fallback = path.join(BRAND_DIR, substitute);
    if (existsSync(fallback)) return fallback;
  }
  return null;
}

/** One custom property out of the generated palette; this file restates no tone of its own. */
function tone(css, name) {
  const match = css.match(new RegExp(`${name}:\\s*(#[0-9A-Fa-f]{6})`));
  if (!match) fail(`${name} is not in the generated palette`);
  return match[1];
}

function fail(message) {
  console.error(`build-icons: ${message}`);
  process.exit(1);
}

await main();
