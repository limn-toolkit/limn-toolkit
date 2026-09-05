package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.SelectionFacet;
import limn.i18n.I18n;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the layer an in-scene dropdown is drawn on becomes in the accessible tree, which is a node
 * and not the nothing the survey expected.
 *
 * <p>ADR 039 §7 calls this widget "the overlay wrapper" and settles it as transparent, and §1.6
 * lists it among what the transparency predicate deletes "without a line of accessibility code".
 * Both are wrong about this widget, and the code says so twice. The overlay's constructor makes it
 * focusable, and {@code pushOverlay} runs a focus traverse over itself and finds nothing else
 * focusable inside it — so for the whole life of an open list this layer is the scene's focused
 * widget and its only tab stop. It is also declared modal by the walk, before the predicate runs,
 * for being a parentless top overlay. Either fact alone keeps it in the tree.
 *
 * <p>So the question is not whether it is published but what it says, and left alone it said the
 * worst possible thing: role {@code UNKNOWN}, no name, focused, modal, with the whole scene as its
 * box — a focusable control a reader can land on, cannot name and cannot use, which is exactly the
 * defect §1.6 says {@code limn.components} may never produce.
 *
 * <p>The other half of what the tree owes here is the arrow keys. Focus never moves while the list
 * is open — the highlight does — so the only way an assistive technology can follow Up, Down, Home,
 * End or type-ahead is an active descendant on the focused node, which is this one. The facet that
 * carries it is declared here and is inert until {@code PopupPanel}'s own pipeline step marks its
 * highlighted row; the case below pins it in that state and says so, rather than leaving the
 * dependency unwritten.
 *
 * <p>Everything drives the combo's public API and the scene, or stands where a bridge stands.
 * Nothing constructs a node.
 */
class ComboBoxScenePopupAccessibilityTest extends AccessibleComponentTestBase {

    /**
     * What the toolkit calls the layer, resolved under one language the way the tree resolves it.
     *
     * <p>Never a literal: the name is an {@code I18nString} the toolkit holds and the tree
     * publishes it already resolved, under the node's own locale, so a test written against the
     * English word passes only on a machine whose language happens to be English.
     *
     * @param locale the language to resolve under
     * @return the resolved name
     */
    private static String optionsIn(Locale locale) {
        Locale enclosing = I18n.pushScope(locale);
        try {
            return ComponentStrings.COMBO_POPUP.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** The same, under the language a scene that declares none resolves to. */
    private static String options() {
        return optionsIn(I18n.processLocale());
    }

    private ComboBox combo;

    /**
     * A combo in a column, so that the field keeps its own height and the list has room to drop
     * below it, and drawn in scene, which is the presentation this widget exists for.
     */
    private void bindCombo() {
        combo = new ComboBox(List.of("One", "Two", "Three"));
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(combo);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /** Opens the list and renders the frame that publishes it. */
    private void openList() {
        combo.open();
        frame();
        assertTrue(combo.isInSceneForTest(), "this file is about the in-scene presentation");
    }

    /** The overlay's node, found by the name only the toolkit can give it. */
    private AccessibleNode overlay() {
        return node(options());
    }

    /** @return whether any node in the published tree carries the overlay's name */
    private boolean overlayIsPublished() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).name().equals(options())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- the shape of the node

    @Test
    void theOverlayIsANodeThatSaysWhatItIsAndNamesItself() {
        bindCombo();
        openList();

        AccessibleNode node = overlay();
        assertEquals(Accessible.Role.GROUP, node.role(),
                "not LIST -- the panel below is the list and has the list's rectangle; not "
                        + "WINDOW, which the tree's own root already is" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the control's own name, not a description of it");
        assertEquals(0, node.x(), "the layer that captures every press is the whole scene, and "
                + "the box is its own layout box" + describe(tree()));
        assertEquals(0, node.y());
        assertEquals(tree().sceneWidth(), node.width(), 0.01f);
        assertEquals(tree().sceneHeight(), node.height(), 0.01f);
        assertEquals(0, node.parent(), "an overlay hangs under the window node");

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode each = tree().node(i);
            assertNotSame(Accessible.Role.UNKNOWN, each.role(),
                    "a focusable node with no declared role is published UNKNOWN with a warning "
                            + "nobody reads in CI" + describe(tree()));
            if (each.has(Accessible.State.FOCUSABLE)) {
                assertFalse(each.name().isEmpty(),
                        "every focusable node is named" + describe(tree()));
            }
        }
    }

    @Test
    void theOverlayIsTheFocusedNodeAndTheOnlyTabStop() {
        bindCombo();
        openList();

        AccessibleNode node = overlay();
        assertTrue(node.has(Accessible.State.FOCUSED), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE));
        assertEquals(node.id(), tree().focused(), "and the tree agrees about which node that is");
        assertEquals(List.of(node.id()),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::id).toList(),
                "the set published focusable is exactly the set the keyboard reaches, and the "
                        + "keyboard reaches this one widget: deleting the node as scaffolding "
                        + "would leave that set empty" + describe(tree()));
        assertNotSame(combo, scene.focusedWidget(),
                "pushOverlay moved the focus off the field and onto the layer");
    }

    @Test
    void modalIsPublishedOnceAndNothingDeclaresItTwice() {
        bindCombo();
        openList();

        assertEquals(List.of(overlay().id()),
                nodesWith(Accessible.State.MODAL).stream().map(AccessibleNode::id).toList(),
                "the walk declares MODAL for a parentless top overlay, so the hook must not "
                        + "declare a second setter for one fact" + describe(tree()));
    }

    @Test
    void theOverlayOffersOneVerbAndItIsTheDismissal() {
        bindCombo();
        openList();

        assertTrue(overlay().actions().has(Accessible.Action.CANCEL),
                "Esc dismisses the list, and CANCEL is the verb for dismissing a node");
        assertTrue(overlay().actions().has(Accessible.Action.FOCUS),
                "free, because the widget is focusable");
        assertTrue(overlay().actions().has(Accessible.Action.SCROLL_INTO_VIEW));
        assertFalse(overlay().actions().has(Accessible.Action.PRESS),
                "a press on this layer is the click-outside dismissal, which CANCEL already "
                        + "names; two verbs for one behaviour is a choice a reader has to make "
                        + "and cannot" + describe(tree()));
        assertFalse(overlay().actions().has(Accessible.Action.EXPAND));
        assertFalse(overlay().actions().has(Accessible.Action.INCREMENT));
        assertFalse(overlay().actions().has(Accessible.Action.DECREMENT));
    }

    // --------------------------------------------------------------------------- the selection

    @Test
    void theOverlayDeclaresTheContainerTheArrowKeysMoveInside() {
        bindCombo();
        openList();

        SelectionFacet selection = overlay().selection();
        assertNotNull(selection,
                "focus stays on this node while the highlight moves, so this is the only node "
                        + "whose active descendant a reader will read" + describe(tree()));
        assertFalse(selection.multiSelectable(), "one row at a time");
        assertTrue(selection.required(),
                "a combo refuses an empty item list, so there is always exactly one selection and "
                        + "nothing to clear to; the panel's facet says the same over these very "
                        + "members, and this is the node a bridge reads because it is the focused "
                        + "one" + describe(tree()));
        // The facet declares the container; which of its descendants the cursor is on is the
        // first node in its subtree published ACTIVE, and the panel's own step is what marks
        // that row. It is the HIGHLIGHTED option and not the selected one, which in a combo are
        // separate fields moved by separate paths: the highlight moves under the arrows and the
        // selection moves only on commit. open() sets the highlight to the selection, so on this
        // first frame they name the same row and the assertion below is written as the highlight
        // deliberately. What the highlight does when it moves is
        // limn.components.ComboBoxPopupAccessibilityTest's, at the node that carries it.
        assertEquals(optionAt(combo.highlightedIndex()).id(), selection.activeDescendant(),
                "the layer that holds the keyboard is where a reader reads the cursor from"
                        + describe(tree()));
    }

    /**
     * @return the node the tree's header names as holding the focus, which while a list is open is
     *         the overlay: it is the scene's focused widget and its only tab stop
     */
    private AccessibleNode focusedNode() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).id() == tree().focused()) {
                return tree().node(i);
            }
        }
        throw new AssertionError("the tree names no focused node" + describe(tree()));
    }

    /**
     * @param index the option's model index
     * @return the node the panel published for it
     */
    private AccessibleNode optionAt(int index) {
        return childrenOf(node(Accessible.Role.LIST)).get(index);
    }

    // ----------------------------------------------------------------------------- the actions

    @Test
    void cancelDismissesTheListAndChangesNoSelection() throws Exception {
        bindCombo();
        combo.setSelectedIndex(1);
        openList();
        long id = overlay().id();

        assertTrue(perform(id, Accessible.Action.CANCEL, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertFalse(combo.isOpen(), "the same path Esc takes");
        assertEquals(1, combo.selectedIndex(),
                "a dismissal that committed the highlight would change the user's selection "
                        + "behind them");
    }

    @Test
    void averbTheNodeDoesNotOfferDoesNothing() throws Exception {
        bindCombo();
        openList();

        perform(overlay().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertTrue(combo.isOpen(),
                "the identifier resolves, so the host accepts and posts; the hook refuses it");
        assertTrue(overlayIsPublished(), describe(tree()));
    }

    // ------------------------------------------------------------------ opening and closing

    @Test
    void nothingOfTheListIsPublishedWhileItIsShut() {
        bindCombo();

        assertFalse(overlayIsPublished(), "a closed list has no layer" + describe(tree()));

        openList();
        assertTrue(overlayIsPublished());

        combo.close();
        // close() removes the overlay on the last frame of a wall-clock fade, so the frames are
        // driven until it goes rather than asserted straight after the call, which would be a
        // test that passed or failed on how loaded the machine was.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (overlayIsPublished() && System.nanoTime() < deadline) {
            frame();
        }

        assertFalse(overlayIsPublished(), "the fade ends with the overlay gone" + describe(tree()));
        assertEquals(1, nodesWith(Accessible.State.FOCUSABLE).size(),
                "and the field is the tab stop again" + describe(tree()));
    }

    // -------------------------------------------------------------------- language and relation

    @Test
    void theOverlayResolvesTheFieldsLanguage() {
        bindCombo();
        Locale hebrew = Locale.forLanguageTag("he");
        combo.setLocale(hebrew);
        openList();

        // The focused node, and not the last one published: the panel's own step put the options
        // after this one, and an index into the tree is not an identity.
        AccessibleNode node = focusedNode();
        assertEquals(hebrew, node.locale(),
                "the overlay is parentless and reaches the field through its host link; without "
                        + "that link every name under an in-scene list resolves in the process "
                        + "language" + describe(tree()));
        assertEquals(optionsIn(hebrew), node.name(),
                "and the name is resolved under that language, not under the process one"
                        + describe(tree()));
    }

    @Test
    void theListAndTheFieldNameEachOther() {
        bindCombo();
        // An application-set name, because the field's own description is a later step in this
        // pipeline: until it lands the field declares nothing and the transparency predicate
        // deletes it while the list is open, which is exactly the case a relation may not name.
        combo.setAccessibleName("Colour");
        openList();

        AccessibleNode field = node("Colour");
        AccessibleNode list = overlay();
        assertEquals(field.id(), targetOf(list, Accessible.Relation.POPUP_FOR),
                "the popup's root names the control that opened it" + describe(tree()));
        assertEquals(list.id(), targetOf(field, Accessible.Relation.CONTROLLER_FOR),
                "and the walk emits the mirror, so a client walking either way finds the other. "
                        + "Both halves stand on the overlay carrying the host link: the panel's "
                        + "own is ignored, because the panel has a parent" + describe(tree()));
    }

    /**
     * @param node the node to read
     * @param kind the relation to look for
     * @return the identifier it resolves to, or {@code 0} when the node has no such relation
     */
    private long targetOf(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietFrameWithTheListOpenAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindCombo();
        openList();
        int published = bridge.published.size();
        bridge.events.clear();

        // The list fades in, so the overlay is damaged on every frame the fade lasts and each of
        // those frames walks the tree to conclude that nothing moved. A name formatted in the
        // hook, or the variable-argument action call, is invisible anywhere but here.
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

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // Against the same frame with nothing listening rather than against zero: a headless
        // frame has a floor that has nothing to do with this widget.

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing an open list that did not move must cost no memory at all: the name "
                        + "is a constant compared by reference and everything else is a flag");
    }
}
