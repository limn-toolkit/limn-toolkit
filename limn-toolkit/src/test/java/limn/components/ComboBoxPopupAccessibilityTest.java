package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ScrollFacet;
import limn.accessibility.SelectionFacet;
import limn.accessibility.SelectionItemFacet;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the panel a {@link ComboBox} draws its options on becomes in the accessible tree: the list,
 * and one node per option.
 *
 * <p>The rows are painted and not widgets, so they are synthetic children of the panel — and this
 * is the first component in the toolkit to have any, which is why it is also the component that
 * found the hole underneath them. The walk applied the inherited enabled, visible and showing bits
 * to a widget's own node only, and a widget cannot fill those in for itself by design, so every
 * synthetic child in the toolkit would have published as a disabled, invisible, off-screen element
 * on all three platforms. That is a seam in {@code limn.scene} and {@code limn.accessibility} and
 * not in this widget, and two of the cases below are what pin it here, against the widget that
 * needed it.
 *
 * <p>Three of ADR 039 §7's claims about this row are corrected by the code, and each has a case:
 * the row omits the {@code ScrollFacet} that a list clamped to its scene plainly needs; it puts the
 * active descendant nowhere, when in this widget the cursor is the <em>highlight</em> and the
 * selection is a different field moved by a different path; and it promises one node per option
 * without saying what a scrolled-away one publishes.
 *
 * <p>Everything drives the combo's public API or the scene's own input entry points, and reads the
 * tree a bridge was handed. Nothing constructs a node, and nothing reaches into the panel: the
 * boxes below are asserted by clicking them.
 *
 * <p>The list is always asked for {@link DisplayMode#IN_SCENE}, because
 * {@link StubWindow} reports that it can place a window and the native presentation puts the panel
 * in a second {@code Scene} this harness cannot reach. That mounting's tree is verified in the lab.
 */
class ComboBoxPopupAccessibilityTest extends AccessibleComponentTestBase {

    /** Three options that follow the UI language, so a name can be watched across a locale move. */
    private static final I18nString ONE = new I18nString("combo.one", "One");
    private static final I18nString TWO = new I18nString("combo.two", "Two");
    private static final I18nString THREE = new I18nString("combo.three", "Three");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The only bundle that answers these keys; anything else falls through to the English. */
    private static final StringBundle OPTIONS = (key, locale) -> {
        if (!BRAZILIAN.equals(locale)) {
            return null;
        }
        return switch (key) {
            case "combo.one" -> "Um";
            case "combo.two" -> "Dois";
            case "combo.three" -> "Três";
            default -> null;
        };
    };

    private ComboBox combo;

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(OPTIONS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A combo in a column, drawn in scene, with {@code count} items named "Item 0" upwards.
     *
     * @param count how many options
     */
    private void bindCombo(int count) {
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = "Item " + i;
        }
        bindCombo(new ComboBox(List.of(labels)));
    }

    /**
     * Binds {@code box} as the only control in a scene that asks for the in-scene presentation.
     *
     * @param box the combo under test
     */
    private void bindCombo(ComboBox box) {
        box.setDisplayMode(DisplayMode.IN_SCENE);
        bindCombo(box, new StubWindow());
    }

    /**
     * Binds {@code box} to {@code into}, with the ruler installed before the first frame.
     *
     * <p>Not the base's {@code bind}, and the difference is load-bearing: that one renders its
     * first frame before a test can install a ruler, so the combo measures under the default one
     * and every box in the tree is that measurement until something invalidates the layout. Two
     * cases here compare boxes across a property change, and both would be comparing a stale
     * width with a fresh one.
     *
     * @param box  the combo under test
     * @param into the window to bind to
     */
    private void bindCombo(ComboBox box, StubWindow into) {
        bindCombo(box, into, System::nanoTime);
    }

    /**
     * {@link #bindCombo(ComboBox, StubWindow)} on a clock the test owns, for the one case that
     * has to hold time still.
     */
    private void bindCombo(ComboBox box, StubWindow into, LongSupplier clock) {
        combo = box;
        Column root = new Column();
        root.add(combo);
        bridge = new RecordingBridge();
        window = into;
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root, clock);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** Opens the list and renders the frame that publishes it. */
    private void openList() {
        combo.open();
        frame();
        assertTrue(combo.isInSceneForTest(),
                "the native presentation puts the panel in a scene this harness cannot reach");
    }

    /** @return the panel's node, which is the one and only list in the tree */
    private AccessibleNode list() {
        return node(Accessible.Role.LIST);
    }

    /**
     * @return the option nodes, in tree order, which is model order. The list's children are not
     *         only options: when the panel overflows, its real scroll bar widget is walked after
     *         the synthetic rows and publishes a node of its own beside them.
     */
    private List<AccessibleNode> options() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(list())) {
            if (child.role() == Accessible.Role.LIST_ITEM) {
                found.add(child);
            }
        }
        return found;
    }

    /** @return the scroll-bar nodes among the list's children, in tree order */
    private List<AccessibleNode> scrollBars() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(list())) {
            if (child.role() == Accessible.Role.SCROLL_BAR) {
                found.add(child);
            }
        }
        return found;
    }

    /**
     * @param key the raw key code
     */
    private void key(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
        frame();
    }

    // ------------------------------------------------------------------------- the list is a node

    @Test
    void theListIsOneNodeWithOneOptionPerItem() {
        bindCombo(3);
        openList();

        AccessibleNode list = list();
        SelectionFacet selection = list.selection();
        assertNotNull(selection, "the container the options belong to" + describe(tree()));
        assertFalse(selection.multiSelectable(), "a combo picks one");
        assertTrue(selection.required(),
                "the widget's own invariant: it refuses an empty item list, so there is always "
                        + "exactly one selection and nothing to clear to");

        List<AccessibleNode> options = options();
        assertEquals(3, options.size(), describe(tree()));
        for (AccessibleNode option : options) {
            assertEquals(Accessible.Role.LIST_ITEM, option.role(), describe(tree()));
        }
        assertEquals(List.of("Item 0", "Item 1", "Item 2"),
                options.stream().map(AccessibleNode::name).toList(),
                "in model order, which is the order they are drawn in" + describe(tree()));
        assertEquals(3, childrenOf(list()).size(),
                "three options and nothing else: the panel's scroll bar has nothing to scroll "
                        + "and publishes no node over content that fits" + describe(tree()));
        assertEquals(List.of(), scrollBars(), describe(tree()));
    }

    @Test
    void namesComeFromTheModelAndFollowTheLanguage() {
        I18n.addBundle(OPTIONS);
        I18n.setLocale(Locale.ENGLISH);
        bindCombo(ComboBox.localized(List.of(ONE, TWO, THREE)));
        openList();

        assertEquals(List.of("One", "Two", "Three"),
                options().stream().map(AccessibleNode::name).toList());
        for (AccessibleNode option : options()) {
            assertEquals(Accessible.NameFrom.CONTENT, option.nameFrom(),
                    "an option's name is its own text and not a label put on it");
        }

        combo.setLocale(BRAZILIAN);
        frame();
        assertEquals(List.of("Um", "Dois", "Três"),
                options().stream().map(AccessibleNode::name).toList(),
                "the panel resolves the field's language through the host link" + describe(tree()));
    }

    // ----------------------------------------------------------------- selection and the cursor

    @Test
    void exactlyOneOptionIsSelectedAndEveryOneKnowsWhereItIs() {
        bindCombo(4);
        combo.setSelectedIndex(2);
        openList();

        List<AccessibleNode> options = options();
        for (int i = 0; i < options.size(); i++) {
            SelectionItemFacet item = options.get(i).selectionItem();
            assertNotNull(item, "every option is a member of the selection" + describe(tree()));
            assertEquals(i + 1, item.positionInSet(), "one-based, and the model's own number");
            assertEquals(4, item.sizeOfSet());
            assertEquals(i == 2, item.selected(), "option " + i + describe(tree()));
        }
    }

    @Test
    void aProgrammaticSelectionMovesTheBitAndSaysSo() {
        bindCombo(4);
        openList();
        bridge.events.clear();

        combo.setSelectedIndex(3);
        frame();

        List<AccessibleNode> options = options();
        assertTrue(options.get(3).selectionItem().selected(), describe(tree()));
        assertFalse(options.get(0).selectionItem().selected());
        assertTrue(bridge.countOf(AccessibleEvent.Type.STATE_CHANGED) > 0,
                "a selection a reader was not told about is a selection it does not have: "
                        + bridge.events);
    }

    @Test
    void theActiveDescendantIsTheCursorAndNotTheSelection() {
        bindCombo(4);
        combo.setSelectedIndex(1);
        openList();
        bridge.events.clear();

        key(Keys.DOWN);

        List<AccessibleNode> options = options();
        assertEquals(options.get(2).id(), list().selection().activeDescendant(),
                "the arrows move the highlight, and the highlight is what a reader follows"
                        + describe(tree()));
        assertTrue(options.get(1).selectionItem().selected(),
                "and the selection has not moved: in this widget the two are separate fields "
                        + "moved by separate paths, and only Enter commits" + describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED) > 0,
                "without this a reader can enumerate the options and never learn which one the "
                        + "user is on: " + bridge.events);
    }

    // ------------------------------------------------------------------------------- the boxes

    @Test
    void everyPublishedBoxIsTheBoxAClickLandsIn() {
        bindCombo(4);
        openList();

        List<AccessibleNode> options = options();
        for (int i = 0; i < options.size(); i++) {
            AccessibleNode option = options.get(i);
            float x = option.x() + option.width() / 2;
            float y = option.y() + option.height() / 2;
            scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
            scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
            scene.inputBatchEnded();
            assertEquals(i, combo.selectedIndex(),
                    "the centre of option " + i + "'s published box committed option "
                            + combo.selectedIndex() + ". The published box has to be the box the "
                            + "hit test answers, which is the whole row and not the painted "
                            + "highlight's inset band");
            openList();
        }
    }

    @Test
    void directionDoesNotMoveTheRows() {
        bindCombo(4);
        openList();
        List<String> before = boxesOf(options());
        List<String> namesBefore = options().stream().map(AccessibleNode::name).toList();

        combo.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(before, boxesOf(options()),
                "the marker column and the label mirror; the row band does not, and a box "
                        + "computed from rowTextX would move here" + describe(tree()));
        assertEquals(namesBefore, options().stream().map(AccessibleNode::name).toList(),
                "and reading order is model order in both directions");
    }

    /**
     * @param nodes the nodes to render
     * @return one string per node's rectangle, for a comparison whose failure message is readable
     */
    private static List<String> boxesOf(List<AccessibleNode> nodes) {
        List<String> boxes = new ArrayList<>();
        for (AccessibleNode node : nodes) {
            boxes.add(node.x() + "," + node.y() + " " + node.width() + "x" + node.height());
        }
        return boxes;
    }

    // ------------------------------------------------------------------------------ scrolling

    @Test
    void aClampedListScrollsAndSaysSo() {
        bindCombo(20);
        openList();

        ScrollFacet scroll = list().scroll();
        assertNotNull(scroll,
                "the survey's row omits this facet and the panel plainly scrolls: a wheel, a real "
                        + "scroll bar with a real model, revealRect and the keyboard's auto-reveal "
                        + "all move it" + describe(tree()));
        assertTrue(scroll.verticallyScrollable(),
                "twenty rows do not fit a scene this tall" + describe(tree()));
        assertFalse(scroll.horizontallyScrollable());
        assertTrue(scroll.verticalViewSize() < 1, "it shows less than all of itself");
        assertEquals(0, scroll.verticalPercent(), 1e-6, "and it opens at the top");
        assertEquals(1, scrollBars().size(),
                "the panel's real scroll bar is the one child beside the twenty options, walked "
                        + "after them; it stays through its fade and goes only when the content "
                        + "fits" + describe(tree()));
        assertEquals(20, options().size(), describe(tree()));
        AccessibleNode bar = scrollBars().get(0);
        assertTrue(bar.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(0.0, bar.value().value(), "the bar's offset is the list's" + describe(tree()));
        assertTrue(bar.value().max() > 0, describe(tree()));

        key(Keys.END);

        assertTrue(scrollBars().get(0).value().value() > 0,
                "END moved the list, and the bar's own facet followed the same scroll field"
                        + describe(tree()));

        assertTrue(list().scroll().verticalPercent() > 0,
                "the end of a clamped list is not the top of it" + describe(tree()));
        AccessibleNode list = list();
        List<AccessibleNode> showing = new ArrayList<>();
        for (AccessibleNode option : options()) {
            boolean overlaps = option.y() + option.height() > list.y()
                    && option.y() < list.y() + list.height();
            assertEquals(overlaps, option.has(Accessible.State.SHOWING),
                    "an option is on screen exactly when its box meets the list's, which is the "
                            + "paint loop's own skip test read the other way round; a box computed "
                            + "without the scroll would put a reader's cursor on rows that are "
                            + "scrolled away" + describe(tree()));
            if (overlaps) {
                showing.add(option);
            }
        }
        assertFalse(showing.isEmpty(), describe(tree()));
        AccessibleNode active = options().get(19);
        assertTrue(active.has(Accessible.State.ACTIVE),
                "END moved the highlight to the last option" + describe(tree()));
        assertTrue(showing.contains(active),
                "and the highlight reveals itself, so the cursor is always on screen"
                        + describe(tree()));
    }

    // -------------------------------------------------------------- the inherited bits, and the
    // ------------------------------------------------------------- seam that had to land for them

    @Test
    void everyOptionPublishesEnabledAndVisibleLikeTheListItIsDrawnIn() {
        bindCombo(3);
        openList();

        for (AccessibleNode option : options()) {
            assertTrue(option.has(Accessible.State.ENABLED),
                    "a widget cannot set this bit itself and the walk reached only its own node, "
                            + "so before the seam every option announced as disabled"
                            + describe(tree()));
            assertTrue(option.has(Accessible.State.VISIBLE), describe(tree()));
            assertTrue(option.has(Accessible.State.SHOWING), describe(tree()));
            assertFalse(option.has(Accessible.State.FOCUSABLE),
                    "an option is drawn by the panel and is not a tab stop: the set published "
                            + "focusable has to be the set the keyboard reaches" + describe(tree()));
            assertFalse(option.has(Accessible.State.FOCUSED));
        }
    }

    @Test
    void anOptionScrolledPastTheEdgeIsPublishedAndIsNotShowing() {
        bindCombo(20);
        openList();
        key(Keys.END);

        List<AccessibleNode> options = options();
        assertEquals(20, options.size(),
                "every option is published whatever the scroll: the count and each option's "
                        + "position in it are what a reader is told, and they do not move"
                        + describe(tree()));
        assertFalse(options.get(0).has(Accessible.State.SHOWING),
                "the first option is scrolled away, and a row that is not on the screen must not "
                        + "claim to be" + describe(tree()));
        assertTrue(options.get(0).has(Accessible.State.VISIBLE),
                "scrolled away is not hidden: it is one scroll from being read");
        assertTrue(list().has(Accessible.State.SHOWING),
                "and the list itself is still on screen" + describe(tree()));
    }

    // -------------------------------------------------------------------------------- operating

    @Test
    void anOptionIsChosenThroughTheWidgetsOwnPath() throws Exception {
        AtomicInteger fired = new AtomicInteger(-1);
        AtomicInteger calls = new AtomicInteger();
        bindCombo(4);
        combo.onSelect(index -> {
            fired.set(index);
            calls.incrementAndGet();
        });
        openList();

        assertTrue(perform(options().get(2).id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE), "accepted, which is not the same as done");
        frame();

        assertEquals(2, combo.selectedIndex());
        assertEquals(2, fired.get(), "the application hears an assistive technology's pick exactly "
                + "as it hears a click, because both go through commit");
        assertEquals(1, calls.get());
        assertFalse(combo.isOpen(), "choosing an option closes the list, as a click does");
    }

    @Test
    void pressIsTheSameGestureAndRaisesInvoked() throws Exception {
        bindCombo(4);
        openList();
        long id = options().get(3).id();
        bridge.events.clear();

        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(3, combo.selectedIndex(),
                "choosing an option in a combo is one gesture, so both verbs reach commit");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "which the scene raises for PRESS only: " + bridge.events);
    }

    @Test
    void rePickingWhatIsAlreadySelectedSaysNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        bindCombo(4);
        combo.setSelectedIndex(1);
        combo.onSelect(index -> calls.incrementAndGet());
        openList();

        assertTrue(perform(options().get(1).id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE));
        frame();

        assertEquals(1, combo.selectedIndex());
        assertFalse(combo.isOpen(), "it still closes the list");
        assertEquals(0, calls.get(),
                "commit's own rule -- the listener reports the selection and the selection did "
                        + "not move -- reaching the accessibility path, which is the proof that "
                        + "the hook goes through commit rather than round it");
    }

    @Test
    void aDisabledComboRefusesToBeCommitted() throws Exception {
        bindCombo(4);
        openList();
        long id = options().get(2).id();

        combo.setEnabled(false);
        frame();
        perform(id, Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(0, combo.selectedIndex(),
                "the hook carries its own enabled guard, which is the only one there is in a "
                        + "window of its own: the scene's gate walks the panel's ancestors, and "
                        + "there the panel has none");
        assertFalse(options().get(2).has(Accessible.State.ENABLED),
                "and the row says so. The list is a parentless overlay, so the enabled axis "
                        + "reaches it through the inheritance host rather than through a parent; "
                        + "while it did not, the row advertised SELECT and PRESS on a control "
                        + "that refuses both, which is a node lying about what it will do"
                        + describe(tree()));
    }

    @Test
    void averbTheOptionsDoNotOfferDoesNothing() throws Exception {
        bindCombo(4);
        openList();

        perform(options().get(2).id(), Accessible.Action.INCREMENT, Accessible.Argument.NONE);
        frame();

        assertEquals(0, combo.selectedIndex());
        assertTrue(combo.isOpen(), "the identifier resolves, so it is accepted and posted; the "
                + "hook refuses it");
    }

    // ------------------------------------------------------------------------ the other mounting

    @Test
    void aPlatformThatCannotPlaceAWindowDescribesTheSameList() {
        // The Wayland shape, and no setDisplayMode call: the platform forces presentInScene.
        bindCombo(new ComboBox(List.of("Item 0", "Item 1", "Item 2")), new StubWindow(false));

        combo.open();
        frame();
        assertTrue(combo.isInSceneForTest(), "chosen by the platform and not by the application");

        List<AccessibleNode> options = options();
        assertEquals(List.of("Item 0", "Item 1", "Item 2"),
                options.stream().map(AccessibleNode::name).toList(), describe(tree()));
        for (AccessibleNode option : options) {
            assertEquals(Accessible.Role.LIST_ITEM, option.role());
            assertEquals(3, option.selectionItem().sizeOfSet());
        }
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietOpenListAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        // On a clock this test owns, because the two windows below have to be measured in the
        // same state and a frame count cannot put them there. The list fades in, its scroll bar
        // holds for over a second and then fades out, and each of those is a plateau with its own
        // per-frame cost: a bar that is painted, a bar that is fading, a bar that is gone. On a
        // warm machine 200 frames pass in under ten milliseconds and both windows sit on the first
        // plateau; on a cold one they take about as long as the hold, and the second window landed
        // on the fade -- every one of its sixty frames, so the minimum could not filter it. Time is
        // therefore moved by decree past all three, in steps the tick clamp accepts, and then not
        // moved again: nothing can expire or animate inside either window.
        long[] now = {1_000_000_000L};
        ComboBox box = new ComboBox(List.of("Item 0", "Item 1", "Item 2", "Item 3", "Item 4",
                "Item 5", "Item 6", "Item 7", "Item 8", "Item 9", "Item 10", "Item 11"));
        box.setDisplayMode(DisplayMode.IN_SCENE);
        bindCombo(box, new StubWindow(), () -> now[0]);
        openList();
        for (int i = 0; i < 12; i++) {
            now[0] += (long) (Scene.MAX_TICK_SECONDS * 1e9);
            combo.invalidate();
            frame();
        }
        int published = bridge.published.size();
        bridge.events.clear();

        // Twelve options, described on every damaged frame for as long as the list is open. A
        // name read out of the model with get(), a formatted position, or the variable-argument
        // action call is a string or an array per option per frame spent concluding that nothing
        // moved, and this is the only place any of them is visible.
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            combo.invalidate();
            frame();
        }, 60);

        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events + describe(tree()));
        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            combo.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a list that did not move must cost no memory: every name is an "
                        + "I18nString the combo already holds, compared by reference, and "
                        + "everything else is a primitive");
    }
}
