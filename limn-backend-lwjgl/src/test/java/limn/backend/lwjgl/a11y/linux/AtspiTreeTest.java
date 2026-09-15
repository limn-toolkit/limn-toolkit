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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        AtspiTree.Window window = new AtspiTree.Window() {
            @Override public AccessibleTree tree() { return tree.get(); }
            @Override public AccessibilityBridge.Host host() { return host; }
        };
        atspi = new AtspiTree(() -> List.of(window), () -> "Limn");
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

    /**
     * A window holding a table of two columns over three rows, published the way ADR 041 §7 says a
     * table publishes: a header group with a header cell per column, then a row per realized data
     * row with a cell per column. Ids are chosen so that a path can be written by hand.
     */
    private void publishAWindowWithATable() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        int table = a.begin(2000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.table(3, 2);
        a.selection(false, false);
        a.inherited(true, true, true, true, false);
        int header = a.begin(2100, table, Locale.ENGLISH, 0, 0, 400, 30);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        for (int c = 0; c < 2; c++) {
            a.begin(2101 + c, header, Locale.ENGLISH, c * 200, 0, 200, 30);
            a.role(Accessible.Role.COLUMN_HEADER);
            a.name(I18nString.literal(c == 0 ? "Name" : "Age"), Accessible.NameFrom.CONTENT);
            a.cell(-1, c);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        for (int r = 0; r < 3; r++) {
            int row = a.begin(2200 + r * 10, table, Locale.ENGLISH, 0, 30 + r * 30, 400, 30);
            a.role(Accessible.Role.ROW);
            a.selectionItem(r == 1, r + 1, 3);
            a.action(Accessible.Action.SELECT);
            a.inherited(true, true, true, false, false);
            for (int c = 0; c < 2; c++) {
                a.begin(2201 + r * 10 + c, row, Locale.ENGLISH, c * 200, 30 + r * 30, 200, 30);
                a.role(Accessible.Role.CELL);
                a.name(I18nString.literal("r" + r + "c" + c), Accessible.NameFrom.CONTENT);
                a.cell(r, c);
                a.inherited(true, true, true, false, false);
                a.end();
            }
            a.end();
        }
        a.end();
        a.end();
        tree.set(a.publish(0, 200, 100, 2f, true));
    }

    /**
     * A window holding a caption, a field the caption names, and a message beneath the field that
     * describes it, with the two relations resolved the way the walk resolves them: to node ids.
     */
    private void publishAWindowWithALabelledAndDescribedField() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(3001, 0, Locale.ENGLISH, 0, 0, 400, 20);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.CONTENT);
        a.relation(Accessible.Relation.LABEL_FOR, 3002L);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(3002, 0, Locale.ENGLISH, 0, 20, 400, 32);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.LABEL);
        a.description(I18nString.literal("Enter an address like ada@example.com"));
        a.relation(Accessible.Relation.LABELLED_BY, 3001L);
        a.relation(Accessible.Relation.DESCRIBED_BY, 3003L);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(3003, 0, Locale.ENGLISH, 0, 52, 400, 20);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Enter an address like ada@example.com"),
                Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        tree.set(a.publish(0, 200, 100, 2f, true));
    }

    private static String path(long id) {
        return "/org/a11y/atspi/accessible/" + id;
    }

    @Test
    void aTableAnswersItsShapeItsCellsAndItsHeadersFromTheTwoFacets() {
        publishAWindowWithATable();
        DBus.Msg interfaces = call(path(2000), Atspi.I_ACCESSIBLE, "GetInterfaces", null);
        assertTrue(((List<?>) interfaces.body[0]).contains(Atspi.I_TABLE),
                "a node with a table facet implements Table");
        DBus.Msg cellInterfaces = call(path(2211), Atspi.I_ACCESSIBLE, "GetInterfaces", null);
        assertTrue(((List<?>) cellInterfaces.body[0]).contains(Atspi.I_TABLE_CELL));

        DBus.Msg cell = call(path(2000), Atspi.I_TABLE, "GetAccessibleAt", "ii", 1, 1);
        assertEquals(path(2212), ((Object[]) cell.body[0])[1], "row 1, column 1");
        DBus.Msg missing = call(path(2000), Atspi.I_TABLE, "GetAccessibleAt", "ii", 7, 0);
        assertEquals(Atspi.PATH_NULL, ((Object[]) missing.body[0])[1],
                "a row the walk did not publish is the null object, not a guess");
        DBus.Msg header = call(path(2000), Atspi.I_TABLE, "GetColumnHeader", "i", 1);
        assertEquals(path(2102), ((Object[]) header.body[0])[1]);
        DBus.Msg description = call(path(2000), Atspi.I_TABLE, "GetColumnDescription", "i", 1);
        assertEquals("Age", description.body[0]);
        DBus.Msg selected = call(path(2000), Atspi.I_TABLE, "GetSelectedRows", null);
        assertEquals(List.of(1), selected.body[0]);
        DBus.Msg isSelected = call(path(2000), Atspi.I_TABLE, "IsRowSelected", "i", 1);
        assertEquals(true, isSelected.body[0]);
        DBus.Msg index = call(path(2000), Atspi.I_TABLE, "GetIndexAt", "ii", 2, 1);
        assertEquals(5, index.body[0]);

        DBus.Msg select = call(path(2000), Atspi.I_TABLE, "AddRowSelection", "i", 2);
        assertEquals(true, select.body[0]);
        assertEquals(List.of("2220:SELECT"), performed, "a row selection reaches the widget");

        DBus.Msg span = call(path(2212), Atspi.I_TABLE_CELL, "GetRowColumnSpan", null);
        assertEquals("iiii", span.signature, "four out arguments, which is what libatspi reads; a "
                + "struct of four was refused on the guest");
        assertEquals(1, span.body[0]);
        assertEquals(1, span.body[1]);
        assertEquals(1, span.body[2]);
        assertEquals(1, span.body[3]);
        DBus.Msg headers = call(path(2212), Atspi.I_TABLE_CELL, "GetColumnHeaderCells", null);
        Object[] first = (Object[]) ((List<?>) headers.body[0]).get(0);
        assertEquals(path(2102), first[1], "the cell's column header is the header group's child");
    }

    /**
     * The relation set, one entry per type with every target of that type, in the platform's own
     * numbering: what Orca reads a field's label and its description from when it lands on it.
     */
    @Test
    void aFieldsRelationsArriveAsATypedSetOfObjectReferences() {
        publishAWindowWithALabelledAndDescribedField();

        DBus.Msg set = call(path(3002), Atspi.I_ACCESSIBLE, "GetRelationSet", null);
        assertEquals("a(ua(so))", set.signature);
        List<?> entries = (List<?>) set.body[0];
        assertEquals(2, entries.size(), "one entry per relation type the field declares");
        Object[] labelled = (Object[]) entries.get(0);
        assertEquals(Atspi.RELATION_LABELLED_BY, labelled[0]);
        assertEquals(path(3001), DBus.Ref.of(((List<?>) labelled[1]).get(0)).path,
                "the caption's own object, so a client may read either");
        Object[] described = (Object[]) entries.get(1);
        assertEquals(Atspi.RELATION_DESCRIBED_BY, described[0]);
        assertEquals(path(3003), DBus.Ref.of(((List<?>) described[1]).get(0)).path,
                "and the message beneath the field, which the description already copied");

        DBus.Msg mirror = call(path(3001), Atspi.I_ACCESSIBLE, "GetRelationSet", null);
        Object[] labelFor = (Object[]) ((List<?>) mirror.body[0]).get(0);
        assertEquals(Atspi.RELATION_LABEL_FOR, labelFor[0]);
        assertEquals(path(3002), DBus.Ref.of(((List<?>) labelFor[1]).get(0)).path);

        DBus.Msg none = call(path(3003), Atspi.I_ACCESSIBLE, "GetRelationSet", null);
        assertEquals(List.of(), none.body[0], "a node with no relations answers an empty set");
        DBus.Msg root = call(Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetRelationSet", null);
        assertEquals(List.of(), root.body[0], "and so does the application object");
    }

    private DBus.Msg call(String path, String iface, String member, String sig, Object... args) {
        DBus.Msg m = DBus.Msg.call("org.a11y.atspi.Registry", path, iface, member, sig, args);
        m.path = path;
        m.iface = iface;
        m.member = member;
        return atspi.handle(null, m);
    }

    /**
     * What a client sees when it sends a member the wrong arguments: an error naming them, from the
     * same step the reader thread takes, rather than the exception that used to leave the client
     * waiting out its whole timeout (LINUX-NEW-9).
     */
    @Test
    void aCallWithTheWrongArgumentsIsAnsweredInvalidArgsAndNotLeftUnanswered() {
        publishAWindowWithAButton();
        for (DBus.Msg call : List.of(
                aCall(Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetChildAtIndex", null),
                aCall(path(1001), Atspi.I_ACTION, "DoAction", "s", "press"),
                aCall(path(1001), Atspi.I_COMPONENT, "Contains", "ii", 1, 2),
                aCall(path(1001), Atspi.I_PROPS, "Get", "s", Atspi.I_ACCESSIBLE))) {
            call.serial = 41;
            call.sender = ":1.99";
            DBus.Msg reply = DBus.Conn.replyFor(atspi::handle, null, call);
            assertEquals(DBus.ERROR, reply.type, call.member + " with <" + call.signature + ">");
            assertEquals("org.freedesktop.DBus.Error.InvalidArgs", reply.errorName, call.member);
            assertEquals(41, reply.replySerial, "the reply names the call it answers");
            assertEquals(":1.99", reply.destination);
        }
        assertTrue(performed.isEmpty(), "and nothing was performed on a verb nobody named");
    }

    private static DBus.Msg aCall(String path, String iface, String member, String sig,
                                  Object... args) {
        DBus.Msg m = DBus.Msg.call("org.a11y.atspi.Registry", path, iface, member, sig, args);
        m.path = path;
        m.iface = iface;
        m.member = member;
        return m;
    }

    @Test
    void aPingIsAnsweredOnAPathThatIsNeitherTheRootNorANode() {
        // "/" is where the registry sends it, and it is neither the application root nor any node,
        // so this used to fall through the path lookup and be answered with nothing at all.
        //
        // The consequence was invisible until at-spi2-core 2.60, which added "detect unresponsive
        // applications, and do not expose them as children of the desktop". On Fedora 44 that made
        // this application impossible for any client to see -- Orca included -- while the registry
        // went on talking to it perfectly: hundreds of calls, a full Cache.GetItems, no error
        // anywhere. Being hidden from the desktop's children is an omission, not a refusal, so
        // nothing in the conversation says it happened. Ubuntu's 2.52 does not ping.
        assertNotNull(call("/", Atspi.I_PEER, "Ping", null),
                "an unanswered ping is an application the newer registry hides");
        assertNotNull(call(Atspi.PATH_ROOT, Atspi.I_PEER, "Ping", null));
    }

    @Test
    void anUnknownMemberOfPeerIsStillDeclined() {
        assertNull(call("/", Atspi.I_PEER, "GetMachineId", null),
                "answering a member we do not have would be worse than saying we have not got it");
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
