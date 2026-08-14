/**
 * Ready-to-use components (Label, Button, TextField, Checkbox/Switch, Dialog)
 * and the light/dark {@code Theme}.
 *
 * <h2>The discrete-choice widgets answer {@code setSelectedIndex} the same way</h2>
 *
 * {@link limn.components.ListView}, {@link limn.components.TabbedPane},
 * {@link limn.components.ComboBox}, {@link limn.components.SegmentedControl} and
 * {@link limn.components.ButtonGroup} spell one operation with one name, and it means one thing
 * in all five.
 *
 * <ul>
 * <li><b>An out-of-range index throws {@link java.lang.IndexOutOfBoundsException}</b>, exactly as
 *     {@code List.get} does and for the same reason: an index computed from a search that found
 *     nothing, a filter that emptied the data, or a value restored from saved state is a caller's
 *     bug, and clamping it selects the wrong thing quietly. A caller holding a computed index
 *     checks it against the count first.</li>
 * <li><b>{@code -1} is not an argument.</b> Where "nothing is selected" is a real state (a list
 *     of records need not have a current record), a {@code clearSelection()} names it:
 *     {@link limn.components.ListView} and {@link limn.components.ButtonGroup} have one. A
 *     {@link limn.components.TabbedPane} holding tabs, a {@link limn.components.ComboBox} and a
 *     {@link limn.components.SegmentedControl} always have exactly one selection and so offer
 *     none.</li>
 * <li><b>A programmatic set fires the change listener</b>, the same one a click fires. A listener
 *     describes the selection, not the mouse, so a detail pane bound to it stays right without
 *     knowing where the change came from.</li>
 * <li><b>Setting the index that is already selected changes nothing and fires nothing.</b> That
 *     early return is load-bearing, not an optimization. A single UI thread rules out two
 *     <em>concurrent</em> entries, never two <em>nested</em> ones: widget A's listener writing
 *     widget B, whose listener writes A back, recurses on one stack until it overflows. The bounce
 *     dies on the first return instead, which is the whole reason a two-way binding between two of
 *     these controls terminates. Do not delete the guard as redundant with the thread rule.</li>
 * </ul>
 *
 * <p>Keyboard traversal is not bound by the first rule: arrowing past either end of a strip lands
 * on the end, because that is what the key means, not what an index means.
 */
package limn.components;
