/**
 * What has landed in the toolkit since the site's pages were last read against it.
 *
 * The footer records the commit each build came from, which answers "when was this built"
 * and nothing else; it moves every time anyone types `pnpm build`, whether or not a word
 * changed. `site.config.json` records something different: the commit somebody last went
 * through the toolkit's changes against. The gap between the two is the work.
 *
 * Informational, never fatal. A site whose build fails because a widget was renamed
 * upstream is a site nobody can publish a typo fix to.
 *
 * Run: `pnpm since:review`, and it also runs as part of `pnpm generate`.
 */
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SITE_DIR = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const REPO_DIR = path.resolve(SITE_DIR, "..");
const CONFIG = path.join(SITE_DIR, "site.config.json");

/** Commits listed in full before the tail is folded into a count. */
const SHOWN = 20;

async function main() {
  const config = JSON.parse(await readFile(CONFIG, "utf8"));
  const reviewed = config.contentReviewedAt;
  if (!reviewed) {
    note("site.config.json has no contentReviewedAt, so there is nothing to compare against.");
    return;
  }

  const head = git(["rev-parse", "HEAD"]);
  if (head === null) {
    note("not a git checkout, so skipping.");
    return;
  }
  if (git(["merge-base", "--is-ancestor", reviewed, "HEAD"], true) === null) {
    note(
      `contentReviewedAt (${reviewed.slice(0, 8)}) is not an ancestor of HEAD. ` +
        "Rebased or reset? Re-read the pages and set it to a commit on this history.",
    );
    return;
  }

  // The site's own directory is excluded: a commit that only edits these pages is not a
  // toolkit change the pages need to be told about, and leaving it in means the count never
  // reaches zero.
  const log = git(["log", "--oneline", `${reviewed}..HEAD`, "--", ".", ":(exclude)site"]);
  const commits = log ? log.split("\n").filter(Boolean) : [];

  if (commits.length === 0) {
    console.log(
      `since-review: the pages are current with ${head.slice(0, 8)}; nothing has landed since.`,
    );
    return;
  }

  console.log(
    `\nsince-review: ${commits.length} commit(s) in the toolkit since the pages were last ` +
      `read against it (${reviewed.slice(0, 8)} → ${head.slice(0, 8)}):\n`,
  );
  for (const commit of commits.slice(0, SHOWN)) console.log(`  ${commit}`);
  if (commits.length > SHOWN) console.log(`  … and ${commits.length - SHOWN} more`);
  console.log(
    "\n  Read them, update the pages that need it, then set contentReviewedAt in " +
      "site/site.config.json.\n",
  );
}

function git(args, quiet = false) {
  try {
    return execFileSync("git", args, { cwd: REPO_DIR, stdio: ["ignore", "pipe", "ignore"] })
      .toString()
      .trim();
  } catch {
    return quiet ? null : null;
  }
}

function note(message) {
  console.log(`since-review: ${message}`);
}

await main();
