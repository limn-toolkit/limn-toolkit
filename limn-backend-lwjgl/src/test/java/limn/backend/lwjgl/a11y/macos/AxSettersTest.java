package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

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
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(tree, false);
        return new One(new AxGrid(bridge), tree.find(1001));
    }

    private static One one(Accessible.Role role, Consumer<Accessibility> describe) {
        return one(role, true, describe);
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
