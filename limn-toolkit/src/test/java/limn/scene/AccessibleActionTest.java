package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one path from a platform into toolkit state, driven from where a bridge actually stands.
 *
 * <p>Every case here calls the host from a thread that is not the user-interface thread, because
 * that is what a bridge does on two of the three platforms and a test that called it from the
 * right thread would prove nothing about the one property that matters: the bridge resolves
 * nothing itself. It checks one immutable fact, posts, and returns; the map from an identifier to
 * the widget that owns it is read on the thread that owns it, where every real precondition is
 * re-checked as the task arrives.
 *
 * <p><b>The widget performs its own action.</b> Nothing gains a public method that re-derives a
 * guard the component already has: the hook is on the widget, so a component reaches its own
 * private path with its own enabled check and its own notification, and an assistive technology's
 * toggle tells the application exactly as a click does.
 */
class AccessibleActionTest extends AccessibleTestBase {

    private Probe probe;

    private void bindProbe() {
        Group root = new Group();
        probe = new Probe(Accessible.Role.BUTTON, "Save");
        probe.setFocusable(true);
        probe.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        root.add(probe);
        bind(root);
        frame();
        bridge.events.clear();
    }

    /** Calls the host from another thread, as a bridge does, and waits for the answer. */
    private boolean performOffThread(long nodeId, Accessible.Action action,
                                     Accessible.Argument arg) throws Exception {
        AtomicBoolean accepted = new AtomicBoolean();
        Thread caller = new Thread(
                () -> accepted.set(bridge.host.perform(nodeId, action, arg)), "platform-thread");
        caller.start();
        caller.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(caller.isAlive(), "the host must never block its caller");
        runtime.drain();
        return accepted.get();
    }

    @Test
    void anActionReachesTheWidgetItself() throws Exception {
        bindProbe();

        boolean accepted = performOffThread(node("Save").id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE);

        assertTrue(accepted, "accepted, which is not the same as done");
        assertEquals(List.of("PRESS(None[])"), probe.performed);
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a press an assistive technology performed is acknowledged: " + bridge.events);
    }

    @Test
    void aParameterisedSetterArrivesWithItsArgument() throws Exception {
        bindProbe();

        performOffThread(node("Save").id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(42));

        assertEquals(List.of("SET_VALUE(OfValue[value=42.0])"), probe.performed);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "only a press is acknowledged that way");
    }

    @Test
    void anIdentifierThatIsNotInThePublishedTreeIsRefusedImmediately() throws Exception {
        bindProbe();

        assertFalse(performOffThread(999_999, Accessible.Action.PRESS, Accessible.Argument.NONE),
                "the one refusal that can honestly be immediate");
        assertTrue(probe.performed.isEmpty());
    }

    @Test
    void aDisabledWidgetDoesNothingAndSaysSo() throws Exception {
        bindProbe();
        long id = node("Save").id();
        probe.setEnabled(false);

        performOffThread(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertTrue(probe.performed.isEmpty(), "the keyboard refuses it, and so does this");
    }

    /**
     * And a control inside a disabled container, which its own flag would not catch: a widget's
     * own enabled predicate answers for itself and consults no ancestor.
     */
    @Test
    void aControlInsideADisabledContainerDoesNothingEither() throws Exception {
        Group root = new Group();
        Group form = new Group();
        probe = new Probe(Accessible.Role.BUTTON, "Save");
        probe.setFocusable(true);
        form.add(probe);
        root.add(form);
        bind(root);
        frame();
        long id = node("Save").id();

        form.setEnabled(false);
        performOffThread(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertTrue(probe.isEnabled(), "its own flag still says yes");
        assertTrue(probe.performed.isEmpty(), "and the action is refused anyway");
    }

    @Test
    void aHiddenWidgetDoesNothing() throws Exception {
        bindProbe();
        long id = node("Save").id();
        probe.setVisible(false);

        performOffThread(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertTrue(probe.performed.isEmpty());
    }

    @Test
    void adetachedWidgetDoesNothing() throws Exception {
        bindProbe();
        long id = node("Save").id();
        probe.parent().remove(probe);

        performOffThread(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertTrue(probe.performed.isEmpty());
    }

    /**
     * Behind an open layer, nothing underneath may be operated — which the tree already says, and
     * which is re-checked here anyway because a snapshot can predate the layer.
     */
    @Test
    void aWidgetBehindAModalDoesNothing() throws Exception {
        bindProbe();
        long id = node("Save").id();

        Group dialog = new Group();
        dialog.add(new Probe(Accessible.Role.BUTTON, "confirm"));
        scene.pushOverlay(dialog);
        performOffThread(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertTrue(probe.performed.isEmpty(),
                "an invocation refused with nothing said about why is what the tree exists to "
                        + "prevent, and the gate is what keeps it honest anyway");
    }

    @Test
    void anActionOnASyntheticChildReachesTheOtherHookWithItsKey() throws Exception {
        Group root = new Group();
        Menu menu = new Menu();
        root.add(menu);
        bind(root);
        frame();

        performOffThread(node("Open").id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(List.of("child 17: PRESS"), menu.performed,
                "a model index of zero is a legitimate key, so the two hooks cannot share one");
    }

    // ------------------------------------------------------------------------ the two free verbs

    /**
     * ADR 039 §1.5 says a focusable widget gets {@code FOCUS} and {@code SCROLL_INTO_VIEW} for
     * free, and the walk advertises both on every focusable node. Nothing performed them until the
     * scene did: the hook's default refuses and no component wrote the two lines, so a reader's
     * request for focus was accepted and dropped everywhere in the toolkit.
     */
    @Test
    void theFocusVerbMovesTheKeyboardAndIsHeard() throws Exception {
        bindProbe();
        assertFalse(probe.isFocused(), "the fixture starts with the focus nowhere");

        assertTrue(performOffThread(node("Save").id(), Accessible.Action.FOCUS,
                Accessible.Argument.NONE));

        assertTrue(probe.isFocused(), "the verb the walk advertised is the verb it performs");
        frame();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED),
                "and the move is published like any other: " + bridge.events);
    }

    /**
     * The widget is not asked at all, which is the whole point: it never declared either verb, so
     * a hook that answered for one would be answering for something it does not own. This probe's
     * hook accepts everything &mdash; a shape a real widget could take, and one that would have
     * swallowed the request silently if the scene had asked it first.
     */
    @Test
    void theFreeVerbsNeverReachTheWidgetsHook() throws Exception {
        bindProbe();

        performOffThread(node("Save").id(), Accessible.Action.FOCUS, Accessible.Argument.NONE);
        performOffThread(node("Save").id(), Accessible.Action.SCROLL_INTO_VIEW,
                Accessible.Argument.NONE);

        assertEquals(List.of(), probe.performed,
                "and the hook's contract is unchanged: it answers for what it declared");
        assertTrue(probe.isFocused(), "while the verbs themselves were performed");
    }

    /** A widget the walk never offered either verb on is refused both. */
    @Test
    void anUnfocusableWidgetIsRefusedBothFreeVerbs() throws Exception {
        Group root = new Group();
        probe = new Probe(Accessible.Role.LABEL, "Total");
        root.add(probe);
        bind(root);
        frame();
        long id = node("Total").id();

        performOffThread(id, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        performOffThread(id, Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE);

        assertFalse(probe.isFocused(),
                "the walk offers the two to focusable nodes and to no other, so the scene "
                        + "performs them there and nowhere else");
        assertNull(bridge.first(AccessibleEvent.Type.FOCUS_CHANGED), bridge.events.toString());
    }

    @Test
    void theScrollIntoViewVerbMovesThePaneAndTheNodeBecomesShowing() throws Exception {
        Group content = new Group();
        Probe spacer = new Probe();
        spacer.prefHeight = 400;
        content.add(spacer);
        probe = new Probe(Accessible.Role.BUTTON, "Save");
        probe.setFocusable(true);
        content.add(probe);
        limn.components.ScrollView pane = new limn.components.ScrollView(content);
        Group root = new Group();
        root.add(pane);
        bind(root);
        frame();

        assertEquals(0, pane.offsetY(), 1e-3, "the fixture starts at the top");
        assertFalse(node("Save").has(Accessible.State.SHOWING),
                "and the probe is below the fold" + describe(tree()));

        assertTrue(performOffThread(node("Save").id(), Accessible.Action.SCROLL_INTO_VIEW,
                Accessible.Argument.NONE));
        frame();

        assertTrue(pane.offsetY() > 0, "the pane scrolled to it");
        assertTrue(node("Save").has(Accessible.State.SHOWING),
                "and the node the reader asked about says so" + describe(tree()));
    }

    /**
     * The relaxation is the clip and not visibility. A widget the scene refuses to show at all
     * &mdash; an unselected tab's contents, anything under a hidden container &mdash; is refused
     * both verbs like every other: revealing it would scroll to a box that paints nothing, and
     * focusing it would put the keyboard somewhere the user cannot see.
     */
    @Test
    void aWidgetUnderAHiddenAncestorIsRefusedTheFreeVerbsToo() throws Exception {
        Group root = new Group();
        Group page = new Group();
        probe = new Probe(Accessible.Role.BUTTON, "Save");
        probe.setFocusable(true);
        page.add(probe);
        root.add(page);
        bind(root);
        frame();
        long id = node("Save").id();
        page.setVisible(false);
        frame();
        assertNotNull(tree().node(tree().indexOf(id)),
                "the fixture needs the node still in the tree, or this refuses for want of an "
                        + "owner and proves nothing" + describe(tree()));

        performOffThread(id, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        performOffThread(id, Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE);

        assertFalse(probe.isFocused(), "hidden is not merely scrolled away");
    }

    @Test
    void aRefusedActionRaisesNoAcknowledgement() throws Exception {
        bindProbe();
        probe.accepts = false;

        performOffThread(node("Save").id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertNull(bridge.first(AccessibleEvent.Type.INVOKED));
    }

    /**
     * {@code FOCUS} on an item is the item's own verb and not the walk's free one (ADR 039 §1.5,
     * amended 2026-09-14; decision 11): a widget that paints its rows publishes it on a row where
     * the cursor and the selection are separate things, and the scene hands it to the synthetic
     * hook with the row's key -- it moves the widget's cursor, not the keyboard -- while the same
     * verb on the widget's own node stays the scene's {@code requestFocus()}.
     */
    @Test
    void theFocusVerbOnAnItemReachesTheSyntheticHookAndNotTheScenesFreeVerb() throws Exception {
        Group root = new Group();
        Menu menu = new Menu();
        menu.setFocusable(true);
        root.add(menu);
        bind(root);
        frame();
        assertTrue(node("Open").actions().has(Accessible.Action.FOCUS),
                "the row publishes it" + describe(tree()));
        assertFalse(menu.isFocused(), "the fixture starts with the focus nowhere");

        assertTrue(performOffThread(node("Open").id(), Accessible.Action.FOCUS,
                Accessible.Argument.NONE));

        assertEquals(List.of("child 17: FOCUS"), menu.performed,
                "the row's own verb, with the row's key");
        assertFalse(menu.isFocused(), "and not the keyboard moving to the widget");

        performOffThread(node("File").id(), Accessible.Action.FOCUS,
                Accessible.Argument.NONE);

        assertTrue(menu.isFocused(), "on the widget itself it is still the walk's free verb");
        assertEquals(List.of("child 17: FOCUS"), menu.performed, "which no hook is asked about");
    }

    // ------------------------------------------------------------------------ delegated verbs

    /**
     * A verb a container claims on a widget child (ADR 039 §1.5, amended 2026-09-14; decision 7)
     * is published on the child, where a reader addresses the row, and performed by the
     * container, which is the only thing that knows what selecting that row means: the scene
     * routes it to the container's child-action hook with the key the container gave the child.
     * The child's own verbs still reach the child, and the child's hook never hears the
     * delegated one.
     */
    @Test
    void aVerbAContainerClaimsOnAChildReachesTheContainerWithTheChildsKey() throws Exception {
        Group root = new Group();
        Rows rows = new Rows();
        Probe first = new Probe(Accessible.Role.BUTTON, "First");
        first.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        Probe second = new Probe(Accessible.Role.BUTTON, "Second");
        second.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        rows.add(first);
        rows.add(second);
        root.add(rows);
        bind(root);
        frame();
        assertTrue(node("Second").actions().has(Accessible.Action.SELECT),
                "the delegated verb is the child's to a reader" + describe(tree()));
        assertTrue(node("Second").actions().has(Accessible.Action.PRESS),
                "beside the child's own" + describe(tree()));

        assertTrue(performOffThread(node("Second").id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE));
        assertTrue(performOffThread(node("Second").id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));

        assertEquals(List.of("row 1: SELECT"), rows.performed,
                "the container's hook, with the key it gave the child");
        assertEquals(List.of("PRESS(None[])"), second.performed,
                "the child's own verb reaches the child, and the delegated one never does");
        assertEquals(List.of(), first.performed);
    }

    /**
     * The scene gates a delegated verb on the child the reader addressed, as it gates any verb
     * on its node, and on the container still being that child's parent: a container that let
     * the child go since the walk is handed nothing.
     */
    @Test
    void aDelegatedVerbIsGatedOnTheChildAndOnTheContainerStillHoldingIt() throws Exception {
        Group root = new Group();
        Rows rows = new Rows();
        Probe row = new Probe(Accessible.Role.BUTTON, "Row");
        rows.add(row);
        root.add(rows);
        bind(root);
        frame();
        long id = node("Row").id();

        row.setEnabled(false);
        performOffThread(id, Accessible.Action.SELECT, Accessible.Argument.NONE);
        assertEquals(List.of(), rows.performed, "the child is disabled, so its node refuses");

        row.setEnabled(true);
        rows.remove(row);
        root.add(row);
        performOffThread(id, Accessible.Action.SELECT, Accessible.Argument.NONE);
        assertEquals(List.of(), rows.performed,
                "the child left the container after the walk, so the container is not asked");
    }

    /**
     * A delegated verb is the container's to perform, so it is gated on the container being on
     * the glass and not on the child (decision 22 read with semantics 5; ADR 039 §1.5, amended
     * 2026-09-15): a list's cursor row kept outside its viewport is exactly the row a reader
     * stands on, and a gate on the row's own showing answered yes in {@code Host.perform} and
     * then dropped every verb but {@code SCROLL_INTO_VIEW}. A container that is itself clipped
     * away is still refused, and so is a child hidden by its own flag.
     */
    @Test
    void aDelegatedVerbIsGatedOnTheContainerShowingAndNotOnTheChild() throws Exception {
        Group root = new Group();
        Rows rows = new Rows();
        rows.clipTo = 20; // one row's height: the second row is clipped out of the box
        Probe first = new Probe(Accessible.Role.BUTTON, "First");
        Probe second = new Probe(Accessible.Role.BUTTON, "Second");
        rows.add(first);
        rows.add(second);
        root.add(rows);
        bind(root);
        frame();
        assertFalse(node("Second").has(Accessible.State.SHOWING),
                "the fixture clips the second row out of its container" + describe(tree()));
        assertTrue(node("First").has(Accessible.State.SHOWING), describe(tree()));

        assertTrue(performOffThread(node("Second").id(), Accessible.Action.SELECT,
                Accessible.Argument.NONE));
        assertEquals(List.of("row 1: SELECT"), rows.performed,
                "the container is showing, so the verb it claimed on a clipped row reaches it");

        long secondId = node("Second").id();
        second.setVisible(false); // the snapshot still holds the node: the gate is what refuses
        performOffThread(secondId, Accessible.Action.SELECT, Accessible.Argument.NONE);
        assertEquals(List.of("row 1: SELECT"), rows.performed,
                "a child hidden by its own flag is refused, whatever its container");
    }

    /**
     * The routing table is recorded on every walk, published or not (ADR 039 §1.5, amended
     * 2026-09-14), and a walk under an overlay withdraws the verb from publication without
     * forgetting who performs it. A reader that took SELECT off the snapshot from before the
     * overlay opened, and sends it after the overlay closed but before the next walk, reaches
     * the container and never the child; while the overlay is up the layer gate refuses it.
     */
    @Test
    void aDelegatedVerbSentAfterAnOverlayClosedStillReachesTheContainer() throws Exception {
        Group root = new Group();
        Rows rows = new Rows();
        Probe row = new Probe(Accessible.Role.BUTTON, "Row");
        rows.add(row);
        root.add(rows);
        bind(root);
        frame();
        long id = node("Row").id();
        assertTrue(node("Row").actions().has(Accessible.Action.SELECT), describe(tree()));

        Group dialog = new Group();
        dialog.add(new Probe(Accessible.Role.BUTTON, "Confirm"));
        scene.pushOverlay(dialog);
        frame();
        assertNull(node("Row").actions(),
                "beneath the overlay the row publishes nothing: " + describe(tree()));
        performOffThread(id, Accessible.Action.SELECT, Accessible.Argument.NONE);
        assertEquals(List.of(), rows.performed, "the layer gate refuses it while the overlay is up");
        assertEquals(List.of(), row.performed);

        scene.removeOverlay(dialog);
        performOffThread(id, Accessible.Action.SELECT, Accessible.Argument.NONE); // no walk yet

        assertEquals(List.of("row 0: SELECT"), rows.performed,
                "the verb the container claimed is still routed to the container");
        assertEquals(List.of(), row.performed, "and never dispatched to the child's own hook");
    }

    /**
     * An overlay is parentless, and its enabled axis resolves through its host: the walk publishes
     * the contents of a disabled control's popup without {@code ENABLED} and so with no verb (ADR
     * 039 §1.5, amended 2026-09-15), and the scene refuses them on the same chain, so what is
     * published and what is performed are one reading (§1.9, corrected the same day). Until then
     * the gate stopped at the overlay and performed a press the snapshot no longer offered.
     */
    @Test
    void aVerbInsideAnOverlayWhoseHostIsDisabledIsNeitherPublishedNorPerformed() throws Exception {
        Group root = new Group();
        Group form = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Opener");
        form.add(opener);
        root.add(form);
        bind(root);
        Group dialog = new Group();
        Probe confirm = new Probe(Accessible.Role.BUTTON, "Confirm");
        confirm.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        dialog.add(confirm);
        dialog.setInheritanceHost(opener);
        scene.pushOverlay(dialog);
        frame();
        assertTrue(node("Confirm").actions().has(Accessible.Action.PRESS), describe(tree()));
        assertTrue(performOffThread(node("Confirm").id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        assertEquals(List.of("PRESS(None[])"), confirm.performed, "an enabled host: performed");

        form.setEnabled(false); // the host's own flag stays: its parent is what is disabled
        scene.requestRender();
        frame();
        assertFalse(node("Confirm").has(Accessible.State.ENABLED), describe(tree()));
        assertNull(node("Confirm").actions(),
                "the popup of a disabled control publishes no verb" + describe(tree()));
        performOffThread(node("Confirm").id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        assertEquals(List.of("PRESS(None[])"), confirm.performed,
                "and the scene refuses the one sent anyway, on the chain the walk read");
    }

    /** The other half: a container clipped out of an ancestor performs nothing it claimed. */
    @Test
    void aDelegatedVerbOnAChildOfAContainerClippedAwayIsRefused() throws Exception {
        Group root = new Group();
        Rows pane = new Rows(); // a clipping box one row high, holding a spacer and then the list
        pane.clipTo = 20;
        pane.add(new Probe());
        Rows rows = new Rows();
        Probe row = new Probe(Accessible.Role.BUTTON, "Row");
        rows.add(row);
        pane.add(rows);
        root.add(pane);
        bind(root);
        frame();
        assertFalse(node("Row").has(Accessible.State.SHOWING), describe(tree()));

        performOffThread(node("Row").id(), Accessible.Action.SELECT, Accessible.Argument.NONE);

        assertEquals(List.of(), rows.performed,
                "the container is scrolled off the glass, so nothing it claimed is performed");
    }

    /**
     * A container that keys its children by position and claims SELECT on each; given a
     * {@link #clipTo}, a box that high which clips what it holds.
     */
    private static final class Rows extends Group {
        final List<String> performed = new ArrayList<>();
        float clipTo;

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size content = super.onMeasure(constraints);
            return clipTo > 0 ? constraints.constrain(content.width(), clipTo) : content;
        }

        @Override
        protected boolean clipsChildren() {
            return clipTo > 0;
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            a.role(Accessible.Role.LIST);
        }

        @Override
        protected void onAccessibilityChildIdentity(Widget child,
                                                    limn.accessibility.Accessibility a) {
            a.key(children().indexOf(child));
        }

        @Override
        protected void onAccessibilityChild(Widget child, limn.accessibility.Accessibility a) {
            a.delegate(Accessible.Action.SELECT);
        }

        @Override
        protected boolean onAccessibilityChildAction(Widget child, long key,
                                                     Accessible.Action action,
                                                     Accessible.Argument arg) {
            performed.add("row " + key + ": " + action);
            return true;
        }
    }

    /** A widget that draws its own rows and never instantiated one of them as a widget. */
    private static final class Menu extends Probe {
        Menu() {
            super(Accessible.Role.MENU, "File");
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            super.onAccessibility(a);
            a.child(17);
            a.role(Accessible.Role.MENU_ITEM);
            a.name(limn.i18n.I18nString.literal("Open"));
            a.bounds(0, 0, 40, 10);
            a.action(Accessible.Action.PRESS, Accessible.Action.FOCUS);
            a.endChild();
        }
    }
}
