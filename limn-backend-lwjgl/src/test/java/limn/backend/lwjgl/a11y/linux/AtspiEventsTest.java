package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pushes a client waits on, against the names it subscribes by.
 *
 * <p>Everything else this bridge does answers a question a client thought to ask. These are the
 * ones it is told, and the detail string is not decoration: a client's match rule is literally
 * {@code object:state-changed:focused}, so a detail spelled the toolkit's way rather than the
 * platform's is an event nobody has subscribed to and therefore an event that does not arrive.
 */
class AtspiEventsTest {

    private static final String BUS = ":1.9";

    /** A context over no tree: names on {@link #BUS}, and every node at index 3 of its parent. */
    static final AtspiEvents.Context NAMES = new AtspiEvents.Context() {
        @Override public AccessibleTree tree() {
            return AccessibleTree.EMPTY;
        }

        @Override public AccessibleTree previousTree() {
            return AccessibleTree.EMPTY;
        }

        @Override public DBus.Ref application() {
            return new DBus.Ref(BUS, Atspi.PATH_ROOT);
        }

        @Override public DBus.Ref refOf(long id) {
            return new DBus.Ref(BUS, "/org/a11y/atspi/accessible/" + id);
        }

        @Override public DBus.Ref nullRef() {
            return new DBus.Ref(BUS, Atspi.PATH_NULL);
        }

        @Override public int indexInParent(long id) {
            return 3;
        }

        @Override public Object[] cacheItem(long id) {
            return new Object[] {"the item of", id};
        }
    };

    private static List<AtspiEvents.Signal> signals(AccessibleEvent event) {
        return AtspiEvents.of(event, NAMES);
    }

    private static AtspiEvents.Signal one(AccessibleEvent event) {
        List<AtspiEvents.Signal> out = signals(event);
        assertEquals(1, out.size(), "one signal for " + event + ": " + out);
        return out.get(0);
    }

    /**
     * Focus travels as a state change, and only once.
     *
     * <p>The dedicated Focus signal is deprecated and Orca subscribes to
     * {@code object:state-changed:focused}; the difference already raises {@code STATE_CHANGED} for
     * that bit on the node gaining it <em>and</em> the node losing it. Mapping {@code
     * FOCUS_CHANGED} as well sent the arrival twice and the departure once, which a listening
     * client showed plainly: a reader announces the newly focused control and then announces it
     * again.
     */
    @Test
    void focusTravelsAsAStateChangeAndIsNotAlsoSentAsItsOwnEvent() {
        assertEquals(List.of(), signals(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 7)),
                "the state change below is the one that carries it, in both directions");

        AtspiEvents.Signal arriving = one(AccessibleEvent.state(7, Accessible.State.FOCUSED, true));
        assertEquals("StateChanged", arriving.member());
        assertEquals("focused", arriving.detail());
        assertEquals(1, arriving.detail1());
        assertEquals(0, one(AccessibleEvent.state(7, Accessible.State.FOCUSED, false)).detail1(),
                "and the departure, which only this one raises");
    }

    @Test
    void aStateCarriesThePlatformsSpellingAndWhetherItWentOnOrOff() {
        AtspiEvents.Signal on = one(AccessibleEvent.state(3, Accessible.State.CHECKED, true));
        assertEquals("checked", on.detail());
        assertEquals(1, on.detail1());

        AtspiEvents.Signal off = one(AccessibleEvent.state(3, Accessible.State.CHECKED, false));
        assertEquals(0, off.detail1());

        AtspiEvents.Signal hyphened = one(AccessibleEvent.state(3, Accessible.State.READ_ONLY, true));
        assertEquals("read-only", hyphened.detail(),
                "the platform hyphenates where the toolkit underscores, and a client's match "
                        + "string is the platform's");

        AtspiEvents.Signal mixed = one(AccessibleEvent.state(3, Accessible.State.MIXED, true));
        assertEquals("indeterminate", mixed.detail(),
                "and where it uses another word entirely, the word is the platform's");
    }

    @Test
    void aStateThisPlatformDoesNotCarryRaisesNothingRatherThanSomethingApproximate() {
        assertEquals(List.of(), signals(AccessibleEvent.state(3, Accessible.State.PASSWORD, true)),
                "being a password is the role here, and the nearest-looking bit means the entry "
                        + "was rejected: an approximate event is worse than none");
    }

    /**
     * An announcement goes out as the installed interface declares it (LINUX-NEW-3):
     * {@code Announcement(s, i politeness, i, v, a{sv})} with the message as a string, which is the
     * only {@code any_data} Orca 50.2's {@code _on_announcement} presents, and the politeness as
     * {@code Atspi.Live} (POLITE 1, ASSERTIVE 2, read off the Fedora guest's typelib 2026-09-13).
     * It was mapped to nothing.
     */
    @Test
    void anAnnouncementGoesOutAsAnObjectAnnouncementWithItsTextAndItsLiveValue() {
        AtspiEvents.Signal polite = one(AccessibleEvent.announcement("Saved",
                Accessible.Politeness.POLITE));
        assertEquals(AtspiEvents.I_EVENT_OBJECT, polite.iface());
        assertEquals("Announcement", polite.member());
        assertEquals("", polite.detail());
        assertEquals(1, polite.detail1(), "Atspi.Live.POLITE");
        assertEquals(0, polite.detail2());
        assertEquals("s", polite.value().sig, "a string, the one any_data Orca presents");
        assertEquals("Saved", polite.value().value);

        assertEquals(2, one(AccessibleEvent.announcement("Deleted",
                Accessible.Politeness.ASSERTIVE)).detail1(), "Atspi.Live.ASSERTIVE");
    }

    @Test
    void aNameChangeCarriesTheNewNameUnderThePropertyAClientAsksFor() {
        AtspiEvents.Signal renamed = one(AccessibleEvent.property(
                AccessibleEvent.Type.NAME_CHANGED, 5, "Save", "Save as"));

        assertEquals("PropertyChange", renamed.member());
        assertEquals("accessible-name", renamed.detail());
        assertEquals("Save as", renamed.value().value);
        assertEquals("/org/a11y/atspi/accessible/5", renamed.path(), "from the node it is about");
    }

    @Test
    void aWindowEventGoesOutOnTheWindowInterfaceAndNotTheObjectOne() {
        AtspiEvents.Signal opened = one(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_OPENED, 0));

        assertEquals(AtspiEvents.I_EVENT_WINDOW, opened.iface(),
                "a desktop shell watches these and a screen reader watches the object ones");
        assertEquals("Create", opened.member());
        assertEquals(AtspiEvents.I_EVENT_OBJECT,
                one(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 1)).iface());
    }

    /**
     * The focused node's cursor moved, and the value is the descendant itself (L1).
     *
     * <p>It was an {@code i} 0, which libatspi 2.60.6 turns into no {@code any_data} at all
     * ({@code _atspi_dbus_handle_event}), and Orca 50.2 ignores an active-descendant change with
     * none: every one of the ten the 2026-09-14 baseline counted was dropped "No any_data". The
     * first integer is the descendant's index in its parent, the ATK bridge's convention
     * ({@code active_descendant_event_listener}, at-spi2-core 2.60.6).
     */
    @Test
    void anActiveDescendantChangeCarriesTheDescendantAsAnObjectReferenceAndItsIndex() {
        AtspiEvents.Signal moved = one(AccessibleEvent.property(
                AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED, 7, 3L, 9L));

        assertEquals("ActiveDescendantChanged", moved.member());
        assertEquals("/org/a11y/atspi/accessible/7", moved.path(), "from the focused node");
        assertEquals("(so)", moved.value().sig,
                "a reference, the one shape a client makes an accessible of");
        assertEquals(new DBus.Ref(BUS, "/org/a11y/atspi/accessible/9"),
                DBus.Ref.of(moved.value().value), "the descendant it moved TO");
        assertEquals(3, moved.detail1(), "its index in its parent");

        AtspiEvents.Signal gone = one(AccessibleEvent.property(
                AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED, 7, 9L, 0L));
        assertEquals(new DBus.Ref(BUS, Atspi.PATH_NULL), DBus.Ref.of(gone.value().value),
                "a cursor that went away names the null object, never a node called 0");
        assertEquals(-1, gone.detail1());
    }

    /**
     * A replacement is a deletion of what went and an insertion of what came, each carrying only
     * its own text and counted in characters (LINUX-NEW-14).
     *
     * <p>It was one {@code insert} of the removed length at the UTF-16 offset, carrying the whole
     * new text: Orca 50.2 speaks {@code any_data} as the inserted string, so a typed letter read the
     * whole field, and it drops an insertion longer than 1000. GTK 4.22.4 and the ATK bridge send
     * {@code delete} and {@code insert} with the changed text itself.
     */
    @Test
    void aReplacementIsADeleteThenAnInsertOfJustTheChangedTextInCharacters() {
        List<AtspiEvents.Signal> replaced = signals(AccessibleEvent.text(4, 3, 1, 1,
                "a\uD83D\uDE00bc", "a\uD83D\uDE00Xc"));
        assertEquals(2, replaced.size(), "a delete and an insert: " + replaced);
        assertEquals(List.of("delete", 2, 1, "b"), shape(replaced.get(0)),
                "the emoji before it is one character, not two units");
        assertEquals(List.of("insert", 2, 1, "X"), shape(replaced.get(1)));

        assertEquals(List.of(List.of("insert", 1, 1, "X")),
                signals(AccessibleEvent.text(4, 1, 0, 1, "ab", "aXb")).stream()
                        .map(AtspiEventsTest::shape).toList(), "a pure insertion is one signal");
        assertEquals(List.of(List.of("delete", 0, 2, "ab")),
                signals(AccessibleEvent.text(4, 0, 2, 0, "abc", "c")).stream()
                        .map(AtspiEventsTest::shape).toList(), "and a pure deletion");
    }

    /**
     * Two characters outside the basic plane that share their high surrogate differ only in the
     * low one, so the model's unit-by-unit comparison starts the range between the halves; what is
     * said is still the whole character.
     */
    @Test
    void aRangeThatStartsInsideASurrogatePairIsWidenedToTheWholeCharacter() {
        List<AtspiEvents.Signal> replaced = signals(AccessibleEvent.text(4, 2, 1, 1,
                "x\uD83D\uDE00", "x\uD83D\uDE01"));
        assertEquals(List.of(List.of("delete", 1, 1, "\uD83D\uDE00"),
                        List.of("insert", 1, 1, "\uD83D\uDE01")),
                replaced.stream().map(AtspiEventsTest::shape).toList());
    }

    /**
     * The caret's offset travels in {@code detail1}, in characters, read off the published text
     * (LINUX-NEW-14): it was always 0, and Orca 50.2 compares it with the last cursor position it
     * saved.
     */
    @Test
    void aCaretMoveCarriesTheCaretsOffsetInCharactersFromThePublishedText() {
        limn.accessibility.Accessibility a = new limn.accessibility.Accessibility();
        a.beginWalk(400, 300, java.util.Locale.ENGLISH);
        a.begin(1000, limn.accessibility.AccessibleNode.NONE, java.util.Locale.ENGLISH, 0, 0, 400,
                300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, java.util.Locale.ENGLISH, 0, 0, 200, 30);
        a.role(Accessible.Role.TEXT_FIELD);
        a.text("\uD83D\uDE00ab", 1, 3, limn.graphics.ShapedText.Affinity.DOWNSTREAM, 3, 3, 1,
                null, false);
        a.inherited(true, true, true, true, true);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(1001, 0, 0, 1f, true);

        AtspiEvents.Signal moved = AtspiEvents.of(AccessibleEvent.of(
                AccessibleEvent.Type.CARET_MOVED, 1001), over(tree)).get(0);
        assertEquals("TextCaretMoved", moved.member());
        assertEquals(2, moved.detail1(), "after the emoji and the a: three units, two characters");

        AtspiEvents.Signal selection = AtspiEvents.of(AccessibleEvent.of(
                AccessibleEvent.Type.TEXT_SELECTION_CHANGED, 1001), over(tree)).get(0);
        assertEquals("s", selection.value().sig, "an empty string, as GTK sends; an i is nothing");
    }

    /** The names of {@link #NAMES} over a tree of the test's own. */
    private static AtspiEvents.Context over(AccessibleTree tree) {
        return new AtspiEvents.Context() {
            @Override public AccessibleTree tree() {
                return tree;
            }

            @Override public AccessibleTree previousTree() {
                return AccessibleTree.EMPTY;
            }

            @Override public DBus.Ref application() {
                return NAMES.application();
            }

            @Override public DBus.Ref refOf(long id) {
                return NAMES.refOf(id);
            }

            @Override public DBus.Ref nullRef() {
                return NAMES.nullRef();
            }

            @Override public int indexInParent(long id) {
                return NAMES.indexInParent(id);
            }

            @Override public Object[] cacheItem(long id) {
                return NAMES.cacheItem(id);
            }
        };
    }

    private static List<Object> shape(AtspiEvents.Signal signal) {
        return List.of(signal.detail(), signal.detail1(), signal.detail2(), signal.value().value);
    }

    /**
     * The derived COLLAPSED bit travels with the flip that moved it, and only then: before and after
     * are read off the published node with the event's own bit put back.
     */
    @Test
    void anExpandFlipCarriesTheDerivedCollapsedChangeWhenItMovedAndOnlyThen() {
        limn.accessibility.Accessibility a = new limn.accessibility.Accessibility();
        a.beginWalk(400, 300, java.util.Locale.ENGLISH);
        a.begin(1000, limn.accessibility.AccessibleNode.NONE, java.util.Locale.ENGLISH, 0, 0, 400,
                300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        long[] ids = {1001, 1002, 1003};
        Boolean[] facets = {true, false, null};
        for (int i = 0; i < ids.length; i++) {
            a.begin(ids[i], 0, java.util.Locale.ENGLISH, 0, 20 * i, 100, 20);
            a.role(Accessible.Role.TREE_ITEM);
            if (facets[i] != null) {
                a.expand(facets[i]);
            }
            a.inherited(true, true, true, true, false);
            a.end();
        }
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        assertEquals(List.of(List.of("expanded", 1, 0, 0), List.of("collapsed", 0, 0, 0)),
                AtspiEvents.of(AccessibleEvent.state(1001, Accessible.State.EXPANDED, true),
                        over(tree)).stream().map(AtspiEventsTest::shape).toList(), "opened");
        assertEquals(List.of(List.of("expanded", 0, 0, 0), List.of("collapsed", 1, 0, 0)),
                AtspiEvents.of(AccessibleEvent.state(1002, Accessible.State.EXPANDED, false),
                        over(tree)).stream().map(AtspiEventsTest::shape).toList(), "closed");
        assertEquals(List.of(List.of("expandable", 1, 0, 0), List.of("collapsed", 1, 0, 0)),
                AtspiEvents.of(AccessibleEvent.state(1002, Accessible.State.EXPANDABLE, true),
                        over(tree)).stream().map(AtspiEventsTest::shape).toList(),
                "became a closed branch");
        assertEquals(List.of(List.of("expandable", 0, 0, 0), List.of("collapsed", 0, 0, 0)),
                AtspiEvents.of(AccessibleEvent.state(1003, Accessible.State.EXPANDABLE, false),
                        over(tree)).stream().map(AtspiEventsTest::shape).toList(),
                "a closed branch that became a leaf");
        assertEquals(List.of(List.of("expandable", 1, 0, 0)),
                AtspiEvents.of(AccessibleEvent.state(1001, Accessible.State.EXPANDABLE, true),
                        over(tree)).stream().map(AtspiEventsTest::shape).toList(),
                "an open branch was never collapsed: nothing more");
    }

    /**
     * A parent's children moved: per child, {@code ChildrenChanged} from the parent with the index
     * and the child's reference, and the cache told of what left the tree and what arrived
     * (LINUX-NEW-1, LAB-NEW-3). Removals first, highest former index first; then additions and
     * reorders in ascending index, each {@code AddAccessible} after the {@code add} that made room.
     */
    @Test
    void aStructureChangeIsOneChildrenChangedPerChildFromTheParentWithTheCacheToldInLibatspisOrder() {
        List<AtspiEvents.Signal> sent = signals(AccessibleEvent.structure(10,
                List.of(new AccessibleEvent.Child(8, 0, 0), new AccessibleEvent.Child(12, 3, 44)),
                List.of(new AccessibleEvent.Child(6, 0, 0), new AccessibleEvent.Child(5, 2, 0),
                        new AccessibleEvent.Child(7, 1, 99)),
                List.of(new AccessibleEvent.Child(9, 1, 0))));

        List<String> said = new java.util.ArrayList<>();
        for (AtspiEvents.Signal signal : sent) {
            if (signal.iface().equals(AtspiEvents.I_EVENT_OBJECT)) {
                assertEquals("/org/a11y/atspi/accessible/10", signal.path(), "from the parent");
                assertEquals("(so)", signal.value().sig);
                said.add(signal.detail() + " " + signal.detail1() + " "
                        + DBus.Ref.of(signal.value().value).path.replace(
                                "/org/a11y/atspi/accessible/", ""));
            } else {
                assertEquals(Atspi.PATH_CACHE, signal.path());
                Object arg = signal.body()[0];
                said.add(signal.member() + " " + (signal.member().equals("AddAccessible")
                        ? ((Object[]) arg)[1]
                        : DBus.Ref.of(arg).path.replace("/org/a11y/atspi/accessible/", "")));
            }
        }
        assertEquals(List.of(
                "remove 2 5", "RemoveAccessible 5",
                "remove 1 7",
                "remove 0 6", "RemoveAccessible 6",
                "add 0 8", "AddAccessible 8",
                "add 1 9",
                "add 3 12", "AddAccessible 12"), said,
                "a child that moved to another parent (7) is never taken out of the cache, a "
                        + "reordered one (9) is only moved, and one that moved here (12) is "
                        + "announced where it now stands");
        AtspiEvents.Signal add = sent.get(6);
        assertEquals(Atspi.CACHE_ITEM, add.signature(), "the item struct libatspi compares against");
        assertEquals("(so)", sent.get(1).signature());
    }

    /**
     * A node that left the tree says so from its own path, as GTK 4.22.4 does before it
     * unregisters a context: {@code StateChanged defunct 1}. It used to send a
     * {@code ChildrenChanged remove} from that path with an {@code i}, which libatspi could do
     * nothing with and Orca 50.2 crashed on.
     */
    @Test
    void aDestroyedNodeSaysItIsDefunctFromItsOwnPath() {
        AtspiEvents.Signal gone = one(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 5));
        assertEquals("StateChanged", gone.member());
        assertEquals("defunct", gone.detail());
        assertEquals(1, gone.detail1());
        assertEquals("/org/a11y/atspi/accessible/5", gone.path());
    }

    @Test
    void theBodyCarriesTheApplicationItCameFrom() {
        AtspiEvents.Signal signal = one(AccessibleEvent.state(7, Accessible.State.FOCUSED, true));
        Object[] body = signal.body();

        assertEquals(5, body.length,
                "detail, two integers, the value and the sender -- and nothing after: at-spi2 "
                        + "rejects the whole signal for a trailing dictionary, and logs it against "
                        + "the interface rather than the sender, so the application looks silent");
        assertEquals(AtspiEvents.SIGNATURE, signal.signature());
        assertEquals(5, AtspiEvents.SIGNATURE.replace("(so)", "x").length());
        assertEquals("focused", body[0]);
        assertEquals(BUS, DBus.Ref.of(body[4]).name,
                "a client resolves the event against the application that sent it");
        assertTrue(signal.path().startsWith("/org/a11y/atspi/accessible/"));
    }
}
