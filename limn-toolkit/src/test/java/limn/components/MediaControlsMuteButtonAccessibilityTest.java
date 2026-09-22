package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.Color;
import limn.i18n.I18n;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What the mute button of a {@link MediaControls} becomes in the accessible tree: one plain
 * {@code BUTTON} named "Mute" or "Unmute" by the tooltip the bar keeps in step with its muted
 * state, offering a press that reaches the same {@code setMuted} a click reaches, and nothing
 * else &mdash; no toggle facet, no pressed bit, no description, no children.
 *
 * <p>The button takes its description from the private base it shares with the play button, and
 * {@link MediaControlsAccessibilityTest} pins the family and the bar. What is pinned here is what
 * is the mute button's alone, and above all the timing of its name. ADR 039 §7's row says the
 * icon buttons are "named by tooltips that already flip with the play state"; against the
 * source, the mute button's name follows {@code muted}, which three paths write and only one of
 * which is a press on the button, and until this step the tooltip was written only on the bar's
 * paint heartbeat. The tree is published before the paint of the same frame, so the frame that
 * reflected a mute change published the old name and the new one was heard a frame late. The
 * setters and the volume slider write the tooltip now, and the cases below render exactly one
 * frame after each change and expect the tree to be right in it.
 *
 * <p>It is a button and not a toggle button, decided against the source: the label is a verb that
 * flips, so the state travels as a rename and a pressed bit beside "Unmute" would be heard twice.
 *
 * <p>Every case drives the public API of {@link MediaControls} and {@code Widget} on a bound
 * scene, or calls the scene from where a bridge stands, and reads back what the scene published.
 * Nothing constructs a node.
 */
class MediaControlsMuteButtonAccessibilityTest extends AccessibleComponentTestBase {

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The two verbs in Portuguese; everything else falls through to the English. */
    private static final StringBundle SOUND = (key, locale) -> {
        if (!BRAZILIAN.equals(locale)) {
            return null;
        }
        return switch (key) {
            case "limn.mediaControls.mute" -> "Silenciar";
            case "limn.mediaControls.unmute" -> "Ativar som";
            default -> null;
        };
    };

    private VideoView view;
    private MediaControls controls;

    /** The mute button's node identifier, taken from the first tree and stable for the test. */
    private long muteId;

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(SOUND);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * The bar over an empty view with the sound cluster forced on, as the scene's root, with the
     * first frame rendered. An empty view rather than a source, because nothing about the mute
     * button depends on there being anything to play, and the play button then reads disabled,
     * which one case below leans on.
     */
    private void bindOffered() {
        view = new VideoView();
        controls = new MediaControls(view);
        controls.setSound(MediaControls.Sound.ON);
        bind(controls);
        muteId = node("Mute").id();
    }

    /** @return the mute button's node in the tree the bridge is holding now */
    private AccessibleNode mute() {
        return byId(tree(), muteId);
    }

    /** @return the play button's node: the tool bar's first child, as {@code rebuild()} orders it */
    private AccessibleNode play() {
        return childrenOf(node(Accessible.Role.TOOL_BAR)).get(0);
    }

    /**
     * @return the mute button itself, reached through the public child lists: the bar's only
     *         child is the row and the row's second child is the mute button
     */
    private Widget muteWidget() {
        return controls.children().get(0).children().get(1);
    }

    /** @return the play button itself, the row's first child */
    private Widget playWidget() {
        return controls.children().get(0).children().get(0);
    }

    /** @return the volume slider, inside the sized box that is the row's third child */
    private Widget volumeSlider() {
        return controls.children().get(0).children().get(2).children().get(0);
    }

    /** @return the one node with {@code id} in {@code tree} */
    private static AccessibleNode byId(AccessibleTree tree, long id) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).id() == id) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no node with id " + id + " in " + describe(tree));
    }

    /** @return how many nodes in the current tree carry {@code name} */
    private int countNamed(String name) {
        int count = 0;
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).name().equals(name)) {
                count++;
            }
        }
        return count;
    }

    /** @return every rename of the mute button raised so far, in order */
    private List<AccessibleEvent> renames() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.NAME_CHANGED && event.nodeId() == muteId) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return every state event of {@code state} on the mute button raised so far, in order */
    private List<AccessibleEvent> stateEvents(Accessible.State state) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED && event.nodeId() == muteId
                    && event.state() == state) {
                found.add(event);
            }
        }
        return found;
    }

    /** Presses and releases {@code key} on whatever holds the focus, as a keyboard does. */
    private void type(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    // -------------------------------------------------------------------------------- the shape

    @Test
    void itIsOnePlainButtonNamedByItsTooltipWithNoFacetNoDescriptionAndNoChildren() {
        bindOffered();

        assertEquals(1, countNamed("Mute"), "one button, once" + describe(tree()));
        AccessibleNode node = mute();
        assertEquals(Accessible.Role.BUTTON, node.role(),
                "a button and not a toggle button: the verb flips, so the state is the name"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, node.nameFrom(),
                "the walk's free default, compared by reference" + describe(tree()));
        assertEquals("", node.description(),
                "the tooltip is the name and must not also be the description, or the reader "
                        + "hears it twice" + describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(node.has(Accessible.State.CHECKED), describe(tree()));
        assertFalse(node.has(Accessible.State.MIXED), describe(tree()));
        assertFalse(node.has(Accessible.State.PRESSED),
                "armed is the hover veil and no platform maps it" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS), describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertFalse(node.actions().has(Accessible.Action.TOGGLE),
                "a toggle verb would announce the state twice" + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.expand(), describe(tree()));
        assertNull(node.selectionItem(), describe(tree()));
        assertEquals(List.of(), node.relations(), describe(tree()));
        assertEquals(AccessibleNode.NONE, node.firstChild(),
                "the speaker, the slash and the wave are paint, not children" + describe(tree()));
    }

    // --------------------------------------------------------------------------------- the name

    @Test
    void theNameIsPresentInTheFirstTreeAndExactInTheFrameThatPublishesEachChange() {
        bindOffered();

        assertEquals("Mute", byId(bridge.published.get(0), muteId).name(),
                "named from birth: the first heartbeat runs in the constructor"
                        + describe(bridge.published.get(0)));
        int published = bridge.published.size();

        controls.setMuted(true);
        frame();

        assertEquals("Unmute", mute().name(),
                "the setter writes the tooltip, so the frame that reflects the change publishes "
                        + "the new name and not the one the paint heartbeat had yet to replace"
                        + describe(tree()));
        assertEquals(published + 1, bridge.published.size(), "one change, one tree");
        assertEquals(1, renames().size(), bridge.events.toString());
        assertEquals("Mute", renames().get(0).oldValue(), bridge.events.toString());
        assertEquals("Unmute", renames().get(0).newValue(), bridge.events.toString());
        assertEquals(List.of(), stateEvents(Accessible.State.CHECKED),
                "the state travels as the name, never as a checked bit: " + bridge.events);

        controls.setMuted(false);
        frame();

        assertEquals("Mute", mute().name(), describe(tree()));
        assertEquals(2, renames().size(), bridge.events.toString());
        assertEquals(published + 2, bridge.published.size());
    }

    @Test
    void mutingThroughTheVolumeRenamesTheButtonWithNothingPressed() {
        bindOffered();

        controls.setVolume(0f);
        frame();

        assertTrue(controls.isMuted(), "a level of zero is muted");
        assertEquals("Unmute", mute().name(),
                "the name follows muted, which setVolume writes" + describe(tree()));

        controls.setVolume(0.5f);
        frame();

        assertFalse(controls.isMuted());
        assertEquals("Mute", mute().name(), describe(tree()));

        // The third writer of muted is the volume slider's own change handler, reached only from
        // the user: Home on the focused slider drags it to zero without a press on the button.
        scene.requestFocus(volumeSlider());
        type(Keys.HOME);
        frame();

        assertTrue(controls.isMuted(), "the slider at zero mutes");
        assertEquals(0f, controls.volume());
        assertEquals("Unmute", mute().name(),
                "renamed in the frame the slider moved, with nothing pressed" + describe(tree()));

        type(Keys.END);
        frame();

        assertFalse(controls.isMuted());
        assertEquals(1f, controls.volume());
        assertEquals("Mute", mute().name(), describe(tree()));
        assertEquals(4, renames().size(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "nothing was pressed: " + bridge.events);
    }

    @Test
    void theNameFollowsTheSubtreesLanguageWithTheSameProvenance() {
        I18n.addBundle(SOUND);
        bindOffered();

        controls.setLocale(BRAZILIAN);
        frame();

        assertEquals("Silenciar", mute().name(),
                "the held source re-resolved under the subtree's language, which a string "
                        + "formatted inside a hook could not be" + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, mute().nameFrom(), describe(tree()));
        assertEquals(BRAZILIAN, mute().locale(), describe(tree()));
        assertEquals(1, renames().size(), bridge.events.toString());

        controls.setMuted(true);
        frame();

        assertEquals("Ativar som", mute().name(), describe(tree()));
        assertEquals(2, renames().size(), bridge.events.toString());
    }

    // -------------------------------------------------------------------------------- the press

    @Test
    void aPressFromThePlatformThreadIsTheClickAndRestoresTheLevelAnUnmuteNeeds() throws Exception {
        bindOffered();

        assertTrue(perform(muteId, Accessible.Action.PRESS, Accessible.Argument.NONE));

        assertTrue(controls.isMuted(), "the hook reaches setMuted(!muted), as a click does");
        assertEquals(1f, controls.volume(), "muting keeps the level an unmute restores");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        assertEquals(muteId, bridge.events.stream()
                .filter(event -> event.type() == AccessibleEvent.Type.INVOKED)
                .findFirst().orElseThrow().nodeId());

        frame();

        assertEquals("Unmute", mute().name(),
                "one frame after the press, the name is the new one" + describe(tree()));
        assertEquals(1, renames().size(), bridge.events.toString());

        controls.setVolume(0f);
        frame();
        assertTrue(controls.isMuted());

        perform(muteId, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertFalse(controls.isMuted(), "the press unmutes");
        assertEquals(0.7f, controls.volume(),
                "an unmute from zero restores the level the setter restores: one path, not two");
        assertEquals("Mute", mute().name(), describe(tree()));
        assertEquals(2, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindOffered();

        perform(muteId, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(muteId, Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertFalse(controls.isMuted(), "the hook answers false for everything but PRESS");
        assertEquals("Mute", mute().name(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- enabled, hidden

    @Test
    void aDisabledBarDisablesItAndNothingElseDoes() throws Exception {
        bindOffered();

        // Nothing to play: the heartbeat disables the play button and leaves the mute alone,
        // because a soundtrack's gain is there to turn whether or not the picture can run.
        assertFalse(play().has(Accessible.State.ENABLED),
                "an empty view is nothing to play" + describe(tree()));
        assertTrue(mute().has(Accessible.State.ENABLED),
                "refresh() never disables the mute" + describe(tree()));

        controls.setEnabled(false);
        frame();

        AccessibleNode disabled = mute();
        assertFalse(disabled.has(Accessible.State.ENABLED),
                "the bar's flag reaches it down the walk" + describe(tree()));
        assertFalse(disabled.has(Accessible.State.FOCUSABLE),
                "and a disabled control is not a tab stop" + describe(tree()));
        assertTrue(muteWidget().isEnabled(), "the fixture left the button's own flag alone");

        perform(muteId, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertFalse(controls.isMuted(), "a control inside a disabled container is refused");
        assertEquals("Mute", mute().name(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void hiddenBySoundModeItIsPublishedNamedNotShowingAndRefusesAPress() throws Exception {
        view = new VideoView();
        controls = new MediaControls(view);
        bind(controls);
        muteId = node("Mute").id();

        AccessibleNode hidden = mute();
        assertEquals(Accessible.Role.BUTTON, hidden.role(),
                "the declared role keeps the node in the tree while hidden" + describe(tree()));
        assertFalse(hidden.has(Accessible.State.VISIBLE),
                "AUTO with no player keeps the cluster aside" + describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(hidden.has(Accessible.State.FOCUSABLE), describe(tree()));

        perform(muteId, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertFalse(controls.isMuted(), "the scene's showing gate refuses a press on a hidden node");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());

        controls.setSound(MediaControls.Sound.ON);
        frame();

        AccessibleNode shown = mute();
        assertTrue(shown.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(shown.has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(shown.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals("Mute", shown.name(), describe(tree()));
        assertEquals(1, stateEvents(Accessible.State.VISIBLE).size(), bridge.events.toString());
        assertEquals(1, stateEvents(Accessible.State.SHOWING).size(), bridge.events.toString());
        assertEquals(1, stateEvents(Accessible.State.FOCUSABLE).size(), bridge.events.toString());

        perform(muteId, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertTrue(controls.isMuted(), "shown, the same node takes the press");
        assertEquals("Unmute", mute().name(), describe(tree()));

        controls.setSound(MediaControls.Sound.OFF);
        frame();

        assertFalse(mute().has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "hidden is a state, not a different node: " + bridge.events);
    }

    // ------------------------------------------------------------------------------- geometry

    @Test
    void itsBoxIsTheSquareItMeasuredAndNotTheRingItPaintsAroundIt() {
        bindOffered();

        AccessibleNode node = mute();
        Widget button = muteWidget();
        assertEquals(button.localToSceneX(), node.x(), describe(tree()));
        assertEquals(button.localToSceneY(), node.y(), describe(tree()));
        assertEquals(button.width(), node.width(), describe(tree()));
        assertEquals(button.height(), node.height(), describe(tree()));
        assertEquals(node.width(), node.height(), "a square" + describe(tree()));
        assertNotEquals(button.height() + 2 * Strokes.FOCUS_RING_OUTSET, node.height(),
                "paintOutset() is damage and never bounds" + describe(tree()));
        assertTrue(node.x() > play().x(),
                "second in the row, after the play button" + describe(tree()));
        assertTrue(node.x() >= playWidget().localToSceneX() + playWidget().width(),
                "and clear of it" + describe(tree()));
    }

    @Test
    void underARightToLeftRootTheBarStillReadsLeftToRight() {
        view = new VideoView();
        controls = new MediaControls(view);
        controls.setSound(MediaControls.Sound.ON);
        Column root = new Column();
        root.setLayoutDirection(LayoutDirection.RTL);
        root.add(controls);
        bind(root);
        muteId = node("Mute").id();

        assertEquals(LayoutDirection.RTL, root.layoutDirection(), "the fixture is mirrored");
        assertEquals(LayoutDirection.LTR, controls.layoutDirection(),
                "the bar declares LTR for itself and does not inherit");
        assertTrue(mute().x() > play().x(),
                "the transport is the standing exception to mirroring, so the mute stays to the "
                        + "right of the play button under a mirrored tree" + describe(tree()));
        assertEquals(muteWidget().localToSceneX(), mute().x(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    @Test
    void aDamagedFrameThatChangesNothingPublishesNothing() {
        bindOffered();
        int published = bridge.published.size();
        bridge.events.clear();

        controls.setInk(Color.WHITE, Color.BLACK);
        frame();
        controls.setMuted(false);
        frame();
        controls.setVolume(1f);
        frame();

        assertEquals(published, bridge.published.size(),
                "the buttons repainted and nothing a reader hears moved: the name is a held "
                        + "static string compared by reference, and a setter that changes nothing "
                        + "leaves the tooltip alone");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }
}
