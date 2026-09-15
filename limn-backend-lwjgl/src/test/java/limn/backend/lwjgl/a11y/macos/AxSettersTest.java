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
 * and each attribute is settable exactly where that holds — the gate's answer for the setter, which is
 * what AppKit reads as settable (read on the guest, 2026-09-13).
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
                "the gate's answer for the setter is what a client reads as settable");
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
}
