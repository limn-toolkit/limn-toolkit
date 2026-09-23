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
import java.util.List;

/**
 * What every widget of the {@code POPUP_OWNER} shape owes a reader, as named cases over a
 * {@link PopupOwnerSubject}: it says {@code HAS_POPUP} before anything has happened; its open
 * state is the facet's and the API's; closed, it offers {@code EXPAND} and not {@code COLLAPSE};
 * {@code EXPAND} opens it and something appears in the tree, described where it lives; open, it
 * never offers {@code EXPAND}, and offers {@code COLLAPSE} unless a layer above owns the input,
 * in which case it offers nothing; a second {@code EXPAND} changes nothing; closing through the
 * API brings {@code EXPAND} back; and the node keeps its id across the round trip. A refusal is
 * read as an effect (see {@link ValueContract}).
 */
public final class PopupOwnerContract {

    private PopupOwnerContract() {
    }

    /**
     * The cases, in the order the class description gives them.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(PopupOwnerSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("closed, the owner says it has a popup and offers EXPAND alone",
                () -> closedItOffersExpand(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("EXPAND opens it, something appears, and EXPAND is withdrawn",
                () -> expandOpensIt(subject, runtime)));
        cases.add(new ContractCase("a second EXPAND changes nothing and closing through the API "
                + "brings EXPAND back", () -> aSecondExpandChangesNothing(subject, runtime)));
        cases.add(new ContractCase("COLLAPSE on a closed owner changes nothing",
                () -> collapseOnAClosedOwnerChangesNothing(subject, runtime)));
        cases.add(new ContractCase("the node keeps its id across the round trip",
                () -> theIdSurvivesTheRoundTrip(subject, runtime)));
        return cases;
    }

    private static void closedItOffersExpand(PopupOwnerSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        AccessibleNode node = b.node();
        check(Shape.of(node) == Shape.POPUP_OWNER, b, "the node is a POPUP_OWNER shape, not "
                + Shape.of(node));
        check(node.has(State.HAS_POPUP), b, "it says HAS_POPUP before anything has happened");
        check(!subject.isOpen(), b, "the subject is built closed");
        check(node.expand() != null && !node.expand().expanded(), b,
                "and the facet says closed");
        check(offers(node, Action.EXPAND) && !offers(node, Action.COLLAPSE), b,
                "closed, it offers EXPAND and not COLLAPSE");
    }

    private static void theInvariantsHold(PopupOwnerSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void expandOpensIt(PopupOwnerSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int nodes = b.harness.tree().nodeCount();
        check(b.perform(Action.EXPAND), b, "EXPAND is accepted");
        check(subject.isOpen(), b, "and the API says open");
        AccessibleNode node = b.node();
        check(node.expand() != null && node.expand().expanded(), b, "and the facet says open");
        check(b.harness.tree().nodeCount() > nodes, b, "and something appeared in the tree, "
                + "described where it lives: " + nodes + " nodes before, "
                + b.harness.tree().nodeCount() + " after");
        check(!offers(node, Action.EXPAND), b, "open, it never offers EXPAND");
    }

    private static void aSecondExpandChangesNothing(PopupOwnerSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.perform(Action.EXPAND);
        int nodes = b.harness.tree().nodeCount();
        b.perform(Action.EXPAND);
        check(subject.isOpen() && b.harness.tree().nodeCount() == nodes, b,
                "a second EXPAND changed nothing: " + nodes + " nodes, then "
                        + b.harness.tree().nodeCount());
        subject.close();
        b.harness.frame();
        check(!subject.isOpen(), b, "closing through the API closed it");
        AccessibleNode node = b.node();
        check(offers(node, Action.EXPAND) && !offers(node, Action.COLLAPSE), b,
                "and EXPAND is back, COLLAPSE gone");
    }

    private static void collapseOnAClosedOwnerChangesNothing(PopupOwnerSubject subject,
                                                             UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int nodes = b.harness.tree().nodeCount();
        b.perform(Action.COLLAPSE);
        check(!subject.isOpen() && b.harness.tree().nodeCount() == nodes, b,
                "COLLAPSE on a closed owner changed nothing");
    }

    private static void theIdSurvivesTheRoundTrip(PopupOwnerSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        long id = b.node().id();
        b.perform(Action.EXPAND);
        check(b.node().id() == id, b, "the node kept its id while open");
        subject.close();
        b.harness.frame();
        check(b.node().id() == id, b, "and after closing");
    }

    private static boolean offers(AccessibleNode node, Action verb) {
        return node.actions() != null && node.actions().has(verb);
    }

    private static void check(boolean condition, Bound b, String rule) {
        if (!condition) {
            throw new AssertionError(rule + "\nthe tree published:" + b.harness.describe());
        }
    }

    private static final class Bound {
        final AccessibleHarness harness;
        final long id;

        private Bound(AccessibleHarness harness) {
            this.harness = harness;
            AccessibleTree tree = harness.tree();
            long found = 0;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.has(State.HAS_POPUP)) {
                    if (found != 0) {
                        throw new AssertionError("two popup owners; the subject must publish "
                                + "one:" + harness.describe());
                    }
                    found = node.id();
                }
            }
            if (found == 0) {
                throw new AssertionError("no node says HAS_POPUP:" + harness.describe());
            }
            id = found;
        }

        /** Bound in a window that cannot position, so the popup opens in the scene and is seen. */
        static Bound of(PopupOwnerSubject subject, UiRuntime runtime) {
            return new Bound(new AccessibleHarness(runtime, subject.build(), new StubWindow(false)));
        }

        AccessibleNode node() {
            return harness.node(id);
        }

        boolean perform(Action verb) {
            return harness.perform(id, verb, Accessible.Argument.NONE);
        }
    }
}
