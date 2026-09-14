package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.DatePicker;
import limn.i18n.I18n;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a screen reader is told about a date picker as it stands in a form, closed; ADR 042 §8,
 * amended 2026-09-14 (decisions 18 and 55).
 *
 * <p>The node a reader arrives at is the field, so the field is what says a calendar can be
 * opened from here, whether it is, and takes the verbs that open and close it — the shape
 * {@code ComboBox} publishes and the ARIA combobox pattern names. A single picker's own group
 * is no node at all, so a caption bound to the picker names the field; a range picker keeps its
 * group under the caption and names its two ends for themselves. The button is a plain press.
 *
 * <p>In-scene on purpose: {@link StubWindow} cannot create the native popup window. Here the
 * open popup is an overlay of the scene, so the field publishes its expanded state and no verb
 * (the scene would refuse a verb on the field beneath the overlay; the overlay's {@code CANCEL}
 * closes). The native presentation's half — the field keeping the focus and taking
 * {@code COLLAPSE} while the calendar is a window of its own — is limn-demo's
 * {@code DatePickerNativePopupTest}, over a {@code HeadlessBackend} that can.
 */
class DatePickerAccessibilityTest extends AccessibleComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private DatePicker picker;

    private void bindCaptioned(DatePicker built, String caption) {
        I18n.setLocale(PT_BR);
        picker = built;
        picker.setDate(LocalDate.of(2026, 9, 9));
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        Label label = new Label(caption);
        root.add(label);
        root.add(picker);
        label.setLabelFor(picker);
        bind(root);
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private List<AccessibleNode> nodesOf(Accessible.Role role) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == role) {
                found.add(tree().node(i));
            }
        }
        return found;
    }

    private static boolean offers(AccessibleNode node, Accessible.Action action) {
        return node.actions() != null && node.actions().actions().contains(action);
    }

    @Test
    void theCaptionNamesTheFieldAndTheFieldSaysItHasAPopup() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        List<AccessibleNode> groups = nodesOf(Accessible.Role.GROUP);
        assertEquals(1, groups.size(),
                "one group: the field's; the picker's own is no node " + describe(tree()));
        AccessibleNode field = groups.get(0);
        assertEquals("Data de entrega", field.name(),
                "the caption bound to the picker names the field a reader arrives at");
        assertEquals(Accessible.NameFrom.LABEL, field.nameFrom());
        assertTrue(field.has(Accessible.State.FOCUSABLE));
        assertTrue(field.has(Accessible.State.HAS_POPUP), describe(tree()));
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND), "closed: Expand is the verb");
        assertFalse(offers(field, Accessible.Action.COLLAPSE));
        assertEquals(3, childrenOf(field).size(), "and it is still the group of three segments");

        AccessibleNode button = node(Accessible.Role.BUTTON);
        assertEquals("Abrir calendário", button.name());
        assertNull(button.expand(), "a plain press: the popup's state is the field's to tell");
        assertTrue(offers(button, Accessible.Action.PRESS));
        assertFalse(offers(button, Accessible.Action.EXPAND));
        // Brief item 14: that the button refuses the verb it no longer publishes was a claim by
        // reading. The host accepts any verb on a published node and leaves the list to the
        // bridges (semantics 5), so what is asserted is the effect: nothing opens.
        perform(button.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        assertFalse(picker.isOpen(), "Expand on the button does nothing: its verb is Press");

        assertTrue(perform(field.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE));
        assertTrue(picker.isOpen(), "Expand on the field opens the calendar");
        frame();
        field = nodesOf(Accessible.Role.GROUP).stream()
                .filter(group -> group.name().equals("Data de entrega")).findFirst().orElseThrow();
        assertTrue(field.has(Accessible.State.EXPANDED));
        assertTrue(field.has(Accessible.State.HAS_POPUP));
        // In this presentation the popup is an overlay of the scene, and the scene refuses every
        // verb on a widget beneath it: a COLLAPSE published on the field would be answered
        // "accepted" from the snapshot and close nothing (semantics 5). So the field publishes
        // its state and no verb, the overlay's CANCEL is the closing verb, and the same is
        // true of what is performed: Collapse on the field is refused, Cancel on the overlay
        // closes. In a window of its own the field takes COLLAPSE -- DatePickerNativePopupTest.
        assertFalse(offers(field, Accessible.Action.COLLAPSE),
                "open in the scene: no verb on the field " + describe(tree()));
        assertFalse(offers(field, Accessible.Action.EXPAND));
        // The host accepts any verb on a node it published and leaves the published list to
        // the bridges (semantics 5), so what is asserted is what happens: nothing.
        perform(field.id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        assertTrue(picker.isOpen(), "Collapse on the field beneath the overlay does nothing, "
                + "which is why the field does not publish it");
        AccessibleNode overlay = nodesOf(Accessible.Role.GROUP).stream()
                .filter(group -> group.name().equals("Calendário") && offers(group, Accessible.Action.CANCEL))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no overlay group with CANCEL in " + describe(tree())));
        assertEquals(overlay.id(), tree().focused(), "the overlay holds the focus (decision 1)");
        long cursor = tree().firstActiveBelow(tree().indexOf(overlay.id()));
        assertTrue(cursor != 0, "and the calendar's cursor is ACTIVE under it " + describe(tree()));
        assertEquals(cursor, tree().effectiveFocus(), "which is the tree's effective focus");
        assertEquals(Accessible.Role.CELL, tree().node(tree().indexOf(cursor)).role());
        assertTrue(perform(overlay.id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        assertFalse(picker.isOpen(), "Cancel on the overlay closes the calendar");
        frame();
        field = nodesOf(Accessible.Role.GROUP).get(0);
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND));
    }

    @Test
    void aRangePickerKeepsTheCaptionOnItsGroupAndNamesItsTwoEnds() {
        bindCaptioned(DatePicker.ofRange(), "Estadia");
        List<AccessibleNode> groups = nodesOf(Accessible.Role.GROUP);
        assertEquals(3, groups.size(), "the picker's group and its two fields " + describe(tree()));
        AccessibleNode group = groups.get(0);
        assertEquals("Estadia", group.name(), "the caption stays on the group");
        assertFalse(group.has(Accessible.State.FOCUSABLE));
        assertNull(group.expand(), "which carries no expand state of its own");
        assertNull(group.actions());
        List<AccessibleNode> ends = childrenOf(group).stream()
                .filter(child -> child.role() == Accessible.Role.GROUP).toList();
        assertEquals("Data inicial", ends.get(0).name());
        assertEquals("Data final", ends.get(1).name());
        for (AccessibleNode end : ends) {
            assertTrue(end.has(Accessible.State.HAS_POPUP), end.name());
            assertTrue(offers(end, Accessible.Action.EXPAND), end.name());
        }
    }
}
