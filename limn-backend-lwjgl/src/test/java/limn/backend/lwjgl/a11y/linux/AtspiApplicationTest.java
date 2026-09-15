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
        /** Whether each recorded signal was offered as the reserved tail, index for index. */
        final List<Boolean> tails = new ArrayList<>();
        /** A connection whose ordinary backlog is full: it refuses every signal but the tail's. */
        boolean refusesOrdinarySignals;

        Runnable lost;

        @Override
        public AtspiApplication.Link join(AtspiTree objects, Runnable whenLost) {
            joins++;
            lost = whenLost;
            objects.busName(BUS);
            return new AtspiApplication.Link() {
                @Override public boolean signal(DBus.Msg signal, boolean tail) {
                    if (refusesOrdinarySignals && !tail) {
                        return false;
                    }
                    signals.add(signal);
                    tails.add(tail);
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
                @Override public boolean signal(DBus.Msg signal, boolean tail) { return true; }
                @Override public void close() { closed[0] = true; }
            };
        }, AtspiApplication.Starter.ON_THE_CALLER, System::nanoTime);
        app[0].enabled(true);
        app[0].window().publish(aWindow("Main", 0).tree(), false);
        assertFalse(app[0].isJoined(), "nothing stays joined for a switch that is off");
        assertTrue(closed[0]);
    }

    @Test
    void aSwitchTurnedOffBetweenTheJoinsLastLookAndItsPublicationStillLeaves() {
        // The window the review named: the joiner has its link and has not yet published the
        // joined state when the reader quits. The clock is read in exactly that gap (the join's
        // timestamp), so the test turns the switch off there: enabled(false) then finds nothing
        // joined to leave, and only a look at the switch after the publication can let it go.
        AtspiApplication[] app = new AtspiApplication[1];
        boolean[] armed = {false};
        boolean[] closed = {false};
        app[0] = new AtspiApplication((objects, lost) -> {
            armed[0] = true;
            return new AtspiApplication.Link() {
                @Override public boolean signal(DBus.Msg signal, boolean tail) { return true; }
                @Override public void close() { closed[0] = true; }
            };
        }, AtspiApplication.Starter.ON_THE_CALLER, () -> {
            if (armed[0]) {
                armed[0] = false;
                app[0].enabled(false);
                assertFalse(app[0].isJoined(), "the switch went off before the state was published");
            }
            return 1_000_000_000L;
        });
        app[0].enabled(true);
        app[0].window().publish(aWindow("Main", 0).tree(), false);
        assertFalse(app[0].isJoined(), "nothing stays joined for a switch that is off, whichever "
                + "thread looked first");
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
    void anActiveDescendantIsNamedExactlyAsTheTreeAnswersForItEvenInAnotherWindow() {
        // Decision 5: the focused field's cursor may be an option in a native popup, which is
        // another window of this process. The event must name it by the reference and index a
        // client would get by asking, or the two halves of the conversation disagree.
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        Published opener = aWindow("Main", 0);
        Published list = aWindow("Options", opener.control());
        main.publish(opener.tree(), false);
        popup.publish(list.tree(), false);
        bus.signals.clear();

        main.emit(limn.accessibility.AccessibleEvent.property(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                opener.control(), 0L, list.control()));
        main.emit(limn.accessibility.AccessibleEvent.property(
                limn.accessibility.AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                opener.control(), list.control(), list.window()));

        assertEquals(2, bus.signals.size(), "one signal per event: " + bus.signals);
        for (int i = 0; i < 2; i++) {
            DBus.Msg signal = bus.signals.get(i);
            long descendant = i == 0 ? list.control() : list.window();
            assertEquals(path(opener.control()), signal.path, "from the focused field");
            assertEquals("ActiveDescendantChanged", signal.member);
            DBus.Variant value = (DBus.Variant) signal.body[3];
            assertEquals("(so)", value.sig);
            String parent = i == 0 ? path(list.window()) : Atspi.PATH_ROOT;
            int index = i == 0 ? 0 : 1;
            DBus.Msg asked = call(app, parent, Atspi.I_ACCESSIBLE, "GetChildAtIndex", "i", index);
            assertEquals(DBus.Ref.of(asked.body[0]), DBus.Ref.of(value.value),
                    "the reference GetChildAtIndex answers for the same node");
            assertEquals(path(descendant), DBus.Ref.of(value.value).path);
            assertEquals(call(app, path(descendant), Atspi.I_ACCESSIBLE, "GetIndexInParent",
                    null).body[0], signal.body[1], "and the index GetIndexInParent answers");
        }
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
        assertEquals(3, bus.signals.size(), "one arrival, announced once: " + bus.signals);
        assertFrameSignal(bus.signals.get(0), "add", 1, second.window());
        assertEquals("AddAccessible", bus.signals.get(1).member, "the cache told of the frame");
        assertEquals(path(second.window()), DBus.Ref.of(((Object[]) bus.signals.get(1).body[0])[0]).path);
        assertWindowSignal(bus.signals.get(2), "Create", second.window(), "Calendar");

        popup.detach();
        assertEquals(6, bus.signals.size());
        assertWindowSignal(bus.signals.get(3), "Destroy", second.window(), "Calendar");
        assertFrameSignal(bus.signals.get(4), "remove", 1, second.window());
        assertEquals("RemoveAccessible", bus.signals.get(5).member);
        assertEquals(path(second.window()), DBus.Ref.of(bus.signals.get(5).body[0]).path);
        assertEquals(List.of(AtspiStates.DEFUNCT), statesAt(app, path(second.window())),
                "and the departed frame answers that it is defunct, not UnknownMethod");
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
        long[] now = {1_000_000_000L};
        AtspiApplication app = new AtspiApplication(bus, AtspiApplication.Starter.ON_THE_CALLER,
                () -> now[0]);
        app.enabled(true);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        int[] republishes = {0};
        AccessibilityBridge.Host host = hostCounting(republishes);
        main.attach(host);
        popup.attach(host);
        main.publish(aWindow("Main", 0).tree(), false);
        popup.publish(aWindow("Calendar", 0).tree(), false);
        assertTrue(app.isJoined());

        now[0] += AtspiApplication.STEADY_NANOS;  // a connection that held, then was lost
        bus.lost.run();
        assertFalse(app.isJoined(), "a connection whose reader has stopped is not a connection: "
                + "believing it still embedded is the deaf application nobody can see");
        assertTrue(bus.closed);
        assertEquals(2, republishes[0], "each window buys the frame that joins again");

        main.publish(aWindow("Main", 0).tree(), false);
        assertEquals(2, bus.joins, "and the next publish joins again");
        assertTrue(app.isJoined());
    }

    @Test
    void aConnectionLostSoonAfterItsJoinIsAFailureAndWaitsOutTheBackOffBeforeAnyoneIsAsked() {
        // A handler error that ends the reader, or a bus still restarting, loses the connection
        // right after the join. A successful join used to reset the failure count, so each such
        // loss was rejoined as fast as the scene published: a socket and two threads a time.
        FakeBus bus = new FakeBus();
        long[] now = {1_000_000_000L};
        List<Runnable> threads = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        AtspiApplication app = new AtspiApplication(bus, (name, body) -> threads.add(body),
                () -> now[0], nanos -> {
                    waits.add(nanos);
                    now[0] += nanos;
                });
        app.enabled(true);
        AtspiBridge main = app.window();
        int[] republishes = {0};
        main.attach(hostCounting(republishes));

        main.publish(aWindow("Main", 0).tree(), false);
        threads.remove(0).run();
        assertTrue(app.isJoined());
        now[0] += AtspiApplication.FIRST_RETRY_NANOS;
        bus.lost.run();
        assertFalse(app.isJoined());
        assertEquals(0, republishes[0], "a loss one second after the join is not rejoined at once");
        main.publish(aWindow("Main", 0).tree(), false);
        assertEquals(1, bus.joins, "nor by the next frame");
        assertEquals(1, threads.size(), "a thread of its own waits the back-off");
        threads.remove(0).run();
        assertEquals(List.of(AtspiApplication.FIRST_RETRY_NANOS), waits);
        assertEquals(1, republishes[0], "and then asks");

        main.publish(aWindow("Main", 0).tree(), false);
        threads.remove(0).run();
        assertEquals(2, bus.joins);
        bus.lost.run();
        threads.remove(0).run();
        assertEquals(List.of(AtspiApplication.FIRST_RETRY_NANOS,
                        2 * AtspiApplication.FIRST_RETRY_NANOS), waits,
                "the join between the two losses did not wipe the count: the second waits longer");

        main.publish(aWindow("Main", 0).tree(), false);
        threads.remove(0).run();
        now[0] += AtspiApplication.STEADY_NANOS;
        bus.lost.run();
        assertEquals(3, republishes[0], "a connection that held is asked for again at once");
        assertTrue(threads.isEmpty());
        main.publish(aWindow("Main", 0).tree(), false);
        threads.remove(0).run();
        bus.lost.run();
        threads.remove(0).run();
        assertEquals(AtspiApplication.FIRST_RETRY_NANOS, waits.get(waits.size() - 1),
                "and the count starts again after it");
    }

    @Test
    void theSwitchTurningOffEndsABackOffAndNobodyIsAsked() {
        List<Runnable> threads = new ArrayList<>();
        AtspiApplication[] app = new AtspiApplication[1];
        boolean[] interrupted = {false};
        app[0] = new AtspiApplication((objects, lost) -> {
            throw new java.io.IOException("the registry is not there");
        }, (name, body) -> threads.add(body), System::nanoTime, nanos -> {
            app[0].enabled(false);  // the reader quits during the wait
            if (Thread.interrupted()) {
                interrupted[0] = true;
                throw new InterruptedException();
            }
        });
        AtspiBridge main = app[0].window();
        int[] republishes = {0};
        main.attach(hostCounting(republishes));
        app[0].enabled(true);
        assertEquals(1, republishes[0]);
        main.publish(aWindow("Main", 0).tree(), false);
        threads.remove(0).run();
        assertTrue(interrupted[0], "the wait is ended rather than kept for up to a minute for a "
                + "reader that has gone");
        assertEquals(1, republishes[0], "and nobody is asked to publish for it");

        app[0].enabled(true);
        assertEquals(2, republishes[0]);
        main.publish(aWindow("Main", 0).tree(), false);
        assertEquals(1, threads.size(), "a reader that comes back is joined for at once: the ended "
                + "wait holds nothing");
    }

    /**
     * An {@code Event.Window} signal from a frame's own path, whose value is the window's name (the
     * ATK bridge's {@code window_event_listener}, at-spi2-core 2.60.6).
     */
    private static void assertWindowSignal(DBus.Msg signal, String member, long frame,
                                           String name) {
        assertEquals(AtspiEvents.I_EVENT_WINDOW, signal.iface, "a window event: " + signal);
        assertEquals(member, signal.member);
        assertEquals(path(frame), signal.path, "sent from the frame, never the application object");
        assertEquals(AtspiEvents.SIGNATURE, signal.signature);
        DBus.Variant value = (DBus.Variant) signal.body[3];
        assertEquals("s", value.sig);
        assertEquals(name, value.value, "the window's name, a string libatspi hands on as any_data");
    }

    /** The members of the signals sent, each as "member detail detail1 path", for order checks. */
    private static List<String> spoken(List<DBus.Msg> signals) {
        List<String> out = new ArrayList<>();
        for (DBus.Msg m : signals) {
            out.add(m.body.length < 2 ? m.member + " " + m.path
                    : m.member + " " + m.body[0] + " " + m.body[1] + " " + m.path);
        }
        return out;
    }

    /** One window's scene as the differ sees it: a persistent builder published frame by frame. */
    private static final class Frames {
        final Accessibility a = new Accessibility();
        final AtspiBridge window;

        Frames(AtspiBridge window) {
            this.window = window;
        }

        /**
         * Publishes window 3000 ("Main") holding buttons 3001 and 3002, with the window ACTIVE or
         * not and {@code focused} holding the focus, and hands the bridge every event the
         * difference found, as a scene does.
         */
        List<limn.accessibility.AccessibleEvent> publish(boolean active, long focused) {
            return publish(active, focused, 3001, 3002);
        }

        List<limn.accessibility.AccessibleEvent> publish(boolean active, long focused,
                                                         long... buttons) {
            return publish(active, focused, id -> true, buttons);
        }

        List<limn.accessibility.AccessibleEvent> publish(boolean active, long focused,
                                                         java.util.function.LongPredicate showing,
                                                         long... buttons) {
            a.beginWalk(400, 300, Locale.ENGLISH);
            a.begin(3000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            a.role(Accessible.Role.WINDOW);
            a.name(I18nString.literal("Main"), Accessible.NameFrom.EXPLICIT);
            if (active) {
                a.state(Accessible.State.ACTIVE);
            }
            a.inherited(true, true, true, false, false);
            for (long id : buttons) {
                a.begin(id, 0, Locale.ENGLISH, 10, 20 + (id - 3001) * 50, 160, 40);
                a.role(Accessible.Role.BUTTON);
                a.name(I18nString.literal("Button " + id), Accessible.NameFrom.CONTENT);
                a.inherited(true, true, showing.test(id), true, focused == id);
                a.end();
            }
            a.end();
            AccessibleTree tree = a.publish(focused, 0, 0, 1f, true);
            window.publish(tree, false);
            List<limn.accessibility.AccessibleEvent> events = List.copyOf(a.events());
            for (limn.accessibility.AccessibleEvent event : events) {
                window.emit(event);
            }
            return events;
        }
    }

    @Test
    void aWindowsActivationIsSentFromItsFrameWithItsNameAndTheFocusIsSaidAgainAfterIt() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        Frames main = new Frames(app.window());
        main.publish(false, 3001);
        bus.signals.clear();

        main.publish(true, 3001);
        List<String> sent = spoken(bus.signals);
        int active = sent.indexOf("StateChanged active 1 " + path(3000));
        int activate = sent.indexOf("Activate  0 " + path(3000));
        int focused = sent.lastIndexOf("StateChanged focused 1 " + path(3001));
        assertTrue(active >= 0 && activate > active, "the frame says it is active, then the window "
                + "event, both from the frame: " + sent);
        assertWindowSignal(bus.signals.get(activate), "Activate", 3000, "Main");
        assertTrue(focused > activate, "and the focus is said again after it, because Orca puts "
                + "its locus on the frame when a window activates: " + sent);

        main.publish(false, 3001);
        assertTrue(spoken(bus.signals).contains("Deactivate  0 " + path(3000)));
        bus.signals.clear();
        main.publish(true, 3002);
        sent = spoken(bus.signals);
        assertEquals(1, java.util.Collections.frequency(sent, "StateChanged focused 1 "
                + path(3002)), "a focus change already sent in this publish is not sent twice, "
                + "which Orca's 0.1 s filter would drop: " + sent);
        assertTrue(sent.indexOf("Activate  0 " + path(3000)) >= 0, sent.toString());
    }

    /**
     * A node that arrives already holding the focus — a dialog's first field, a popup's list, a cell
     * widget realized under the cursor — raises no STATE_CHANGED (a new node's states are read when
     * it is discovered) and only the tail's FOCUS_CHANGED (semantics 7, settled focus-reannounce).
     * Until the review of linux-B that event sent nothing on Linux, so the node losing the focus was
     * heard and the node gaining it never was.
     */
    @Test
    void aNodeThatArrivesFocusedIsSaidFocusedAfterTheCacheHasItAndOnlyOnce() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        Frames main = new Frames(app.window());
        main.publish(true, 3001, 3001, 3002);
        bus.signals.clear();

        main.publish(true, 3003, 3001, 3002, 3003);

        assertEquals(List.of("StateChanged focused 0 " + path(3001),
                "ChildrenChanged add 2 " + path(3000),
                "AddAccessible " + Atspi.PATH_CACHE,
                "StateChanged focused 1 " + path(3003)), spoken(bus.signals),
                "the loser, the arrival told to the cache, then the focus on the node the cache now "
                        + "holds, once");
        bus.signals.clear();

        main.publish(true, 3002, 3001, 3002, 3003);
        assertEquals(List.of("StateChanged focused 1 " + path(3002),
                "StateChanged focused 0 " + path(3003)), spoken(bus.signals),
                "a surviving node's gain is its STATE_CHANGED (in reading order, so before the "
                        + "later sibling's loss), and the FOCUS_CHANGED that follows it in the tail is "
                        + "not sent again");
    }

    /** The state bits a path answers GetState with, as bit indices. */
    private static List<Integer> statesAt(AtspiApplication app, String path) {
        DBus.Msg reply = call(app, path, Atspi.I_ACCESSIBLE, "GetState", null);
        List<?> words = (List<?>) reply.body[0];
        long set = (((Number) words.get(1)).longValue() << 32)
                | (((Number) words.get(0)).longValue() & 0xffffffffL);
        List<Integer> out = new ArrayList<>();
        for (int bit = 0; bit < 64; bit++) {
            if ((set & (1L << bit)) != 0) {
                out.add(bit);
            }
        }
        return out;
    }

    @Test
    void aChildLeavingAndAChildArrivingAreToldFromTheParentWithTheIndicesAndItemsTheTreeAnswers() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        Frames main = new Frames(app.window());
        main.publish(true, 3001, 3001, 3002, 3003);
        bus.signals.clear();

        main.publish(true, 3001, 3001, 3003, 3004);

        List<String> sent = spoken(bus.signals);
        int defunct = sent.indexOf("StateChanged defunct 1 " + path(3002));
        int remove = sent.indexOf("ChildrenChanged remove 1 " + path(3000));
        int add = sent.indexOf("ChildrenChanged add 2 " + path(3000));
        assertTrue(defunct >= 0 && remove >= 0 && add > remove, "the departure from its own path, "
                + "the removal and the arrival from the parent, at the indices: " + sent);
        assertEquals(path(3002), DBus.Ref.of(((DBus.Variant) bus.signals.get(remove).body[3]).value)
                .path, "the child that left, by reference");
        assertEquals("RemoveAccessible", bus.signals.get(remove + 1).member);
        assertEquals(path(3002), DBus.Ref.of(bus.signals.get(remove + 1).body[0]).path);
        assertEquals(call(app, path(3004), Atspi.I_ACCESSIBLE, "GetIndexInParent", null).body[0],
                bus.signals.get(add).body[1], "the index GetIndexInParent answers for the arrival");
        DBus.Msg item = bus.signals.get(add + 1);
        assertEquals("AddAccessible", item.member);
        assertEquals(Atspi.CACHE_ITEM, item.signature);
        Object announced = null;
        for (Object entry : (List<?>) call(app, Atspi.PATH_CACHE, Atspi.I_CACHE, "GetItems",
                null).body[0]) {
            if (DBus.Ref.of(((Object[]) entry)[0]).path.equals(path(3004))) {
                announced = entry;
            }
        }
        assertTrue(DBusWireTest.deepEq(announced, item.body[0]),
                "the item GetItems lists for the same node: " + DBus.fmt(item.body[0]));
        assertEquals(List.of(AtspiStates.DEFUNCT), statesAt(app, path(3002)),
                "and the child that left answers defunct when it is asked");
    }

    private static long[] buttons(int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = 3001 + i;
        }
        return ids;
    }

    /**
     * A publish wider than the model's budget collapses to INVALIDATED and keeps its tail (decision
     * 28, semantics 7): here the focus moves in the same frame as three hundred boxes stop showing
     * and the last one leaves, so no per-node state change survives, the structure change does,
     * and the reader must still hear where the focus went.
     */
    @Test
    void aCollapsedPublishStillSaysWhereTheFocusWentAndSendsOnlyTailSignals() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        Frames main = new Frames(app.window());
        long[] ids = buttons(300);
        main.publish(true, 3001, id -> true, ids);
        bus.signals.clear();
        bus.tails.clear();

        List<limn.accessibility.AccessibleEvent> events = main.publish(true, 3002,
                id -> id == 3002, java.util.Arrays.copyOf(ids, 299));
        assertEquals(limn.accessibility.AccessibleEvent.Type.INVALIDATED, events.get(0).type(),
                "the fixture must cross the budget: " + events.size() + " events");

        List<String> sent = spoken(bus.signals);
        assertEquals(1, java.util.Collections.frequency(sent, "StateChanged focused 1 "
                + path(3002)), "the focus change the collapse swallowed, said once: " + sent);
        assertFalse(sent.stream().anyMatch(line -> line.startsWith("StateChanged showing")),
                "and nothing of what was collapsed: " + sent);
        assertTrue(sent.contains("ChildrenChanged remove 299 " + path(3000)),
                "while the structure the tail kept is told, so a client's cached children hold: "
                        + sent);
        assertTrue(bus.tails.stream().allMatch(tail -> tail),
                "every signal of a collapsed publish is the tail's, which no backlog refuses");
    }

    /**
     * A connection whose ordinary backlog is full refuses an event; the reader is told where the
     * focus is anyway, as a tail signal, once (semantics 4: a bridge re-announces after its own
     * queue collapse).
     */
    @Test
    void aRefusedSignalIsFollowedByTheFocusSaidAgainAsATailSignal() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        Frames main = new Frames(app.window());
        main.publish(true, 3001);
        bus.signals.clear();
        bus.tails.clear();
        bus.refusesOrdinarySignals = true;

        main.publish(true, 3002);

        assertEquals(List.of("StateChanged focused 1 " + path(3002)), spoken(bus.signals),
                "the losing node's change was refused, and the focus was said again at once");
        assertEquals(List.of(true), bus.tails);
    }

    /**
     * EXPANDABLE is a model state derived from the expand facet, and COLLAPSED this platform's bit
     * derived from the two (decision 27, semantics 9): both reach the bus as state changes, from a
     * real difference, so a client's cached state set follows a branch as it gains a triangle, opens
     * and loses it (L3).
     */
    @Test
    void expandableAndTheDerivedCollapsedReachTheBusAsTheBranchGainsOpensAndLosesItsTriangle() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge window = app.window();
        Accessibility a = new Accessibility();
        java.util.function.Consumer<Boolean> publish = expanded -> {
            a.beginWalk(400, 300, Locale.ENGLISH);
            a.begin(5000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            a.role(Accessible.Role.WINDOW);
            a.inherited(true, true, true, false, false);
            a.begin(5001, 0, Locale.ENGLISH, 0, 0, 100, 20);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal("Media"), Accessible.NameFrom.EXPLICIT);
            if (expanded != null) {
                a.expand(expanded);
            }
            a.inherited(true, true, true, true, false);
            a.end();
            a.end();
            window.publish(a.publish(0, 0, 0, 1f, true), false);
            for (limn.accessibility.AccessibleEvent event : List.copyOf(a.events())) {
                window.emit(event);
            }
        };
        publish.accept(null);
        bus.signals.clear();

        publish.accept(false);
        assertEquals(List.of("StateChanged expandable 1 " + path(5001),
                "StateChanged collapsed 1 " + path(5001)), spoken(bus.signals),
                "a leaf became a closed branch");
        assertEquals(List.of(AtspiStates.COLLAPSED, 8, AtspiStates.EXPANDABLE, 11,
                        AtspiStates.SENSITIVE, 25, 30),
                statesAt(app, path(5001)), "and the set a client would read says the same");
        bus.signals.clear();

        publish.accept(true);
        assertEquals(List.of("StateChanged expanded 1 " + path(5001),
                "StateChanged collapsed 0 " + path(5001)), spoken(bus.signals),
                "opening it clears the derived bit, or a cached set holds expanded and collapsed");
        bus.signals.clear();

        publish.accept(null);
        assertEquals(List.of("StateChanged expanded 0 " + path(5001),
                "StateChanged expandable 0 " + path(5001)), spoken(bus.signals),
                "an open branch that lost its facet was never collapsed, so nothing more");
    }

    @Test
    void anAnnouncementIsSentFromTheFrameOfTheWindowWhoseSceneSaidIt() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        AtspiBridge popup = app.window();
        Published first = aWindow("Main", 0);
        Published second = aWindow("Calendar", 0);
        main.publish(first.tree(), false);
        popup.publish(second.tree(), false);
        bus.signals.clear();

        popup.emit(limn.accessibility.AccessibleEvent.announcement("Saved",
                Accessible.Politeness.POLITE));

        assertEquals(1, bus.signals.size());
        assertEquals(path(second.window()), bus.signals.get(0).path,
                "the toolkit names no node; the window that spoke is a frame a reader can place, "
                        + "the application object is not");
        assertEquals("Announcement", bus.signals.get(0).member);
    }

    @Test
    void aWindowLevelBoxChangeIsSentFromTheFrameWithTheFramesNewExtents() {
        FakeBus bus = new FakeBus();
        AtspiApplication app = anApplication(bus);
        AtspiBridge main = app.window();
        Published first = aWindow("Main", 0);
        main.publish(first.tree(), false);
        bus.signals.clear();

        main.emit(limn.accessibility.AccessibleEvent.of(
                limn.accessibility.AccessibleEvent.Type.BOUNDS_CHANGED, 0));

        assertEquals(1, bus.signals.size());
        DBus.Msg signal = bus.signals.get(0);
        assertEquals(path(first.window()), signal.path,
                "node zero is the window here, and the application object has no geometry");
        DBus.Variant value = (DBus.Variant) signal.body[3];
        assertEquals("(iiii)", value.sig, "a rectangle, which libatspi makes an AtspiRect of");
        Object[] asked = (Object[]) call(app, path(first.window()), Atspi.I_COMPONENT, "GetExtents",
                "u", Atspi.COORD_SCREEN).body[0];
        assertEquals(List.of(asked), List.of((Object[]) value.value),
                "the extents GetExtents answers in screen coordinates");
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
