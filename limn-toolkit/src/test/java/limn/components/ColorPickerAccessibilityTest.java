package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.graphics.Color;
import limn.i18n.I18n;
import limn.scene.LayoutDirection;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ColorPicker} becomes in the accessible tree: one nameless
 * {@code COLOR_CHOOSER} that is a tab stop and says nothing else about itself, over the controls
 * that actually carry the colour — the hex field, the notation tabs, and one line per channel of
 * caption, rail and number, each named by its letter.
 *
 * <p>The chooser's own node is one line of code, and the work this widget owes is the other half:
 * three of its controls are general components with no string of their own, their letters exist
 * only in the sibling captions, and no describe hook can reach them, because the walk offers a
 * child to its <em>direct</em> parent alone and every one of them is several wrappers deep. So the
 * constructor wires the names, and most of what is asserted here is that the wiring holds in
 * another language, in the other layout direction, across a format switch and across the alpha
 * mode.
 *
 * <p>Where ADR 039 §7's row is short. It gives the role and two dashes and reads as a leaf; the
 * widget is a twenty-node composite, and the dashes are right for a reason the row does not give —
 * the answer lives in the fields below, so a copy of it on the chooser would publish one fact
 * twice. The row is silent that the picker is focusable, which is the only reason its node
 * survives the transparency predicate at all and therefore why the role is not optional. And the
 * neighbouring {@code ColorPickerButton} row's instinct, "value text is the hex", must not be
 * carried across: a value text with no number is dropped by the builder and a value change is
 * raised only when a number moves, so it would cost a formatted string per drag step and reach
 * nobody.
 *
 * <p>Nothing here is transitional any more. Every control the chooser holds has taken its own step:
 * the rails publish {@code SLIDER}, the hex field {@code TEXT_FIELD}, and the eleven steppers
 * {@code SPIN_BUTTON} over two nameless arrow buttons each, which is what
 * {@code limn.components.SpinnerAccessibilityTest} pins. Those arrows sit a level below the
 * steppers and are neither nameless groups nor tab stops, so nothing counted here moves because of
 * them. All three painted parts have taken theirs too: the saturation/value plane is
 * the {@code CANVAS} at the head of the chooser's children, over one axis node per channel, the
 * hue ramp is the {@code SLIDER} named "Hue" beside it and the before/after swatch is the
 * {@code IMAGE} after them, and what each says is pinned by
 * {@code limn.components.ColorPickerSaturationValueFieldAccessibilityTest},
 * {@code limn.components.ColorPickerHueRampAccessibilityTest} and
 * {@code limn.components.ColorPickerPreviewAccessibilityTest} rather than here. Nothing asserted
 * below depends on which role those nodes end up with; they are found by the names this widget
 * gives them.
 *
 * <p>Every case drives the picker's public API, and {@code Widget}'s, on a bound scene, or calls
 * the scene from where a bridge stands. Nothing constructs a node.
 */
class ColorPickerAccessibilityTest extends AccessibleComponentTestBase {

    /** The exact class an application must never find in its log because of this step. */
    private static final String THE_PICKER = "limn.components.ColorPicker";

    private ColorPicker picker;

    /** The column the picker sits in, the root of every fixture. */
    private Column root;

    /** Every record the walk logged while a test was running; see {@link #theLogNamesThePicker}. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    private Locale before;

    @BeforeEach
    void captureTheWalksLogAndPinTheLanguage() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
        before = I18n.processLocale();
        // Everything but French falls back to the letters the component declares, and one case
        // below asks for French by name, so the rest are pinned to the language they assert in.
        I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The picker is focusable and declares a role, so the walk must never publish it
     * {@code UNKNOWN}; it paints nothing itself, so it must never be named by the
     * paints-and-says-nothing warning either. Both are logged once per class, so whichever case
     * runs first is the one that would see it — hence the check after every one.
     *
     * <p>The class name is read from the record's parameter rather than matched inside the
     * message: the chooser's inner classes are named there too, and every one of them starts
     * with this class's name.
     */
    @AfterEach
    void theLogNamesThePicker() {
        walkLogger.removeHandler(capture);
        I18n.setLocale(before);
        for (LogRecord record : logged) {
            Object[] parameters = record.getParameters();
            if (parameters == null) {
                continue;
            }
            for (Object parameter : parameters) {
                assertNotEquals(THE_PICKER, parameter,
                        "the picker is focusable, so a step that dropped its role would publish it "
                                + "as an unknown control and name a toolkit class in an "
                                + "application's log: " + record.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One picker at the width the gallery gives it, so every rail has a line to itself. */
    private void bindPicker() {
        picker = new ColorPicker();
        root = new Column();
        root.add(new SizedBox(380, SizedBox.UNSET, picker));
        bind(root);
    }

    /** @return the one colour chooser in the current tree */
    private AccessibleNode chooser() {
        return node(Accessible.Role.COLOR_CHOOSER);
    }

    /**
     * @param name the accessible name to look for
     * @return every node carrying it, in tree order
     */
    private List<AccessibleNode> named(String name) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).name().equals(name)) {
                found.add(tree().node(i));
            }
        }
        return found;
    }

    /**
     * The caption, the rail and the stepper of one channel, in tree order.
     *
     * @param letter the channel's letter in the language under test
     * @return the three nodes, which is what a line publishes
     */
    private List<AccessibleNode> line(String letter) {
        List<AccessibleNode> found = named(letter);
        assertEquals(3, found.size(),
                "a channel line is a caption and the two controls it names" + describe(tree()));
        return found;
    }

    /**
     * @param caption the notation's caption in the language under test
     * @return the panel that notation's channel lines are published under
     */
    private AccessibleNode panel(String caption) {
        for (AccessibleNode node : named(caption)) {
            if (node.role() == Accessible.Role.TAB_PANEL) {
                return node;
            }
        }
        throw new AssertionError("no panel named \"" + caption + "\"" + describe(tree()));
    }

    /**
     * @param node the node to read
     * @param kind the relation to look for
     * @return the target identifier, or {@code 0} when there is no such relation
     */
    private static long relationTarget(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    /** @return the identifiers of every focusable node, in tree order */
    private List<Long> focusableIds() {
        List<Long> found = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            found.add(node.id());
        }
        return found;
    }

    /** @return the identifier of the node holding the keyboard, or {@code 0} */
    private long focusedId() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).has(Accessible.State.FOCUSED)) {
                return tree().node(i).id();
            }
        }
        return 0;
    }

    /** @return every identifier in the current tree, in order */
    private List<Long> allIds() {
        List<Long> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            found.add(tree().node(i).id());
        }
        return found;
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void aPickerIsOneColourChooserWithNoValueAndNoVerbOfItsOwn() {
        bindPicker();

        AccessibleNode node = chooser();
        assertEquals(0, node.parent(), "a child of the window; every wrapper above it is deleted"
                + describe(tree()));
        assertEquals("", node.name(),
                "the widget holds no title, placeholder or tooltip, so it hands over no name and "
                        + "invents none" + describe(tree()));
        assertEquals("", node.description(), describe(tree()));
        assertNull(node.value(),
                "no scalar and therefore no value text: a text handed over without a number is "
                        + "dropped by the builder, and a colour has no honest number to pair with "
                        + "it" + describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.selection(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertEquals(Set.of(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                node.actions().actions(),
                "the walk's two, and nothing else. No INCREMENT or DECREMENT: this widget's arrows "
                        + "walk saturation across and value up, and one pair of verbs cannot say "
                        + "which axis" + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL),
                "a colour space has no reading axis" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "focusable from the constructor, which is why the node survives the predicate at "
                        + "all" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));

        assertEquals(picker.localToSceneX(), node.x(), describe(tree()));
        assertEquals(picker.localToSceneY(), node.y(), describe(tree()));
        assertEquals(picker.width(), node.width(), describe(tree()));
        assertEquals(picker.height(), node.height(),
                "the picker's own box: the root column is laid out over the whole of it, so every "
                        + "descendant's rectangle is inside this one" + describe(tree()));
    }

    @Test
    void theChooserHoldsTheControlsAndNoneOfTheBoxesAroundThem() {
        bindPicker();

        List<AccessibleNode> children = childrenOf(chooser());
        List<String> shape = new ArrayList<>();
        for (AccessibleNode child : children) {
            shape.add(child.role() + " \"" + child.name() + "\"");
        }
        assertEquals(List.of(
                        "CANVAS \"Saturation and value\"",
                        "SLIDER \"Hue\"",
                        "IMAGE \"Colour #FFFFFF, was #FFFFFF\"",
                        "LABEL \"#\"",
                        "TEXT_FIELD \"Hex\"",
                        "TAB_LIST \"\"",
                        "BUTTON \"Previous tabs\"",
                        "BUTTON \"Next tabs\"",
                        "BUTTON \"All tabs\"",
                        "TAB_PANEL \"RGB\"",
                        "TAB_PANEL \"HSV\"",
                        "TAB_PANEL \"CMYK\"",
                        "LABEL \"A\"",
                        "SLIDER \"A\"",
                        "SPIN_BUTTON \"A\""),
                shape,
                "the rows, columns, paddings, token boxes and expanded shares between the picker "
                        + "and its controls are all deleted, and the three parts it draws have "
                        + "each taken their own step: the saturation/value plane is the CANVAS at "
                        + "the head of this list and the hue ramp the SLIDER beside it, because "
                        + "the ramps row is the first thing in the column and they are the two "
                        + "things in that row, and the swatch is the IMAGE after them, because it "
                        + "is the first thing in the identity row. What each says is pinned by "
                        + "limn.components.ColorPickerSaturationValueFieldAccessibilityTest, "
                        + "limn.components.ColorPickerHueRampAccessibilityTest and "
                        + "limn.components.ColorPickerPreviewAccessibilityTest. The notation tabs "
                        + "bring their pane's three overflow controls with them, which publish "
                        + "whether or not the strip overflows and are not on screen while it fits. "
                        + "Nothing here is transitional any more: the alpha stepper's SPIN_BUTTON "
                        + "was the last of these to take its own step, and what it says is pinned "
                        + "by limn.components.SpinnerAccessibilityTest" + describe(tree()));

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group is scaffolding that survived the predicate" + describe(tree()));
        }
    }

    // -------------------------------------------------------------------------------- the names

    @Test
    void everyChannelLineNamesBothOfItsControlsByItsLetter() {
        bindPicker();

        for (String letter : List.of("R", "G", "B")) {
            List<AccessibleNode> line = line(letter);
            AccessibleNode caption = line.get(0);
            AccessibleNode rail = line.get(1);
            AccessibleNode stepper = line.get(2);

            assertEquals(Accessible.Role.LABEL, caption.role(), describe(tree()));
            assertEquals(Accessible.NameFrom.CONTENT, caption.nameFrom(), describe(tree()));
            assertEquals(rail.id(), relationTarget(caption, Accessible.Relation.LABEL_FOR),
                    "a caption carries one target, and it is the rail it sits against"
                            + describe(tree()));

            for (AccessibleNode control : List.of(rail, stepper)) {
                assertEquals(Accessible.NameFrom.LABEL, control.nameFrom(),
                        "named by the caption beside it rather than by a string this widget wrote "
                                + "into the application's own override" + describe(tree()));
                assertEquals(caption.id(),
                        relationTarget(control, Accessible.Relation.LABELLED_BY),
                        "and a reader that would rather walk to the caption can" + describe(tree()));
                assertTrue(control.has(Accessible.State.FOCUSABLE), describe(tree()));
            }
            assertTrue(rail.width() > caption.width(),
                    "the rail takes what the caption and the stepper leave" + describe(tree()));
        }

        picker.setFormat(ColorPicker.Format.HSV);
        frame();
        for (String letter : List.of("H", "S", "V")) {
            assertEquals(Accessible.NameFrom.LABEL, line(letter).get(1).nameFrom(),
                    describe(tree()));
        }

        picker.setFormat(ColorPicker.Format.CMYK);
        frame();
        for (String letter : List.of("C", "M", "Y", "K")) {
            assertEquals(Accessible.NameFrom.LABEL, line(letter).get(2).nameFrom(),
                    describe(tree()));
        }
    }

    @Test
    void theHexFieldIsNamedByAWordAndNotByTheHashBesideIt() {
        bindPicker();

        AccessibleNode hex = named("Hex").get(0);
        assertEquals(1, named("Hex").size(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, hex.nameFrom(),
                "there is no caption in the tree holding this word: the '#' beside the field is a "
                        + "mark a reader speaks as \"number sign\", so the field carries the word "
                        + "itself" + describe(tree()));
        assertTrue(hex.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(1, named("#").size(),
                "and the '#' stays what it is, naming nothing" + describe(tree()));
    }

    @Test
    void theLettersAndTheTabCaptionsFollowTheLanguage() {
        bindPicker();
        List<Long> ids = allIds();

        picker.setLocale(Locale.FRENCH);
        frame();

        assertEquals(List.of("RVB", "TSV", "CMJN"),
                List.of(named("RVB").get(0).name(), named("TSV").get(0).name(),
                        named("CMJN").get(0).name()),
                "French is the one shipped language that renames the notations, which is why these "
                        + "are keys at all" + describe(tree()));
        List<String> lines = new ArrayList<>();
        for (AccessibleNode child : childrenOf(panel("RVB"))) {
            lines.add(child.name() + "/" + child.nameFrom());
            assertEquals(Locale.FRENCH, child.locale(), describe(tree()));
        }
        assertEquals(List.of("R/CONTENT", "R/LABEL", "R/LABEL",
                        "V/CONTENT", "V/LABEL", "V/LABEL",
                        "B/CONTENT", "B/LABEL", "B/LABEL"),
                lines,
                "the letters follow the notation's own language, which is the whole reason they "
                        + "are keys; a name resolved inside a hook rather than handed over as the "
                        + "string the caption holds could not have moved" + describe(tree()));
        assertEquals(List.of(), named("G"),
                "and green is V here, not G" + describe(tree()));
        assertEquals(ids, allIds(), "a language is not a rebuild" + describe(tree()));
    }

    @Test
    void theApplicationCanNameThePickerAndTheChannelsKeepTheirLetters() {
        bindPicker();
        long id = chooser().id();

        picker.setAccessibleName("Fill colour");
        frame();

        assertEquals("Fill colour", chooser().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, chooser().nameFrom(), describe(tree()));
        assertEquals(id, chooser().id(), "a name is not a rebuild" + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, line("R").get(1).nameFrom(),
                "the override lands on the widget it was called on and nowhere else"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the colour

    @Test
    void theColourIsNotOnTheChooserAndTheFieldsAreWhereItIsRead() {
        bindPicker();

        picker.setInitialColor(Color.rgb(0x3366CC));
        frame();

        assertNull(chooser().value(),
                "the chosen colour reaches a reader through the swatch's own name, the channel "
                        + "numbers and, once the field has taken its step, the hex field's text — "
                        + "each of which is a node of its own; a copy here would be one fact "
                        + "published twice, and would republish the tree on every step of a drag"
                        + describe(tree()));
        assertNull(chooser().text(), describe(tree()));
        assertEquals("", chooser().name(), describe(tree()));
    }

    // -------------------------------------------------------------------------------- the modes

    @Test
    void turningAlphaOffLeavesTheAlphaLineInTheTreeAndOutOfReach() {
        bindPicker();
        int count = tree().nodeCount();
        List<Long> alpha = List.of(line("A").get(1).id(), line("A").get(2).id());
        bridge.events.clear();

        picker.setAlphaEnabled(false);
        frame();

        assertEquals(count, tree().nodeCount(),
                "the line is hidden, not removed: publishing it only while it is offered would "
                        + "destroy and rebuild three nodes on a mode flip" + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
        assertEquals(alpha, List.of(line("A").get(1).id(), line("A").get(2).id()),
                "and the identifiers a client is holding survive it" + describe(tree()));
        for (AccessibleNode node : List.of(line("A").get(1), line("A").get(2))) {
            assertFalse(node.has(Accessible.State.VISIBLE), describe(tree()));
            assertFalse(node.has(Accessible.State.SHOWING), describe(tree()));
            assertFalse(node.has(Accessible.State.FOCUSABLE),
                    "which is what the keyboard says about it too" + describe(tree()));
            assertFalse(node.actions() != null && node.actions().has(Accessible.Action.FOCUS),
                    "the walk's two verbs go with the tab stop, so nothing here offers to move "
                            + "the keyboard into a line that is off screen" + describe(tree()));
        }
        assertNull(line("A").get(2).actions(),
                "and the stepper publishes nothing for the same reason the rail does"
                        + describe(tree()));
        assertNull(line("A").get(1).actions(),
                "the rail publishes no verb either: the line is not VISIBLE, and decision 66 "
                        + "(2026-09-15) says the published list is empty on every node the scene "
                        + "refuses -- a verb list that said INCREMENT here was a control a reader "
                        + "is shown and the gate then refuses in silence. It is the VISIBLE bit "
                        + "and not the SHOWING one: a rail merely scrolled out of a viewport keeps "
                        + "its two and the scene reveals it before stepping. Pinned, with the "
                        + "refusal itself, by limn.components.ColorPickerAlphaRailAccessibilityTest"
                        + describe(tree()));
        assertEquals(1f, picker.color().a(), "and the colour is opaque while it is off");

        picker.setAlphaEnabled(true);
        frame();

        assertEquals(alpha, List.of(line("A").get(1).id(), line("A").get(2).id()), describe(tree()));
        assertTrue(line("A").get(1).has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
    }

    @Test
    void switchingTheFormatMovesWhichChannelsAreOnScreenAndDestroysNothing() {
        bindPicker();
        int count = tree().nodeCount();
        List<Long> ids = allIds();
        bridge.events.clear();

        picker.setFormat(ColorPicker.Format.CMYK);
        frame();

        assertEquals(count, tree().nodeCount(),
                "all three groups are always the pane's children; only which of them is on screen "
                        + "moves" + describe(tree()));
        assertEquals(ids, allIds(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
        for (String letter : List.of("C", "M", "Y", "K")) {
            assertTrue(line(letter).get(1).has(Accessible.State.SHOWING), describe(tree()));
            assertTrue(line(letter).get(2).has(Accessible.State.FOCUSABLE), describe(tree()));
        }
        for (String letter : List.of("R", "G", "B")) {
            assertFalse(line(letter).get(1).has(Accessible.State.SHOWING), describe(tree()));
            assertFalse(line(letter).get(2).has(Accessible.State.FOCUSABLE),
                    "an unselected notation's numbers are no more reachable from a reader than "
                            + "from the keyboard" + describe(tree()));
        }
        assertTrue(named("CMYK").get(0).has(Accessible.State.SELECTED),
                "and the strip says which notation it is" + describe(tree()));

        // The other half of the round trip: the tab the user clicks reports the selection back,
        // and the picker's guard is what stops the row being synchronised twice.
        picker.tabs().setSelectedIndex(1);
        frame();

        assertEquals(ColorPicker.Format.HSV, picker.format());
        assertTrue(line("H").get(1).has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());
    }

    // -------------------------------------------------------------------------------- the order

    @Test
    void theFocusableNodesAreExactlyWhatTabReachesInTheSameOrder() {
        bindPicker();

        List<Long> focusable = focusableIds();
        assertEquals(11, focusable.size(),
                "the chooser, the hex field, the one selected tab, three rails and three steppers, "
                        + "and the alpha pair" + describe(tree()));
        assertEquals(chooser().id(), focusable.get(0), describe(tree()));

        List<Long> tabbed = new ArrayList<>();
        scene.requestFocus(null);
        for (int i = 0; i < focusable.size(); i++) {
            scene.focusTraverse(false);
            frame();
            tabbed.add(focusedId());
        }

        assertEquals(focusable, tabbed,
                "reading order is defined to equal Tab order, so a tree that published a stop the "
                        + "keyboard cannot reach — an unselected tab's controls, a hidden panel's, "
                        + "the saturation/value field the pointer alone drives — is the thing that "
                        + "is wrong" + describe(tree()));
    }

    @Test
    void readingRightToLeftMirrorsTheBoxesAndNotTheOrder() {
        bindPicker();
        List<Long> ids = allIds();
        float railX = line("R").get(1).x();
        float stepperX = line("R").get(2).x();
        float width = chooser().width();

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(ids, allIds(),
                "the boxes reflect and reading order does not: an order derived from geometry "
                        + "would reverse every line here" + describe(tree()));
        assertTrue(line("R").get(2).x() < line("R").get(1).x(),
                "the stepper is on the leading edge now, which is the left of the box"
                        + describe(tree()));
        assertNotEquals(railX, line("R").get(1).x(), describe(tree()));
        assertNotEquals(stepperX, line("R").get(2).x(), describe(tree()));
        assertEquals(width, chooser().width(),
                "a mirrored picker is the same picture in the other direction" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the guards

    @Test
    void aDisabledPickerLosesItsVerbsAndGrowsNoSkeleton() throws Exception {
        bindPicker();
        int count = tree().nodeCount();
        long cmyk = named("CMYK").get(0).id();

        picker.setEnabled(false);
        frame();

        assertEquals(count, tree().nodeCount(),
                "the predicate reads a widget's own declarations and never the inherited bits; a "
                        + "disabled form is a form whose controls announce as disabled, not one "
                        + "that grows a skeleton" + describe(tree()));
        for (int i = 1; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
            assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    describe(tree()));
        }
        assertNull(chooser().actions(),
                "the walk's two verbs go with the tab stop" + describe(tree()));

        // The tabs still advertise their own two verbs, and the scene's gate is what refuses them:
        // it walks the ancestors, which is what the keyboard does and what a node's own enabled
        // bit cannot answer.
        perform(cmyk, Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(ColorPicker.Format.RGB, picker.format(),
                "a verb inside a disabled subtree does nothing on arrival");
    }

    // ---------------------------------------------------------------------------- what it costs

    @Test
    void aQuietPickerAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindPicker();
        picker.setInitialColor(Color.rgb(0xAF7AFF));
        frame();

        // The tab strip's indicator slides on the scene's clock; a measurement taken while it is
        // mid-flight is a measurement of the animation.
        settleAnimations(picker);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            picker.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            picker.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            picker.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a picker that did not move must cost no memory. Three ways to fail it "
                        + "are one careless line away here: color() builds a Color, toHex() runs "
                        + "two format calls, and the channel letters are a fresh array per lookup "
                        + "— which is why the letters are captured once in the constructor and "
                        + "handed over as the strings the labels hold");
    }
}
