package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aRefusedActionRaisesNoAcknowledgement() throws Exception {
        bindProbe();
        probe.accepts = false;

        performOffThread(node("Save").id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertNull(bridge.first(AccessibleEvent.Type.INVOKED));
    }

    /** A widget that draws its own rows and never instantiated one of them as a widget. */
    private static final class Menu extends Probe {
        Menu() {
            role = Accessible.Role.MENU;
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            super.onAccessibility(a);
            a.child(17);
            a.role(Accessible.Role.MENU_ITEM);
            a.name(limn.i18n.I18nString.literal("Open"));
            a.bounds(0, 0, 40, 10);
            a.action(Accessible.Action.PRESS);
            a.endChild();
        }
    }
}
