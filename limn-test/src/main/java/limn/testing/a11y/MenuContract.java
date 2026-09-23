package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.Accessible.State;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.concurrent.UiRuntime;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;
import limn.testing.StubWindow;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What every widget of the {@code MENU} shape owes a reader, as named cases over a
 * {@link MenuSubject}: a menu bar and a popup menu under the same rules, which until this were
 * tested in each widget's own file.
 * <ul>
 *   <li>the rows are the members of one selection, named in order, and classify as
 *       {@code MENU};</li>
 *   <li>the four invariants hold, unfocused and focused;</li>
 *   <li>{@code FOCUS} moves the highlight onto a row and chooses nothing and opens nothing; a
 *       disabled row offers it not;</li>
 *   <li>a row with something to open offers {@code SHOW_MENU} and {@code EXPAND} while closed and
 *       {@code COLLAPSE} alone while open, each verb doing what it says and the other refused by
 *       state; a row with nothing to open offers none of the three;</li>
 *   <li>{@code PRESS} chooses a command row and {@code TOGGLE} flips a check row, which takes
 *       {@code PRESS} too; a title, a submenu and an empty one take neither;</li>
 *   <li>a row's verbs are the menu verb set and nothing else, and a disabled row carries
 *       none;</li>
 *   <li>a row keeps its id while the highlight moves.</li>
 * </ul>
 * A refusal is read as an effect: the scene's {@code perform} answers true whenever the node
 * exists, so what the contract checks after a refused verb is that nothing changed.
 */
public final class MenuContract {

    /** The verbs a menu row may carry. */
    public static final Set<Action> ROW_VERBS = EnumSet.of(Action.FOCUS, Action.SHOW_MENU,
            Action.EXPAND, Action.COLLAPSE, Action.PRESS, Action.TOGGLE);

    private MenuContract() {
    }

    /**
     * The cases, in the order above.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(MenuSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the rows are the members of one selection, named in order, "
                + "of the MENU shape", () -> theRowsAreMembersOfOneMenu(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("decision 11: FOCUS moves the highlight and chooses nothing",
                () -> focusMovesTheHighlight(subject, runtime)));
        cases.add(new ContractCase("decision 2: SHOW_MENU and EXPAND open a closed row, "
                + "COLLAPSE closes the open one, each by state",
                () -> openAndCloseByState(subject, runtime)));
        cases.add(new ContractCase("PRESS chooses a command row and TOGGLE flips a check row; "
                + "a title and a submenu take neither", () -> pressAndToggle(subject, runtime)));
        cases.add(new ContractCase("a row's verbs are the menu verb set, and a disabled row "
                + "carries none", () -> theRowVerbSet(subject, runtime)));
        cases.add(new ContractCase("a row keeps its id while the highlight moves",
                () -> idsAreStable(subject, runtime)));
        return cases;
    }

    // ------------------------------------------------------------------------------ the cases

    private static void theRowsAreMembersOfOneMenu(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        check(b.members.size() == subject.rowNames().size(), b, "every row the subject names is "
                + "published as a member: " + b.members.size() + " of " + subject.rowNames());
        check(Shape.of(b.container) == Shape.MENU, b, "the container is of the MENU shape, not "
                + Shape.of(b.container));
        check(b.container.selection() != null, b, "and carries the selection the rows are members of");
        int last = -1;
        for (AccessibleNode member : b.members) {
            check(Shape.of(member) == Shape.MENU, b, "a row is of the MENU shape, not "
                    + Shape.of(member) + ": " + member.name());
            int row = b.rowOf(member);
            check(row > last, b, "the rows are published in the order the subject names them; \""
                    + member.name() + "\" came after row " + last);
            last = row;
            int position = member.selectionItem().positionInSet();
            int size = member.selectionItem().sizeOfSet();
            check(position >= 1 && position <= size, b, "\"" + member.name() + "\" is item "
                    + position + " of " + size);
        }
    }

    private static void theInvariantsHold(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void focusMovesTheHighlight(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        for (AccessibleNode member : b.members()) {
            boolean disabled = subject.kindOf(b.rowOf(member)) == MenuSubject.Row.DISABLED;
            check(offers(member, Action.FOCUS) == !disabled, b, disabled
                    ? "a disabled row offers no FOCUS: \"" + member.name() + "\""
                    : "a row the arrows can land on offers FOCUS (decision 11): \""
                            + member.name() + "\"");
        }
        int target = b.firstOfKind(MenuSubject.Row.COMMAND, MenuSubject.Row.TITLE,
                MenuSubject.Row.EMPTY, MenuSubject.Row.CHECK);
        int nodes = b.harness.tree().nodeCount();
        check(b.perform(target, Action.FOCUS), b, "FOCUS on row " + target + " is accepted");
        check(b.highlight() == target, b, "FOCUS moves the highlight onto the row addressed: "
                + b.highlight());
        check(subject.chosen().isEmpty() && subject.toggled().isEmpty(), b,
                "and chooses nothing: " + subject.chosen() + " " + subject.toggled());
        check(b.harness.tree().nodeCount() == nodes, b, "and opens nothing: " + nodes
                + " nodes before, " + b.harness.tree().nodeCount() + " after");
    }

    private static void openAndCloseByState(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        for (AccessibleNode member : b.members()) {
            MenuSubject.Row kind = subject.kindOf(b.rowOf(member));
            boolean opens = kind == MenuSubject.Row.TITLE || kind == MenuSubject.Row.SUBMENU;
            check(offers(member, Action.SHOW_MENU) == opens && offers(member, Action.EXPAND) == opens,
                    b, opens ? "a closed row with something to open offers SHOW_MENU and EXPAND: \""
                            + member.name() + "\""
                            : "a row with nothing to open offers neither SHOW_MENU nor EXPAND: \""
                            + member.name() + "\"");
            check(!offers(member, Action.COLLAPSE), b, "a closed row offers no COLLAPSE: \""
                    + member.name() + "\"");
            check(member.has(State.HAS_POPUP) == opens, b, "a row says it has a popup exactly "
                    + "when it has something to open: \"" + member.name() + "\"");
        }
        int row = b.firstOfKind(MenuSubject.Row.SUBMENU, MenuSubject.Row.TITLE);
        if (row < 0) {
            return;
        }
        int nodes = b.harness.tree().nodeCount();
        check(b.perform(row, Action.COLLAPSE), b, "COLLAPSE on a closed row is accepted");
        check(!b.isOpen(row) && b.harness.tree().nodeCount() == nodes, b,
                "and changes nothing, because the row is closed");
        check(b.perform(row, Action.EXPAND), b, "EXPAND on row " + row + " is accepted");
        check(b.isOpen(row), b, "the row is open");
        check(b.harness.tree().nodeCount() > nodes, b, "and something appeared: " + nodes
                + " nodes before, " + b.harness.tree().nodeCount() + " after");
        AccessibleNode open = b.member(row);
        if (!open.has(State.ENABLED)) {
            // A bar's dropdown opened in the scene is a modal layer above the bar, and the walk
            // withdraws every verb from what a modal shadows (ADR 039 §1.9): the open title
            // carries none, COLLAPSE included, until the dropdown closes. That is the harness's
            // window, which cannot host a window of its own; the case ends here for such a
            // subject, with the title's state checked and nothing driven through it.
            check(open.actions() == null || open.actions().actions().isEmpty(), b,
                    "a row shadowed by the popup it opened carries no verb at all");
            check(subject.chosen().isEmpty(), b, "opening chose nothing");
            return;
        }
        check(offers(open, Action.COLLAPSE) && !offers(open, Action.EXPAND)
                && !offers(open, Action.SHOW_MENU), b, "the open row offers COLLAPSE alone");
        int opened = b.harness.tree().nodeCount();
        check(b.perform(row, Action.SHOW_MENU), b, "SHOW_MENU on the open row is accepted");
        check(b.isOpen(row) && b.harness.tree().nodeCount() == opened, b,
                "and changes nothing, because the row is open");
        check(b.perform(row, Action.COLLAPSE), b, "COLLAPSE on the open row is accepted");
        check(!b.isOpen(row), b, "the row is closed again");
        check(b.harness.tree().nodeCount() == nodes, b, "and what appeared is gone: "
                + b.harness.tree().nodeCount());
        check(subject.chosen().isEmpty(), b, "opening and closing chose nothing");
    }

    private static void pressAndToggle(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        for (AccessibleNode member : b.members()) {
            MenuSubject.Row kind = subject.kindOf(b.rowOf(member));
            boolean press = kind == MenuSubject.Row.COMMAND || kind == MenuSubject.Row.CHECK;
            check(offers(member, Action.PRESS) == press, b, press
                    ? "a command or a check row offers PRESS: \"" + member.name() + "\""
                    : "a title, a submenu, an empty and a disabled row offer no PRESS: \""
                            + member.name() + "\"");
            check(offers(member, Action.TOGGLE) == (kind == MenuSubject.Row.CHECK), b,
                    "TOGGLE is a check row's alone: \"" + member.name() + "\"");
            if (kind == MenuSubject.Row.CHECK) {
                check(member.toggle() != null, b, "a check row carries its toggle: \""
                        + member.name() + "\"");
            }
        }
        int check = b.firstOfKind(MenuSubject.Row.CHECK);
        if (check >= 0) {
            check(b.perform(check, Action.TOGGLE), b, "TOGGLE on the check row is accepted");
            check(subject.toggled().size() == 1, b, "and flips it once: " + subject.toggled());
        }
        int command = b.firstOfKind(MenuSubject.Row.COMMAND);
        if (command >= 0) {
            Bound again = Bound.of(subject, rt);
            again.harness.focus(subject.widget());
            check(again.perform(command, Action.PRESS), again, "PRESS on the command row is accepted");
            check(subject.chosen().equals(List.of(subject.rowNames().get(command))), again,
                    "and chooses that row and no other: " + subject.chosen());
        }
    }

    private static void theRowVerbSet(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        for (AccessibleNode member : b.members()) {
            if (member.actions() != null) {
                for (Action verb : member.actions().actions()) {
                    check(ROW_VERBS.contains(verb), b, "a row carries only the menu verb set; \""
                            + member.name() + "\" carries " + verb);
                }
            }
            if (subject.kindOf(b.rowOf(member)) == MenuSubject.Row.DISABLED) {
                check(!member.has(State.ENABLED), b, "a disabled row is not enabled: \""
                        + member.name() + "\"");
                check(member.actions() == null || member.actions().actions().isEmpty(), b,
                        "and carries no verb: \"" + member.name() + "\"");
            }
        }
    }

    private static void idsAreStable(MenuSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        Map<String, Long> before = b.idsByName();
        int first = b.firstOfKind(MenuSubject.Row.COMMAND, MenuSubject.Row.TITLE,
                MenuSubject.Row.CHECK, MenuSubject.Row.EMPTY);
        b.perform(first, Action.FOCUS);
        int second = b.lastOfKind(MenuSubject.Row.COMMAND, MenuSubject.Row.TITLE,
                MenuSubject.Row.CHECK, MenuSubject.Row.EMPTY);
        b.perform(second, Action.FOCUS);
        Map<String, Long> after = b.idsByName();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long now = after.get(e.getKey());
            check(now != null && now.equals(e.getValue()), b, "row \"" + e.getKey()
                    + "\" kept its id across two highlight moves: was " + e.getValue() + ", is "
                    + now);
        }
    }

    // ---------------------------------------------------------------------------- the reading

    private static boolean offers(AccessibleNode node, Action verb) {
        return node.actions() != null && node.actions().has(verb);
    }

    private static void check(boolean condition, Bound b, String rule) {
        if (!condition) {
            throw new AssertionError(rule + "\nthe tree published:" + b.harness.describe());
        }
    }

    /** A subject built, bound and opened, with its container and rows read off the tree. */
    private static final class Bound {
        final MenuSubject subject;
        final AccessibleHarness harness;
        AccessibleNode container;
        List<AccessibleNode> members;

        private Bound(MenuSubject subject, AccessibleHarness harness) {
            this.subject = subject;
            this.harness = harness;
            subject.open();
            harness.frame();
            read();
        }

        /** Bound in a window that cannot position, so a dropdown opens in the scene and is seen. */
        static Bound of(MenuSubject subject, UiRuntime runtime) {
            return new Bound(subject, new AccessibleHarness(runtime, subject.build(),
                    new StubWindow(false)));
        }

        /** Reads the rows the subject names, and their one container, off the tree published last. */
        void read() {
            AccessibleTree tree = harness.tree();
            List<AccessibleNode> found = new ArrayList<>();
            int containerAt = AccessibleNode.NONE;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.selectionItem() == null || !subject.rowNames().contains(node.name())) {
                    continue;
                }
                int at = node.selectionContainer();
                if (containerAt == AccessibleNode.NONE) {
                    containerAt = at;
                } else if (containerAt != at) {
                    continue; // a row of a dropdown or a submenu that shares a name: not ours
                }
                found.add(node);
            }
            if (found.isEmpty() || containerAt == AccessibleNode.NONE) {
                throw new AssertionError("no member named as one of the rows " + subject.rowNames()
                        + " resolves to a selection container:" + harness.describe());
            }
            container = tree.node(containerAt);
            members = found;
        }

        List<AccessibleNode> members() {
            read();
            return members;
        }

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

        /** The highlighted row as the tree says it: the selected member, or -1. */
        int highlight() {
            for (AccessibleNode member : members()) {
                if (member.has(State.SELECTED)) {
                    return rowOf(member);
                }
            }
            return -1;
        }

        boolean isOpen(int row) {
            AccessibleNode member = member(row);
            return member.expand() != null && member.expand().expanded();
        }

        int firstOfKind(MenuSubject.Row... kinds) {
            for (MenuSubject.Row kind : kinds) {
                for (int row = 0; row < subject.rowNames().size(); row++) {
                    if (subject.kindOf(row) == kind) {
                        return row;
                    }
                }
            }
            return -1;
        }

        int lastOfKind(MenuSubject.Row... kinds) {
            for (MenuSubject.Row kind : kinds) {
                for (int row = subject.rowNames().size() - 1; row >= 0; row--) {
                    if (subject.kindOf(row) == kind) {
                        return row;
                    }
                }
            }
            return -1;
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
