package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ExpandFacet;
import limn.accessibility.ValueFacet;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the field of a {@link ComboBox} becomes in the accessible tree: a combo box that says
 * whether its list is down, which of its items it is showing, and how to change that.
 *
 * <p>The list itself is not here. It is drawn by the panel and described by it, and the layer that
 * holds the keyboard while it is open is described by that layer; {@code
 * ComboBoxPopupAccessibilityTest} and {@code ComboBoxScenePopupAccessibilityTest} are those two
 * steps. What is left, and is this file's, is the control an application actually puts in a form.
 *
 * <p>Two of ADR 039 §7's claims about this row are corrected here, each with a case. The row asks
 * for a value facet whose text is the selected item and says nothing about a number, and a text
 * without a number is dropped by the publish step in silence and would raise no event if it were
 * not — so the index is published as the number, which is also what makes {@code SET_VALUE} mean
 * anything. And the row's own placement of the options "not here" is only half the story in the
 * scene presentation, where the field publishes while the list is open <em>without</em> {@code
 * ENABLED} and cannot be collapsed through its own verb: the layer above it owns the input, and
 * that is asserted rather than discovered by a bridge.
 *
 * <p>Every case that opens the list asks for {@link DisplayMode#IN_SCENE} first. {@link StubWindow}
 * reports that it can place a window, and the native presentation would put the panel in a second
 * {@link Scene} in a window this harness cannot create at all — {@code StubWindow.backend()} throws
 * by design. So the native mounting's tree is lab work, and the one difference it makes to this
 * file is named where it matters: there the field keeps the focus and the input, and its {@code
 * COLLAPSE} lands.
 */
class ComboBoxAccessibilityTest extends AccessibleComponentTestBase {

    /** Three items that follow the UI language, for the case that moves the language. */
    private static final I18nString ONE = new I18nString("comboField.one", "One");
    private static final I18nString TWO = new I18nString("comboField.two", "Two");
    private static final I18nString THREE = new I18nString("comboField.three", "Three");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The only bundle that answers these keys; anything else falls through to the English. */
    private static final StringBundle ITEMS = (key, locale) -> {
        if (!BRAZILIAN.equals(locale)) {
            return null;
        }
        return switch (key) {
            case "comboField.one" -> "Um";
            case "comboField.two" -> "Dois";
            case "comboField.three" -> "Três";
            default -> null;
        };
    };

    private ComboBox combo;

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(ITEMS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** A combo over three literal items, in a column so the field keeps its own height. */
    private void bindCombo() {
        bindCombo(new ComboBox(List.of("One", "Two", "Three")));
    }

    /**
     * Binds {@code box} as the only control in a column, so that the field keeps its own height and
     * the list has room to drop below it, and measured by the ruler, so that the boxes in the tree
     * are the ones a laid-out field has.
     *
     * @param box the combo under test
     */
    private void bindCombo(ComboBox box) {
        combo = box;
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(combo);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /** @return the field's node, which is the one and only combo box in the tree */
    private AccessibleNode field() {
        return node(Accessible.Role.COMBO_BOX);
    }

    /** @return the node of the layer an in-scene list puts over the scene */
    private AccessibleNode overlay() {
        return node(ComponentStrings.COMBO_POPUP.get());
    }

    /** Opens the list and renders the frame that publishes it. */
    private void openList() {
        combo.open();
        frame();
        assertTrue(combo.isInSceneForTest(),
                "the native presentation puts the panel in a window this harness cannot create");
    }

    /**
     * @param type the kind to look for
     * @return the first event of that kind since the last clear, or {@code null}
     */
    private AccessibleEvent firstEvent(AccessibleEvent.Type type) {
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == type) {
                return event;
            }
        }
        return null;
    }

    // --------------------------------------------------------------------------- the shut shape

    @Test
    void aShutComboIsOneNodeThatSaysItHasAListAndWhichItemItShows() {
        bindCombo();

        AccessibleNode node = field();
        assertEquals(Accessible.Role.COMBO_BOX, node.role());
        assertTrue(node.has(Accessible.State.HAS_POPUP),
                "the list exists whether or not it is down, and this is what a reader says so "
                        + "with before anything has happened" + describe(tree()));
        ExpandFacet expand = node.expand();
        assertNotNull(expand, "a combo that could not open would not be a combo" + describe(tree()));
        assertFalse(expand.expanded());
        assertTrue(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED));

        for (int i = 0; i < tree().nodeCount(); i++) {
            assertFalse(tree().node(i).role() == Accessible.Role.LIST,
                    "a shut combo publishes no list: the options are the panel's, and there is no "
                            + "panel" + describe(tree()));
        }
        assertEquals(combo.localToSceneX(), node.x(), 0.01f, describe(tree()));
        assertEquals(combo.localToSceneY(), node.y(), 0.01f);
        assertEquals(combo.width(), node.width(), 0.01f);
        assertEquals(combo.height(), node.height(), 0.01f);
    }

    @Test
    void theNumberIsTheIndexAndTheTextIsTheItem() {
        bindCombo();
        combo.setSelectedIndex(1);
        frame();

        ValueFacet value = field().value();
        assertNotNull(value,
                "the survey asks for a value facet whose text is the selected item, and the "
                        + "publish step builds no facet at all for a node that declared no number: "
                        + "the text alone is dropped in silence" + describe(tree()));
        assertEquals(1, value.value(), "the index is the only ordered thing about a selection");
        assertEquals(0, value.min());
        assertEquals(2, value.max(), "zero-based, so the last item is the count less one");
        assertEquals(1, value.step());
        assertEquals("Two", value.text(), "and the text is what the field is showing");
    }

    @Test
    void theFieldOffersTheVerbThatIsNotWhatItIsAlreadyDoing() {
        bindCombo();

        assertTrue(field().actions().has(Accessible.Action.EXPAND), describe(tree()));
        assertFalse(field().actions().has(Accessible.Action.COLLAPSE),
                "collapsing a list that is not down is not a thing a reader may offer");
        assertFalse(field().actions().has(Accessible.Action.PRESS),
                "what a press on a combo means is exactly the ambiguity EXPAND and COLLAPSE "
                        + "remove, and a platform whose only activation verb is a press has the "
                        + "expand facet to route through" + describe(tree()));
        assertTrue(field().actions().has(Accessible.Action.FOCUS),
                "free, because the field is focusable");

        openList();

        assertTrue(field().expand().expanded(), describe(tree()));
        assertNull(field().actions(),
                "down in the scene, the list's own layer owns the input and the scene refuses "
                        + "every verb on the field beneath it, so the field publishes none, "
                        + "COLLAPSE included (ADR 039 §1.13, amended 2026-09-15). In a window of "
                        + "its own the field keeps the input and publishes COLLAPSE, which is the "
                        + "lab's mounting" + describe(tree()));
        assertFalse(field().accepts(Accessible.Action.SET_VALUE),
                "and no SET_VALUE either: the field is not ENABLED beneath the layer"
                        + describe(tree()));
        assertFalse(field().value().readOnly(),
                "while its value keeps the writability it has (fix round 2e)" + describe(tree()));
    }

    // ---------------------------------------------------------------------------------- the name

    @Test
    void theFieldNamesItselfFromTheItemItIsShowing() {
        bindCombo();

        assertEquals("One", field().name(),
                "a combo has no caption of its own, and a focusable node with no name at all is "
                        + "what §12.1's gallery test refuses. Naming it from the item costs a "
                        + "reader the same word twice where a platform maps name and value to "
                        + "different attributes; that is the accepted price of a default no "
                        + "application has to remember" + describe(tree()));
        assertEquals("One", field().value().text(),
                "and the item is still published as the value it also is");
    }

    @Test
    void anApplicationsNameAndATooltipEachReachTheNode() {
        bindCombo();

        combo.setTooltip("Theme");
        frame();
        assertEquals("One", field().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, field().nameFrom(),
                "the field already named itself from its item, so the tooltip does not take the "
                        + "name slot the way it does on a widget that supplied none");
        assertEquals("Theme", field().description(),
                "it becomes the description instead, which is the walk's own rule");

        combo.setAccessibleName("Colour theme");
        frame();
        assertEquals("Colour theme", field().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, field().nameFrom(),
                "an application's name always wins over one derived");
        assertEquals("Theme", field().description(),
                "and the tooltip becomes the description rather than being lost");
    }

    // ----------------------------------------------------------------- the selection, and events

    @Test
    void aSelectionMadeThroughThePublicSetterReachesAReader() {
        bindCombo();

        combo.setSelectedIndex(2);
        frame();

        AccessibleEvent event = firstEvent(AccessibleEvent.Type.VALUE_CHANGED);
        assertNotNull(event,
                "the difference raises this off the number and never off the text, so a combo "
                        + "publishing a constant number would say nothing on the one channel a "
                        + "reader listens to: " + bridge.events + describe(tree()));
        assertEquals(field().id(), event.nodeId());
        assertEquals(0.0, event.oldValue());
        assertEquals(2.0, event.newValue());
        assertEquals("Three", field().value().text());
    }

    @Test
    void theItemFollowsTheLanguageWithoutTheFieldFormattingAnything() {
        I18n.addBundle(ITEMS);
        I18n.setLocale(Locale.ENGLISH);
        bindCombo(ComboBox.localized(List.of(ONE, TWO, THREE)));
        combo.setSelectedIndex(1);
        frame();
        assertEquals("Two", field().value().text());

        combo.setLocale(BRAZILIAN);
        frame();

        assertEquals("Dois", field().value().text(),
                "the item is the model's own string, resolved under the field's language rather "
                        + "than the process one" + describe(tree()));
        assertEquals(BRAZILIAN, field().locale(), describe(tree()));
    }

    // -------------------------------------------------------------------------------- expanding

    @Test
    void expandOpensTheListThroughTheWidgetsOwnPath() throws Exception {
        bindCombo();
        long id = field().id();

        assertTrue(perform(id, Accessible.Action.EXPAND, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertTrue(combo.isOpen(), "the same path Space, Enter and Down take");
        assertTrue(field().expand().expanded(), describe(tree()));
        assertNotNull(node(Accessible.Role.LIST), "and the list is in the tree" + describe(tree()));
    }

    @Test
    void expandingAListThatIsAlreadyDownDoesNothingTwice() throws Exception {
        bindCombo();
        openList();
        int published = bridge.published.size();
        bridge.events.clear();

        perform(field().id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();

        assertTrue(combo.isOpen(), "and it was not closed and opened again either");
        assertEquals(published, bridge.published.size(),
                "the hook refuses what it is already doing, and refusing costs a frame that "
                        + "changes nothing: a second open() would have torn the list down and "
                        + "built another one, with new identifiers for every option"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), "and nothing to report: " + bridge.events);
    }

    @Test
    void collapseIsRefusedWhileTheListsOwnLayerOwnsTheInput() throws Exception {
        bindCombo();
        openList();

        AccessibleNode node = field();
        assertTrue(node.expand().expanded());
        assertFalse(node.has(Accessible.State.ENABLED),
                "the modal rule applied to a control shadowed by its own dropdown: it stays in "
                        + "the tree and stays showing, and it is not operable" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the set published focusable is the set the keyboard reaches, and while the list "
                        + "is down the keyboard reaches the layer above" + describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), "it is still on screen");
        assertNull(node.actions(),
                "and it publishes no verb, because the platform is answered from the snapshot "
                        + "and a COLLAPSE published here would be reported accepted and dropped"
                        + describe(tree()));

        perform(node.id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        frame();

        assertTrue(combo.isOpen(),
                "the host accepts and posts, and its own reachability test then refuses it: this "
                        + "field is behind the layer that owns input" + describe(tree()));

        assertTrue(perform(overlay().id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        frame();
        assertFalse(combo.isOpen(),
                "the dismissal that lands is the overlay's, which is where Esc goes too. In a "
                        + "window of its own the field keeps the input and its own COLLAPSE is "
                        + "the path; that mounting is the lab's");
    }

    // ------------------------------------------------------------------------ setting the value

    @Test
    void settingTheValueByNumberPicksThatItemAndNotifiesOnce() throws Exception {
        AtomicInteger fired = new AtomicInteger(-1);
        AtomicInteger calls = new AtomicInteger();
        bindCombo();
        combo.onSelect(index -> {
            fired.set(index);
            calls.incrementAndGet();
        });

        assertTrue(perform(field().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(2)));
        frame();

        assertEquals(2, combo.selectedIndex());
        assertEquals(2, fired.get(), "which the public setter does too, and this is the proof "
                + "that the hook goes through it rather than round it");
        assertEquals(1, calls.get());
        assertEquals("Three", field().value().text(), describe(tree()));
    }

    @Test
    void anIndexNothingAnswersToIsRefusedAndNeverClamped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        bindCombo();
        combo.setSelectedIndex(1);
        combo.onSelect(index -> calls.incrementAndGet());

        perform(field().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(9));
        perform(field().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-1));
        perform(field().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.NaN));
        frame();

        assertEquals(1, combo.selectedIndex(),
                "clamping would move the selection to a neighbour the client did not ask for, "
                        + "and rounding a NaN would land on the first item");
        assertEquals(0, calls.get());
    }

    @Test
    void settingTheValueByTextTakesTheWholeItemAndNotAPrefix() throws Exception {
        bindCombo();

        assertTrue(perform(field().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfText("Two")));
        frame();
        assertEquals(1, combo.selectedIndex(), describe(tree()));

        perform(field().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("Tw"));
        frame();

        assertEquals(1, combo.selectedIndex(),
                "the type-ahead matches a prefix, case-insensitively, which is what a user "
                        + "typing into an open list wants and is the wrong answer for a client "
                        + "that was handed a value and is asking for it back");
    }

    @Test
    void settingTheValueWhileTheListIsDownCommitsAndCloses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        bindCombo();
        combo.onSelect(index -> calls.incrementAndGet());
        openList();

        // The field is shadowed by its own list, so this is asked of the node the panel is under.
        // It reaches the field's hook all the same, because the identifier is the field's and the
        // reachability test is the host's: see the case above for the half that is refused.
        assertTrue(perform(overlay().id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        frame();
        assertTrue(perform(field().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(2)));
        frame();

        assertEquals(2, combo.selectedIndex());
        assertFalse(combo.isOpen());
        assertEquals(1, calls.get(), "the application hears it exactly once");
    }

    @Test
    void aVerbTheFieldDoesNotOfferDoesNothing() throws Exception {
        bindCombo();

        perform(field().id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        perform(field().id(), Accessible.Action.SET_TEXT, new Accessible.Argument.OfText("Two"));
        frame();

        assertEquals(0, combo.selectedIndex(),
                "the identifier resolves, so the host accepts and posts; the hook refuses both. "
                        + "SET_TEXT belongs to a text facet this node does not have");
        assertFalse(combo.isOpen());
    }

    // ----------------------------------------------------------------------------- disabled

    @Test
    void aDisabledComboSaysSoAndCannotBeOpened() throws Exception {
        bindCombo();
        combo.setEnabled(false);
        frame();

        AccessibleNode node = field();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the keyboard does not reach a disabled control, and the tree agrees with it");
        assertTrue(node.has(Accessible.State.HAS_POPUP), "it is still a combo");

        perform(node.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();

        assertFalse(combo.isOpen(),
                "the scene's own gate walks the widget and every ancestor for isEnabled(), which "
                        + "is why this hook carries no guard of its own");
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietFieldAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindCombo();
        combo.setSelectedIndex(1);
        // The hover and focus fades are transitions; a measurement taken while one is mid-flight
        // is a measurement of the animation and not of this hook.
        for (int i = 0; i < 200; i++) {
            combo.invalidate();
            frame();
        }
        int published = bridge.published.size();
        bridge.events.clear();

        // A value text built with a format call, a name read out of the model on every pass, or
        // the variable-argument action call is a string or an array per damaged frame spent
        // concluding that nothing moved, and this is the only place any of them is visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            combo.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            combo.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a field that did not move must cost no memory: the item's text comes "
                        + "out of the model string's own memo and everything else is a primitive");
    }

    // ------------------------------------------------------------------- nothing else is invented

    @Test
    void theFieldCarriesNoFacetItHasNoBusinessWith() {
        bindCombo();

        AccessibleNode node = field();
        assertNull(node.selection(),
                "the options belong to the panel that draws them, and a combo declaring a "
                        + "selection here would have two containers in one tree claiming the same "
                        + "one" + describe(tree()));
        assertNull(node.selectionItem());
        assertNull(node.text(), "a combo is not editable: it picks from a list");
        assertNull(node.toggle());
        assertNull(node.scroll());
        assertNull(node.window());
    }
}
