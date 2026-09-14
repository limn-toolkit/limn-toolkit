package limn.accessibility;

/**
 * Where a row stands in an outline: how deep it is, and which visible row it is.
 *
 * <p>Two sets of numbers answer two kinds of platform, and both are published because neither can
 * be rebuilt from the other in a virtualized tree, where the rows above a realized one may not be
 * in the snapshot at all. The <b>level</b> is what UI Automation's {@code Level} and AT-SPI2's
 * {@code level} attribute carry and what a reader speaks as "level 3". The <b>row</b> is the flat
 * index of this row among every row the outline shows open, counted down the outline, which is what
 * AppKit's {@code accessibilityIndex} and {@code accessibilityRows} address; a loading line is not a
 * row and is not counted. Where a row stands <em>among its siblings</em> — the "2 of 5" a reader
 * speaks — is {@link SelectionItemFacet}'s, not this facet's (decision 4, 2026-09-13).
 *
 * <p>Levels and rows are one-based in the model, and a zero means the number is not known; a
 * bridge publishes nothing for a zero (ADR 039 §1.2, amended 2026-09-14) and converts to its
 * platform's base itself, since UIA's base and AppKit's are read off the platform, not assumed.
 *
 * @param level    how deep the row is, from one at a root, or {@code 0} when unknown
 * @param row      which row of the outline this is, from one at the top, or {@code 0} when unknown
 * @param rowCount how many rows the outline shows open, or {@code 0} when unknown
 */
public record HierarchyFacet(int level, int row, int rowCount) {
}
