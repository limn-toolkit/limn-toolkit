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
    private final List<Accessible.Argument> arguments = new ArrayList<>();
    private AtspiTree atspi;

    private final AccessibilityBridge.Host host = new AccessibilityBridge.Host() {
        @Override public void requestRepublish() { }
        @Override public void requestRestamp() { }
        @Override public AccessibleTree republishNow() { return tree.get(); }
        @Override public boolean perform(long nodeId, Accessible.Action action,
                                         Accessible.Argument arg) {
            performed.add(nodeId + ":" + action);
            arguments.add(arg);
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
     * A calendar's grid (LINUX-NEW-10, semantics 2): week rows that are no selection's members, and
     * day cells that carry their row and column and a day-of-month position (decision 37). The
     * lookup went through a row's position in set, which a week row does not have, so every cell of
     * every calendar answered the null object.
     */
    @Test
    void aCalendarShapedGridAnswersItsCellsByRowAndColumnAndNeverByAPositionInSet() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int grid = a.begin(4000, 0, Locale.ENGLISH, 0, 0, 350, 100);
        a.role(Accessible.Role.TABLE);
        a.table(2, 7);
        a.selection(false, true);
        a.inherited(true, true, true, true, false);
        for (int w = 0; w < 2; w++) {
            int row = a.begin(4100 + w * 10, grid, Locale.ENGLISH, 0, w * 50, 350, 50);
            a.role(Accessible.Role.ROW);
            a.inherited(true, true, true, false, false);
            for (int c = 0; c < 7; c++) {
                a.begin(4101 + w * 10 + c, row, Locale.ENGLISH, c * 50, w * 50, 50, 50);
                a.role(Accessible.Role.CELL);
                a.cell(w, c);
                int day = w * 7 + c + 1;
                a.selectionItem(day == 10, day, 30);
                a.action(Accessible.Action.SELECT);
                a.inherited(true, true, true, false, false);
                a.end();
            }
            a.end();
        }
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));

        DBus.Msg cell = call(path(4000), Atspi.I_TABLE, "GetAccessibleAt", "ii", 1, 2);
        assertEquals(path(4113), ((Object[]) cell.body[0])[1],
                "week 1, weekday 2 is the day its CellFacet names, whatever a row publishes");
        DBus.Msg extents = call(path(4000), Atspi.I_TABLE, "GetRowColumnExtentsAtIndex", "i", 9);
        assertEquals(List.of(true, 1, 2, 1, 1, true), List.of(extents.body),
                "index 9 is row 1, column 2, and that day is the selected one");
        assertEquals(true, call(path(4000), Atspi.I_TABLE, "IsSelected", "ii", 1, 2).body[0],
                "a selected cell is selected at its row and column, though its row is not");
        assertEquals(false, call(path(4000), Atspi.I_TABLE, "IsRowSelected", "i", 1).body[0]);
        assertEquals(Atspi.PATH_NULL, ((Object[]) call(path(4000), Atspi.I_TABLE, "GetAccessibleAt",
                "ii", 2, 0).body[0])[1], "a week the grid does not hold is the null object");
    }

    /**
     * A table's column headers are found by their CellFacet among its direct groups (LINUX-NEW-11,
     * semantics 3): with the header hidden and a footer shown, the footer was the first group, so a
     * reader was told the totals were the column headers, and a partial footer named the wrong
     * column's.
     */
    @Test
    void aColumnHeaderIsTheCellAtRowMinusOneOfItsColumnAndAFooterIsNeverOne() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int table = a.begin(5000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.table(1, 2);
        a.inherited(true, true, true, true, false);
        int row = a.begin(5010, table, Locale.ENGLISH, 0, 0, 400, 30);
        a.role(Accessible.Role.ROW);
        a.inherited(true, true, true, false, false);
        for (int c = 0; c < 2; c++) {
            a.begin(5011 + c, row, Locale.ENGLISH, c * 200, 0, 200, 30);
            a.role(Accessible.Role.CELL);
            a.name(I18nString.literal("r0c" + c), Accessible.NameFrom.CONTENT);
            a.cell(0, c);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        int footer = a.begin(5100, table, Locale.ENGLISH, 0, 30, 400, 30);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        a.begin(5102, footer, Locale.ENGLISH, 200, 30, 200, 30);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("Total 99"), Accessible.NameFrom.CONTENT);
        a.cell(-2, 1);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));

        for (int column = 0; column < 2; column++) {
            assertEquals(Atspi.PATH_NULL, ((Object[]) call(path(5000), Atspi.I_TABLE,
                    "GetColumnHeader", "i", column).body[0])[1],
                    "no header row: column " + column + " has no header, the footer is not one");
            assertEquals("", call(path(5000), Atspi.I_TABLE, "GetColumnDescription", "i", column)
                    .body[0]);
        }
        assertEquals(List.of(), call(path(5012), Atspi.I_TABLE_CELL, "GetColumnHeaderCells", null)
                .body[0], "and a data cell of column 1 names no header cell");
        assertEquals(path(5012), ((Object[]) call(path(5000), Atspi.I_TABLE, "GetAccessibleAt",
                "ii", 0, 1).body[0])[1]);
    }

    /**
     * {@code AddRowSelection} posts the first of [ADD_TO_SELECTION, SELECT] the row accepts and
     * {@code RemoveRowSelection} posts DESELECT (semantics 5): it posted SELECT alone, so on a
     * multi-select table a client's "add" replaced the selection, and a removal was refused on a row
     * that offered it.
     */
    @Test
    void aRowSelectionPostsTheFirstOfItsCandidatesTheRowAcceptsAndNothingElse() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int table = a.begin(6000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.table(4, 1);
        a.selection(true, false);
        a.inherited(true, true, true, true, false);
        Accessible.Action[][] verbs = {
                {Accessible.Action.SELECT, Accessible.Action.ADD_TO_SELECTION},
                {Accessible.Action.SELECT},
                {Accessible.Action.SELECT, Accessible.Action.DESELECT},
                {},
        };
        for (int r = 0; r < verbs.length; r++) {
            int row = a.begin(6010 + r * 10, table, Locale.ENGLISH, 0, r * 30, 400, 30);
            a.role(Accessible.Role.ROW);
            a.selectionItem(r == 2, r + 1, verbs.length);
            if (verbs[r].length > 0) {
                a.action(verbs[r]);
            }
            a.inherited(true, true, true, false, false);
            a.begin(6011 + r * 10, row, Locale.ENGLISH, 0, r * 30, 400, 30);
            a.role(Accessible.Role.CELL);
            a.cell(r, 0);
            a.inherited(true, true, true, false, false);
            a.end();
            a.end();
        }
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));

        List<Object> answers = new ArrayList<>();
        for (int r = 0; r < verbs.length; r++) {
            answers.add(call(path(6000), Atspi.I_TABLE, "AddRowSelection", "i", r).body[0]);
            answers.add(call(path(6000), Atspi.I_TABLE, "RemoveRowSelection", "i", r).body[0]);
        }
        assertEquals(List.of(true, false, true, false, true, true, false, false), answers,
                "add is taken where the row offers ADD_TO_SELECTION or SELECT; remove only where "
                        + "it offers DESELECT");
        assertEquals(List.of("6010:ADD_TO_SELECTION", "6020:SELECT", "6030:SELECT",
                "6030:DESELECT"), performed, "the first candidate the row accepts, and on a row "
                + "offering nothing, nothing");
    }

    /**
     * A list whose first child is its scroll bar, holding four rows of which two are selected and one
     * offers nothing: the shape of a Tree or a ListView, where a child index and a selected-member
     * index are not the same number.
     */
    private void publishAWindowWithAMultiSelectList() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int list = a.begin(7000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.LIST);
        a.selection(true, false);
        a.inherited(true, true, true, true, true);
        a.begin(7001, list, Locale.ENGLISH, 390, 0, 10, 300);
        a.role(Accessible.Role.SCROLL_BAR);
        a.value(0, 0, 100, 10);
        a.inherited(true, true, true, false, false);
        a.end();
        for (int r = 0; r < 4; r++) {
            a.begin(7010 + r, list, Locale.ENGLISH, 0, r * 30, 390, 30);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal("Row " + r), Accessible.NameFrom.CONTENT);
            boolean selected = r == 1 || r == 3;
            a.selectionItem(selected, r + 1, 4);
            if (r == 2) {
                a.action(Accessible.Action.SELECT, Accessible.Action.ADD_TO_SELECTION);
            } else if (r == 0) {
                a.action(Accessible.Action.SELECT);
            } else if (r == 1) {
                a.action(Accessible.Action.SELECT, Accessible.Action.DESELECT);
            }
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        tree.set(a.publish(7000, 0, 0, 1f, true));
    }

    /**
     * org.a11y.atspi.Selection on a container (L2, semantics 1 and 5): nine containers published a
     * SelectionFacet and none was served, so libatspi's get_n_selected_children answered -1 in every
     * snapshot of the tree reader and Orca found no selection to present.
     */
    @Test
    void aSelectionContainerCountsItsSelectedMembersAndAddressesItsChildrenByTheirIndex() {
        publishAWindowWithAMultiSelectList();

        assertTrue(((List<?>) call(path(7000), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_SELECTION), "a node with a selection facet implements Selection");
        assertTrue(!((List<?>) call(path(7010), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_SELECTION), "a member does not");
        DBus.Msg count = call(path(7000), Atspi.I_PROPS, "Get", "ss", Atspi.I_SELECTION,
                "NSelectedChildren");
        assertNull(count.errorName, "answered, not refused as a property this object lacks");
        assertEquals(2, ((DBus.Variant) count.body[0]).value,
                "a property, read through Properties as libatspi reads it: two rows are selected");
        assertEquals("i", ((DBus.Variant) count.body[0]).sig);

        assertEquals(path(7011), ((Object[]) call(path(7000), Atspi.I_SELECTION,
                "GetSelectedChild", "i", 0).body[0])[1], "the first selected member");
        assertEquals(path(7013), ((Object[]) call(path(7000), Atspi.I_SELECTION,
                "GetSelectedChild", "i", 1).body[0])[1], "and the second, in reading order");
        assertEquals(Atspi.PATH_NULL, ((Object[]) call(path(7000), Atspi.I_SELECTION,
                "GetSelectedChild", "i", 2).body[0])[1], "past the last is the null object");

        List<Object> selected = new ArrayList<>();
        for (int child = 0; child < 6; child++) {
            selected.add(call(path(7000), Atspi.I_SELECTION, "IsChildSelected", "i", child).body[0]);
        }
        assertEquals(List.of(false, false, true, false, true, false), selected,
                "a child index is the literal child: 0 is the scroll bar, 2 and 4 are rows 1 and 3");

        List<Object> answers = new ArrayList<>();
        answers.add(call(path(7000), Atspi.I_SELECTION, "SelectChild", "i", 0).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "SelectChild", "i", 1).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "SelectChild", "i", 3).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "SelectChild", "i", 4).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "DeselectChild", "i", 2).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "DeselectChild", "i", 4).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "DeselectSelectedChild", "i", 0).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "DeselectSelectedChild", "i", 5).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "SelectAll", null).body[0]);
        answers.add(call(path(7000), Atspi.I_SELECTION, "ClearSelection", null).body[0]);
        assertEquals(List.of(false, true, true, false, true, false, true, false, false, false),
                answers, "each write is taken only where the child offers one of its candidates");
        assertEquals(List.of("7010:SELECT", "7012:ADD_TO_SELECTION", "7011:DESELECT",
                "7011:DESELECT"), performed, "the scroll bar is selected by nothing, row 2's add is "
                + "ADD_TO_SELECTION, and a selected index names the member, not the child");
    }

    /**
     * A calendar's days are members of the grid although they hang under week rows (semantics 1):
     * the selected-member count and GetSelectedChild reach through the rows, and a row, which is
     * the grid's literal child, is never selected.
     */
    @Test
    void aGridsSelectedMembersAreFoundUnderItsRowsAndTheRowsAreNeverSelected() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int grid = a.begin(8000, 0, Locale.ENGLISH, 0, 0, 350, 100);
        a.role(Accessible.Role.TABLE);
        a.table(2, 2);
        a.selection(false, true);
        for (int w = 0; w < 2; w++) {
            a.child(100 + w);
            a.bounds(0, w * 50, 100, 50);
            a.role(Accessible.Role.ROW);
            for (int c = 0; c < 2; c++) {
                a.child(200 + w * 10 + c);
                a.bounds(c * 50, w * 50, 50, 50);
                a.role(Accessible.Role.CELL);
                a.name(I18nString.literal("Day " + (w * 2 + c + 1)), Accessible.NameFrom.CONTENT);
                a.cell(w, c);
                a.selectionItem(w == 1 && c == 0, w * 2 + c + 1, 30);
                a.endChild();
            }
            a.endChild();
        }
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        AccessibleTree published = a.publish(0, 0, 0, 1f, true);
        tree.set(published);
        String gridPath = path(published.node(grid).id());
        AccessibleNode day3 = null;
        for (int i = 0; i < published.nodeCount(); i++) {
            if ("Day 3".equals(published.node(i).name())) {
                day3 = published.node(i);
            }
        }
        assertNotNull(day3);

        assertEquals(1, ((DBus.Variant) call(gridPath, Atspi.I_PROPS, "Get", "ss",
                Atspi.I_SELECTION, "NSelectedChildren").body[0]).value);
        assertEquals(path(day3.id()), ((Object[]) call(gridPath, Atspi.I_SELECTION,
                "GetSelectedChild", "i", 0).body[0])[1], "the selected day, under its week row");
        assertEquals(false, call(gridPath, Atspi.I_SELECTION, "IsChildSelected", "i", 1).body[0],
                "child 1 is the week row holding it, which is no member");
    }

    /**
     * A window of four values: a spinner, a date segment nobody has typed into (decision 16), a
     * progress bar that may be read and not set, and a spinner under a disabled ancestor.
     */
    private void publishAWindowWithValues() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(9001, 0, Locale.ENGLISH, 0, 0, 100, 30);
        a.role(Accessible.Role.SPIN_BUTTON);
        a.name(I18nString.literal("Day"), Accessible.NameFrom.EXPLICIT);
        a.value(13, 1, 31, 1);
        a.valueText("13", 1);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(9002, 0, Locale.ENGLISH, 100, 0, 100, 30);
        a.role(Accessible.Role.SPIN_BUTTON);
        a.name(I18nString.literal("Month"), Accessible.NameFrom.EXPLICIT);
        a.emptyValue(1, 12, 1, false);
        a.valueText("empty", 1);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(9003, 0, Locale.ENGLISH, 200, 0, 100, 30);
        a.role(Accessible.Role.PROGRESS_BAR);
        a.value(40, 0, 100, 0, true);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(9004, 0, Locale.ENGLISH, 300, 0, 100, 30);
        a.role(Accessible.Role.SPIN_BUTTON);
        a.value(5, 0, 10, 1);
        a.inherited(false, true, true, false, false);
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<Object, Object> valueOf(long id) {
        DBus.Msg all = call(path(id), Atspi.I_PROPS, "GetAll", "s", Atspi.I_VALUE);
        java.util.Map<Object, Object> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Object, Object> e
                : ((java.util.Map<Object, Object>) all.body[0]).entrySet()) {
            DBus.Variant v = (DBus.Variant) e.getValue();
            out.put(e.getKey(), v.sig + " " + v.value);
        }
        return out;
    }

    /**
     * org.a11y.atspi.Value on every node with a ValueFacet (LINUX-NEW-4, DATES-NEW-5): no Linux
     * client could read a date segment's number, a spinner's or a slider's, because the interface
     * was served nowhere. CurrentValue is mandatory, so an empty segment answers its minimum and
     * says empty through Text (decision 16).
     */
    @Test
    void aValueIsReadAsTheFourNumbersAndTheTextAndAnEmptyOneAnswersItsMinimum() {
        publishAWindowWithValues();

        assertTrue(((List<?>) call(path(9001), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_VALUE), "a node with a value facet implements Value");
        assertEquals(java.util.Map.of("MinimumValue", "d 1.0", "MaximumValue", "d 31.0",
                "MinimumIncrement", "d 1.0", "CurrentValue", "d 13.0", "Text", "s 13",
                "version", "u 1"),
                valueOf(9001), "every number a double, as libatspi reads them, and the text");
        assertEquals(java.util.Map.of("MinimumValue", "d 1.0", "MaximumValue", "d 12.0",
                "MinimumIncrement", "d 1.0", "CurrentValue", "d 1.0", "Text", "s empty",
                "version", "u 1"),
                valueOf(9002), "an empty segment: its minimum where a number is mandatory, and "
                        + "the word");
        assertEquals("d 40.0", valueOf(9003).get("CurrentValue"));
        assertEquals("s ", valueOf(9003).get("Text"), "no display form is an empty string");
    }

    /**
     * The "version" property the served XML declares on Selection, Value, Text, EditableText, Table
     * and TableCell is answered u 1, as GTK 3's ATK bridge answers it on the Fedora guest; it was
     * declared by Introspect and refused as a property the object lacks (the review of sub-lane
     * linux-C). An interface the node does not serve still has no properties at all.
     */
    @Test
    void theVersionTheServedXmlDeclaresIsAnsweredAsTheAtkBridgeAnswersIt() {
        publishAWindowWithValues();
        DBus.Msg value = call(path(9001), Atspi.I_PROPS, "Get", "ss", Atspi.I_VALUE, "version");
        assertNull(value.errorName, "Value.version");
        assertEquals("u 1", ((DBus.Variant) value.body[0]).sig + " "
                + ((DBus.Variant) value.body[0]).value);
        assertTrue(Atspi.XML_VALUE.contains("<property name=\"version\" type=\"u\""),
                "the XML Introspect serves declares it");

        publishAWindowWithAnEditableField();
        for (String iface : List.of(Atspi.I_TEXT, Atspi.I_EDITABLE_TEXT)) {
            DBus.Msg text = call(path(9211), Atspi.I_PROPS, "Get", "ss", iface, "version");
            assertNull(text.errorName, iface + ".version");
            assertEquals(1, ((DBus.Variant) text.body[0]).value, iface);
        }

        publishAWindowWithATable();
        for (String[] served : new String[][] {{"2000", Atspi.I_TABLE}, {"2000", Atspi.I_SELECTION},
                {"2212", Atspi.I_TABLE_CELL}}) {
            DBus.Msg answer = call(path(Long.parseLong(served[0])), Atspi.I_PROPS, "Get", "ss",
                    served[1], "version");
            assertNull(answer.errorName, served[1] + ".version on " + served[0]);
            assertEquals(1, ((DBus.Variant) answer.body[0]).value, served[1]);
        }
        assertEquals(DBus.Conn.INVALID_ARGS, call(path(2212), Atspi.I_PROPS, "Get", "ss",
                Atspi.I_VALUE, "version").errorName, "a cell serves no Value, so it has no version");
    }

    /**
     * Properties.Set(Value, CurrentValue) is the one write libatspi makes, and it posts SET_VALUE
     * only where the node accepts it now (semantics 5): a read-only value and a disabled node are
     * refused with an error, never answered as a success the widget then ignores.
     */
    @Test
    void settingCurrentValuePostsTheNumberWhereTheNodeAcceptsItAndIsRefusedElsewhere() {
        publishAWindowWithValues();

        DBus.Msg set = call(path(9001), Atspi.I_PROPS, "Set", "ssv", Atspi.I_VALUE, "CurrentValue",
                new DBus.Variant("d", 20.0));
        assertNull(set.errorName, "a writable enabled spinner takes it");
        assertEquals(List.of("9001:SET_VALUE"), performed);
        assertEquals(List.of(new Accessible.Argument.OfValue(20.0)), arguments,
                "the number the client sent, as the value verb's argument");

        performed.clear();
        for (long refused : new long[] {9003, 9004}) {
            DBus.Msg no = call(path(refused), Atspi.I_PROPS, "Set", "ssv", Atspi.I_VALUE,
                    "CurrentValue", new DBus.Variant("d", 3.0));
            assertEquals(DBus.Conn.FAILED, no.errorName, refused + ": read-only, or not enabled");
        }
        DBus.Msg minimum = call(path(9001), Atspi.I_PROPS, "Set", "ssv", Atspi.I_VALUE,
                "MinimumValue", new DBus.Variant("d", 3.0));
        assertEquals(DBus.Conn.PROPERTY_READ_ONLY, minimum.errorName,
                "the other properties are read-only, and say so as GTK 3's ATK bridge does");
        DBus.Msg unknown = call(path(9001), Atspi.I_PROPS, "Set", "ssv", Atspi.I_VALUE,
                "NoSuchProperty", new DBus.Variant("d", 3.0));
        assertEquals(DBus.Conn.INVALID_ARGS, unknown.errorName, "a name Value does not have");
        DBus.Msg word = call(path(9001), Atspi.I_PROPS, "Set", "ssv", Atspi.I_VALUE,
                "CurrentValue", new DBus.Variant("s", "twenty"));
        assertEquals(DBus.Conn.INVALID_ARGS, word.errorName, "a CurrentValue that is no number");
        assertEquals(List.of(), performed, "and nothing reached a widget");
    }

    /** "Hi 😀 there.\nNext line": an astral character, two words, two lines. */
    private static final String FIELD = "Hi \uD83D\uDE00 there.\nNext line";

    /**
     * A window holding a text area with FIELD, its caret after the emoji (UTF-16 5, character 4)
     * and "Hi" selected; a date segment whose value reads "empty"; a spinner whose value has no
     * display form; and a disabled field.
     */
    private void publishAWindowWithText() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(9101, 0, Locale.ENGLISH, 0, 0, 400, 60);
        a.role(Accessible.Role.TEXT_AREA);
        a.text(FIELD, 1, 5, limn.graphics.ShapedText.Affinity.UPSTREAM, 0, 2, 2, null, false);
        a.inherited(true, true, true, true, true);
        a.end();
        a.begin(9102, 0, Locale.ENGLISH, 0, 60, 100, 30);
        a.role(Accessible.Role.SPIN_BUTTON);
        a.emptyValue(1, 12, 1, false);
        a.valueText("empty", 1);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(9103, 0, Locale.ENGLISH, 100, 60, 100, 30);
        a.role(Accessible.Role.SPIN_BUTTON);
        a.value(3, 0, 10, 1);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(9104, 0, Locale.ENGLISH, 200, 60, 100, 30);
        a.role(Accessible.Role.TEXT_FIELD);
        a.text("off", 1, 0, limn.graphics.ShapedText.Affinity.UPSTREAM, 0, 0, 1, null, false);
        a.inherited(false, true, true, false, false);
        a.end();
        a.end();
        tree.set(a.publish(9101, 0, 0, 1f, true));
    }

    private List<Object> range(long id, String member, String sig, Object... args) {
        DBus.Msg reply = call(path(id), Atspi.I_TEXT, member, sig, args);
        assertEquals("sii", reply.signature, member + " answers the string and its two offsets");
        return List.of(reply.body);
    }

    /**
     * org.a11y.atspi.Text over a TextFacet (LINUX-NEW-4): no Linux client could read a field's text,
     * its caret or its selection, although the events already said they changed. Every offset is a
     * character: the emoji is one.
     */
    @Test
    void aTextIsReadInCharactersByOffsetGranularityAndBoundary() {
        publishAWindowWithText();

        assertTrue(((List<?>) call(path(9101), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_TEXT), "a node with a text facet implements Text");
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Object> props = (java.util.Map<Object, Object>) call(path(9101),
                Atspi.I_PROPS, "GetAll", "s", Atspi.I_TEXT).body[0];
        assertEquals(21, ((DBus.Variant) props.get("CharacterCount")).value,
                "twenty-two UTF-16 units, twenty-one characters");
        assertEquals(4, ((DBus.Variant) props.get("CaretOffset")).value,
                "after the emoji: five units, four characters");

        assertEquals(FIELD, call(path(9101), Atspi.I_TEXT, "GetText", "ii", 0, -1).body[0]);
        assertEquals("\uD83D\uDE00", call(path(9101), Atspi.I_TEXT, "GetText", "ii", 3, 4).body[0]);
        assertEquals(0x1F600, call(path(9101), Atspi.I_TEXT, "GetCharacterAtOffset", "i", 3)
                .body[0]);

        assertEquals(List.of("\uD83D\uDE00", 3, 4), range(9101, "GetStringAtOffset", "iu", 3,
                Atspi.TEXT_GRANULARITY_CHAR));
        assertEquals(List.of("there.\n", 5, 12), range(9101, "GetStringAtOffset", "iu", 7,
                Atspi.TEXT_GRANULARITY_WORD), "from the word's start to the next word's");
        assertEquals(List.of("Hi \uD83D\uDE00 there.\n", 0, 12), range(9101,
                "GetStringAtOffset", "iu", 2, Atspi.TEXT_GRANULARITY_LINE), "a line through its "
                + "line feed");
        assertEquals(List.of("Next line", 12, 21), range(9101, "GetStringAtOffset", "iu", 21,
                Atspi.TEXT_GRANULARITY_LINE), "and a caret after the last character reads the last");
        assertEquals(List.of("Next line", 12, 21), range(9101, "GetStringAtOffset", "iu", 15,
                Atspi.TEXT_GRANULARITY_SENTENCE));
        assertEquals(List.of("Hi \uD83D\uDE00 there.\n", 0, 12), range(9101,
                "GetTextBeforeOffset", "iu", 14, Atspi.TEXT_BOUNDARY_LINE_START));
        assertEquals(List.of("Next line", 12, 21), range(9101, "GetTextAfterOffset", "iu", 0,
                Atspi.TEXT_BOUNDARY_LINE_START));
        assertEquals(List.of("\nNext line", 11, 21), range(9101, "GetTextAtOffset", "iu", 15,
                Atspi.TEXT_BOUNDARY_LINE_END), "an _END boundary runs from one end to the next");

        assertEquals(1, call(path(9101), Atspi.I_TEXT, "GetNSelections", null).body[0]);
        assertEquals(List.of(0, 2), List.of(call(path(9101), Atspi.I_TEXT, "GetSelection", "i", 0)
                .body));
        DBus.Msg run = call(path(9101), Atspi.I_TEXT, "GetAttributeRun", "ib", 3, true);
        assertEquals("a{ss}ii", run.signature, "the shape libatspi checks before it reads a run");
        assertEquals(List.of(java.util.Map.of(), 0, 21), List.of(run.body));
        assertNull(call(path(9101), Atspi.I_TEXT, "GetRangeExtents", "iiu", 0, 2, 1),
                "no geometry is answered (ADR 039 §11)");
    }

    /**
     * The writes of Text post SET_CARET and SET_SELECTION in UTF-16 units, where the node accepts
     * them (semantics 5); a value's display form is read-only text (settled linux-value-text), and a
     * value with no display form serves no Text at all.
     */
    @Test
    void aTextsCaretAndSelectionAreSetInUnitsAndAValuesDisplayFormIsReadOnlyText() {
        publishAWindowWithText();

        assertEquals(true, call(path(9101), Atspi.I_TEXT, "SetCaretOffset", "i", 4).body[0]);
        assertEquals(false, call(path(9101), Atspi.I_TEXT, "AddSelection", "ii", 5, 6).body[0],
                "one selection stands already, and the model holds one");
        assertEquals(true, call(path(9101), Atspi.I_TEXT, "SetSelection", "iii", 0, 4, 3).body[0]);
        assertEquals(false, call(path(9101), Atspi.I_TEXT, "SetSelection", "iii", 1, 0, 1).body[0]);
        assertEquals(true, call(path(9101), Atspi.I_TEXT, "RemoveSelection", "i", 0).body[0]);
        assertEquals(List.of("9101:SET_CARET", "9101:SET_SELECTION", "9101:SET_SELECTION"),
                performed);
        assertEquals(List.of(new Accessible.Argument.OfRange(5, 5),
                new Accessible.Argument.OfRange(3, 5), new Accessible.Argument.OfRange(5, 5)),
                arguments, "character 4 is unit 5; a removal collapses the selection at the caret");

        performed.clear();
        assertTrue(((List<?>) call(path(9102), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_TEXT), "a value with a display form serves it as text");
        assertEquals("empty", call(path(9102), Atspi.I_TEXT, "GetText", "ii", 0, -1).body[0]);
        assertEquals(false, call(path(9102), Atspi.I_TEXT, "SetCaretOffset", "i", 1).body[0]);
        assertTrue(!((List<?>) call(path(9103), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_TEXT), "a value whose number is the whole of it does not");
        assertNull(call(path(9103), Atspi.I_TEXT, "GetText", "ii", 0, -1));
        assertEquals(false, call(path(9104), Atspi.I_TEXT, "SetCaretOffset", "i", 1).body[0],
                "a disabled field keeps its text and takes no caret");
        assertEquals(List.of(), performed);
    }

    /**
     * org.a11y.atspi.EditableText on an editable text (LINUX-NEW-4): every write is one SET_TEXT of
     * the whole new string, built from the published text in characters, and taken only where the
     * field accepts SET_TEXT (semantics 5); a masked field takes only a whole replacement, and the
     * clipboard is not the bridge's.
     */
    @Test
    void anEditableTextWritesTheWholeNewStringThroughSetTextWhereTheFieldAcceptsIt() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        String[] texts = {"a\uD83D\uDE00b", "off", "••••", "fixed"};
        for (int i = 0; i < texts.length; i++) {
            a.begin(9201 + i, 0, Locale.ENGLISH, 0, i * 30, 400, 30);
            a.role(i == 2 ? Accessible.Role.PASSWORD_FIELD : Accessible.Role.TEXT_FIELD);
            a.text(texts[i], i + 1, 0, limn.graphics.ShapedText.Affinity.UPSTREAM, 0, 0, 1, null,
                    i == 3);
            if (i != 3) {
                a.state(Accessible.State.EDITABLE);
            }
            if (i == 2) {
                a.state(Accessible.State.PASSWORD);
            }
            a.inherited(i != 1, true, true, true, false);
            a.end();
        }
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));

        List<?> ifaces = (List<?>) call(path(9201), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0];
        assertTrue(ifaces.contains(Atspi.I_EDITABLE_TEXT) && ifaces.contains(Atspi.I_TEXT),
                "an editable field implements both");
        assertTrue(((List<?>) call(path(9202), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_EDITABLE_TEXT), "a disabled field is still an editable field");
        assertTrue(!((List<?>) call(path(9204), Atspi.I_ACCESSIBLE, "GetInterfaces", null).body[0])
                .contains(Atspi.I_EDITABLE_TEXT), "read-only text is not");

        List<Object> answers = new ArrayList<>();
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "InsertText", "isi", 2, "xyz", 2).body[0]);
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "InsertText", "isi", 1, "\u00e9", 2)
                .body[0]);
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "DeleteText", "ii", 1, 2).body[0]);
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "SetTextContents", "s", "new").body[0]);
        answers.add(call(path(9202), Atspi.I_EDITABLE_TEXT, "SetTextContents", "s", "no").body[0]);
        answers.add(call(path(9203), Atspi.I_EDITABLE_TEXT, "InsertText", "isi", 0, "x", 1).body[0]);
        answers.add(call(path(9203), Atspi.I_EDITABLE_TEXT, "SetTextContents", "s", "hunter2")
                .body[0]);
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "CutText", "ii", 0, 1).body[0]);
        answers.add(call(path(9201), Atspi.I_EDITABLE_TEXT, "PasteText", "i", 0).body[0]);
        assertEquals(List.of(true, true, true, true, false, false, true, false, false), answers);
        assertEquals(0, call(path(9201), Atspi.I_EDITABLE_TEXT, "CopyText", "ii", 0, 1).body.length,
                "CopyText has no reply value");
        assertEquals(List.of("9201:SET_TEXT", "9201:SET_TEXT", "9201:SET_TEXT", "9201:SET_TEXT",
                "9203:SET_TEXT"), performed, "the disabled field and the mask's insertion take "
                + "nothing");
        assertEquals(List.of(new Accessible.Argument.OfText("a\uD83D\uDE00xyb"),
                        new Accessible.Argument.OfText("a\u00e9\uD83D\uDE00b"),
                        new Accessible.Argument.OfText("ab"),
                        new Accessible.Argument.OfText("new"),
                        new Accessible.Argument.OfText("hunter2")), arguments,
                "offsets in characters: after the emoji is character 2; two characters of xyz; a "
                        + "length of UTF-8 bytes still inserts the whole é; the emoji deleted whole");
    }

    /** A window holding one editable, enabled field whose text is "ab". */
    private void publishAWindowWithAnEditableField() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(9211, 0, Locale.ENGLISH, 0, 0, 400, 30);
        a.role(Accessible.Role.TEXT_FIELD);
        a.text("ab", 2, 0, limn.graphics.ShapedText.Affinity.UPSTREAM, 0, 0, 1, null, false);
        a.state(Accessible.State.EDITABLE);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));
    }

    /**
     * EditableText.InsertText's length counts UTF-8 bytes, as GTK 3's ATK bridge reads it on the
     * Fedora guest: "é" is two bytes and the emoji four, so a length of 2 or 3 inserts "é" alone,
     * 6 inserts both, 1 inserts nothing, and a negative length all of it. It was taken as
     * characters, which inserted "é😀" for a length of 2 (the review of sub-lane linux-C).
     */
    @Test
    void anInsertionsLengthCountsUtf8BytesAndNeverCutsACharacter() {
        publishAWindowWithAnEditableField();

        for (int length : new int[] {1, 2, 3, 6, 7, 100, -1}) {
            assertEquals(true, call(path(9211), Atspi.I_EDITABLE_TEXT, "InsertText", "isi", 1,
                    "\u00e9\uD83D\uDE00x", length).body[0], "length " + length);
        }
        assertEquals(List.of(new Accessible.Argument.OfText("ab"),
                        new Accessible.Argument.OfText("a\u00e9b"),
                        new Accessible.Argument.OfText("a\u00e9b"),
                        new Accessible.Argument.OfText("a\u00e9\uD83D\uDE00b"),
                        new Accessible.Argument.OfText("a\u00e9\uD83D\uDE00xb"),
                        new Accessible.Argument.OfText("a\u00e9\uD83D\uDE00xb"),
                        new Accessible.Argument.OfText("a\u00e9\uD83D\uDE00xb")), arguments,
                "what GTK 3's ATK bridge left in \"ab\" for the lengths 1, 2, 3, 6, 7, 100 and -1");
    }

    /**
     * A row's level, position and set size are object attributes (L5, semantics 6): Orca 50.2 reads
     * {@code level}, {@code posinset} and {@code setsize} first, and the bridge answered
     * {@code toolkit} alone, so no tree item had a level and no row an "n of m". A zero publishes
     * nothing.
     */
    @Test
    void aRowSaysItsLevelAndWhereItIsInItsSetAsObjectAttributesAndAZeroSaysNothing() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int outline = a.begin(9300, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TREE);
        a.selection(false, false);
        a.inherited(true, true, true, true, false);
        int[][] rows = {{2, 3, 4}, {0, 0, 0}, {1, 0, 7}};
        for (int i = 0; i < rows.length; i++) {
            a.begin(9301 + i, outline, Locale.ENGLISH, 0, i * 20, 400, 20);
            a.role(Accessible.Role.TREE_ITEM);
            a.hierarchy(rows[i][0], i + 1, rows.length);
            a.selectionItem(false, rows[i][1], rows[i][2]);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        tree.set(a.publish(0, 0, 0, 1f, true));

        assertEquals(java.util.Map.of("toolkit", "limn", "level", "2", "posinset", "3",
                "setsize", "4"), call(path(9301), Atspi.I_ACCESSIBLE, "GetAttributes", null).body[0],
                "level 2, 3 of 4, one-based as the model counts and as Orca reads");
        assertEquals(java.util.Map.of("toolkit", "limn"), call(path(9302), Atspi.I_ACCESSIBLE,
                "GetAttributes", null).body[0], "zeros are no numbers, never 0 of 0");
        assertEquals(java.util.Map.of("toolkit", "limn", "level", "1", "setsize", "7"),
                call(path(9303), Atspi.I_ACCESSIBLE, "GetAttributes", null).body[0],
                "each number stands on its own");
        assertEquals(java.util.Map.of("toolkit", "limn"), call(Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE,
                "GetAttributes", null).body[0]);
    }

    /**
     * A window drawn at (200, 100) of the screen at factor 1: a group at (20, 30) holding a label, a
     * button laid over the whole group after it, and a box that is not showing; then a field that
     * takes focus beside it.
     */
    private void publishAWindowWithOverlappingBoxes() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int group = a.begin(9400, 0, Locale.ENGLISH, 20, 30, 200, 200);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        a.begin(9401, group, Locale.ENGLISH, 30, 40, 50, 20);
        a.role(Accessible.Role.LABEL);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(9402, group, Locale.ENGLISH, 20, 30, 200, 200);
        a.role(Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(9403, group, Locale.ENGLISH, 120, 130, 50, 50);
        a.role(Accessible.Role.BUTTON);
        a.inherited(true, true, false, false, false);
        a.end();
        a.end();
        a.begin(9404, 0, Locale.ENGLISH, 250, 30, 100, 30);
        a.role(Accessible.Role.TEXT_FIELD);
        a.action(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        tree.set(a.publish(0, 200, 100, 1f, true));
    }

    private String hitAt(String path, int x, int y, int coords) {
        return (String) ((Object[]) call(path, Atspi.I_COMPONENT, "GetAccessibleAtPoint", "iiu", x, y,
                coords).body[0])[1];
    }

    /**
     * Component.GetAccessibleAtPoint is a bounds walk over the snapshot (LINUX-NEW-5): it answered
     * UnknownMethod, which is what Orca 50.2's mouse review got. The deepest showing box wins, a
     * later sibling over an earlier one, in whichever coordinates the client asks.
     */
    @Test
    void thePointAClientAsksAboutLandsOnTheDeepestShowingBoxDrawnLast() {
        publishAWindowWithOverlappingBoxes();

        assertEquals(path(9402), hitAt(path(1000), 35, 45, Atspi.COORD_WINDOW),
                "the button laid over the label after it is what the point is on");
        assertEquals(path(9402), hitAt(path(1000), 235, 145, Atspi.COORD_SCREEN),
                "the same point on the screen");
        assertEquals(path(9402), hitAt(path(9400), 35, 45, Atspi.COORD_WINDOW),
                "asked of the group, the same child");
        assertEquals(path(9402), hitAt(path(1000), 125, 135, Atspi.COORD_WINDOW),
                "a box that is not showing is no hit, whatever it is drawn after");
        assertEquals(path(9404), hitAt(path(1000), 260, 40, Atspi.COORD_WINDOW));
        assertEquals(Atspi.PATH_NULL, hitAt(path(1000), 5, 5, Atspi.COORD_WINDOW),
                "a point on the window and no child is the null object");
        assertEquals(Atspi.PATH_NULL, hitAt(path(9401), 35, 45, Atspi.COORD_WINDOW),
                "a leaf has no child at any point");
        assertEquals(path(1000), hitAt(Atspi.PATH_ROOT, 235, 145, Atspi.COORD_SCREEN),
                "the application's child at a screen point is the window there");

        Object[] parentBox = (Object[]) call(path(9401), Atspi.I_COMPONENT, "GetExtents", "u",
                Atspi.COORD_PARENT).body[0];
        assertEquals(List.of(10, 10, 50, 20), List.of(parentBox), "PARENT is relative to the parent");
    }

    /**
     * Component.GrabFocus posts FOCUS where the node publishes it (semantics 5; LINUX-NEW-5): it
     * answered UnknownMethod although ADR 039 §2.3 promised it.
     */
    @Test
    void grabFocusPostsFocusWhereTheNodeOffersItAndIsRefusedElsewhere() {
        publishAWindowWithOverlappingBoxes();

        assertEquals(true, call(path(9404), Atspi.I_COMPONENT, "GrabFocus", null).body[0]);
        assertEquals(false, call(path(9402), Atspi.I_COMPONENT, "GrabFocus", null).body[0],
                "a node that publishes no FOCUS is refused");
        assertEquals(List.of("9404:FOCUS"), performed);
    }

    /**
     * Introspectable.Introspect on every path this application exports (LINUX-NEW-5): nothing
     * handled it, so busctl tree and gdbus introspect saw no object at all.
     */
    @Test
    void everyExportedPathIntrospectsAsWhatItServesAndItsChildren() {
        publishAWindowWithOverlappingBoxes();

        DBus.Msg topReply = call("/", Atspi.I_INTROSPECT, "Introspect", null);
        assertNotNull(topReply, "answered, where nothing handled Introspect");
        String top = (String) topReply.body[0];
        assertTrue(top.contains("<node name=\"org\"/>"), top);
        String atspiPath = (String) call("/org/a11y/atspi", Atspi.I_INTROSPECT, "Introspect", null)
                .body[0];
        assertTrue(atspiPath.contains("<node name=\"accessible\"/>")
                && atspiPath.contains("<node name=\"cache\"/>"), atspiPath);
        String nodes = (String) call("/org/a11y/atspi/accessible", Atspi.I_INTROSPECT, "Introspect",
                null).body[0];
        assertTrue(nodes.contains("<node name=\"root\"/>") && nodes.contains("<node name=\"9404\"/>"),
                "the application root and every node, flat: " + nodes);

        String field = (String) call(path(9404), Atspi.I_INTROSPECT, "Introspect", null).body[0];
        assertTrue(field.contains("<interface name=\"org.a11y.atspi.Accessible\">")
                && field.contains("<interface name=\"org.a11y.atspi.Component\">")
                && field.contains("<interface name=\"org.a11y.atspi.Action\">")
                && field.contains("<interface name=\"org.freedesktop.DBus.Properties\">"), field);
        assertTrue(!field.contains("org.a11y.atspi.Table"), "only what the node serves");
        String root = (String) call(Atspi.PATH_ROOT, Atspi.I_INTROSPECT, "Introspect", null).body[0];
        assertTrue(root.contains("<interface name=\"org.a11y.atspi.Application\">"), root);
        assertNull(call("/org/a11y/atspi/accessible/999999", Atspi.I_INTROSPECT, "Introspect", null),
                "a path that names nothing is declined");
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

    /**
     * Component.GetPosition and GetSize answer two out arguments each, on a node and on the
     * application object: libatspi 2.60.6 reads "u=>ii" and "=>ii", and both answered one struct,
     * which it refuses where flat arguments are expected (the review of sub-lane linux-C). GTK 3's
     * ATK bridge and GTK 4.22.4 answer "ii" on the Fedora guest.
     */
    @Test
    void aPositionAndASizeAreTwoOutArgumentsEachAndNeverAStruct() {
        publishAWindowWithAButton();

        for (String path : List.of("/org/a11y/atspi/accessible/1001", Atspi.PATH_ROOT)) {
            Object[] box = (Object[]) call(path, Atspi.I_COMPONENT, "GetExtents", "u",
                    Atspi.COORD_SCREEN).body[0];
            DBus.Msg position = call(path, Atspi.I_COMPONENT, "GetPosition", "u",
                    Atspi.COORD_SCREEN);
            DBus.Msg size = call(path, Atspi.I_COMPONENT, "GetSize", null);
            position.destination = ":1.2";
            size.destination = ":1.2";
            DBus.Msg positionBack = DBus.Msg.parse(position.marshal(31));
            DBus.Msg sizeBack = DBus.Msg.parse(size.marshal(32));
            assertEquals("ii", positionBack.signature, path + ": GetPosition is u=>ii");
            assertEquals(List.of(box[0], box[1]), List.of(positionBack.body), path);
            assertEquals("ii", sizeBack.signature, path + ": GetSize is =>ii");
            assertEquals(List.of(box[2], box[3]), List.of(sizeBack.body), path);
        }
        assertEquals(List.of(220, 140), List.of(call("/org/a11y/atspi/accessible/1001",
                Atspi.I_COMPONENT, "GetPosition", "u", Atspi.COORD_SCREEN).body),
                "the button's origin on the screen");
        assertEquals(List.of(320, 80), List.of(call("/org/a11y/atspi/accessible/1001",
                Atspi.I_COMPONENT, "GetSize", null).body));
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
        assertEquals(List.of(1 << AtspiStates.DEFUNCT, 0), call("/org/a11y/atspi/accessible/999999",
                Atspi.I_ACCESSIBLE, "GetState", null).body[0],
                "except its state, which is DEFUNCT: a client holding it may ask (LINUX-NEW-1)");
        assertEquals(null, call("/org/a11y/atspi/accessible/not-a-number", Atspi.I_ACCESSIBLE,
                "GetState", null), "a path that was never a node is not one that left");
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
