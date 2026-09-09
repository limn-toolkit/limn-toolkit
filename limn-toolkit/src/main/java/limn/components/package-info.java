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
 * <li><b>A programmatic set reaches the watchers and not the handler.</b> {@code onSelect} is
 *     the application's response to the <em>user</em> choosing, and it runs only for a click, a
 *     key or an assistive technology's select; a {@code setSelectedIndex} from code announces
 *     {@code SELECTION}/{@code CODE} to whoever {@linkplain limn.scene.Widget#observeChanges
 *     watches} the widget, and to nobody else. A detail pane that must follow the selection
 *     wherever it came from watches; a pane that answers the user handles. That is the one rule
 *     of {@link limn.scene.Change.Origin}, and it is why two of these controls bound to each
 *     other through their handlers cannot recurse at all.</li>
 * <li><b>Setting the index that is already selected changes nothing and announces nothing.</b>
 *     The guard is on the announcement: a mutator handed the state it already holds says nothing,
 *     which is what ends a two-way binding written on the watcher channel on its first echo.</li>
 * </ul>
 *
 * <p>Keyboard traversal is not bound by the first rule: arrowing past either end of a strip lands
 * on the end, because that is what the key means, not what an index means.
 */
package limn.components;
