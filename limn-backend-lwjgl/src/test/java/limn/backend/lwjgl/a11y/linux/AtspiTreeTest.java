package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client on the accessibility bus is told about one window, out of one snapshot.
 *
 * <p>A tree is built here rather than walked from a scene, and that is the right way round for
 * this module: the bridge's input <em>is</em> a published tree, there is no walk in the picture,
 * and a test that drove a scene would be asserting the toolkit's behaviour in the one place that
 * has no business re-checking it. What these pin is the translation — object paths, the
 * application object the toolkit knows nothing about, the parent and child links, the coordinate
 * conversion, and the verbs a client may actually invoke.
 *
 * <p>No socket is opened. Every assertion is a reply message computed from a snapshot, which is
 * exactly what the reader thread does on the guest.
 */
class AtspiTreeTest {

    private static final String BUS = ":1.42";

    private final AtomicReference<AccessibleTree> tree = new AtomicReference<>(AccessibleTree.EMPTY);
    private final List<String> performed = new ArrayList<>();
    private AtspiTree atspi;

    private final AccessibilityBridge.Host host = new AccessibilityBridge.Host() {
        @Override public void requestRepublish() { }
        @Override public void requestRestamp() { }
        @Override public AccessibleTree republishNow() { return tree.get(); }
        @Override public boolean perform(long nodeId, Accessible.Action action,
                                         Accessible.Argument arg) {
            performed.add(nodeId + ":" + action);
            return true;
        }
    };

    @BeforeEach
    void setUp() {
        atspi = new AtspiTree(tree::get, () -> host, "Limn");
        atspi.busName(BUS);
        atspi.desktop(new DBus.Ref("org.a11y.atspi.Registry", Atspi.PATH_ROOT));
    }

    /** A window holding one button, published the way a scene publishes one. */
    private void publishAWindowWithAButton() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int button = a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        tree.set(a.publish(0, 200, 100, 2f, true));
        assertTrue(button > 0);
    }

    private DBus.Msg call(String path, String iface, String member, String sig, Object... args) {
        DBus.Msg m = DBus.Msg.call("org.a11y.atspi.Registry", path, iface, member, sig, args);
        m.path = path;
        m.iface = iface;
        m.member = member;
        return atspi.handle(null, m);
    }

    @Test
    void theApplicationObjectIsOursAndItsOneChildIsTheWindowTheScenePublished() {
        publishAWindowWithAButton();

        DBus.Msg kids = call(Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetChildren", null);
        assertNotNull(kids, "the root path answers");
        List<?> refs = (List<?>) kids.body[0];
        assertEquals(1, refs.size(),
                "AT-SPI wants an application above the windows, and the toolkit publishes a window "
                        + "and knows nothing of applications; this is where the two meet");
        assertEquals(BUS, DBus.Ref.of(refs.get(0)).name);
        assertEquals("/org/a11y/atspi/accessible/1000", DBus.Ref.of(refs.get(0)).path,
                "the path is the node id, which is stable for the life of the widget");

        DBus.Msg role = call(Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetRole", null);
        assertEquals(Atspi.ROLE_APPLICATION, role.body[0]);
    }

    @Test
    void aNodeCarriesItsNameItsParentAndItsChildrenBackToTheClient() {
        publishAWindowWithAButton();
        String buttonPath = "/org/a11y/atspi/accessible/1001";

        DBus.Msg props = call(buttonPath, Atspi.I_PROPS, "GetAll", "s", Atspi.I_ACCESSIBLE);
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Object> all = (java.util.Map<Object, Object>) props.body[0];
        assertEquals("Save", ((DBus.Variant) all.get("Name")).value);
        assertEquals("/org/a11y/atspi/accessible/1000",
                DBus.Ref.of(((DBus.Variant) all.get("Parent")).value).path,
                "the window, not the application: only node zero's parent is the application");
        assertEquals(0, ((DBus.Variant) all.get("ChildCount")).value);

        DBus.Msg index = call(buttonPath, Atspi.I_ACCESSIBLE, "GetIndexInParent", null);
        assertEquals(0, index.body[0]);

        DBus.Msg ifaces = call(buttonPath, Atspi.I_ACCESSIBLE, "GetInterfaces", null);
        List<?> names = (List<?>) ifaces.body[0];
        assertTrue(names.contains(Atspi.I_ACTION),
                "it offers a verb, so it says so: a client asks the interface list before the verb"
                        + names);
        assertTrue(names.contains(Atspi.I_COMPONENT), "" + names);
    }

    @Test
    void aBoxCrossesIntoScreenCoordinatesThroughTheStampTheTreeCarries() {
        publishAWindowWithAButton();

        DBus.Msg extents = call("/org/a11y/atspi/accessible/1001", Atspi.I_COMPONENT,
                "GetExtents", "u", Atspi.COORD_SCREEN);
        Object[] box = (Object[]) extents.body[0];
        // The scene's own points times the window's factor, plus the window's origin: the stamp
        // is what a client's thread cannot ask the user-interface thread for.
        assertEquals(200 + 10 * 2, box[0]);
        assertEquals(100 + 20 * 2, box[1]);
        assertEquals(160 * 2, box[2]);
        assertEquals(40 * 2, box[3]);

        DBus.Msg windowBox = call("/org/a11y/atspi/accessible/1001", Atspi.I_COMPONENT,
                "GetExtents", "u", Atspi.COORD_WINDOW);
        Object[] local = (Object[]) windowBox.body[0];
        assertEquals(10 * 2, local[0], "window coordinates leave the origin out");
        assertEquals(20 * 2, local[1]);
    }

    @Test
    void aClientsDoActionReachesTheWidgetThroughTheHostAndNothingElse() {
        publishAWindowWithAButton();
        String buttonPath = "/org/a11y/atspi/accessible/1001";

        DBus.Msg count = call(buttonPath, Atspi.I_ACTION, "GetNActions", null);
        assertEquals(1, count.body[0], "PRESS, and only the verbs that take no argument");

        DBus.Msg name = call(buttonPath, Atspi.I_ACTION, "GetName", "i", 0);
        assertEquals("press", name.body[0]);

        DBus.Msg done = call(buttonPath, Atspi.I_ACTION, "DoAction", "i", 0);
        assertEquals(true, done.body[0]);
        assertEquals(List.of("1001:PRESS"), performed,
                "the scene is asked to perform it, on the identifier the client was holding");

        performed.clear();
        DBus.Msg outOfRange = call(buttonPath, Atspi.I_ACTION, "DoAction", "i", 7);
        assertEquals(false, outOfRange.body[0]);
        assertEquals(List.of(), performed, "an index that names no verb reaches no widget");
    }

    @Test
    void aPathThatNamesNoLivingNodeIsAnsweredByNobodyRatherThanGuessedAt() {
        publishAWindowWithAButton();

        assertEquals(null, call("/org/a11y/atspi/accessible/999999", Atspi.I_ACCESSIBLE,
                "GetRole", null), "a node that left the tree resolves to nothing");
        assertEquals(null, call("/org/a11y/atspi/accessible/not-a-number", Atspi.I_ACCESSIBLE,
                "GetRole", null));
    }

    @Test
    void aNodeIsPublishedUnderTheNumberAndTheNameTheMachineGave() {
        publishAWindowWithAButton();

        DBus.Msg role = call("/org/a11y/atspi/accessible/1001", Atspi.I_ACCESSIBLE, "GetRole", null);
        assertEquals(43, role.body[0], "Atspi.Role.PUSH_BUTTON, off the guest's typelib");

        DBus.Msg windowRole = call("/org/a11y/atspi/accessible/1000", Atspi.I_ACCESSIBLE,
                "GetRoleName", null);
        assertEquals("frame", windowRole.body[0]);

        DBus.Msg states = call("/org/a11y/atspi/accessible/1001", Atspi.I_ACCESSIBLE,
                "GetState", null);
        List<?> words = (List<?>) states.body[0];
        long set = (((Number) words.get(1)).longValue() << 32)
                | (((Number) words.get(0)).longValue() & 0xffffffffL);
        assertTrue((set & (1L << 11)) != 0, "focusable, which the button is: " + set);
        assertTrue((set & (1L << 8)) != 0, "enabled");
        assertTrue((set & (1L << AtspiStates.SENSITIVE)) != 0, "and sensitive with it");
    }
}
