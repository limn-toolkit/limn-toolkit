package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setter half (MACOS-NEW-11; semantics 5): each write is the verb the node accepts, or nothing,
 * and each attribute is written exactly where that holds.
 *
 * <p><b>This pins the write, and since 2026-09-16 the telling as well.</b> The gate alone could never
 * pin the telling: AppKit asks it at settability time and <b>discards a NO whenever the class
 * implements the setter</b>, so every element of the node class read as settable for all six however
 * this gate answered ({@code readings/macos-gate-setter-probe-read.txt}). What it does after that NO
 * is fall back to the legacy {@code accessibilityIsAttributeSettable:} where the element answers it,
 * so a client is told settable unless both refuse; the element now answers that one from this same
 * gate ({@link AxGate#settable}), which is what
 * {@link #whatAClientIsToldIsWhatTheGateWillDo()} holds. Readings:
 * {@code macos-settable-mechanism-serve.txt} and {@code macos-settable-mechanism-read.txt}. See
 * {@link AxSetters}'s own note and ADR 039 §2.2's amendments of that date.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxSettersTest {

    private record One(AxGrid grid, AccessibleNode node) {
    }

    /** A window holding one node, described by {@code describe}, enabled or not. */
    private static One one(Accessible.Role role, boolean enabled, Consumer<Accessibility> describe) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(role);
        a.name(I18nString.literal("n"), Accessible.NameFrom.CONTENT);
        describe.accept(a);
        a.inherited(enabled, true, true, true, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new One(new AxGrid(bridge), tree.find(1001));
    }

    private static One one(Accessible.Role role, Consumer<Accessibility> describe) {
        return one(role, true, describe);
    }

    private record Outline(AxGrid grid, AccessibleTree tree) {
        AccessibleNode node(long id) {
            return tree.find(id);
        }
    }

    /**
     * WINDOW > TREE 1010 (multi or single) > TREE_ITEM 1011 (selected), 1012, 1013, each publishing
     * the row verbs Tree publishes (decision 20); BUTTON 1020; TAB_LIST 1030 > TAB 1031.
     */
    private static Outline anOutline(boolean multi) {
        return anOutline(multi, true);
    }

    private static Outline anOutline(boolean multi, boolean rowsTakeVerbs) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int outline = a.begin(1010, 0, Locale.ENGLISH, 0, 0, 200, 90);
        a.role(Accessible.Role.TREE);
        a.selection(multi, false);
        a.inherited(true, true, true, true, false);
        for (int i = 0; i < 3; i++) {
            boolean selected = i == 0;
            a.begin(1011 + i, outline, Locale.ENGLISH, 0, 30L * i, 200, 30);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal("row " + i), Accessible.NameFrom.CONTENT);
            a.selectionItem(selected, i + 1, 3);
            a.hierarchy(1, i + 1, 3);
            if (rowsTakeVerbs && !multi) a.action(Accessible.Action.SELECT);
            else if (rowsTakeVerbs && selected) a.action(Accessible.Action.SELECT, Accessible.Action.DESELECT);
            else if (rowsTakeVerbs) a.action(Accessible.Action.SELECT, Accessible.Action.ADD_TO_SELECTION);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.begin(1020, 0, Locale.ENGLISH, 0, 100, 80, 30);
        a.role(Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        int tabs = a.begin(1030, 0, Locale.ENGLISH, 0, 140, 200, 30);
        a.role(Accessible.Role.TAB_LIST);
        a.selection(false, true);
        a.inherited(true, true, true, false, false);
        a.begin(1031, tabs, Locale.ENGLISH, 0, 140, 60, 30);
        a.role(Accessible.Role.TAB);
        a.selectionItem(true, 1, 1);
        a.action(Accessible.Action.SELECT);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Outline(new AxGrid(bridge), tree);
    }

    /**
     * WINDOW &gt; TREE 1010 (multi) &gt; synthetic GROUP &gt; TREE_ITEM 1011 (selected), 1012: the
     * container rule's one shape that parts from "the container's direct children", climbed through
     * a synthetic ancestor that holds no selection of its own.
     */
    private static Outline anOutlineWithASyntheticBody() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int outline = a.begin(1010, 0, Locale.ENGLISH, 0, 0, 200, 90);
        a.role(Accessible.Role.TREE);
        a.selection(true, false);
        a.inherited(true, true, true, true, false);
        a.child(7);
        int body = outline + 1;   // the slot child() just began, the next one after the outline's
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        for (int i = 0; i < 2; i++) {
            boolean selected = i == 0;
            a.begin(1011 + i, body, Locale.ENGLISH, 0, 30L * i, 200, 30);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal("row " + i), Accessible.NameFrom.CONTENT);
            a.selectionItem(selected, i + 1, 2);
            a.hierarchy(1, i + 1, 2);
            if (selected) a.action(Accessible.Action.SELECT, Accessible.Action.DESELECT);
            else a.action(Accessible.Action.SELECT, Accessible.Action.ADD_TO_SELECTION);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.endChild();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Outline(new AxGrid(bridge), tree);
    }

    @Test
    void aSelectedRowsWriteReachesTheRowsThatHangUnderASyntheticBody() {
        Outline o = anOutlineWithASyntheticBody();
        assertEquals(1010, o.tree().node(o.node(1011).selectionContainer()).id(),
                "the rule climbed the synthetic group: these rows are the outline's members");
        assertTrue(AxSetters.offers(o.grid(), o.node(1010), AxSetters.SELECTED_ROWS),
                "a row taking a selection verb is found wherever it hangs under its container, so the "
                        + "selected rows read settable here as they do when the rows are its children");
        assertEquals(List.of(row(1012, Accessible.Action.SELECT)),
                AxSetters.forSelectedRows(o.grid(), o.node(1010), List.of(o.node(1012))),
                "one row replaces the selection, as it does when the rows are the outline's children");
        assertEquals(List.of(row(1012, Accessible.Action.ADD_TO_SELECTION)),
                AxSetters.forSelectedRows(o.grid(), o.node(1010),
                        List.of(o.node(1011), o.node(1012))),
                "and the difference against the rows already selected is read from the same set, so a "
                        + "client can write back exactly what AXSelectedRows handed it (semantics 1)");
    }

    private static List<AxSetters.RowSetting> write(Outline o, long... rows) {
        List<AccessibleNode> written = new java.util.ArrayList<>();
        for (long row : rows) written.add(o.node(row));
        return AxSetters.forSelectedRows(o.grid(), o.node(1010), written);
    }

    private static AxSetters.RowSetting row(long id, Accessible.Action action) {
        return new AxSetters.RowSetting(id, action);
    }

    @Test
    void aSelectedRowsWriteLeavesExactlyTheWrittenRowsSelectedAsANativeOutlinesDoes() {
        Outline multi = anOutline(true);
        assertTrue(AxSetters.offers(multi.grid(), multi.node(1010), AxSetters.SELECTED_ROWS),
                "a native outline's AXSelectedRows read settable");
        assertEquals(List.of(row(1012, Accessible.Action.SELECT)), write(multi, 1012),
                "one row replaces the selection, as [Charlie] replaced [Alpha, Charlie] natively: a click");
        assertEquals(List.of(row(1011, Accessible.Action.DESELECT), row(1012, Accessible.Action.ADD_TO_SELECTION),
                        row(1013, Accessible.Action.ADD_TO_SELECTION)), write(multi, 1012, 1013),
                "two rows become the selection, as [Alpha, Charlie] did natively");
        assertEquals(List.of(row(1011, Accessible.Action.DESELECT)), write(multi, new long[0]),
                "an empty array empties it, as natively");
        assertEquals(List.of(row(1012, Accessible.Action.ADD_TO_SELECTION)), write(multi, 1011, 1012),
                "a row already selected and written stays; only the difference is posted");
        assertNull(write(multi, 1012, 1020), "a button is no row of the outline: the write is refused whole");
        assertNull(AxSetters.forSelectedRows(multi.grid(), multi.node(1010),
                java.util.Arrays.asList(multi.node(1012), null)), "nor is an element that stands for no node");

        Outline single = anOutline(false);
        assertTrue(AxSetters.offers(single.grid(), single.node(1010), AxSetters.SELECTED_ROWS),
                "settable in a single-select outline too, as natively");
        assertEquals(List.of(row(1013, Accessible.Action.SELECT)), write(single, 1013));
        assertNull(write(single, 1012, 1013),
                "two rows in a single-select outline are refused, as natively (kAXErrorIllegalArgument)");
        assertNull(write(single, new long[0]),
                "an empty array is refused: a single-select row publishes no DESELECT (decision 20), where "
                        + "a native outline allowing an empty selection clears it");
    }

    @Test
    void theSelectedRowsAreSettableOnlyWhereAContainersSelectionIsItsRowsAndARowTakesAVerb() {
        Outline o = anOutline(true);
        assertFalse(AxSetters.offers(o.grid(), o.node(1030), AxSetters.SELECTED_ROWS),
                "a tab strip's selection is its children, not rows");
        assertFalse(AxSetters.offers(o.grid(), o.node(1020), AxSetters.SELECTED_ROWS), "a button holds none");
        assertFalse(AxSetters.offers(o.grid(), o.node(1011), AxSetters.SELECTED_ROWS), "nor does a row");
        assertNull(AxSetters.forSelectedRows(o.grid(), o.node(1030), List.of(o.node(1031))),
                "and nothing offered is nothing written");
        assertTrue(AxGate.allows(o.grid(), o.node(1010), AxSetters.SELECTED_ROWS),
                "the gate is what decides whether a write is delivered; what a client reads as "
                        + "settable is AppKit's own answer once the setter is installed (2026-09-16)");
        assertFalse(AxGate.allows(o.grid(), o.node(1030), AxSetters.SELECTED_ROWS));
        Outline inert = anOutline(true, false);
        assertFalse(AxSetters.offers(inert.grid(), inert.node(1010), AxSetters.SELECTED_ROWS),
                "an outline whose rows publish no selection verb — disabled, or beneath a layer that owns "
                        + "input — says its selected rows are not settable (semantics 5)");
    }

    @Test
    void aFocusWriteIsTheFocusVerbAndOnlyWhereItIsPublished() {
        One button = one(Accessible.Role.BUTTON, a -> a.action(Accessible.Action.PRESS, Accessible.Action.FOCUS));
        assertTrue(AxSetters.offers(button.grid(), button.node(), AxSetters.FOCUSED));
        assertEquals(Accessible.Action.FOCUS,
                AxSetters.forBool(button.grid(), button.node(), AxSetters.FOCUSED, true).action());
        assertNull(AxSetters.forBool(button.grid(), button.node(), AxSetters.FOCUSED, false),
                "focus is not written away; it is written to where it goes");
        One label = one(Accessible.Role.LABEL, a -> { });
        assertFalse(AxSetters.offers(label.grid(), label.node(), AxSetters.FOCUSED),
                "a label's AXFocused is not settable: AppKit would store the write and nothing would read it");
        assertNull(AxSetters.forBool(label.grid(), label.node(), AxSetters.FOCUSED, true));
    }

    /**
     * WINDOW &gt; TREE 1010 &gt; TREE_ITEM 1011 and TABLE 1020 &gt; ROW 1021, every row publishing FOCUS
     * and SELECT, and BUTTON 1030 publishing FOCUS: the discriminator between "the node takes the
     * cursor" and "AXFocused is settable on it".
     */
    private static AccessibleTree rowsThatTakeTheCursor() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int outline = a.begin(1010, 0, Locale.ENGLISH, 0, 0, 200, 30);
        a.role(Accessible.Role.TREE);
        a.selection(false, false);
        a.action(Accessible.Action.FOCUS);
        a.inherited(true, true, true, true, false);
        a.begin(1011, outline, Locale.ENGLISH, 0, 0, 200, 30);
        a.role(Accessible.Role.TREE_ITEM);
        a.name(I18nString.literal("row"), Accessible.NameFrom.CONTENT);
        a.selectionItem(true, 1, 1);
        a.hierarchy(1, 1, 1);
        a.action(Accessible.Action.FOCUS, Accessible.Action.SELECT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        int table = a.begin(1020, 0, Locale.ENGLISH, 0, 40, 200, 30);
        a.role(Accessible.Role.TABLE);
        a.table(1, 1);
        a.selection(false, false);
        a.action(Accessible.Action.FOCUS);
        a.inherited(true, true, true, true, false);
        int row = a.begin(1021, table, Locale.ENGLISH, 0, 40, 200, 30);
        a.role(Accessible.Role.ROW);
        a.selectionItem(true, 1, 1);
        a.action(Accessible.Action.FOCUS, Accessible.Action.SELECT);
        a.inherited(true, true, true, false, false);
        a.begin(1022, row, Locale.ENGLISH, 0, 40, 200, 30);
        a.role(Accessible.Role.CELL);
        a.cell(0, 0);
        a.name(I18nString.literal("cell"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.FOCUS);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        a.begin(1030, 0, Locale.ENGLISH, 0, 80, 80, 30);
        a.role(Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS, Accessible.Action.FOCUS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    @Test
    void aRowsAXFocusedIsNotSettableThoughTheRowTakesTheCursor() {
        // P5M-1. A native NSOutlineView's row answers kAXErrorAttributeUnsupported for AXFocused and
        // for its settability, while the outline itself answers AXFocused=1 settable=true
        // (readings/macos-outline-probe.txt); a native NSTableView row's AXAttributeNames carry
        // AXSelected and no AXFocused, while the table's carry AXFocused (readings/macos-table-probe.txt).
        // The view takes focus and rows are selected — and a row that was focus-settable let
        // VoiceOver's cursor sync write its previous row back after every key.
        AccessibleTree tree = rowsThatTakeTheCursor();
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        AxGrid grid = new AxGrid(bridge);
        for (long id : new long[] {1011L, 1021L}) {
            AccessibleNode node = tree.find(id);
            assertTrue(node.accepts(Accessible.Action.FOCUS), id + ": the model does take the cursor here");
            assertTrue(grid.isRow(node), id + ": and it is a row");
            assertFalse(AxSetters.offers(grid, node, AxSetters.FOCUSED),
                    id + ": so its AXFocused is not settable, as a native row's is not");
            assertNull(AxSetters.forBool(grid, node, AxSetters.FOCUSED, true),
                    id + ": and a write to it posts nothing");
            assertFalse(AxGate.allows(grid, node, AxSetters.FOCUSED), id + ": the gate says the same");
            assertEquals(Accessible.Action.SELECT,
                    AxSetters.forBool(grid, node, AxSetters.SELECTED, true).action(),
                    id + ": the native route to the cursor is left open");
        }
        for (long id : new long[] {1010L, 1020L, 1022L, 1030L}) {
            AccessibleNode node = tree.find(id);
            assertTrue(AxSetters.offers(grid, node, AxSetters.FOCUSED),
                    id + ": a container, a cell and a control keep AXFocused settable, as natively "
                            + "the outline and the table do and their rows do not");
            assertEquals(Accessible.Action.FOCUS,
                    AxSetters.forBool(grid, node, AxSetters.FOCUSED, true).action(), String.valueOf(id));
        }
    }

    @Test
    void aSelectedWriteSelectsOrDeselectsByWhatTheItemPublishes() {
        One single = one(Accessible.Role.LIST_ITEM, a -> {
            a.selectionItem(true, 1, 3);
            a.action(Accessible.Action.SELECT);
        });
        assertEquals(Accessible.Action.SELECT,
                AxSetters.forBool(single.grid(), single.node(), AxSetters.SELECTED, true).action());
        assertNull(AxSetters.forBool(single.grid(), single.node(), AxSetters.SELECTED, false),
                "a single-select item publishes no DESELECT, so NO is refused");
        One multi = one(Accessible.Role.TREE_ITEM, a -> {
            a.selectionItem(true, 1, 3);
            a.action(Accessible.Action.SELECT, Accessible.Action.DESELECT);
        });
        assertEquals(Accessible.Action.DESELECT,
                AxSetters.forBool(multi.grid(), multi.node(), AxSetters.SELECTED, false).action());
        One button = one(Accessible.Role.BUTTON, a -> a.action(Accessible.Action.PRESS));
        assertFalse(AxSetters.offers(button.grid(), button.node(), AxSetters.SELECTED));
    }

    @Test
    void anExpandedWriteOpensOrClosesByWhatTheNodePublishes() {
        One closed = one(Accessible.Role.COMBO_BOX, a -> {
            a.expand(false);
            a.action(Accessible.Action.EXPAND);
        });
        assertTrue(AxSetters.offers(closed.grid(), closed.node(), AxSetters.EXPANDED));
        assertFalse(AxSetters.offers(closed.grid(), closed.node(), AxSetters.DISCLOSED),
                "AXDisclosing is an outline row's, and a combo box is none");
        assertEquals(Accessible.Action.EXPAND,
                AxSetters.forBool(closed.grid(), closed.node(), AxSetters.EXPANDED, true).action());
        assertNull(AxSetters.forBool(closed.grid(), closed.node(), AxSetters.EXPANDED, false),
                "closing a closed combo box publishes no COLLAPSE");
        One stuck = one(Accessible.Role.COMBO_BOX, a -> a.expand(false));
        assertFalse(AxSetters.offers(stuck.grid(), stuck.node(), AxSetters.EXPANDED),
                "an expand facet with no verb is not settable: no facet implies a verb");
    }

    @Test
    void aValueWriteIsTextToATextAndTheValueOrItsTextToAWritableValue() {
        One field = one(Accessible.Role.TEXT_FIELD, a -> a.text("abc", 1, 3, ShapedText.Affinity.UPSTREAM,
                3, 3, 1, null, false));
        assertTrue(AxSetters.offers(field.grid(), field.node(), AxSetters.VALUE));
        AxSetters.Setting typed = AxSetters.forValue(field.node(), "hello", null);
        assertEquals(Accessible.Action.SET_TEXT, typed.action());
        assertEquals(new Accessible.Argument.OfText("hello"), typed.argument());
        assertNull(AxSetters.forValue(field.node(), null, 3.0), "a text is written with a string");

        One readOnly = one(Accessible.Role.TEXT_FIELD, a -> a.text("abc", 1, 3,
                ShapedText.Affinity.UPSTREAM, 3, 3, 1, null, true));
        assertFalse(AxSetters.offers(readOnly.grid(), readOnly.node(), AxSetters.VALUE));
        assertNull(AxSetters.forValue(readOnly.node(), "hello", null));

        One slider = one(Accessible.Role.SLIDER, a -> a.value(40, 0, 100, 1));
        assertTrue(AxSetters.offers(slider.grid(), slider.node(), AxSetters.VALUE));
        assertEquals(new AxSetters.Setting(Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(55)),
                AxSetters.forValue(slider.node(), null, 55.0));
        assertEquals(new AxSetters.Setting(Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("55")),
                AxSetters.forValue(slider.node(), "55", null),
                "semantics 5: a value's text write posts SET_VALUE of text, and the widget parses it");
        assertNull(AxSetters.forValue(slider.node(), null, Double.NaN));

        One progress = one(Accessible.Role.PROGRESS_BAR, a -> a.value(40, 0, 100, 1, true));
        assertFalse(AxSetters.offers(progress.grid(), progress.node(), AxSetters.VALUE));
        assertNull(AxSetters.forValue(progress.node(), null, 55.0));
    }

    @Test
    void aDisabledNodeTakesNoWriteAndSaysNoneIsSettable() {
        One slider = one(Accessible.Role.SLIDER, false, a -> a.value(40, 0, 100, 1));
        assertFalse(AxSetters.offers(slider.grid(), slider.node(), AxSetters.VALUE),
                "fix round 2e: a setter is implied only on an ENABLED node");
        assertNull(AxSetters.forValue(slider.node(), null, 55.0));
        One field = one(Accessible.Role.TEXT_FIELD, false, a -> a.text("abc", 1, 3,
                ShapedText.Affinity.UPSTREAM, 3, 3, 1, null, false));
        assertNull(AxSetters.forValue(field.node(), "x", null));
    }

    @Test
    void everyOtherStoredSetterIsRefusedOnEveryNode() {
        One slider = one(Accessible.Role.SLIDER, a -> a.value(40, 0, 100, 1));
        for (String stored : new String[] {"setAccessibilityRole:", "setAccessibilityLabel:",
                "setAccessibilityDisclosureLevel:", "setAccessibilitySelectedChildren:"}) {
            assertFalse(AxGate.allows(slider.grid(), slider.node(), stored),
                    stored + ": NSAccessibilityElement stores it, a client reads it settable, nothing reads it back");
        }
        assertTrue(AxGate.allows(slider.grid(), slider.node(), AxSetters.VALUE), "the gate asks AxSetters");
        assertFalse(AxGate.allows(slider.grid(), slider.node(), AxSetters.FOCUSED),
                "and a slider publishing no FOCUS is not focus-settable");
    }

    /**
     * WINDOW &gt; TREE 1010 &gt; TREE_ITEM 1011 that publishes EXPAND (a branch) and TREE_ITEM 1012 that
     * publishes neither EXPAND nor COLLAPSE (a leaf): the one case per-shape classes could not have
     * expressed, because both rows are the same shape and answer differently.
     */
    private static Outline aLeafAndABranch() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int outline = a.begin(1010, 0, Locale.ENGLISH, 0, 0, 200, 60);
        a.role(Accessible.Role.TREE);
        a.selection(false, false);
        a.inherited(true, true, true, true, false);
        a.begin(1011, outline, Locale.ENGLISH, 0, 0, 200, 30);
        a.role(Accessible.Role.TREE_ITEM);
        a.name(I18nString.literal("branch"), Accessible.NameFrom.CONTENT);
        a.selectionItem(false, 1, 2);
        a.hierarchy(1, 1, 2);
        a.expand(false);
        a.action(Accessible.Action.EXPAND, Accessible.Action.SELECT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(1012, outline, Locale.ENGLISH, 0, 30, 200, 30);
        a.role(Accessible.Role.TREE_ITEM);
        a.name(I18nString.literal("leaf"), Accessible.NameFrom.CONTENT);
        a.selectionItem(false, 2, 2);
        a.hierarchy(1, 2, 2);
        a.action(Accessible.Action.SELECT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        AxBridge bridge = PlatformFreeBridges.make();
        bridge.publish(tree, false);
        return new Outline(new AxGrid(bridge), tree);
    }

    /**
     * Decision 78, and the reason the owner's approval to multiply the Objective-C classes was not
     * needed: two rows of one element class answer {@code AXDisclosing} differently because their
     * nodes do, which is what a native {@code NSOutlineView} does with one row class — its leaves
     * {@code row#2} and {@code row#5} settable=false, its collapsed branch {@code row#4} and its open
     * branches {@code row#0} and {@code row#1} settable=true
     * ({@code readings/macos-outline-probe.txt}, 2026-09-15).
     */
    @Test
    void aLeafAndABranchOfOneClassAnswerDisclosingDifferently() {
        Outline o = aLeafAndABranch();
        assertTrue(AxGate.settable(o.grid(), o.node(1011), AxSetters.DISCLOSED),
                "a row that can open is disclosure-settable, as the native collapsed branch is");
        assertFalse(AxGate.settable(o.grid(), o.node(1012), AxSetters.DISCLOSED),
                "and a leaf is not, which is the answer a client used to be denied");
        for (long id : new long[] {1011L, 1012L}) {
            assertFalse(AxGate.settable(o.grid(), o.node(id), AxSetters.FOCUSED),
                    id + ": a native row carries no AXFocused at all (P5M-1)");
            assertFalse(AxGate.settable(o.grid(), o.node(id), AxSetters.EXPANDED),
                    id + ": an outline row's disclosure is AXDisclosing, never AXExpanded");
        }
    }

    /**
     * The whole point of installing {@code accessibilityIsAttributeSettable:} (2026-09-16): what a
     * client is TOLD is what the gate will DO, for every node and every setter, with no second rule
     * that could drift from the first.
     *
     * <p>Before it, AppKit discarded the gate's NO for any setter the class implements and reported
     * every one of them settable on every element — so this equality held nowhere that mattered
     * ({@code readings/macos-gate-setter-probe-read.txt}).
     */
    @Test
    void whatAClientIsToldIsWhatTheGateWillDo() {
        for (Outline o : List.of(anOutline(true), anOutline(false), aLeafAndABranch())) {
            for (int i = 0; i < o.tree().nodeCount(); i++) {
                AccessibleNode node = o.tree().node(i);
                for (String setter : AxSetters.selectors()) {
                    assertEquals(AxGate.allows(o.grid(), node, setter),
                            AxGate.settable(o.grid(), node, setter),
                            node.id() + " " + setter + ": the telling and the delivery are one answer");
                }
            }
        }
    }

    /**
     * An attribute with none of our setters behind it is not settable, and that is measured rather
     * than defaulted: AppKit asks this selector for {@code AXPosition} and {@code AXElementBusy} on
     * every element, whatever the gate said, and the probe's control with no hook installed reported
     * both {@code no} ({@code readings/macos-settable-mechanism-read.txt}).
     */
    @Test
    void anAttributeWithNoSetterBehindItIsNotSettable() {
        One slider = one(Accessible.Role.SLIDER, a -> a.value(40, 0, 100, 1));
        assertFalse(AxGate.settable(slider.grid(), slider.node(), null),
                "AXPosition, AXElementBusy, AXRole: the map has no setter, so the answer is no");
        assertTrue(AxGate.settable(slider.grid(), slider.node(), AxSetters.VALUE),
                "and the control that the null is doing the work, not the node");
    }

    /**
     * The attribute names AppKit asks with are read off the running AppKit, so what this can hold off
     * a Mac is that every setter installed has exactly one name mapped to it and no name is mapped to
     * anything else. {@code AxConstantsTest} holds the symbols themselves against the dump.
     */
    @Test
    void everyInstalledSetterHasExactlyOneAttributeNameBehindIt() {
        assertEquals(List.copyOf(AxSetters.selectors()).size(), AxSetters.ATTRIBUTE_SYMBOLS.size(),
                "a setter with no attribute name is a setter a client can never be told about");
        assertEquals(java.util.Set.copyOf(AxSetters.selectors()),
                java.util.Set.copyOf(AxSetters.ATTRIBUTE_SYMBOLS.values()),
                "the attribute table maps exactly the installed setters");
        assertEquals(AxSetters.ATTRIBUTE_SYMBOLS.keySet(), AxSetters.symbols(),
                "and AxConstantsTest is handed every symbol the table names");
        for (String symbol : AxSetters.symbols()) {
            assertTrue(symbol.startsWith("NSAccessibility") && symbol.endsWith("Attribute"),
                    symbol + " is not an AppKit attribute-name symbol, so the dump cannot hold it");
        }
    }
}
