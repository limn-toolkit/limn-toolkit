/**
 * The toolkit version the site documents — the one number no source file may carry.
 *
 * The guides write `{{version}}` and the home page reads this; both are filled in at BUILD
 * time, so the repository never holds a literal that a release would have to rewrite (see
 * scripts/check-versions.sh at the root, and RELEASING.md). Where the number comes from:
 *
 *   1. LIMN_VERSION in the environment — what site-deploy passes, computed by its gate job as
 *      the newest PUBLISHED release, which is what the site follows;
 *   2. otherwise the newest `v*` tag reachable from this checkout, for a local build of a
 *      clone that has tags;
 *   3. otherwise `x.y.z`, so a local preview of a tagless clone shows a visible placeholder
 *      rather than a wrong number.
 */
import { execSync } from "node:child_process";

export function limnVersion() {
  const fromEnv = (process.env.LIMN_VERSION || "").trim().replace(/^v/, "");
  if (fromEnv) {
    return fromEnv;
  }
  try {
    const tags = execSync("git tag --list 'v*' --sort=-v:refname", {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    })
      .split("\n")
      .map((tag) => tag.trim())
      .filter((tag) => /^v\d+\.\d+\.\d+$/.test(tag));
    if (tags.length > 0) {
      return tags[0].slice(1);
    }
  } catch {
    // No git, or no repository: fall through to the visible placeholder.
  }
  return "x.y.z";
}

/** Every `{{version}}` in `text`, filled in. */
export function fillVersion(text, version = limnVersion()) {
  return text.replaceAll("{{version}}", version);
}
