package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When this bridge joins the accessibility bus, and why not earlier.
 *
 * <p>Fedora 44 found the rule these pin. Its at-spi2-core 2.60 registry reads an application as it
 * registers — role, name, a whole {@code Cache.GetItems} — and one that answers "no children" is
 * one it never adds to the desktop. The bridge used to join at construction, before any scene had
 * been built, so it registered with an empty tree every time; Ubuntu's 2.52 adds first and reads
 * later, which is why that was invisible for every run this bridge had had.
 *
 * <p>And on which thread, and how often (LINUX-NEW-12, 2026-09-15). The join used to run inside the
 * publish, on the user-interface thread, as up to four round trips of up to fifteen seconds each;
 * a failure left the accessibility connection and its two threads behind, and the next frame tried
 * again. The join is now one short-lived thread per attempt, closes what it opened when it fails,
 * and waits out a back-off before the next attempt.
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

    /** Buses whose accessibility connection opens and then refuses one step of the join. */
    private static final class RefusingBuses implements AtspiApplication.Buses {
        final String refusedStep;
        int opened;
        int closed;

        RefusingBuses(String refusedStep) {
            this.refusedStep = refusedStep;
        }

        @Override public String a11yAddress() {
            return "unix:path=/nowhere";
        }

        @Override public AtspiApplication.Bus open(String address) {
            opened++;
            return new AtspiApplication.Bus() {
                @Override public String hello() throws IOException {
                    if (refusedStep.equals("Hello")) {
                        throw new IOException("timeout after 15000 ms waiting for reply to Hello");
                    }
                    return ":1.7";
                }

                @Override public void exportFallback(DBus.Handler handler) { }

                @Override public Object[] embed(Object[] root) {
                    if (refusedStep.equals("Embed")) {
                        throw new DBus.DBusError("org.freedesktop.DBus.Error.ServiceUnknown",
                                "The name org.a11y.atspi.Registry was not provided");
                    }
                    return new Object[0];
                }

                @Override public AtspiApplication.Link link() {
                    throw new AssertionError("a refused join hands out no link");
                }

                @Override public void close() {
                    closed++;
                }
            };
        }
    }

    @Test
    void aJoinThatFailsClosesTheConnectionItOpened() {
        for (String step : List.of("Hello", "Embed")) {
            RefusingBuses buses = new RefusingBuses(step);
            AtspiTree objects = AtspiApplication.forThisMachine().objects();
            assertThrows(IOException.class, () -> AtspiApplication.join(buses, objects),
                    "a refused " + step + " is a failed join");
            assertEquals(1, buses.opened);
            assertEquals(1, buses.closed, "a refused " + step + " closes the connection it opened: "
                    + "left open, it was a socket and two threads per attempt");
        }
    }

    @Test
    void aFailedJoinIsTriedAgainAfterItsBackOffAndNotOnEveryFrame() {
        long[] now = {1_000_000_000L};
        RefusingBuses buses = new RefusingBuses("Embed");
        AtspiApplication application = new AtspiApplication(
                objects -> AtspiApplication.join(buses, objects),
                AtspiApplication.Starter.ON_THE_CALLER, () -> now[0]);
        AtspiBridge bridge = application.window();

        for (int frame = 0; frame < 10; frame++) {
            bridge.publish(aWindow(), false);
        }
        assertEquals(1, bridge.joinAttempts(),
                "ten frames inside the back-off make one attempt, not ten");
        assertEquals(1, buses.closed);

        now[0] += AtspiApplication.FIRST_RETRY_NANOS;
        bridge.publish(aWindow(), false);
        assertEquals(2, bridge.joinAttempts(), "past the back-off the next frame tries again");

        now[0] += AtspiApplication.FIRST_RETRY_NANOS;
        bridge.publish(aWindow(), false);
        assertEquals(2, bridge.joinAttempts(), "and the second failure waits twice as long");
        now[0] += AtspiApplication.FIRST_RETRY_NANOS;
        bridge.publish(aWindow(), false);
        assertEquals(3, bridge.joinAttempts());
        assertFalse(bridge.isOnTheBus());
    }

    @Test
    void aPublishNeverWaitsForTheJoin() throws InterruptedException {
        CountDownLatch registryAnswers = new CountDownLatch(1);
        CountDownLatch joinedLatch = new CountDownLatch(1);
        AtspiApplication application = new AtspiApplication(objects -> {
            // A registry that takes its time over Embed, as a busy or hung one does: the join waits.
            try {
                registryAnswers.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            joinedLatch.countDown();
            return new AtspiApplication.Link() {
                @Override public boolean signal(DBus.Msg signal) { return true; }
                @Override public void close() { }
            };
        }, AtspiApplication.Starter.DAEMON, System::nanoTime);
        AtspiBridge bridge = application.window();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int frame = 0; frame < 3; frame++) {
                bridge.publish(aWindow(), false);
            }
        }, "a frame must not wait on the registry: the join is not the user-interface thread's");
        assertEquals(1, bridge.joinAttempts(), "a join in flight is not started again");
        assertFalse(bridge.isOnTheBus());

        registryAnswers.countDown();
        assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 500 && !bridge.isOnTheBus(); i++) {
            Thread.sleep(10);
        }
        assertTrue(bridge.isOnTheBus(), "and the join completes on its own thread");
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
