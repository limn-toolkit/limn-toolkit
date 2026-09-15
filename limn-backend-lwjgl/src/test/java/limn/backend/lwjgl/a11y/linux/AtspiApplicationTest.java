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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One process, one AT-SPI2 application, every window a frame of it (ADR 039 §2.3, LINUX-NEW-8).
 *
 * <p>Until 2026-09-15 each native window did its own {@code Socket.Embed} on a connection of its own,
 * so a native popup — a DatePicker's calendar, a ComboBox's list — was a second application on the
 * desktop named "popup", and the {@code POPUP_FOR} its root carries named a field in an application
 * a client had no way to reach from it. These pin the shape that replaced it, with the join faked:
 * no socket is opened, and every assertion is a reply the reader thread would send or a signal the
 * writer thread would be handed.
 */
class AtspiApplicationTest {

    private static final String BUS = ":1.42";

    /** A connector that joins at once and records what the application sends. */
    private static final class FakeBus implements AtspiApplication.Connector {
        int joins;
        boolean closed;
        final List<DBus.Msg> signals = new ArrayList<>();

        Runnable lost;

        @Override
        public AtspiApplication.Link join(AtspiTree objects, Runnable whenLost) {
            joins++;
            lost = whenLost;
            objects.busName(BUS);
            return new AtspiApplication.Link() {
                @Override public boolean signal(DBus.Msg signal) {
                    signals.add(signal);
                    return true;
                }

                @Override public void close() {
                    closed = true;
                }
            };
        }
    }

    /** A host that records what it is asked to perform, under a name. */
    private static AccessibilityBridge.Host hostRecording(String who, List<String> performed,
                                                          AccessibilityBridge bridge) {
        return new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) {
                performed.add(who + ":" + nodeId + ":" + action);
                return true;
            }
        };
    }

    /** The ids a published window holds: its own node, and the one control inside it. */
    private record Published(AccessibleTree tree, long window, long control) {
    }

    /**
     * A window holding one button, with identifiers minted the way a scene mints them: tagged with
     * the builder's scene, so two windows never share one.
     *
     * @param popupFor the node this window is the popup of, or 0
     */
    private static Published aWindow(String title, long popupFor) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        long window = a.mint();
        long control = a.mint();
        a.begin(window, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal(title), Accessible.NameFrom.EXPLICIT);
        if (popupFor != 0) {
            a.relation(Accessible.Relation.POPUP_FOR, popupFor);
        }
        a.inherited(true, true, true, false, false);
        a.begin(control, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal(title + " button"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        return new Published(a.publish(0, 0, 0, 1f, true), window, control);
    }

    /**
     * An application joining through {@code bus} on the calling thread, so a publish has joined, on
     * a desktop whose switch says assistive technology is running.
     */
    private static AtspiApplication anApplication(FakeBus bus) {
        AtspiApplication app = new AtspiApplication(bus, AtspiApplication.Starter.ON_THE_CALLER,
                System::nanoTime);
        app.enabled(true);
        return app;
    }

    /** A host that counts the publishes it is asked for. */
    private static AccessibilityBridge.Host hostCounting(int[] republishes) {
        return new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { republishes[0]++; }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) { return false; }
        };
    }

    @Test
    void theSwitchDecidesWhetherAWindowListensAndJoinsAndTurningItOnWakesEveryWindow() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = new AtspiApplication(bus, AtspiApplication.Starter.ON_THE_CALLER,
                System::nanoTime);
        AtspiBridge window = app.window();
        int[] republishes = {0};
        window.attach(hostCounting(republishes));
        assertFalse(window.isListening(), "nothing is reading until the desktop says so");
        window.publish(aWindow("Main", 0).tree(), false);
        assertEquals(0, bus.joins, "a tree published while the switch is off joins nothing");

        // A scene bound while the switch is off never publishes, so its window is known only by
        // its attach.
        AtspiBridge quiet = app.window();
        int[] quietRepublishes = {0};
        quiet.attach(hostCounting(quietRepublishes));

        app.enabled(true);
        assertTrue(window.isListening());
        assertEquals(1, republishes[0]);
        assertEquals(1, quietRepublishes[0], "the window whose scene never published is woken: "
                + "an application started before the screen reader becomes readable");
        app.enabled(true);
        assertEquals(1, republishes[0], "a switch that did not move wakes nobody");
        window.publish(aWindow("Main", 0).tree(), false);
        assertEquals(1, bus.joins);
        assertTrue(app.isJoined());

        app.enabled(false);
        assertFalse(window.isListening());
        assertFalse(app.isJoined(), "the reader quit: the application leaves the bus");
        assertTrue(bus.closed);

        app.enabled(true);
        assertEquals(2, republishes[0]);
        assertEquals(2, quietRepublishes[0]);
        window.publish(aWindow("Main", 0).tree(), false);
        assertEquals(2, bus.joins, "and comes back when a reader does");
    }

    @Test
    void aJoinThatCompletesAfterTheSwitchWentOffLeavesAtOnce() {
        AtspiApplication[] app = new AtspiApplication[1];
        boolean[] closed = {false};
        app[0] = new AtspiApplication((objects, lost) -> {
            app[0].enabled(false);  // the reader quits while the registry is embedding us
            return new AtspiApplication.Link() {
                @Override public boolean signal(DBus.Msg signal) { return true; }
                @Override public void close() { closed[0] = true; }
            };
        }, AtspiApplication.Starter.ON_THE_CALLER, System::nanoTime);
        app[0].enabled(true);
        app[0].window().publish(aWindow("Main", 0).tree(), false);
        assertFalse(app[0].isJoined(), "nothing stays joined for a switch that is off");
        assertTrue(closed[0]);
    }

    private static String path(long id) {
        return "/org/a11y/atspi/accessible/" + id;
    }

    private static DBus.Msg call(AtspiApplication app, String path, String iface, String member,
                                 String sig, Object... args) {
        DBus.Msg m = DBus.Msg.call("org.a11y.atspi.Registry", path, iface, member, sig, args);
        m.path = path;
        m.iface = iface;
        m.member = member;
        return app.objects().handle(null, m);
    }

    private static List<String> pathsOf(Object refs) {
        List<String> out = new ArrayList<>();
        for (Object ref : (List<?>) refs) {
            out.add(DBus.Ref.of(ref).path);
        }
        return out;
    }

    @Test
    void twoWindowsAreTwoFramesOfOneApplicationOnOneConnection() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        app.name("Kitchen Sink");
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        List<String> performed = new ArrayList<>();
        main.attach(hostRecording("main", performed, main));
        popup.attach(hostRecording("popup", performed, popup));

        Published first = aWindow("Main", 0);
        Published second = aWindow("Calendar", 0);
        main.publish(first.tree(), false);
        popup.publish(second.tree(), false);

        assertEquals(1, bus.joins, "one connection for the process, joined once, not once a window");
        DBus.Msg kids = call(app, Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetChildren", null);
        assertEquals(List.of(path(first.window()), path(second.window())), pathsOf(kids.body[0]),
                "the application object's children are both windows, in the order they joined");
        @SuppressWarnings("unchecked")
        Map<Object, Object> root = (Map<Object, Object>) call(app, Atspi.PATH_ROOT, Atspi.I_PROPS,
                "GetAll", "s", Atspi.I_ACCESSIBLE).body[0];
        assertEquals(2, ((DBus.Variant) root.get("ChildCount")).value);
        assertEquals("Kitchen Sink", ((DBus.Variant) root.get("Name")).value,
                "named by the application, not by whichever window asked last");
        DBus.Msg second0 = call(app, Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetChildAtIndex", "i", 1);
        assertEquals(path(second.window()), DBus.Ref.of(second0.body[0]).path);
        assertEquals(1, call(app, path(second.window()), Atspi.I_ACCESSIBLE, "GetIndexInParent",
                null).body[0], "the second frame is the application's second child");
        @SuppressWarnings("unchecked")
        Map<Object, Object> frame = (Map<Object, Object>) call(app, path(second.window()),
                Atspi.I_PROPS, "GetAll", "s", Atspi.I_ACCESSIBLE).body[0];
        assertEquals(Atspi.PATH_ROOT, DBus.Ref.of(((DBus.Variant) frame.get("Parent")).value).path);

        List<?> items = (List<?>) call(app, Atspi.PATH_CACHE, Atspi.I_CACHE, "GetItems", null).body[0];
        assertEquals(1 + first.tree().nodeCount() + second.tree().nodeCount(), items.size(),
                "one Cache.GetItems covers the application and every window");
        for (Object item : items) {
            Object[] fields = (Object[]) item;
            if (DBus.Ref.of(fields[0]).path.equals(path(second.window()))) {
                assertEquals(1, fields[3], "the cache item says where the frame sits, too");
            }
        }

        DBus.Msg done = call(app, path(second.control()), Atspi.I_ACTION, "DoAction", "i", 0);
        assertEquals(true, done.body[0]);
        assertEquals(List.of("popup:" + second.control() + ":PRESS"), performed,
                "a verb on the popup's node goes to the popup's scene and to no other");
    }

    @Test
    void aPopupsRootNamesTheFieldThatOpenedItInTheOtherWindow() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        Published opener = aWindow("Main", 0);
        Published calendar = aWindow("Calendar", opener.control());
        main.publish(opener.tree(), false);
        popup.publish(calendar.tree(), false);

        List<?> set = (List<?>) call(app, path(calendar.window()), Atspi.I_ACCESSIBLE,
                "GetRelationSet", null).body[0];
        assertEquals(1, set.size(), "the popup's relation to its opener is published: " + set);
        Object[] entry = (Object[]) set.get(0);
        assertEquals(Atspi.RELATION_POPUP_FOR, entry[0]);
        DBus.Ref target = DBus.Ref.of(((List<?>) entry[1]).get(0));
        assertEquals(BUS, target.name, "on this connection, because it is the same application");
        assertEquals(path(opener.control()), target.path,
                "the field in the other window, which a client can now reach");
    }

    @Test
    void aFrameArrivingOrLeavingAfterTheJoinIsAnnouncedFromTheApplication() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        Published first = aWindow("Main", 0);
        main.publish(first.tree(), false);
        assertEquals(List.of(), bus.signals, "the frames the registry reads at the join are not news");

        Published second = aWindow("Calendar", 0);
        popup.publish(second.tree(), false);
        popup.publish(second.tree(), false);
        assertEquals(1, bus.signals.size(), "one arrival, announced once: " + bus.signals);
        assertFrameSignal(bus.signals.get(0), "add", 1, second.window());

        popup.detach();
        assertEquals(2, bus.signals.size());
        assertFrameSignal(bus.signals.get(1), "remove", 1, second.window());
        assertFalse(bus.closed, "the application stays while it still has a window");
        assertEquals(List.of(path(first.window())),
                pathsOf(call(app, Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetChildren", null).body[0]));

        main.detach();
        assertTrue(bus.closed, "the last window takes the connection with it, so the next window "
                + "registers with a tree rather than into an application already read as empty");
        assertFalse(app.isJoined());
    }

    @Test
    void aConnectionThatStopsOnItsOwnIsLetGoAndEveryWindowIsAskedToPublishAgain() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        int[] republishes = {0};
        AccessibilityBridge.Host host = new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { republishes[0]++; }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) { return false; }
        };
        main.attach(host);
        popup.attach(host);
        main.publish(aWindow("Main", 0).tree(), false);
        popup.publish(aWindow("Calendar", 0).tree(), false);
        assertTrue(app.isJoined());

        bus.lost.run();
        assertFalse(app.isJoined(), "a connection whose reader has stopped is not a connection: "
                + "believing it still embedded is the deaf application nobody can see");
        assertTrue(bus.closed);
        assertEquals(2, republishes[0], "each window buys the frame that joins again");

        main.publish(aWindow("Main", 0).tree(), false);
        assertEquals(2, bus.joins, "and the next publish joins again");
        assertTrue(app.isJoined());
    }

    private static void assertFrameSignal(DBus.Msg signal, String detail, int index, long frame) {
        assertEquals(Atspi.PATH_ROOT, signal.path, "sent from the application object");
        assertEquals(AtspiEvents.I_EVENT_OBJECT, signal.iface);
        assertEquals("ChildrenChanged", signal.member);
        assertEquals(AtspiEvents.SIGNATURE, signal.signature);
        assertEquals(detail, signal.body[0]);
        assertEquals(index, signal.body[1], "detail1 is where the frame is among the children");
        DBus.Variant value = (DBus.Variant) signal.body[3];
        assertEquals("(so)", value.sig, "a reference, which libatspi turns into an accessible");
        assertEquals(path(frame), DBus.Ref.of(value.value).path);
        assertEquals(Atspi.PATH_ROOT, DBus.Ref.of(signal.body[4]).path);
    }
}
