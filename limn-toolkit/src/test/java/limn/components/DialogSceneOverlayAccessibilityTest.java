package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.AllocationProbe;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Dialog}'s in-scene overlay becomes in the accessible tree, which depends on where
 * it sits in the overlay stack and never on anything it says about itself.
 *
 * <p>The overlay is the scrim: a parentless widget pushed onto the scene, full-scene, with the
 * dialog's card as its one child. It declares no role, no name, no description, no action and no
 * state of its own, is never focusable, and can never be given a tooltip. ADR 039 §1.6's predicate,
 * asked about the widget's own declarations, would delete it &mdash; and while it is the top
 * overlay the walk writes {@code MODAL} on it first, because the layer that owns input is where a
 * reader should hear that the background is blocked. That bit is what makes it a node: an unnamed
 * {@code GROUP} over the whole scene, the card's parent, related to the widget that opened it.
 * Buried under a second in-scene dialog it is no longer the layer that owns input, the walk writes
 * nothing, the predicate deletes it, and the card hoists to the window node with its identity
 * intact. Both positions are the walk's doing and neither needs a line in the class.
 *
 * <p><b>What was not free is the log.</b> The overlay paints the scrim, so on the buried path
 * §1.6's paints-and-says-nothing warning fired on it: a toolkit class named in an application's
 * log the first time two in-scene dialogs were stacked, recommending {@code setAccessibleIgnored},
 * which on this widget deletes the dialog's own controls. It is the {@code BackdropPanel} defect
 * §7.2 records, reached from the second dialog, and the fix is the same seam: the class says its
 * painting is decoration, and the deletion happens in silence. The absence of the warning is
 * asserted after <em>every</em> test rather than in one of them, because the walk names a class at
 * most once for the life of the virtual machine, and only the first test to bury an overlay could
 * ever see it. The check asks for a record naming <em>this</em> class rather than for an empty
 * log, because the walk's logger is the process's: a dialog's body scrolls, its scroll bar paints
 * and has not had its own step yet, and that class's one warning lands in whichever test happens
 * to walk a scroll bar first in the virtual machine. That is the scroll bar's step's to settle,
 * and a test here that failed on it would be reporting the wrong widget.
 *
 * <p>§7's row for the dialog says "panel or overlay" and puts {@code DIALOG}, {@code MODAL} and
 * {@code CANCEL} on whichever it is. Against the source, none of that is the overlay's: it is never
 * the dialog, it declares no verb even though Escape and a scrim press reach it, because both go
 * through the one private resolution the card's verb already offers, and it declares no
 * {@code MODAL} because the walk does. The cases below pin each of those against the tree the scene
 * published, driving only {@code Dialog}'s public API.
 */
class DialogSceneOverlayAccessibilityTest extends AccessibleComponentTestBase {

    /** Two titles that resolve to distinct names, so two stacked cards can be told apart. */
    private static final I18nString FIRST = new I18nString("limn.test.dialog.first", "First");
    private static final I18nString SECOND = new I18nString("limn.test.dialog.second", "Second");

    /** A message with its own key, so the card's description is a resolved string. */
    private static final I18nString MESSAGE =
            new I18nString("limn.test.dialog.overlay.message", "Choose one.");

    /** The clock an in-scene presentation's fades run on, so the test can move time itself. */
    private final AtomicLong clock = new AtomicLong();

    /** Every record the walk logged while a test was running; see the class comment. */
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

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void theScrimIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        assertFalse(logged.stream().anyMatch(record -> names(record, "Dialog$SceneOverlay")),
                "the overlay paints a scrim, and when it is buried it is deleted, and there is "
                        + "nothing about that worth telling an application: the veil is a wash "
                        + "over content the layer does not own, the fact it stands for is "
                        + "already MODAL on the layer above and the enabled bit taken from "
                        + "everything beneath, and the flag the warning recommends would delete "
                        + "the dialog's controls: " + rendered(logged));
    }

    /**
     * @param record    a record the walk logged
     * @param className the simple or binary name to look for
     * @return whether the record's message or any of its parameters names that class
     */
    private static boolean names(LogRecord record, String className) {
        if (record.getMessage() != null && record.getMessage().contains(className)) {
            return true;
        }
        if (record.getParameters() != null) {
            for (Object parameter : record.getParameters()) {
                if (String.valueOf(parameter).contains(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return the records as their message, parameters, logger and thread, one per line */
    private static String rendered(List<LogRecord> records) {
        StringBuilder out = new StringBuilder();
        for (LogRecord record : records) {
            out.append("\n  [").append(record.getLoggerName()).append(" on thread ")
                    .append(record.getLongThreadID()).append("] ")
                    .append(record.getMessage()).append(" <- ")
                    .append(record.getParameters() == null
                            ? "" : java.util.Arrays.toString(record.getParameters()));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------- the harness

    /** @return a dialog with the title, the message and a Cancel-then-OK footer, OK primary */
    private static Dialog cancelAndOk(I18nString title) {
        return new Dialog(title, MESSAGE)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok")
                .setCancelResult("cancel");
    }

    /** @return a second dialog whose buttons are named differently from the first's */
    private static Dialog noAndYes(I18nString title) {
        return new Dialog(title, MESSAGE)
                .addButton("No", "no")
                .addPrimaryButton("Yes", "yes")
                .setCancelResult("no");
    }

    /** Binds a scene over a background label to a window that can host an overlay. */
    private void openScene() {
        bridge = RecordingAccessibilityBridge.listening();
        OverlayHost host = new OverlayHost();
        host.accessibility = bridge;
        window = host;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(new Label("fundo"), clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /**
     * Presents {@code dialog} in scene, checks it really stayed there, and drives the fade until
     * the card has settled where it will stay.
     */
    private Dialog showInScene(Dialog dialog) {
        dialog.setDisplayMode(DisplayMode.IN_SCENE).show(scene);
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode(),
                "the host is never modal-blocked, so nothing promotes the dialog to a window");
        assertNull(dialog.modalWindow(), "this file is about the overlay");
        pumpUntilSettled(dialog);
        return dialog;
    }

    /** Advances the clock and renders, until the fade-in is over and its ticker has gone. */
    private void pumpUntilSettled(Dialog dialog) {
        for (int i = 0; i < 100 && dialog.fadeLevel() < 1; i++) {
            tick();
        }
        assertEquals(1, dialog.fadeLevel(), "the fade-in did not settle");
        // One more, so the card is placed for the settled fade and the tree has read it.
        tick();
        tick();
    }

    /** Advances the clock and renders, until no dialog of that title is published. */
    private void pumpUntilGone(String title) {
        for (int i = 0; i < 100 && cardNamed(title) != null; i++) {
            tick();
        }
        assertNull(cardNamed(title), "the fade-out did not end" + describe(tree()));
        tick();
    }

    private void tick() {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
        frame();
    }

    private void pressEscape() {
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.keyEvent(Keys.ESCAPE, false, false, 0);
        scene.inputBatchEnded();
    }

    /** Presses and releases on the scrim's top-left corner, which no centred card reaches. */
    private void pressTheScrim() {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 2, 2);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 2, 2);
        scene.inputBatchEnded();
    }

    private static String resultNow(Dialog dialog) {
        return dialog.result().toCompletableFuture().getNow("<open>");
    }

    // ------------------------------------------------------------------------------- readers

    /** @return the dialog node carrying that name, or {@code null} when none is published */
    private AccessibleNode cardNamed(String title) {
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.DIALOG && node.name().equals(title)) {
                return node;
            }
        }
        return null;
    }

    /** @return the dialog node carrying that name */
    private AccessibleNode card(String title) {
        AccessibleNode found = cardNamed(title);
        assertNotNull(found, "no dialog named \"" + title + "\"" + describe(tree()));
        return found;
    }

    /** @return the node the card hangs under */
    private AccessibleNode parentOf(AccessibleNode node) {
        return tree().node(node.parent());
    }

    private List<Long> idsOf(List<AccessibleNode> nodes) {
        return nodes.stream().map(AccessibleNode::id).toList();
    }

    private List<String> namesWith(Accessible.State state) {
        return nodesWith(state).stream().map(AccessibleNode::name).toList();
    }

    /** @return every node offering that verb, in tree order */
    private List<AccessibleNode> nodesOffering(Accessible.Action action) {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.actions() != null && node.actions().has(action)) {
                found.add(node);
            }
        }
        return found;
    }

    private static long targetOf(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    /**
     * @return the tree's shape, one line per node: parent, role, name and the verbs offered, and
     *         nothing that depends on a fresh scene's identifiers
     */
    private static String shapeOf(AccessibleTree tree) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            out.append(node.parent()).append(' ').append(node.role())
                    .append(" \"").append(node.name()).append("\" ")
                    .append(node.actions() == null ? "-" : new TreeSet<>(node.actions().actions()))
                    .append(node.has(Accessible.State.MODAL) ? " modal" : "")
                    .append('\n');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------- the top position

    /**
     * With one dialog open the card's parent is the layer the walk materialised, and it carries
     * exactly what the walk gave it: the reset role, no name, no description, no verb, the modal
     * bit, the inherited bits, the scene's box, the window as its parent and the popup relation
     * to the widget that opened it.
     *
     * <p>A hook that names the layer, gives it a verb or a second {@code MODAL} setter, or a
     * {@code setFocusable} call that turns it into an {@code UNKNOWN} tab stop would each fail
     * here.
     */
    @Test
    void theLayerIsTheWalksNodeAndDeclaresNothingOfItsOwn() {
        openScene();
        showInScene(cancelAndOk(FIRST));

        AccessibleNode card = card("First");
        AccessibleNode layer = parentOf(card);
        assertEquals(Accessible.Role.GROUP, layer.role(), describe(tree()));
        assertEquals("", layer.name(), "a name here is the title heard twice" + describe(tree()));
        assertEquals("", layer.description(), describe(tree()));
        assertNull(layer.actions(),
                "no CANCEL, which would be the card's verb offered twice, and no FOCUS or "
                        + "SCROLL_INTO_VIEW, which are free only on a focusable widget"
                        + describe(tree()));

        assertTrue(layer.has(Accessible.State.MODAL), describe(tree()));
        assertTrue(layer.has(Accessible.State.ENABLED));
        assertTrue(layer.has(Accessible.State.VISIBLE));
        assertTrue(layer.has(Accessible.State.SHOWING));
        assertFalse(layer.has(Accessible.State.FOCUSABLE),
                "never a tab stop" + describe(tree()));
        assertFalse(layer.has(Accessible.State.FOCUSED));
        assertEquals(List.of(layer.id()), idsOf(nodesWith(Accessible.State.MODAL)),
                "the fact is published once" + describe(tree()));

        assertEquals(0, layer.x(), "the layer's box is the whole scene" + describe(tree()));
        assertEquals(0, layer.y());
        assertEquals(tree().sceneWidth(), layer.width(), 0.01f);
        assertEquals(tree().sceneHeight(), layer.height(), 0.01f);
        assertEquals(0, layer.parent(),
                "the layer hangs under the window node, and is the one node between it and the "
                        + "card" + describe(tree()));
        assertEquals(Accessible.Role.WINDOW, tree().node(0).role());
        assertEquals(node("fundo").id(), targetOf(layer, Accessible.Relation.POPUP_FOR),
                "a parentless root with an inheritance host is a popup for that host, and "
                        + "show(Scene) makes the host the scene's root" + describe(tree()));
    }

    /**
     * Whether a scrim press dismisses the dialog is a fact about the pointer and not about the
     * tree: both settings publish the same shape, {@code CANCEL} is offered by exactly one node
     * and it is the dialog, and a press on the scrim reaches the same resolution as that verb
     * when the setting allows it and leaves the tree as it was when it does not.
     *
     * <p>A conditional verb on the layer would make the tree's shape depend on a setting and
     * would duplicate the card's dismissal.
     */
    @Test
    void theScrimSettingChangesNothingInTheTree() {
        openScene();
        Dialog dismissable = showInScene(cancelAndOk(FIRST).setDismissOnScrim(true));
        String shape = shapeOf(tree());
        assertEquals(List.of(card("First").id()), idsOf(nodesOffering(Accessible.Action.CANCEL)),
                "one node offers the dismissal, and it is the dialog" + describe(tree()));

        pressTheScrim();
        pumpUntilGone("First");
        assertEquals("cancel", resultNow(dismissable),
                "the scrim reaches the cancel result, through the card's own resolution");
        assertEquals(List.of(), nodesWith(Accessible.State.MODAL), describe(tree()));

        openScene();
        Dialog modal = showInScene(cancelAndOk(FIRST).setDismissOnScrim(false));
        assertEquals(shape, shapeOf(tree()),
                "the setting is not a fact about the tree" + describe(tree()));

        pressTheScrim();
        tick();
        tick();
        assertEquals("<open>", resultNow(modal), "a truly modal scrim ignores the press");
        assertEquals(shape, shapeOf(tree()), "and the tree is exactly what it was");
        assertEquals(List.of(card("First").id()), idsOf(nodesOffering(Accessible.Action.CANCEL)));
    }

    // ------------------------------------------------------------------- the buried position

    /**
     * A second in-scene dialog on the same scene buries the first: the first's layer is no longer
     * the one that owns input, so the walk writes nothing on it, the predicate deletes it and the
     * first card hoists to the window node &mdash; still published, with the identity it had, on
     * screen, and not operable, which is §1.13's frozen dialog. Closing the second puts everything
     * back, the layer included, with the identifiers it had.
     *
     * <p>Removing {@code paintsDecoration} from the overlay makes the warning appear and fails the
     * check after this test. A hook that kept the buried layer alive with an invented fact would
     * leave the first card's parent off the window. A walk change that let a buried layer keep
     * {@code MODAL} would publish the fact twice.
     */
    @Test
    void aBuriedDialogIsFrozenNotGoneAndItsLayerIsDeletedInSilence() {
        openScene();
        Dialog first = showInScene(cancelAndOk(FIRST));
        AccessibleNode firstCard = card("First");
        long firstId = firstCard.id();
        long firstLayerId = parentOf(firstCard).id();
        assertTrue(parentOf(firstCard).has(Accessible.State.MODAL), "sanity" + describe(tree()));
        bridge.events.clear();

        Dialog second = showInScene(noAndYes(SECOND));

        AccessibleNode secondCard = card("Second");
        assertEquals(List.of(parentOf(secondCard).id()), idsOf(nodesWith(Accessible.State.MODAL)),
                "modal is published once, on the layer that owns input" + describe(tree()));
        firstCard = card("First");
        assertEquals(firstId, firstCard.id(), "the buried card keeps its identity");
        assertEquals(0, firstCard.parent(),
                "its layer is deleted and the card hoists to the window node" + describe(tree()));
        assertEquals(AccessibleNode.NONE, tree().indexOf(firstLayerId),
                "the buried layer is no node at all" + describe(tree()));
        assertTrue(firstCard.has(Accessible.State.VISIBLE), "frozen, not gone" + describe(tree()));
        assertTrue(firstCard.has(Accessible.State.SHOWING));
        assertFalse(firstCard.has(Accessible.State.ENABLED));
        assertFalse(firstCard.has(Accessible.State.MODAL));
        for (String button : List.of("Cancel", "OK")) {
            assertTrue(node(button).has(Accessible.State.VISIBLE), button + describe(tree()));
            assertTrue(node(button).has(Accessible.State.SHOWING), button);
            assertFalse(node(button).has(Accessible.State.ENABLED), button);
            assertFalse(node(button).has(Accessible.State.FOCUSABLE), button);
        }
        assertEquals(List.of("No", "Yes"), namesWith(Accessible.State.FOCUSABLE),
                "the focusable set is the top dialog's alone" + describe(tree()));
        assertTrue(bridge.events.stream().anyMatch(event ->
                event.type() == AccessibleEvent.Type.NODE_DESTROYED
                        && event.nodeId() == firstLayerId),
                "what the deletion costs a reader, and it is accepted: " + bridge.events);
        assertEquals(0, bridge.events.stream().filter(event ->
                event.type() == AccessibleEvent.Type.NODE_DESTROYED
                        && event.nodeId() == firstId).count(),
                "the card itself was never destroyed: " + bridge.events);

        pressEscape();
        pumpUntilGone("Second");

        assertEquals("no", resultNow(second), "Escape answered the top dialog");
        assertEquals("<open>", resultNow(first), "and not the buried one");
        firstCard = card("First");
        assertEquals(firstId, firstCard.id(), "same card" + describe(tree()));
        assertEquals(firstLayerId, parentOf(firstCard).id(),
                "and the same layer, because serials are minted on the widget and the widget "
                        + "never went away" + describe(tree()));
        assertEquals(List.of(firstLayerId), idsOf(nodesWith(Accessible.State.MODAL)),
                "modal is back on the first layer, once" + describe(tree()));
        assertTrue(firstCard.has(Accessible.State.ENABLED), describe(tree()));
        assertEquals(List.of("Cancel", "OK"), namesWith(Accessible.State.FOCUSABLE),
                "the first dialog's buttons are the whole focusable set again" + describe(tree()));
    }

    // ------------------------------------------------------------------- where the key lands

    /**
     * A dialog with no buttons has nothing to focus, so Escape reaches the overlay root and not
     * the card; the tree still offers the dismissal on the dialog alone, the layer offers nothing,
     * and the key resolves the dialog with the cancel result all the same.
     *
     * <p>The guard against moving {@code CANCEL} to the layer because that is where the key lands:
     * the key and the verb reach the same private resolution from either root, and the node that
     * offers it is the one a reader calls the dialog.
     */
    @Test
    void aDialogWithNoButtonsStillPublishesItsDismissalOnTheCardOnly() {
        openScene();
        Dialog dialog = showInScene(new Dialog(FIRST, MESSAGE).setCancelResult("cancel"));

        AccessibleNode card = card("First");
        AccessibleNode layer = parentOf(card);
        assertTrue(card.actions().has(Accessible.Action.CANCEL), describe(tree()));
        assertNull(layer.actions(), "the layer offers nothing" + describe(tree()));
        assertEquals(List.of(card.id()), idsOf(nodesOffering(Accessible.Action.CANCEL)),
                describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSABLE),
                "nothing to focus" + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSED));
        assertTrue(layer.has(Accessible.State.MODAL), describe(tree()));

        pressEscape();
        pumpUntilGone("First");

        assertEquals("cancel", resultNow(dialog), "Escape on the overlay root answers the dialog");
        assertEquals(List.of(), nodesWith(Accessible.State.MODAL), describe(tree()));
    }

    // ---------------------------------------------------------------------------------- cost

    /**
     * A frame that damages both cards of two stacked in-scene dialogs and moves nothing publishes
     * no snapshot, no events, and allocates nothing to conclude that, so the buried layer's
     * deletion is not a rebuild per damaged frame and its decoration answer is not a string.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor that has nothing to do with this widget. The single-dialog case
     * is pinned beside the card's own description in {@link DialogPanelAccessibilityTest}.
     */
    @Test
    void aQuietFrameWithTwoStackedDialogsAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        openScene();
        Widget buried = showInScene(cancelAndOk(FIRST)).contentRoot();
        Widget top = showInScene(noAndYes(SECOND)).contentRoot();
        int published = bridge.published.size();
        bridge.events.clear();

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            buried.invalidate();
            top.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            buried.invalidate();
            top.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);


        assertEquals(withNobodyListening, withAReaderAttached,
                "deleting a buried layer and describing the one above it must cost no memory: "
                        + "the modal bit is the walk's, the decoration answer is a boolean, and "
                        + "the rest is references compared by identity");
    }
}
