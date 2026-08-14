import snippets from "../generated/snippets.json";

/**
 * A code sample by its region name.
 *
 * Throws rather than returning empty: a page asking for a region that no source carries is
 * a rename nobody noticed, and an empty code block on a "get started" page is the kind of
 * defect that ships because it looks like a styling problem.
 */
export function snippet(name: string): string {
  const text = (snippets as Record<string, string>)[name];
  if (text === undefined) {
    throw new Error(
      `snippet: no region '${name}' in any source file. Regions are marked with ` +
        "`// #region <name>` … `// #endregion` and collected by scripts/build-gallery.mjs.",
    );
  }
  return text;
}
