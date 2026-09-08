package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When this bridge joins the accessibility bus, and why not earlier.
 *
 * <p>Fedora 44 found the rule these pin. Its at-spi2-core 2.60 registry reads an application as it
 * registers — role, name, a whole {@code Cache.GetItems} — and one that answers "no children" is
 * one it never adds to the desktop. The bridge used to join at construction, before any scene had
 * been built, so it registered with an empty tree every time; Ubuntu's 2.52 adds first and reads
 * later, which is why that was invisible for every run this bridge had had.
 */
class AtspiRegistrationTest {

    private static AccessibleTree aWindow() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    @Test
    void anEmptyTreeJoinsNothing() {
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        bridge.publish(AccessibleTree.EMPTY, false);
        assertFalse(bridge.isOnTheBus(),
                "registering with nothing to show is what Fedora refuses to list");
    }

    @Test
    void theGateDoesNotDependOnBeingOnTheBus() {
        // The cycle this avoids has no way out: a scene publishes only when something is listening,
        // and the bus is joined on the first publish. macOS solves the same shape with a priming
        // publish; this platform can just ask the desktop, which is the honest gate anyway (§6).
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        assertFalse(bridge.isOnTheBus());
        assertTrue(bridge.isListening(),
                "the desktop said accessibility is on, and that is the whole question");
    }

    @Test
    void aTreeWithNodesTriesToJoinExactlyOnce() {
        // The attempt is counted rather than inferred from the connection: whether it SUCCEEDS
        // depends on whether this machine has an accessibility bus, and whether it is MADE does
        // not. The defect was never making it at a moment when there was a tree.
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        assertEquals(0, bridge.joinAttempts());
        bridge.publish(AccessibleTree.EMPTY, false);
        assertEquals(0, bridge.joinAttempts(), "nothing to show, nothing to register");
        bridge.publish(aWindow(), false);
        assertEquals(1, bridge.joinAttempts());
    }

    @Test
    void aBridgeAlreadyOnTheBusDoesNotJoinAgainOnEveryFrame() {
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        bridge.publish(aWindow(), false);
        int after = bridge.joinAttempts();
        for (int frame = 0; frame < 5; frame++) {
            bridge.publish(aWindow(), false);
        }
        // On a machine with a bus the connection is held and no further attempt is made. On one
        // without, connect() failed and retrying each frame is the honest behaviour rather than
        // giving up for the life of the window -- so this asserts the shape, not a fixed number.
        assertTrue(bridge.isOnTheBus() ? after == bridge.joinAttempts()
                                       : bridge.joinAttempts() > after);
    }
    /** A window holding one button, published the way a scene publishes one. */
    private static AccessibleTree aWindowWithAButton() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static DBus.Msg doAction(AtspiBridge bridge, long nodeId, int index) {
        String path = "/org/a11y/atspi/accessible/" + nodeId;
        DBus.Msg m = DBus.Msg.call("org.a11y.atspi.Registry", path, Atspi.I_ACTION, "DoAction",
                "i", index);
        m.path = path;
        m.iface = Atspi.I_ACTION;
        m.member = "DoAction";
        return bridge.objects().handle(null, m);
    }

    @Test
    void aClientsDoActionReachesTheHostTheSceneAttached() {
        // AtspiTreeTest proves the tree asks whatever host it is handed. This proves the BRIDGE
        // hands it the host the scene attached: the two were once wired through a field of the
        // bridge's own that shadowed the superclass's and was never written, so the tree always
        // saw null and every DoAction on this platform answered false while macOS and Windows,
        // which read the accessor, performed.
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        List<String> performed = new ArrayList<>();
        bridge.attach(new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return bridge.tree(); }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) {
                performed.add(nodeId + ":" + action);
                return true;
            }
        });
        bridge.publish(aWindowWithAButton(), false);

        DBus.Msg done = doAction(bridge, 1001, 0);
        assertEquals(true, done.body[0], "the reply a client sees");
        assertEquals(List.of("1001:PRESS"), performed,
                "the host the scene attached is the one asked to perform");
    }

    @Test
    void afterADetachADoActionReachesNobody() {
        AtspiBridge bridge = AtspiBridge.withoutTheGate();
        List<String> performed = new ArrayList<>();
        bridge.attach(new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return bridge.tree(); }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) {
                performed.add(nodeId + ":" + action);
                return true;
            }
        });
        AccessibleTree tree = aWindowWithAButton();
        bridge.publish(tree, false);
        bridge.detach();
        // The tree is gone with the host, so the path names nothing and the reply is nobody's.
        // Re-publishing without re-attaching is the shape a late frame has: a tree and no scene.
        bridge.publish(tree, false);

        DBus.Msg done = doAction(bridge, 1001, 0);
        assertEquals(false, done.body[0], "a detached bridge performs nothing");
        assertEquals(List.of(), performed);
    }
}
