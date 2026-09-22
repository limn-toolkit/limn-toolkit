package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.Accessible.State;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.concurrent.UiRuntime;
import limn.scene.Change;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What every widget of the {@code ROWS} shape owes a reader, as named cases over a
 * {@link RowsSubject} (ADR 045 §4). Each case builds the subject fresh, binds it headlessly,
 * reads the tree the scene published and drives verbs into it the way a platform does. The rules
 * are the ones the 2026-09-13 pass decided for rows, one case each, so that a regression a reader
 * found once in one widget is refused in every widget of the shape:
 * <ul>
 *   <li><b>decision 10</b> — {@code SELECT} means a click and replaces the selection;
 *       {@code ADD_TO_SELECTION} joins it and {@code DESELECT} leaves it, offered by state and
 *       only where there is a multiple selection;</li>
 *   <li><b>decision 11</b> — {@code FOCUS} is offered on a row only where it moves the cursor
 *       without selecting, and refused where the cursor is the selection;</li>
 *   <li><b>decision 20</b> — a row's verbs are the row verb set and nothing else, with
 *       {@code EXPAND} and {@code COLLAPSE} by state;</li>
 *   <li><b>decision 79</b> — a {@code SELECT} that arrives from a client selects the row
 *       addressed, announces one selection change from the user, and leaves the cursor where it
 *       was — unless the cursor is the selection, in which case it follows;</li>
 *   <li><b>decision 80</b> — a row that offers {@code PRESS} activates itself, not the cursor;</li>
 *   <li><b>decision 81</b> — a row of a widget that scrolls offers {@code SCROLL_INTO_VIEW}, and
 *       the verb brings it into the box.</li>
 * </ul>
 * Plus what any tree owes ({@link AccessibleInvariants}), that a row keeps its id while the
 * selection and the cursor move, and that the cursor is published only while the widget has
 * the keyboard.
 *
 * <p>A member is a node carrying a {@code SelectionItemFacet}; the container is the node its
 * {@code selectionContainer} resolves to, or, for a subject whose members are containerless
 * (a radio group; decision 107, 2026-09-22), their common parent, which carries no facet. The members may classify as {@code ROWS} (a list's
 * rows, a tree's, a table's) or as {@code GRID} (a calendar's day cells), because a grid is rows
 * whose members carry cells (§1.2): the rules are the selection's, not the row's role's.
 */
public final class RowsContract {

    /** The verbs a row may carry, decision 20; the walk's free verbs are among them. */
    public static final Set<Action> ROW_VERBS = EnumSet.of(Action.SELECT, Action.ADD_TO_SELECTION,
            Action.DESELECT, Action.FOCUS, Action.PRESS, Action.EXPAND, Action.COLLAPSE,
            Action.SCROLL_INTO_VIEW);

    private RowsContract() {
    }

    /**
     * The cases, in the order above.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(RowsSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the rows are the members of one selection, named in order",
                () -> theRowsAreTheMembersOfOneSelection(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("decision 10: SELECT replaces the selection and "
                + "ADD_TO_SELECTION joins it", () -> selectReplacesAndAddJoins(subject, runtime)));
        cases.add(new ContractCase("decision 11: FOCUS is offered only where it does not select",
                () -> focusOnlyWhereItDoesNotSelect(subject, runtime)));
        cases.add(new ContractCase("decision 20: a row's verbs are the row verb set, by state",
                () -> theRowVerbSet(subject, runtime)));
        cases.add(new ContractCase("decision 79: a SELECT from a client selects and does not "
                + "move the cursor", () -> aClientSelectDoesNotMoveTheCursor(subject, runtime)));
        cases.add(new ContractCase("decision 80: a row that offers PRESS activates itself",
                () -> aRowPressActivatesItself(subject, runtime)));
        cases.add(new ContractCase("decision 81: SCROLL_INTO_VIEW brings a row into the box",
                () -> scrollIntoViewRevealsARow(subject, runtime)));
        cases.add(new ContractCase("a row keeps its id while the selection and the cursor move",
                () -> idsAreStable(subject, runtime)));
        cases.add(new ContractCase("the cursor is published while the widget has the keyboard "
                + "and not before", () -> theCursorIsPublishedWithTheKeyboard(subject, runtime)));
        return cases;
    }

    // ------------------------------------------------------------------------------ the cases

    private static void theRowsAreTheMembersOfOneSelection(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        check(b.members.size() >= 3, b, "a subject shows at least three rows; this one published "
                + b.members.size());
        if (subject.containerless()) {
            check(b.container.selection() == null, b, "containerless members have no selection "
                    + "container: the box is their common parent, which carries no facet");
        } else {
            check(b.container.selection() != null, b, "the container carries the selection facet");
            Shape containerShape = Shape.of(b.container);
            check(containerShape == Shape.ROWS || containerShape == Shape.GRID, b,
                    "the container is a rows or a grid shape, not " + containerShape);
        }
        int last = -1;
        for (AccessibleNode member : b.members) {
            Shape shape = Shape.of(member);
            check(shape == Shape.ROWS || shape == Shape.GRID, b,
                    "a member is a rows or a grid shape, not " + shape + ": " + member.name());
            int row = b.rowOf(member);
            check(row > last, b, "the members are published in the order the subject names them; "
                    + "\"" + member.name() + "\" came after row " + last);
            last = row;
            int position = member.selectionItem().positionInSet();
            int size = member.selectionItem().sizeOfSet();
            check(position >= 1 && position <= size, b, "\"" + member.name() + "\" is item "
                    + position + " of " + size);
        }
    }

    private static void theInvariantsHold(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.takeKeyboard();
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void selectReplacesAndAddJoins(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        check(b.perform(1, Action.SELECT), b, "SELECT on row 1 is accepted");
        check(subject.selectedRows().equals(List.of(1)), b, "SELECT selects the row addressed: "
                + subject.selectedRows());
        check(b.selectedRows().equals(List.of(1)), b, "and the tree says so");
        check(b.perform(2, Action.SELECT), b, "SELECT on row 2 is accepted");
        check(subject.selectedRows().equals(List.of(2)), b, "a second SELECT replaces the first, "
                + "as a click does: " + subject.selectedRows());
        if (!subject.enterMultipleSelection()) {
            for (AccessibleNode member : b.members()) {
                check(!offers(member, Action.ADD_TO_SELECTION) && !offers(member, Action.DESELECT),
                        b, "with no multiple selection no row offers ADD_TO_SELECTION or "
                                + "DESELECT: \"" + member.name() + "\"");
            }
            return;
        }
        b.harness.frame();
        for (AccessibleNode member : b.members()) {
            boolean selected = member.has(State.SELECTED);
            check(offers(member, Action.ADD_TO_SELECTION) == !selected, b,
                    "an unselected row of a multiple selection offers ADD_TO_SELECTION and a "
                            + "selected one does not: \"" + member.name() + "\"");
            check(offers(member, Action.DESELECT) == selected, b,
                    "a selected row offers DESELECT and an unselected one does not: \""
                            + member.name() + "\"");
        }
        check(b.perform(0, Action.ADD_TO_SELECTION), b, "ADD_TO_SELECTION on row 0 is accepted");
        check(subject.selectedRows().equals(List.of(0, 2)), b,
                "ADD_TO_SELECTION joins the row to the selection: " + subject.selectedRows());
        check(b.perform(2, Action.DESELECT), b, "DESELECT on row 2 is accepted");
        check(subject.selectedRows().equals(List.of(0)), b,
                "DESELECT takes the row out and leaves the rest: " + subject.selectedRows());
        check(b.perform(3, Action.SELECT), b, "SELECT on row 3 is accepted");
        check(subject.selectedRows().equals(List.of(3)), b,
                "SELECT still means a click in a multiple selection: " + subject.selectedRows());
    }

    private static void focusOnlyWhereItDoesNotSelect(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.select(0);
        b.takeKeyboard();
        for (AccessibleNode member : b.members()) {
            if (member.has(State.FOCUSABLE)) {
                continue; // FOCUS there is the walk's free verb, not the row's
            }
            check(offers(member, Action.FOCUS) == !subject.cursorIsTheSelection(), b,
                    subject.cursorIsTheSelection()
                            ? "where the cursor is the selection a row refuses FOCUS, because "
                                    + "focusing would select: \"" + member.name() + "\""
                            : "where the cursor is separate a row offers FOCUS: \""
                                    + member.name() + "\"");
        }
        if (subject.cursorIsTheSelection()) {
            return;
        }
        check(b.perform(2, Action.FOCUS), b, "FOCUS on row 2 is accepted");
        check(subject.cursorRow() == 2, b, "FOCUS moves the cursor to the row addressed: "
                + subject.cursorRow());
        check(subject.selectedRows().equals(List.of(0)), b, "and selects nothing: "
                + subject.selectedRows());
        check(b.cursorRow() == 2, b, "the tree's cursor is that row");
        check(!b.member(2).has(State.SELECTED), b, "which is not selected");
    }

    private static void theRowVerbSet(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.select(0);
        b.harness.frame();
        for (AccessibleNode member : b.members()) {
            if (member.actions() != null) {
                for (Action verb : member.actions().actions()) {
                    check(ROW_VERBS.contains(verb), b, "a row carries only the row verb set; \""
                            + member.name() + "\" carries " + verb);
                }
            }
            check(offers(member, Action.SELECT), b, "a selectable row offers SELECT: \""
                    + member.name() + "\"");
            if (member.expand() != null) {
                boolean open = member.expand().expanded();
                check(offers(member, Action.EXPAND) == !open, b, "a closed row offers EXPAND and "
                        + "an open one does not: \"" + member.name() + "\"");
                check(offers(member, Action.COLLAPSE) == open, b, "an open row offers COLLAPSE "
                        + "and a closed one does not: \"" + member.name() + "\"");
            } else {
                check(!offers(member, Action.EXPAND) && !offers(member, Action.COLLAPSE), b,
                        "a row that does not open offers neither EXPAND nor COLLAPSE: \""
                                + member.name() + "\"");
            }
            check(!offers(member, Action.ADD_TO_SELECTION) && !offers(member, Action.DESELECT),
                    b, "in a single selection no row offers ADD_TO_SELECTION or DESELECT: \""
                            + member.name() + "\"");
        }
    }

    private static void aClientSelectDoesNotMoveTheCursor(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.select(0);
        b.takeKeyboard();
        check(subject.cursorRow() == 0, b, "after selecting row 0 through the API the cursor is "
                + "on it: " + subject.cursorRow());
        check(b.cursorRow() == 0, b, "and the tree publishes it as the cursor");
        b.harness.clearObservations();

        check(b.perform(3, Action.SELECT), b, "SELECT on row 3 is accepted");
        check(subject.selectedRows().equals(List.of(3)), b, "the row addressed is selected: "
                + subject.selectedRows());
        check(b.selectedRows().equals(List.of(3)), b, "and the tree says so");
        if (subject.cursorIsTheSelection()) {
            check(subject.cursorRow() == 3, b, "where the cursor is the selection it follows: "
                    + subject.cursorRow());
            check(b.cursorRow() == 3, b, "in the tree too");
        } else {
            check(subject.cursorRow() == 0, b, "the cursor stayed where it was, which a click's "
                    + "does not (decision 79): " + subject.cursorRow());
            check(b.cursorRow() == 0, b, "and the tree still publishes row 0 as the cursor");
        }
        List<Change.Aspect> aspects = b.harness.changes.stream().map(Change::aspect).toList();
        long announced = aspects.stream().filter(subject.selectionAspect()::equals).count();
        if (subject.containerless()) {
            check(announced == 2, b, "with no container the change is announced on the two "
                    + "members that moved, the one left and the one selected, and nowhere "
                    + "else: " + aspects);
        } else {
            check(announced == 1, b, "the selection is announced once: " + aspects);
        }
        if (!subject.cursorIsTheSelection()) {
            check(!aspects.contains(Change.Aspect.FOCUS), b,
                    "and no cursor move is announced, because none happened: " + aspects);
        }
        for (Change change : b.harness.changes) {
            if (change.aspect() == subject.selectionAspect()) {
                check(change.origin() == Change.Origin.USER, b,
                        "and from the user, which is who a reader is: " + change.origin());
            }
        }
    }

    private static void aRowPressActivatesItself(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.select(0);
        b.takeKeyboard();
        for (AccessibleNode member : b.members()) {
            check(offers(member, Action.PRESS) == subject.rowsActivate(), b,
                    subject.rowsActivate()
                            ? "a row that can be activated offers PRESS, acting on itself "
                                    + "(decision 80): \"" + member.name() + "\""
                            : "a row with no activation of its own offers no PRESS: \""
                                    + member.name() + "\"");
        }
        if (!subject.rowsActivate()) {
            return;
        }
        check(b.perform(2, Action.PRESS), b, "PRESS on row 2 is accepted");
        check(subject.lastActivated() == 2, b, "PRESS activates the row addressed and not the "
                + "cursor row: " + subject.lastActivated());
    }

    private static void scrollIntoViewRevealsARow(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        if (subject.scrolling() == RowsSubject.Scrolling.NONE) {
            for (AccessibleNode member : b.members()) {
                check(inside(member, b.container), b, "every row of a widget that does not "
                        + "scroll is inside its box: \"" + member.name() + "\"");
            }
            return;
        }
        boolean overflows = b.members.size() < subject.rowNames().size();
        for (AccessibleNode member : b.members()) {
            overflows |= !inside(member, b.scrollBoxOf(member));
        }
        check(overflows, b, "a subject that scrolls is built with more rows than its box holds, "
                + "so that there is something to reveal");
        AccessibleNode last = b.members().get(b.members.size() - 1);
        check(offers(last, Action.SCROLL_INTO_VIEW), b, "a row of a widget that scrolls offers "
                + "SCROLL_INTO_VIEW (decision 81): \"" + last.name() + "\"");
        int row = b.rowOf(last);
        check(b.perform(row, Action.SCROLL_INTO_VIEW), b, "SCROLL_INTO_VIEW is accepted");
        AccessibleNode revealed = b.member(row);
        check(revealed.has(State.SHOWING) && inside(revealed, b.scrollBoxOf(revealed)), b,
                "the row is inside the box after the reveal: \"" + revealed.name() + "\"");
        check(subject.selectedRows().isEmpty(), b, "and the reveal selected nothing: "
                + subject.selectedRows());
    }

    private static void idsAreStable(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        Map<String, Long> before = b.idsByName();
        subject.select(1);
        b.harness.frame();
        b.perform(2, Action.SELECT);
        if (!subject.cursorIsTheSelection()) {
            b.takeKeyboard();
            b.perform(0, Action.FOCUS);
        }
        Map<String, Long> after = b.idsByName();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long now = after.get(e.getKey());
            if (now != null) {
                check(now.equals(e.getValue()), b, "row \"" + e.getKey() + "\" kept its id "
                        + "across a selection and a cursor move: was " + e.getValue() + ", is "
                        + now);
            }
        }
    }

    private static void theCursorIsPublishedWithTheKeyboard(RowsSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.select(0);
        b.harness.frame();
        check(b.cursorRow() == -1, b, "before the widget has the keyboard no row is the cursor");
        b.takeKeyboard();
        check(b.cursorRow() == 0, b, "with the keyboard the selected row is the cursor");
        int cursors = 0;
        long focused = b.harness.tree().focused();
        int under = b.cursorRow();
        for (AccessibleNode member : b.members()) {
            if (member.has(State.ACTIVE) || member.id() == focused || b.rowOf(member) == under) {
                cursors++;
            }
        }
        check(cursors == 1, b, "exactly one row is the cursor, carrying ACTIVE, holding the "
                + "keyboard itself or holding the active cell: " + cursors);
    }

    // ---------------------------------------------------------------------------- the reading

    private static boolean offers(AccessibleNode node, Action verb) {
        return node.actions() != null && node.actions().has(verb);
    }

    private static boolean inside(AccessibleNode member, AccessibleNode container) {
        return member.y() >= container.y() - 0.01f
                && member.y() + member.height() <= container.y() + container.height() + 0.01f
                && member.x() >= container.x() - 0.01f
                && member.x() + member.width() <= container.x() + container.width() + 0.01f;
    }

    private static void check(boolean condition, Bound b, String rule) {
        if (!condition) {
            throw new AssertionError(rule + "\nthe tree published:" + b.harness.describe());
        }
    }

    /** A subject built and bound, with its container and members read off the tree. */
    private static final class Bound {
        final RowsSubject subject;
        final AccessibleHarness harness;
        AccessibleNode container;
        List<AccessibleNode> members;

        private Bound(RowsSubject subject, AccessibleHarness harness) {
            this.subject = subject;
            this.harness = harness;
            read();
        }

        static Bound of(RowsSubject subject, UiRuntime runtime) {
            return new Bound(subject, new AccessibleHarness(runtime, subject.build()));
        }

        /** Reads the container and the members off the tree published last. */
        void read() {
            AccessibleTree tree = harness.tree();
            List<AccessibleNode> found = new ArrayList<>();
            int containerAt = AccessibleNode.NONE;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.selectionItem() == null) {
                    continue;
                }
                int at = node.selectionContainer();
                if (at == AccessibleNode.NONE) {
                    if (!subject.containerless()) {
                        throw new AssertionError("the member \"" + node.name() + "\" has no "
                                + "selection container, and the subject does not declare its "
                                + "members containerless:" + harness.describe());
                    }
                    at = node.parent(); // the box: the members' common parent (decision 107)
                } else if (subject.containerless()) {
                    throw new AssertionError("the member \"" + node.name() + "\" resolves to a "
                            + "selection container, and the subject declares its members "
                            + "containerless:" + harness.describe());
                }
                if (containerAt == AccessibleNode.NONE) {
                    containerAt = at;
                } else if (containerAt != at) {
                    throw new AssertionError("the members belong to two selections; the subject "
                            + "must publish one:" + harness.describe());
                }
                found.add(node);
            }
            if (found.isEmpty()) {
                throw new AssertionError("no node carries a selection membership:"
                        + harness.describe());
            }
            container = tree.node(containerAt);
            members = found;
            for (AccessibleNode member : members) {
                if (!subject.rowNames().contains(member.name())) {
                    List<String> published = new ArrayList<>();
                    for (AccessibleNode m : members) {
                        published.add(m.name());
                    }
                    throw new AssertionError("the member \"" + member.name() + "\" is not among "
                            + "the subject's row names " + subject.rowNames() + "; the members "
                            + "published are " + published + ":" + harness.describe());
                }
            }
        }

        /**
         * Gives the widget the keyboard the way a reader would: the container itself, or, where
         * the rows hold the keyboard themselves (a tab strip's roving focus), the selected row
         * through the walk's own {@code FOCUS} on it.
         */
        void takeKeyboard() {
            harness.focus(subject.widget());
            if (cursorRow() != -1) {
                return;
            }
            AccessibleNode target = null;
            for (AccessibleNode member : members()) {
                if (!member.has(State.FOCUSABLE)) {
                    continue;
                }
                if (target == null || member.has(State.SELECTED)) {
                    target = member;
                }
            }
            if (target != null && offers(target, Action.FOCUS)) {
                harness.perform(target.id(), Action.FOCUS, Accessible.Argument.NONE);
            }
        }

        /** The members, re-read from the tree published last. */
        List<AccessibleNode> members() {
            read();
            return members;
        }

        /** The container, re-read from the tree published last. */
        AccessibleNode container() {
            read();
            return container;
        }

        /** The published member that is row {@code row}. */
        AccessibleNode member(int row) {
            for (AccessibleNode member : members()) {
                if (rowOf(member) == row) {
                    return member;
                }
            }
            throw new AssertionError("row " + row + " (\"" + subject.rowNames().get(row)
                    + "\") is not published:" + harness.describe());
        }

        int rowOf(AccessibleNode member) {
            return subject.rowNames().indexOf(member.name());
        }

        boolean perform(int row, Action verb) {
            return harness.perform(member(row).id(), verb, Accessible.Argument.NONE);
        }

        /** The rows the tree says are selected, ascending. */
        List<Integer> selectedRows() {
            List<Integer> rows = new ArrayList<>();
            for (AccessibleNode member : members()) {
                if (member.has(State.SELECTED)) {
                    rows.add(rowOf(member));
                }
            }
            return rows;
        }

        /**
         * The row the tree publishes as the cursor, or -1: the member that holds the keyboard
         * itself (a tab, under roving focus), else the member the active node is or sits under
         * (a table's cursor is one of its row's cells).
         */
        int cursorRow() {
            AccessibleTree tree = harness.tree();
            List<AccessibleNode> members = members();
            long focused = tree.focused();
            for (AccessibleNode member : members) {
                if (member.id() == focused) {
                    return rowOf(member);
                }
            }
            long active = tree.activeDescendant();
            if (active == 0) {
                return -1;
            }
            AccessibleNode node = tree.find(active);
            while (node != null && node.selectionItem() == null) {
                node = node.parent() == AccessibleNode.NONE ? null : tree.node(node.parent());
            }
            if (node == null) {
                return -1;
            }
            for (AccessibleNode member : members) {
                if (member.id() == node.id()) {
                    return rowOf(member);
                }
            }
            return -1;
        }

        /** The box a row is revealed into: the nearest scrolling node at or above it. */
        AccessibleNode scrollBoxOf(AccessibleNode member) {
            AccessibleTree tree = harness.tree();
            AccessibleNode node = member;
            while (node != null) {
                if (node.scroll() != null) {
                    return node;
                }
                node = node.parent() == AccessibleNode.NONE ? null : tree.node(node.parent());
            }
            return container();
        }

        Map<String, Long> idsByName() {
            Map<String, Long> ids = new HashMap<>();
            for (AccessibleNode member : members()) {
                ids.put(member.name(), member.id());
            }
            return ids;
        }
    }
}
