package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.UiRuntime;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Dialog}'s card becomes in the accessible tree: one {@code DIALOG} node, named by
 * the title the dialog holds, described by its message, dismissable, and nothing more.
 *
 * <p>The card is the one widget of the dialog's that survives ADR 039 §1.6's predicate on its own
 * merits, and the reason it has to is that it paints: left undeclared it would be deleted as
 * scaffolding, the outline and the fill it draws would be absent from the tree, and the toolkit
 * would log the paints-and-says-nothing warning against one of its own classes. §1.11 names it in
 * so many words as the {@code DIALOG} that owns its content, in both of ADR 028's mountings: the
 * root of its own scene when the dialog is a native window, the centred child of a scrim overlay
 * when it is drawn in scene. This file drives both.
 *
 * <p>§7's row for the dialog is wrong in four places, and each case below pins the correction
 * against the tree the scene published. The row says "panel or overlay": only the card is the
 * dialog, because the overlay's box is the whole scene and a dialog on it would hand a client's
 * dialog handling a full-window rectangle. It says {@code MODAL}: the walk already puts that bit
 * on the layer that owns input, so in scene a second declaration would publish one fact twice,
 * and as a native window §1.13 keeps modality on the window's own node. It says "WindowFacet
 * only when it is a real window": the card is never one, since the walk mints the window node
 * beside a scene root and a facet on the card would be two windows for one. And it is silent on
 * the name and the description, which the source has and the tree now carries.
 *
 * <p>Nothing here constructs a node or calls a hook. Every dialog is built through its public
 * constructors and setters, presented the way an application presents it or bound the way
 * {@link DialogTest} binds a headless one, and read back from the tree. Nodes below the card are
 * found by role and name and never by parent or index, so the scroll view's and the overlay's own
 * steps can land without touching this file.
 */
class DialogPanelAccessibilityTest extends AccessibleComponentTestBase {

    /**
     * A title with a shipped translation, so that the language case can tell a name resolved
     * under the node's own locale from one resolved under the process's. It is the string the
     * colour picker's own dialog carries, which makes the dialog under test one the toolkit
     * really raises.
     */
    private static final I18nString TITLE = ComponentStrings.COLOR_TITLE;

    /** A message with its own key, so the description is a resolved string and not a literal. */
    private static final I18nString MESSAGE =
            new I18nString("limn.test.dialog.message", "Pick a colour for the selection.");

    /**
     * Content an application puts in a dialog: a focusable control with a name and a role of its
     * own, so the focus order can be read from the tree without depending on any toolkit widget
     * whose own pipeline step has not landed.
     */
    private static final class Field extends Widget {
        Field(String name) {
            setFocusable(true);
            setAccessibleName(name);
            setAccessibleRole(Accessible.Role.TEXT_FIELD);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(120, 24);
        }
    }

    /**
     * A window that can host an in-scene dialog: the presentation asks the window's backend to
     * lock the owner's sibling windows, and the stub's backend refuses to exist. This one answers
     * that request with a handle and nothing else, so the card can be drawn in scene under a
     * listening bridge without a display server.
     */
    private static final class OverlayHost extends StubWindow implements Backend {
        /** The window the presentation named as the one that stays interactive. */
        NativeWindow sceneModalOwner;

        @Override public Backend backend() { return this; }
        @Override public UiRuntime uiRuntime() { return null; }
        @Override public NativeWindow createWindow(WindowConfig config) {
            throw new AssertionError("an in-scene dialog must not create a window");
        }
        @Override public void runEventLoop() { }
        @Override public void pushModal(NativeWindow modal, NativeWindow parent) { }
        @Override public void popModal(NativeWindow modal) { }
        @Override public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
            sceneModalOwner = owner;
            return () -> sceneModalOwner = null;
        }
        @Override public void signalModalBlocked() { }
        @Override public void stop() { }
    }

    private Dialog dialog;

    /** The clock an in-scene presentation's fades run on, so the test can move time itself. */
    private final AtomicLong clock = new AtomicLong();

    /**
     * @param locale the language to resolve under
     * @return the title as the tree resolves it, under that language and never as a literal
     */
    private static String titleIn(Locale locale) {
        return resolve(TITLE, locale);
    }

    private static String resolve(I18nString text, Locale locale) {
        Locale enclosing = I18n.pushScope(locale);
        try {
            return text.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** @return the title under the language a scene that declares none resolves to */
    private static String title() {
        return titleIn(I18n.processLocale());
    }

    private static String message() {
        return resolve(MESSAGE, I18n.processLocale());
    }

    /** @return a dialog with the title, the message and a Cancel-then-OK footer, OK primary */
    private static Dialog cancelAndOk() {
        return new Dialog(TITLE, MESSAGE)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok")
                .setCancelResult("cancel");
    }

    // ------------------------------------------------------------------------ the two mountings

    /** Binds the card as a scene root, which is what a headless or native dialog has. */
    private void bindNative(Dialog under) {
        dialog = under;
        bind(dialog.contentRoot());
        // A dialog's own native window is registered as a modal before its first frame; the
        // headless host claims the same, so the window node beside the card says what it would.
        window.modal = true;
        scene.requestRender();
        frame();
        bridge.events.clear();
    }

    /**
     * Presents the dialog in scene over a background label, under a listening bridge, and drives
     * the fade until the card has settled where it will stay.
     */
    private void showInScene(Dialog under) {
        dialog = under;
        bridge = new RecordingBridge();
        OverlayHost host = new OverlayHost();
        host.accessibility = bridge;
        window = host;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(new Label("fundo"), clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();

        dialog.setDisplayMode(DisplayMode.IN_SCENE).show(scene);
        assertNull(dialog.modalWindow(), "this file's second half is about the overlay");
        pumpUntilSettled();
    }

    /** Advances the clock and renders, until the fade-in is over and its ticker has gone. */
    private void pumpUntilSettled() {
        for (int i = 0; i < 100 && dialog.fadeLevel() < 1; i++) {
            tick();
        }
        assertEquals(1, dialog.fadeLevel(), "the fade-in did not settle");
        // One more, so the card is placed for the settled fade and the tree has read it.
        tick();
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

    private String resultNow() {
        return dialog.result().toCompletableFuture().getNow("<open>");
    }

    // ------------------------------------------------------------------------------- readers

    /** @return whether any node in the published tree is a dialog */
    private boolean dialogIsPublished() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.DIALOG) {
                return true;
            }
        }
        return false;
    }

    /** @return the one dialog node */
    private AccessibleNode card() {
        return node(Accessible.Role.DIALOG);
    }

    /**
     * @param node     the node to test
     * @param ancestor the node it is expected to hang under, at any depth
     * @return whether climbing the parent links from {@code node} reaches {@code ancestor}
     */
    private boolean isDescendantOf(AccessibleNode node, AccessibleNode ancestor) {
        AccessibleTree tree = tree();
        int ancestorAt = tree.indexOf(ancestor.id());
        for (int at = node.parent(); at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (at == ancestorAt) {
                return true;
            }
        }
        return false;
    }

    /** @return where the node named {@code name} sits in tree order */
    private int indexOf(String name) {
        return tree().indexOf(node(name).id());
    }

    /** @return every node of that role, in tree order */
    private List<AccessibleNode> nodesOf(Accessible.Role role) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == role) {
                found.add(tree().node(i));
            }
        }
        return found;
    }

    private List<Long> idsOf(List<AccessibleNode> nodes) {
        return nodes.stream().map(AccessibleNode::id).toList();
    }

    // ------------------------------------------------------------------------- the native shape

    /**
     * As a scene root the card is exactly one dialog node under the window node, with the whole
     * scene as its box, named by the title and described by the message, with none of the
     * window's facts on it.
     *
     * <p>A window facet added to the card, a name formatted in the hook, or the role dropped so
     * the predicate deleted a painting widget would each fail here.
     */
    @Test
    void theCardIsOneDialogNodeBesideTheWindowNodeAndNotTheWindow() {
        bindNative(cancelAndOk());

        assertEquals(1, nodesOf(Accessible.Role.DIALOG).size(), describe(tree()));
        AccessibleNode card = card();
        AccessibleNode windowNode = tree().node(0);
        assertEquals(Accessible.Role.WINDOW, windowNode.role(), describe(tree()));
        assertEquals(0, card.parent(), "the card hangs under the window node" + describe(tree()));
        assertNotEquals(windowNode.id(), card.id(),
                "one identifier for two elements is a merged element on every platform");
        assertEquals(0, card.x(), describe(tree()));
        assertEquals(0, card.y());
        assertEquals(tree().sceneWidth(), card.width(), 0.01f,
                "a scene lays its root out tight, so the card is the scene");
        assertEquals(tree().sceneHeight(), card.height(), 0.01f);

        assertEquals(title(), card.name(),
                "named by the title the dialog holds, resolved by the walk" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, card.nameFrom(),
                "the dialog's own painted text, which one platform reads as its title");
        assertEquals(message(), card.description(), describe(tree()));

        assertFalse(card.has(Accessible.State.FOCUSABLE),
                "the card is never a tab stop" + describe(tree()));
        assertFalse(card.has(Accessible.State.FOCUSED));
        assertFalse(card.has(Accessible.State.MODAL),
                "modality is the window's fact as a native window, and the layer's in scene");
        assertNull(card.window(), "the card is never a window in either mounting");
        assertNotNull(windowNode.window(), "the window node beside it carries the facet");
        assertTrue(windowNode.window().modal(),
                "and says the dialog's window blocks what it owns, which is the bit a client asks "
                        + "a window for; the owner it froze carries no such bit" + describe(tree()));
    }

    /**
     * A dialog built without a message publishes no description and no label, and never resolves
     * the message to find that out.
     */
    @Test
    void anEmptyMessageIsNoDescriptionAndNoLabel() {
        bindNative(new Dialog(TITLE, I18nString.EMPTY).addPrimaryButton("OK", "ok"));

        assertEquals("", card().description(), describe(tree()));
        assertEquals(List.of(), nodesOf(Accessible.Role.LABEL),
                "the heading is the only text in the body" + describe(tree()));
        assertEquals(1, nodesOf(Accessible.Role.HEADING).size(), describe(tree()));
    }

    /**
     * Everything the dialog holds hangs under its node, at whatever depth the scaffolding leaves
     * it, in the order a user reads it: the heading, the message, the content, the buttons.
     *
     * <p>Asserted as descendants and never as direct children, so the scroll view's step can put
     * a node between the card and its body without a line here changing.
     */
    @Test
    void theHeadingTheMessageTheContentAndTheButtonsAreItsDescendantsInReadingOrder() {
        bindNative(cancelAndOk().setContent(new Field("Name")));

        AccessibleNode card = card();
        AccessibleNode heading = node(Accessible.Role.HEADING);
        assertEquals(title(), heading.name(),
                "the heading carries the same string as the card" + describe(tree()));
        for (AccessibleNode each : List.of(heading, node(message()), node("Name"),
                node("Cancel"), node("OK"))) {
            assertTrue(isDescendantOf(each, card),
                    each.name() + " hangs under the dialog" + describe(tree()));
        }
        assertTrue(indexOf(title()) < indexOf(message()), describe(tree()));
        assertTrue(indexOf(message()) < indexOf("Name"), describe(tree()));
        assertTrue(indexOf("Name") < indexOf("Cancel"), describe(tree()));
        assertTrue(indexOf("Cancel") < indexOf("OK"), describe(tree()));
    }

    /**
     * The set published focusable is exactly what the keyboard reaches: the content before the
     * buttons, in depth-first order, which is where initial focus lands and not "the first
     * button" as the presentation's own comment says. A dialog with nothing to focus publishes
     * an empty set and is still a dialog.
     */
    @Test
    void theFocusableSetIsTheKeyboardsAndTheCardIsNotInIt() {
        bindNative(cancelAndOk().setContent(new Field("Name")));

        assertEquals(List.of("Name", "Cancel", "OK"),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::name).toList(),
                "content first, then the buttons in the order added" + describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            assertNotSame(Accessible.Role.UNKNOWN, tree().node(i).role(),
                    "a focusable node with no declared role is a defect" + describe(tree()));
        }

        bindNative(new Dialog(TITLE, MESSAGE));
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(Accessible.Role.DIALOG, card().role(),
                "nothing to focus, and still a dialog" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the verb

    /**
     * The card offers the dismissal and nothing else.
     *
     * <p>Not {@code PRESS}: Return is the default button's own verb, and two verbs for one
     * behaviour is a choice a reader has to make and cannot. Not {@code FOCUS} or
     * {@code SCROLL_INTO_VIEW}: those are free on a focusable widget, and the card is not one.
     */
    @Test
    void theCardOffersOneVerbAndItIsTheDismissal() {
        bindNative(cancelAndOk());

        assertTrue(card().actions().has(Accessible.Action.CANCEL), describe(tree()));
        assertFalse(card().actions().has(Accessible.Action.PRESS), describe(tree()));
        assertFalse(card().actions().has(Accessible.Action.FOCUS));
        assertFalse(card().actions().has(Accessible.Action.SCROLL_INTO_VIEW));
        assertFalse(card().actions().has(Accessible.Action.EXPAND));
        assertFalse(card().actions().has(Accessible.Action.COLLAPSE));
    }

    /**
     * A cancel from an assistive technology resolves the dialog with the cancel result, exactly
     * as Escape does, and a second one after that is refused by the same guard Escape meets.
     *
     * <p>Headless, the resolution completes at once. The second cancel is accepted by the host,
     * because the identifier still resolves, and refused by the card, because an answer is
     * already out: the stage's value does not change.
     */
    @Test
    void cancelResolvesWithTheCancelResultAndOnlyOnce() throws InterruptedException {
        bindNative(cancelAndOk());
        long id = card().id();
        assertEquals("<open>", resultNow(), "sanity: nothing answered yet");

        assertTrue(perform(id, Accessible.Action.CANCEL, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();
        assertEquals("cancel", resultNow(), "the same path Escape takes");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a dismissal is not a press: " + bridge.events);

        assertTrue(perform(id, Accessible.Action.CANCEL, Accessible.Argument.NONE),
                "the identifier still resolves, so the host still accepts");
        frame();
        assertEquals("cancel", resultNow(), "and the card's closing guard refused it");
    }

    /**
     * A verb the card does not offer reaches it and does nothing: the host accepts, because the
     * identifier resolves, and the card refuses, because Return belongs to the default button.
     */
    @Test
    void aPressOnTheCardIsRefusedAndTheDialogStaysOpen() throws InterruptedException {
        bindNative(cancelAndOk());

        assertTrue(perform(card().id(), Accessible.Action.PRESS, Accessible.Argument.NONE),
                "accepted by the host: the id resolves");
        frame();

        assertEquals("<open>", resultNow(), "and refused by the card");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), "" + bridge.events);
    }

    // ------------------------------------------------------------------------ the in-scene shape

    /**
     * Drawn in scene, the card hangs under the layer that owns input, which is the one node
     * carrying {@code MODAL}; the card is centred inside the scene's margin; and the background
     * loses the two bits the modal takes while staying on screen. Closing it leaves the tree as
     * it was.
     *
     * <p>{@code MODAL} declared on the card, {@code DIALOG} put on the overlay, or a hook that
     * handed the walk the scene's box instead of the card's would each fail here.
     */
    @Test
    void inSceneTheCardHangsUnderTheOneModalLayerAndIsCentred() {
        showInScene(cancelAndOk());

        AccessibleNode card = card();
        AccessibleNode layer = tree().node(card.parent());
        assertTrue(layer.has(Accessible.State.MODAL),
                "the layer that owns input carries the fact" + describe(tree()));
        assertEquals(0, layer.x(), "the layer is the whole scene" + describe(tree()));
        assertEquals(0, layer.y());
        assertEquals(tree().sceneWidth(), layer.width(), 0.01f);
        assertEquals(tree().sceneHeight(), layer.height(), 0.01f);
        assertEquals(List.of(layer.id()), idsOf(nodesWith(Accessible.State.MODAL)),
                "modal is published once, and not on the card" + describe(tree()));
        assertFalse(card.has(Accessible.State.MODAL), describe(tree()));
        assertNull(card.window(), "a dialog drawn inside another window's scene is not a window");

        SizeTokens t = SizeTokens.MEDIUM;
        assertTrue(card.width() <= Math.min(t.dialogMaxWidth(),
                tree().sceneWidth() - 2 * t.spacingLarge()),
                "capped by the dialog's own width budget: " + card.width());
        assertTrue(card.width() > 0 && card.height() > 0, describe(tree()));
        assertEquals((tree().sceneWidth() - card.width()) / 2, card.x(), 0.5f,
                "centred, which is the overlay's placement and not the scene's box"
                        + describe(tree()));
        assertEquals((tree().sceneHeight() - card.height()) / 2, card.y(), 0.5f,
                "and the slide has settled" + describe(tree()));

        AccessibleNode background = node("fundo");
        assertFalse(background.has(Accessible.State.ENABLED),
                "the modal takes the background's operability" + describe(tree()));
        assertFalse(background.has(Accessible.State.FOCUSABLE));
        assertTrue(background.has(Accessible.State.VISIBLE), "but it is still on screen");
        assertTrue(background.has(Accessible.State.SHOWING));
        assertEquals(List.of("Cancel", "OK"),
                nodesWith(Accessible.State.FOCUSABLE).stream().map(AccessibleNode::name).toList(),
                describe(tree()));

        pressEscape();
        for (int i = 0; i < 100 && dialogIsPublished(); i++) {
            tick();
        }
        assertFalse(dialogIsPublished(), "the fade-out ends with the card gone" + describe(tree()));
        assertEquals("cancel", resultNow());
        assertEquals(List.of(), nodesWith(Accessible.State.FOCUSABLE),
                "the focusable set is what it was before show()" + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.MODAL), describe(tree()));
    }

    /**
     * Dragging the card moves its published box by the drag, and everything under it with it.
     *
     * <p>The press lands on the card's own padding band, inside the card and outside every
     * control in it, which is the only place a press reaches the card's drag. The overlay
     * re-places the card when it paints, which is after the frame's publish step, so on the
     * frame that moves the card the tree still carries the box the previous paint left; the
     * overlay marks the tree when a paint moved the card, and the frame after that publishes
     * the settled box. Hence two frames here, and the same two after the fade-in settles.
     */
    @Test
    void draggingTheCardMovesItsBoxAndItsDescendantsWithIt() {
        showInScene(cancelAndOk());
        AccessibleNode before = card();
        float okX = node("OK").x();
        float okY = node("OK").y();
        bridge.events.clear();

        float grab = SizeTokens.MEDIUM.spacingLarge() / 2;
        float x = before.x() + grab;
        float y = before.y() + grab;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseMoved(x + 30, y + 20);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x + 30, y + 20);
        scene.inputBatchEnded();
        tick();
        tick();

        AccessibleNode after = card();
        assertEquals(before.id(), after.id(), "identity is not a coordinate");
        assertEquals(before.x() + 30, after.x(), 0.01f, describe(tree()));
        assertEquals(before.y() + 20, after.y(), 0.01f, describe(tree()));
        assertEquals(before.width(), after.width(), 0.01f);
        assertEquals(okX + 30, node("OK").x(), 0.01f, "the descendants moved with it");
        assertEquals(okY + 20, node("OK").y(), 0.01f);
        assertTrue(bridge.events.stream().anyMatch(event ->
                event.type() == AccessibleEvent.Type.BOUNDS_CHANGED && event.nodeId() == after.id()),
                "the card's own bounds event names its id: " + bridge.events);
    }

    // ------------------------------------------------------------------------------ identity

    /**
     * The card's identifier survives frames and a rebuild of the body: the serial is keyed on the
     * widget, and the card is never rebuilt.
     */
    @Test
    void theCardKeepsItsIdentityAcrossFramesAndContentChanges() {
        bindNative(cancelAndOk());
        long id = card().id();

        frame();
        assertEquals(id, card().id());

        dialog.setContent(new Field("Name"));
        frame();
        assertEquals(id, card().id(), "adding content rebuilds the body, not the card");
        assertTrue(isDescendantOf(node("Name"), card()), describe(tree()));

        dialog.setContent(null);
        frame();
        assertEquals(id, card().id(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ language

    /**
     * The card's node carries the scene's language and its name is the title resolved under it,
     * which a name resolved outside the walk's scope would get wrong.
     */
    @Test
    void theCardResolvesItsNameUnderTheScenesLanguage() {
        bindNative(cancelAndOk());
        Locale hebrew = Locale.forLanguageTag("he");
        assertNotEquals(titleIn(hebrew), title(),
                "sanity: the title really is translated, or this case proves nothing");

        scene.setLocale(hebrew);
        frame();

        assertEquals(hebrew, card().locale(), describe(tree()));
        assertEquals(titleIn(hebrew), card().name(), describe(tree()));
        assertEquals(titleIn(hebrew), node(Accessible.Role.HEADING).name(),
                "and the heading agrees with it");
    }

    // ---------------------------------------------------------------------------------- cost

    /**
     * A frame that damages the card and changes nothing about it allocates nothing to say so,
     * as a scene root.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor that has nothing to do with this widget. A title or message
     * resolved in the hook, or the variable-argument action call, would show up here.
     */
    @Test
    void aQuietFrameWithTheCardBoundAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindNative(cancelAndOk());
        assertQuietFrameIsFree(dialog.contentRoot());
    }

    /** The same promise with the card drawn in scene, once the fade has settled. */
    @Test
    void aQuietFrameWithTheCardInSceneAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        showInScene(cancelAndOk());
        assertQuietFrameIsFree(dialog.contentRoot());
    }

    private void assertQuietFrameIsFree(Widget card) {
        int published = bridge.published.size();
        bridge.events.clear();

        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            card.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            card.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a card that did not move must cost no memory: the name and the "
                        + "message are references compared by identity, and the rest is bits");
    }
}
