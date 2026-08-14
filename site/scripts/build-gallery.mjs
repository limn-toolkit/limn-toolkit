/**
 * The Node half of the gallery build: reads the manifest `limn-demo` wrote,
 * extracts each entry's code sample from the marked region in the real source, emits the
 * image derivatives, and writes the data the component pages render from.
 *
 * The Java half (`./gradlew :limn-demo:captureGallery`) renders the PNGs into
 * `site/captures/`. This half never invents an entry: if the manifest is missing, or an
 * entry names a region no source file carries, or an image the manifest promised is not on
 * disk, **the build fails**. A gallery that silently degrades to twelve of thirteen
 * components is worse than one that stops, because nobody notices the one that went
 * missing.
 *
 * **`site/captures/` is read-only to this script, and `public/gallery/` is write-only.**
 * The captures are the pristine input and the published files are pure derivatives of
 * them. An output that is ever read back, or a capture that is ever overwritten, becomes
 * its own input on the next run, and every crop then runs twice. That is why every read
 * goes through {@code CAPTURES_DIR}, every published byte goes through {@code publish},
 * and the whole derivation is re-run and byte-compared on every build (see `publish`).
 *
 * Image work happens here, with sharp, and never in a Gradle task: `checkArchitecture`
 * forbids AWT everywhere in the Java modules, and `javax.imageio` is exactly the shortcut
 * that would erode it.
 */
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const REPO_DIR = path.resolve(SITE_DIR, "..");
/** Pristine input, written only by `captureGallery`. Nothing here is ever published. */
const CAPTURES_DIR = path.join(SITE_DIR, "captures");
/** Published output. Owned entirely by this script: written, verified, and pruned. */
const GALLERY_DIR = path.join(SITE_DIR, "public/gallery");
const MANIFEST = path.join(CAPTURES_DIR, "gallery.json");
const SHOWCASE_MANIFEST = path.join(CAPTURES_DIR, "showcase.json");
const SCENE_ROOT = path.join(REPO_DIR, "limn-demo/src/main/java");
const OUT_DATA = path.join(SITE_DIR, "src/generated/gallery.json");
const OUT_SHOWCASE = path.join(SITE_DIR, "src/generated/showcase.json");
const OUT_SNIPPETS = path.join(SITE_DIR, "src/generated/snippets.json");
const OUT_MOSAIC = path.join(SITE_DIR, "src/generated/mosaic.json");
const OUT_LANGUAGES = path.join(SITE_DIR, "src/generated/languages.json");
/**
 * A digest of everything the derivation depends on, kept beside the other generated data.
 * When it matches, the captures have not changed since the last run, which is what lets
 * `publish` demand that this run's bytes equal the last run's.
 */
const STAMP = path.join(SITE_DIR, "src/generated/gallery-derivation.json");

/** Margin re-added around a trimmed capture, in captured (2×) pixels. */
const MARGIN = 48;

/** Device pixels per logical point in a published asset: the `@2x` in every file name. */
const SCALE = 2;

/**
 * The widest any published picture may be, in device pixels. A page that ships a 2000px
 * screenshot to show it at 1140 is paying for pixels no display asks for; this is the
 * ceiling, and every capture wider than it is resized down to it.
 */
const MAX_PIXEL_WIDTH = 1024;

/**
 * How large one animation may get before it is re-encoded with loss, in bytes. Nothing is
 * fetched until a reader presses play, so this is a budget for one deliberate click rather
 * than for a page load. But a film nobody can wait for is a film nobody watches.
 *
 * The budget is enforced: an animation that stays over it at the lowest quality this file
 * is willing to encode **fails the build** (see `animate`). It is not a warning, because a
 * line in a build log publishes the overrun anyway; overspending is allowed, but only by
 * raising this number here, deliberately.
 */
const ANIMATION_BUDGET = 400 * 1024;

/**
 * The same budget for a filmed SCREEN, which is a different order of picture: a component film is
 * a cropped widget a few hundred pixels wide, and a screen film is the whole window. At the same
 * ceiling the encoder would be pushed to the bottom of the quality ladder and the text in the
 * window, which is most of what a screen shows, would smear.
 *
 * Still a ceiling, still enforced, and still only raised deliberately: there is exactly one filmed
 * screen (see `SiteShowcase.Entry.filmed`), and a page that grew a second one should have to come
 * back here and say so.
 */
const SHOWCASE_ANIMATION_BUDGET = 1800 * 1024;

/**
 * The ceiling for a filmed SCREEN, narrower than the one every still gets.
 *
 * A screen film is the one asset here whose cost is paid while it plays, not once when it
 * arrives. Animated WebP codes most pages against the page before, so the decoder is
 * reconstructing every frame at full size for as long as the film runs, and a window shown at
 * 1024 device pixels was measured degrading into slow motion partway through: it began at the
 * delays written below and drifted behind them, which is what a decoder that cannot keep up
 * looks like. The encoded timing was flat when it was measured, so the fix is not in the
 * delays; it is in how much there is to decode per page.
 *
 * 800 rather than 1024 is a third off the pixels in every page. It applies to films only,
 * because a still is decoded once and can afford the width.
 */
const MAX_ANIMATION_WIDTH = 800;

/**
 * How many captured frames go into one published page of a screen film.
 *
 * The capture renders a frame every {@code GalleryScenes.STEP_MS}, which is the step the film
 * scripts are written against and is not something to change for the sake of a file size. This
 * is the other end: two captured frames become one page holding for both their delays. Half
 * the pages to decode, the same seconds on screen, and the same script.
 *
 * The cost is real and it is motion, not sharpness: a page every 40 ms instead of every 20.
 * That is still above the rate at which a dragged pointer reads as continuous, and it buys back
 * far more than it spends when the alternative is a decoder falling behind.
 */
const SHOWCASE_FRAME_STRIDE = 2;

/**
 * The qualities tried, in order, when lossless is over budget. The floor exists because
 * below it the flat fills and hairlines these captures are made of visibly smear. A film
 * that cannot fit the budget at the floor needs a shorter script or a bigger budget, and
 * either is a decision for a person, not for this loop.
 */
const LOSSY_QUALITIES = [88, 80, 72, 64];

/** The published still formats, widest support last so `<source>` order is cheap. */
const DERIVATIVES = [
  { ext: "avif", options: { quality: 55 } },
  { ext: "webp", options: { quality: 82 } },
];

/**
 * The home page's mosaic: one image quilted from crops of the mosaic captures.
 *
 * <p>It is ONE file rather than a grid of `<img>`s on purpose. One screenshot per dressing laid
 * out in CSS is a request, a decode and a chance for the page to reflow apiece, for a composition
 * whose whole point is to be seen at once.
 *
 * <p>**Crops at native resolution, not whole screens scaled down.** A whole 1024-point window
 * shrunk into a third of this canvas turns every control into a smudge two pixels tall, which
 * says nothing about a UI toolkit. Each tile is a rectangle lifted out of its capture at the
 * pixels it was rendered with, so a reader sees real controls at a size they can read.
 *
 * <p>**Flush, in a regular grid, with no gaps.** The tiles tessellate the canvas exactly: no
 * stagger, no rounded corners, no transparent background between them. Every tile takes its crop
 * from a different part of the same board, so they read as one screen quilted out of several
 * dressings, which is the claim, rather than as unrelated pictures.
 *
 * <p>**Vertical slats, one row.** Tall slices rather than a two-row grid, because a grid spends
 * half its area repeating what the row above already showed: in one row every dressing is visible
 * at once, and each slat runs the full height of its capture, so a reader compares them by moving
 * their eye sideways instead of hunting. The count is {@link MOSAIC_COLUMNS} and lives there.
 */
const MOSAIC_WIDTH = 1024;
/**
 * Capped by the captures: a slat is cropped, never scaled, so this cannot exceed the showcase
 * window's own height (`SHOWCASE_HEIGHT` in Gallery.java). `composeMosaic` fails rather than
 * upscale if it ever does.
 */
const MOSAIC_HEIGHT = 680;
const MOSAIC_COLUMNS = 7;
const MOSAIC_ROWS = 1;

/**
 * The seam drawn on each boundary between slats, in published pixels: half in a dark tone, half
 * in a light one.
 *
 * <p>Two tones because one cannot work: a dark hairline vanishes against a dark theme and a light
 * one vanishes against a light theme, and the seams here have both on either side. Together they
 * read as an edge on any pair.
 *
 * <p>It is a line, not a gap: the slats still tessellate the canvas exactly and the seam is
 * painted ON them. A gap would let the page background through, and eight screenshots floating on
 * the page is the composition this replaced.
 */
const MOSAIC_SEAM = 1;

/**
 * The languages quilt: the same screen captured in several languages, cut into one picture.
 *
 * <p>Each tile is the capture's own top-left quadrant, half its width and half its height, at the
 * pixels it was rendered with, so a 2×2 grid comes out exactly one window in size. That is the
 * point: four scripts inside the frame of a single application window, rather than four thumbnails
 * a reader has to compare across a gap.
 *
 * <p>The top-left corner is not an arbitrary choice. It is where this screen puts its menu bar,
 * its title and the first rows of its form, which is the densest text in the window. A quadrant
 * from the middle would be mostly chart and mostly the same in every language.
 *
 * <p>Unlike the theme mosaic, these tiles are NOT palette-invariant: the quilt is composed once
 * per palette and the page swaps the two the way it swaps any other capture.
 */
const LANGUAGE_COLUMNS = 2;
const LANGUAGE_ROWS = 2;
/** The fraction of each capture a tile takes, from its top-left corner. */
const LANGUAGE_TILE = 0.5;
/** Reading order, and the order the quilt lays them out. */
const LANGUAGE_TILES = ["kitchen-ja", "kitchen-zh-Hans", "kitchen-ko", "kitchen-ru"];

/**
 * Which part of its capture each tile shows, as a fraction of the room available: 0 is the left
 * or top edge, 1 the right or bottom. Fractions rather than pixels because the crop size follows
 * the canvas and the captures' own size follows the window in Gallery.java; pixel offsets here
 * would silently start cropping off-centre the day either moves.
 *
 * <p>Left to right. `focusX` walks across the board so that neighbouring slats show different
 * controls, because two neighbours cut from the same column are one widget in two palettes, which is a
 * colour swatch, and this is meant to read as a screen. `focusY` barely matters at one row: a
 * slat is nearly as tall as its capture.
 *
 * <p>Light and dark alternate. Eight slats sorted by tone would be a gradient, and the eye reads
 * a gradient as one picture darkening rather than as eight themes.
 */
const MOSAIC_TILES = [
  { id: "mosaic-vivid", focusX: 0, focusY: 0 },
  { id: "mosaic-paper", focusX: 0.17, focusY: 1 },
  { id: "mosaic-dusk", focusX: 0.33, focusY: 0 },
  { id: "mosaic-mint", focusX: 0.5, focusY: 1 },
  { id: "mosaic-ember", focusX: 0.67, focusY: 0 },
  { id: "mosaic-shipped-light", focusX: 0.83, focusY: 1 },
  { id: "mosaic-shipped-dark", focusX: 1, focusY: 0 },
];

/**
 * Every published byte, `file name → content`, accumulated by `derive` and `animate` and
 * written in one place by `publish`. No PNG is ever in it: WebP is the `<img src>` last
 * resort, and every browser that renders this site's CSS has read WebP for years.
 */
const outputs = new Map();

async function main() {
  if (!existsSync(MANIFEST)) {
    fail(
      `no gallery manifest at ${MANIFEST}\n` +
        "  run: ./gradlew :limn-demo:captureGallery",
    );
  }
  const manifest = JSON.parse(await readFile(MANIFEST, "utf8"));
  const regions = await collectRegions(SCENE_ROOT);
  const problems = [];
  const entries = [];

  for (const entry of manifest.entries) {
    const snippet = regions.get(entry.region);
    if (snippet === undefined) {
      problems.push(
        `entry '${entry.id}' names region '${entry.region}', which no source file carries`,
      );
      continue;
    }
    const images = {};
    for (const [theme, file] of Object.entries(entry.images)) {
      const source = path.join(CAPTURES_DIR, file);
      if (!existsSync(source)) {
        problems.push(`entry '${entry.id}' promises ${file}, which was not rendered`);
        continue;
      }
      // The component's own box, from the capture, in device pixels with a margin around
      // it. Used by the film AND by the still, so the two are the same picture.
      const captured = await sharp(source).metadata();
      const box = entry.content
        ? clampBox(
            {
              left: Math.round(entry.content.x * SCALE) - MARGIN,
              top: Math.round(entry.content.y * SCALE) - MARGIN,
              width: Math.round(entry.content.width * SCALE) + MARGIN * 2,
              height: Math.round(entry.content.height * SCALE) + MARGIN * 2,
            },
            captured.width,
            captured.height,
          )
        : undefined;
      const animation = entry.frames ? await animate(entry, theme, box, problems) : undefined;
      images[theme] = await derive(source, file, true, MAX_PIXEL_WIDTH, undefined, box);
      if (animation) {
        images[theme].animation = animation;
      }
    }
    entries.push({ id: entry.id, title: entry.title, snippet, images });
  }

  if (problems.length > 0) {
    fail(`${problems.length} gallery problem(s)\n  ${problems.join("\n  ")}`);
  }
  if (entries.length === 0) {
    fail("the manifest produced no entries, so refusing to publish an empty gallery");
  }

  await mkdir(path.dirname(OUT_DATA), { recursive: true });
  await writeFile(OUT_DATA, `${JSON.stringify({ entries }, null, 2)}\n`, "utf8");

  // The showcase set: whole screens, so no snippet and no region, and NOT trimmed, since
  // an application window's own margins are part of what the picture is showing.
  if (!existsSync(SHOWCASE_MANIFEST)) {
    fail(`no showcase manifest at ${SHOWCASE_MANIFEST}\n  run: ./gradlew :limn-demo:captureGallery`);
  }
  const showcase = JSON.parse(await readFile(SHOWCASE_MANIFEST, "utf8"));
  const shots = [];
  for (const entry of showcase.entries) {
    const images = {};
    for (const [theme, file] of Object.entries(entry.images)) {
      if (!existsSync(path.join(CAPTURES_DIR, file))) {
        fail(`showcase entry '${entry.id}' promises ${file}, which was not rendered`);
      }
      // 2× the window's point size, or the ceiling, whichever is smaller. The framebuffer a
      // capture gets depends on the monitor it ran on: the same command produced a 2× file
      // on one machine and a 4× file on another, so the published size is decided here and
      // not by whoever ran the capture.
      const target = Math.min(
        entry.points ? entry.points * SCALE : Number.POSITIVE_INFINITY,
        MAX_PIXEL_WIDTH,
      );
      images[theme] = await derive(
        path.join(CAPTURES_DIR, file), file, false, target, entry.points);

      // A filmed screen. The crop is the WHOLE frame, unlike a component's film: there is no
      // component to crop to, and the claim a screen film makes is that everything in the window
      // reacted, and cropping it to the control being dragged would cut away the evidence.
      if (entry.frames) {
        const captured = await sharp(path.join(CAPTURES_DIR, file)).metadata();
        const animation = await animate(
          { id: `showcase-${entry.id}`, frames: entry.frames, frameMs: entry.frameMs },
          theme,
          { left: 0, top: 0, width: captured.width, height: captured.height },
          problems,
          {
            budget: SHOWCASE_ANIMATION_BUDGET,
            maxWidth: MAX_ANIMATION_WIDTH,
            stride: SHOWCASE_FRAME_STRIDE,
          },
        );
        if (animation) {
          images[theme].animation = animation;
        }
      }
    }
    shots.push({ id: entry.id, title: entry.title, locale: entry.locale, images });
  }

  // Drained again, because `problems` is shared with the component pass above and the check
  // there runs BEFORE this loop exists. Everything the showcase reported was therefore pushed
  // onto an array nobody read again: a film over its budget returned undefined, the entry
  // published with no animation, and the build said nothing. The one gate that has to catch a
  // showcase film is the one that was structurally unable to.
  //
  // Before the manifest is written, not after: a manifest naming an animation that was not
  // produced is exactly the shape of failure this whole file is built to refuse.
  if (problems.length > 0) {
    fail(`${problems.length} showcase problem(s)\n  ${problems.join("\n  ")}`);
  }
  await writeFile(OUT_SHOWCASE, `${JSON.stringify({ entries: shots }, null, 2)}\n`, "utf8");

  await writeFile(
    OUT_MOSAIC,
    `${JSON.stringify(await composeMosaic(showcase), null, 2)}\n`,
    "utf8",
  );

  await writeFile(
    OUT_LANGUAGES,
    `${JSON.stringify(await composeLanguages(showcase), null, 2)}\n`,
    "utf8",
  );

  // Every marked region, not only the ones a gallery entry names: a page may want a
  // sample that has no picture: the hello-window on "get started" is one. Same guarantee
  // either way, because the text still comes from a file the Java build compiles.
  await writeFile(
    OUT_SNIPPETS,
    `${JSON.stringify(Object.fromEntries(regions), null, 2)}\n`,
    "utf8",
  );

  await publish();

  console.log(
    `build-gallery: ${entries.length} component(s), ${shots.length} showcase capture(s), ` +
      `${regions.size} snippet(s), ${outputs.size} published file(s)`,
  );
}

/**
 * Joins one entry's captured frames into a single animated WebP.
 *
 * **Every frame is cropped by the same box, and the box comes from the capture**: the
 * component's own laid-out rectangle, reported in the manifest. Two ways of guessing it were
 * wrong: trimming each frame moves the crop as the pointer enters, so the component jitters
 * under an arrow that is holding still; and trimming ONE frame finds that frame's ink, which
 * for a split pane is two labels and a hairline, so the divider was dragged straight out of
 * the picture.
 *
 * The frames stay on disk afterwards; they live under `captures/`, which is never
 * published, and deleting them is what would make a second run depend on the first.
 *
 * WebP and not GIF: every browser in support has read animated WebP for years, and the same
 * frames cost roughly a quarter of the bytes. A GIF is one more `.gif()` call here if one is
 * ever wanted for pasting into a chat window.
 */
async function animate(entry, theme, box, problems, options = {}) {
  const {
    budget = ANIMATION_BUDGET,
    maxWidth = MAX_PIXEL_WIDTH,
    stride = 1,
  } = options;
  // Checked here rather than trusted, because the failure downstream is unreadable: a missing
  // frameMs reaches the encoder as a NaN delay per page, and sharp reports a comma-separated list
  // of NaNs and blanks with no mention of the manifest or the entry it came from.
  if (!Number.isFinite(entry.frameMs)) {
    fail(
      `entry '${entry.id}' promises ${entry.frames} frame(s) but the manifest carries no ` +
        "frameMs\n  re-run: ./gradlew :limn-demo:captureGallery",
    );
  }
  const base = `${entry.id}-${theme}`;
  const files = Array.from({ length: entry.frames }, (_, i) =>
    path.join(CAPTURES_DIR, `${base}-f${String(i).padStart(3, "0")}@2x.png`));
  const out = `${base}-anim.webp`;
  const missing = files.filter((file) => !existsSync(file));
  if (missing.length > 0) {
    problems.push(
      `entry '${entry.id}' promises ${entry.frames} frame(s) for ${theme}; ` +
        `${missing.length} were not rendered`,
    );
    return undefined;
  }

  // Which captured frames become pages, and how long each one stands in for.
  //
  // At a stride of 1 this is every frame for one delay each, which is what a component film
  // gets. Above 1 it samples, and the LAST sample is short: 425 frames at a stride of 2 is 212
  // samples covering two frames and one covering the odd frame left at the end. Counting the
  // coverage rather than multiplying by the stride is what keeps the film the same length as
  // the one that was captured, instead of quietly running long by a frame.
  const sampled = [];
  for (let i = 0; i < files.length; i += stride) {
    sampled.push({ file: files[i], covers: Math.min(stride, files.length - i) });
  }

  const resize = box.width > maxWidth ? { width: maxWidth } : undefined;
  const frames = [];
  const digests = [];
  for (const { file } of sampled) {
    let pipeline = sharp(file).extract(box);
    if (resize) {
      pipeline = pipeline.resize(resize);
    }
    digests.push(createHash("sha1").update(await pipeline.clone().raw().toBuffer()).digest("hex"));
    frames.push(await pipeline.png().toBuffer());
  }

  // Runs of identical frames are collapsed HERE, into one frame holding for the run's whole
  // length, and the delays are handed to the encoder as an array.
  //
  // The encoder collapses them too if it is left to, and loses the timing doing it: a film
  // of 35 frames at 20 ms came back as 11 pages totalling 3420 ms instead of 700, because
  // every merged page fell back to the 100 ms default. Deduplicating first means the pages
  // the encoder sees all differ, so there is nothing left for it to merge and the delay for
  // each one is the one written here.
  const pages = [];
  const delays = [];
  for (let i = 0; i < frames.length; i++) {
    const held = sampled[i].covers * entry.frameMs;
    if (i > 0 && digests[i] === digests[i - 1]) {
      delays[delays.length - 1] += held;
    } else {
      pages.push(frames[i]);
      delays.push(held);
    }
  }

  if (pages.length < 2) {
    // Every frame identical: the script aimed at nothing, or the crop box missed what moves.
    // Publishing it would be a still that costs a second request and says it is a film.
    problems.push(
      `entry '${entry.id}' filmed ${entry.frames} frame(s) for ${theme} and every one is ` +
        "identical, and the script moves nothing that the crop box can see",
    );
    return undefined;
  }

  // Lossless first, and lossy only if that turns out to be a photograph.
  //
  // These captures are mostly flat fills, hairlines and text on a near-black canvas, which
  // is the content lossy WebP is worst at: its inter-frame residue left visible ghosts of a
  // divider and of labels smeared across the background. But one entry is a rendered 3D
  // scene, and lossless on that came out at 2.8 MB, for the same seconds of film that cost
  // 40 kB everywhere else. Rather than a list of which entry is which, the size decides:
  // anything over the budget is re-encoded lossy, stepping down `LOSSY_QUALITIES` until it
  // fits. A film still over budget at the floor is a build failure, not a log line: the
  // budget is only a budget if the build refuses to spend past it.
  let encoded = await sharp(pages, { join: { animated: true } })
    .webp({ lossless: true, effort: 5, loop: 0, delay: delays })
    .toBuffer();
  if (encoded.length > budget) {
    let fitted;
    for (const quality of LOSSY_QUALITIES) {
      encoded = await sharp(pages, { join: { animated: true } })
        .webp({ quality, effort: 5, loop: 0, delay: delays })
        .toBuffer();
      if (encoded.length <= budget) {
        fitted = quality;
        break;
      }
    }
    if (fitted === undefined) {
      problems.push(
        `entry '${entry.id}' (${theme}): the animation is ${Math.round(encoded.length / 1024)} kB ` +
          `at quality ${LOSSY_QUALITIES.at(-1)}, over the ${Math.round(budget / 1024)} kB ` +
          "budget; shorten the film, or raise budget in build-gallery.mjs on purpose",
      );
      return undefined;
    }
    console.log(
      `  ${out}: ${Math.round(encoded.length / 1024)} kB, lossy q${fitted}; lossless was over budget`,
    );
  }
  outputs.set(out, encoded);

  return {
    webp: out,
    frames: pages.length,
    durationMs: delays.reduce((total, each) => total + each, 0),
  };
}

/** Keeps an extract box inside the image, which `trim` plus a margin can walk out of. */
function clampBox(box, imageWidth, imageHeight) {
  const left = Math.max(0, Math.min(box.left, imageWidth - 1));
  const top = Math.max(0, Math.min(box.top, imageHeight - 1));
  return {
    left,
    top,
    width: Math.min(box.width, imageWidth - left),
    height: Math.min(box.height, imageHeight - top),
  };
}

/**
 * Cuts {@link MOSAIC_TILES} out of the showcase captures into one image, at 2× and 1×.
 *
 * <p>Composed from the pristine captures rather than from the published derivatives: those are
 * already resized to the page's ceiling and re-encoded with loss, so building on them would
 * resample a lossy image down again, and the tiles' hairlines are the first thing that costs.
 *
 * <p>Every tile is masked to a rounded rectangle and outlined. Without the outline two light
 * tiles that touch read as one wide screenshot with a seam in it, which is the opposite of
 * what a mosaic of themes is for.
 *
 * <p>The composition is a pure function of the captures and of the table above, which is what
 * lets `publish` treat these files like every other derivative and verify they come out
 * byte-identical when the captures have not changed.
 */
async function composeMosaic(showcase) {
  const byId = new Map(showcase.entries.map((entry) => [entry.id, entry]));
  if (MOSAIC_TILES.length !== MOSAIC_COLUMNS * MOSAIC_ROWS) {
    fail(
      `the mosaic grid is ${MOSAIC_COLUMNS}×${MOSAIC_ROWS} but ${MOSAIC_TILES.length} tile(s) ` +
        "are listed, and a grid with a hole in it publishes a transparent rectangle",
    );
  }
  // Integer tile sizes that sum to the canvas exactly. Rounding each tile independently is what
  // leaves a one-pixel transparent seam between columns, and a seam is the one thing a flush
  // mosaic must not have.
  const columns = spans(MOSAIC_WIDTH * SCALE, MOSAIC_COLUMNS);
  const rows = spans(MOSAIC_HEIGHT * SCALE, MOSAIC_ROWS);

  const layers = [];
  for (const [index, tile] of MOSAIC_TILES.entries()) {
    const entry = byId.get(tile.id);
    if (!entry) {
      fail(
        `the mosaic names '${tile.id}', which the showcase manifest does not carry\n` +
          "  add the entry in SiteShowcase.mosaicTiles() and re-run the capture",
      );
    }
    // A mosaic tile pins its own theme, so its two palette passes wrote the same file and
    // either key names it. Reading `light` keeps this independent of the palette order.
    const file = entry.images.light ?? Object.values(entry.images)[0];
    const source = path.join(CAPTURES_DIR, file);
    const column = columns[index % MOSAIC_COLUMNS];
    const row = rows[Math.floor(index / MOSAIC_COLUMNS)];

    const captured = await sharp(source).metadata();
    if (captured.width < column.size || captured.height < row.size) {
      fail(
        `capture ${file} is ${captured.width}×${captured.height}, smaller than the ` +
          `${column.size}×${row.size} crop the mosaic takes from it: a capture cannot be ` +
          "cropped larger than it is, and scaling it up is the blur this design avoids",
      );
    }
    const crop = {
      left: Math.round((captured.width - column.size) * tile.focusX),
      top: Math.round((captured.height - row.size) * tile.focusY),
      width: column.size,
      height: row.size,
    };
    layers.push({
      input: await sharp(source).extract(crop).png().toBuffer(),
      left: column.start,
      top: row.start,
    });
  }

  // The seams, in one overlay: a rect pair on every interior boundary. Drawn after the slats so
  // it lands on top of them, and never on the outer edges of the canvas, because an outline around the
  // whole mosaic is a frame, and the page already puts one there.
  const seam = MOSAIC_SEAM * SCALE;
  const rules = columns
    .slice(1)
    .map(
      (column) =>
        `<rect x="${column.start - seam}" y="0" width="${seam}" height="100%" ` +
        'fill="rgba(0,0,0,0.45)"/>' +
        `<rect x="${column.start}" y="0" width="${seam}" height="100%" ` +
        'fill="rgba(255,255,255,0.45)"/>',
    )
    .join("");

  const master = await sharp({
    create: {
      width: MOSAIC_WIDTH * SCALE,
      height: MOSAIC_HEIGHT * SCALE,
      channels: 4,
      // Opaque black, and never seen: the tiles cover every pixel. It exists so that a future
      // grid that does not (one tile short, a crop that fails) shows as a black hole in the
      // published image instead of blending into whichever page background is behind it.
      background: { r: 0, g: 0, b: 0, alpha: 1 },
    },
  })
    .composite([
      ...layers,
      {
        input: Buffer.from(
          `<svg width="${MOSAIC_WIDTH * SCALE}" height="${MOSAIC_HEIGHT * SCALE}">` +
            `${rules}</svg>`,
        ),
        left: 0,
        top: 0,
      },
    ])
    .png()
    .toBuffer();

  // Down to the page's ceiling, exactly like every other published picture. Emitting the full 2×
  // composite as well was 11 MB of decoded bitmap for one image on the home page, four times what
  // any capture beside it costs. MAX_PIXEL_WIDTH is in device pixels and it is the ceiling for
  // this too, however wide the canvas it was quilted on.
  const published = await sharp(master)
    .resize({ width: MAX_PIXEL_WIDTH, withoutEnlargement: true })
    .png()
    .toBuffer();
  const shown = await sharp(published).metadata();
  const images = {};
  for (const { ext, options } of DERIVATIVES) {
    const name = `home-mosaic.${ext}`;
    outputs.set(name, await sharp(published)[ext](options).toBuffer());
    images[ext] = name;
  }
  return { width: shown.width, height: shown.height, tiles: MOSAIC_TILES.length, images };
}

/**
 * Quilts {@link LANGUAGE_TILES} into one picture per palette; see the note on the constants.
 *
 * <p>Emitted in the shape the `Figure` component takes, keyed by palette, so the page renders it
 * with the same component and the same light/dark swap as any single capture. Nothing about the
 * page has to know it is looking at a composite.
 */
async function composeLanguages(showcase) {
  const byId = new Map(showcase.entries.map((entry) => [entry.id, entry]));
  const missing = LANGUAGE_TILES.filter((id) => !byId.has(id));
  if (missing.length > 0) {
    fail(
      `the languages quilt names ${missing.join(", ")}, which the showcase manifest does not ` +
        "carry\n  add the entry in SiteShowcase.entries() and re-run the capture",
    );
  }
  if (LANGUAGE_TILES.length !== LANGUAGE_COLUMNS * LANGUAGE_ROWS) {
    fail(
      `the languages quilt is ${LANGUAGE_COLUMNS}×${LANGUAGE_ROWS} but ` +
        `${LANGUAGE_TILES.length} tile(s) are listed, and a grid with a hole in it publishes a ` +
        "transparent rectangle",
    );
  }

  const images = {};
  for (const theme of ["dark", "light"]) {
    const layers = [];
    let width = 0;
    let height = 0;
    for (const [index, id] of LANGUAGE_TILES.entries()) {
      const source = path.join(CAPTURES_DIR, byId.get(id).images[theme]);
      const captured = await sharp(source).metadata();
      // Rounded once, from the capture, and then used for every tile: the four captures are the
      // same window at the same size, and a per-tile rounding would put a transparent seam
      // between columns whenever that size is odd.
      const tileWidth = Math.round(captured.width * LANGUAGE_TILE);
      const tileHeight = Math.round(captured.height * LANGUAGE_TILE);
      width = tileWidth * LANGUAGE_COLUMNS;
      height = tileHeight * LANGUAGE_ROWS;
      layers.push({
        input: await sharp(source)
          .extract({ left: 0, top: 0, width: tileWidth, height: tileHeight })
          .png()
          .toBuffer(),
        left: (index % LANGUAGE_COLUMNS) * tileWidth,
        top: Math.floor(index / LANGUAGE_COLUMNS) * tileHeight,
      });
    }

    const seam = MOSAIC_SEAM * SCALE;
    const rules =
      `<rect x="${width / 2 - seam}" y="0" width="${seam}" height="100%" fill="rgba(0,0,0,0.45)"/>` +
      `<rect x="${width / 2}" y="0" width="${seam}" height="100%" fill="rgba(255,255,255,0.45)"/>` +
      `<rect x="0" y="${height / 2 - seam}" width="100%" height="${seam}" fill="rgba(0,0,0,0.45)"/>` +
      `<rect x="0" y="${height / 2}" width="100%" height="${seam}" fill="rgba(255,255,255,0.45)"/>`;

    const master = await sharp({
      create: { width, height, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 1 } },
    })
      .composite([
        ...layers,
        { input: Buffer.from(`<svg width="${width}" height="${height}">${rules}</svg>`),
          left: 0, top: 0 },
      ])
      .png()
      .toBuffer();

    // Down to the page's ceiling, like every other published capture. MAX_PIXEL_WIDTH is already
    // in device pixels, so multiplying it by SCALE published a picture four times the bitmap of
    // every capture beside it, and this one has no smaller variant, so every device paid it.
    const published = await sharp(master)
      .resize({ width: MAX_PIXEL_WIDTH, withoutEnlargement: true })
      .png()
      .toBuffer();
    const shown = await sharp(published).metadata();
    const entry = { width: shown.width, height: shown.height };
    for (const { ext, options } of DERIVATIVES) {
      const name = `home-languages-${theme}.${ext}`;
      outputs.set(name, await sharp(published)[ext](options).toBuffer());
      entry[ext] = name;
    }
    images[theme] = entry;
  }
  return { images };
}

/**
 * Splits {@code total} pixels into {@code count} adjacent spans that cover it exactly, giving the
 * remainder to the earliest spans. Returned as start + size so a caller places tiles by their
 * left edge rather than by multiplying a rounded width, which is what leaves seams.
 */
function spans(total, count) {
  const base = Math.floor(total / count);
  const extra = total - base * count;
  const out = [];
  let start = 0;
  for (let i = 0; i < count; i++) {
    const size = base + (i < extra ? 1 : 0);
    out.push({ start, size });
    start += size;
  }
  return out;
}

/**
 * Emits the AVIF and WebP derivatives of one capture and reports the intrinsic size. The
 * size is read from the processed pixels rather than assumed: `width`/`height` on every
 * `<img>` is what stops the gallery (the page the whole site is selling) from shifting
 * as its images land.
 */
async function derive(source, file, trim = true, maxPixelWidth = undefined, pointWidth = undefined,
                      box = undefined) {
  // Trimmed, then given a uniform margin back. Every entry is rendered on one oversized
  // canvas so the capture never has to resize a window mid-run (that race corrupted whole
  // images); cropping the leftover margin belongs here, where it is a pure function of the
  // pixels. The margin colour is read from the image's own corner rather than named, so it
  // follows the palette without this file restating a tone.
  const corner = await sharp(source)
    .extract({ left: 0, top: 0, width: 1, height: 1 })
    .raw()
    .toBuffer();
  const background = { r: corner[0], g: corner[1], b: corner[2], alpha: 1 };
  // An explicit box wins over trimming. The capture reports one for every filmed entry,
  // because trimming finds the ink and a film has to be cropped to the COMPONENT; see
  // `animate`. The still uses the same box, so playing one in place of the other moves
  // nothing on the page.
  let pipeline = box
    ? sharp(source).extract(box)
    : trim
      ? sharp(source)
          .trim({ threshold: 8 })
          .extend({ top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN, background })
      : sharp(source);
  // Down only, never up: a capture already at or below the target is left alone rather
  // than resampled for nothing.
  if (maxPixelWidth !== undefined) {
    const captured = await sharp(source).metadata();
    if (captured.width > maxPixelWidth) {
      pipeline = pipeline.resize({ width: maxPixelWidth, withoutEnlargement: true });
    }
  }
  const master = await pipeline.png().toBuffer();

  const image = sharp(master);
  const { width, height } = await image.metadata();
  const base = file.replace(/\.png$/, "");
  const sources = {};
  for (const { ext, options } of DERIVATIVES) {
    const name = `${base}.${ext}`;
    outputs.set(name, await image.clone().toFormat(ext, options).toBuffer());
    sources[ext] = name;
  }
  // The CSS size, which is NOT the pixel size over a fixed factor once the emitted image
  // has been capped: a picture held to 1024 device pixels still occupies the points its
  // window did, and reporting half of it would show it at half size.
  const cssWidth = pointWidth ?? Math.round(width / SCALE);
  return {
    ...sources,
    width: cssWidth,
    height: Math.round((cssWidth * height) / width),
  };
}

/**
 * Writes every derivative into `public/gallery/`, prunes anything there that this run did
 * not produce, and, when the captures are unchanged since the last run, **fails the build
 * unless every byte matches what is already on disk**.
 *
 * The comparison is the guarantee that the derivation is a pure function of the captures.
 * If it ever fails, one of three things is reading or writing where it must not: the
 * derivation is consuming something outside `captures/`, something else is writing into
 * `public/gallery/`, or an encoder stopped being deterministic. All three ship corrupted
 * or drifting pictures if they are let through, which is why this is not a warning.
 */
async function publish() {
  const digest = await inputDigest();
  let previous;
  if (existsSync(STAMP)) {
    previous = JSON.parse(await readFile(STAMP, "utf8"));
  }
  await mkdir(GALLERY_DIR, { recursive: true });

  if (previous && previous.input === digest) {
    const differing = [];
    for (const [name, content] of outputs) {
      const target = path.join(GALLERY_DIR, name);
      if (existsSync(target) && !content.equals(await readFile(target))) {
        differing.push(name);
      }
    }
    if (differing.length > 0) {
      fail(
        "the derivation is not idempotent: the captures are unchanged since the last " +
          `run, but ${differing.length} output file(s) came out with different bytes\n` +
          `  ${differing.join("\n  ")}\n` +
          "  the derivation must be a pure function of site/captures/: either it is " +
          "reading something else,\n  something else is writing into " +
          "site/public/gallery/, or an encoder is not deterministic",
      );
    }
  }

  const keep = new Set(outputs.keys());
  for (const entry of await readdir(GALLERY_DIR, { withFileTypes: true })) {
    if (entry.isFile() && !keep.has(entry.name)) {
      await rm(path.join(GALLERY_DIR, entry.name));
    }
  }
  for (const [name, content] of outputs) {
    await writeFile(path.join(GALLERY_DIR, name), content);
  }
  await mkdir(path.dirname(STAMP), { recursive: true });
  await writeFile(STAMP, `${JSON.stringify({ input: digest }, null, 2)}\n`, "utf8");
}

/**
 * One digest over everything the published bytes are a function of: every capture, this
 * script's own source (so retuning a quality here is a new derivation, not an idempotency
 * failure), and the encoders' versions (so a sharp upgrade is too).
 */
async function inputDigest() {
  const hash = createHash("sha256");
  hash.update(await readFile(fileURLToPath(import.meta.url)));
  hash.update(JSON.stringify(sharp.versions));
  const names = (await readdir(CAPTURES_DIR)).filter((name) => !name.startsWith(".")).sort();
  for (const name of names) {
    hash.update(name);
    hash.update(createHash("sha256").update(await readFile(path.join(CAPTURES_DIR, name))).digest());
  }
  return hash.digest("hex");
}

/**
 * Every `// #region <name>` … `// #endregion` block in the tree, keyed by name. The
 * marker lines themselves are dropped and the block is de-indented, so the page shows the
 * code and not the scaffolding around it.
 */
async function collectRegions(root) {
  const regions = new Map();
  for await (const file of javaFiles(root)) {
    const lines = (await readFile(file, "utf8")).split("\n");
    let name = null;
    let body = [];
    for (const line of lines) {
      const open = line.match(/^\s*\/\/\s*#region\s+(\S+)\s*$/);
      if (open) {
        name = open[1];
        body = [];
        continue;
      }
      if (name && /^\s*\/\/\s*#endregion\s*$/.test(line)) {
        regions.set(name, dedent(body));
        name = null;
        continue;
      }
      if (name) body.push(line);
    }
    if (name) {
      fail(`${path.relative(REPO_DIR, file)}: region '${name}' is never closed`);
    }
  }
  return regions;
}

function dedent(lines) {
  const body = [...lines];
  while (body.length && body[0].trim() === "") body.shift();
  while (body.length && body[body.length - 1].trim() === "") body.pop();
  const indent = Math.min(
    ...body.filter((line) => line.trim() !== "").map((line) => line.match(/^ */)[0].length),
  );
  return body.map((line) => line.slice(indent)).join("\n");
}

async function* javaFiles(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* javaFiles(full);
    else if (entry.name.endsWith(".java")) yield full;
  }
}

function fail(message) {
  console.error(`build-gallery: ${message}`);
  process.exit(1);
}

await main();
