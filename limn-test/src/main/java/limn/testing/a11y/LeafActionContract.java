package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.concurrent.UiRuntime;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;

import java.util.ArrayList;
import java.util.List;

/**
 * What every widget of the {@code LEAF_ACTION} shape owes a reader, as named cases over a
 * {@link LeafActionSubject}: it is a leaf that offers {@code PRESS}; the verb
 * reaches the widget's own handler exactly once; a disabled leaf withdraws the verb and a press
 * reaches nothing; and the node keeps its id across a press. A refusal is read as an effect
 * (see {@link ValueContract}).
 */
public final class LeafActionContract {

    private LeafActionContract() {
    }

    /**
     * The cases, in the order the class description gives them.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(LeafActionSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the node is a pressable leaf",
                () -> theNodeIsAPressableLeaf(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("PRESS reaches the handler once",
                () -> pressReachesTheHandlerOnce(subject, runtime)));
        cases.add(new ContractCase("a disabled leaf withdraws PRESS and a press reaches nothing",
                () -> aDisabledLeafReachesNothing(subject, runtime)));
        cases.add(new ContractCase("the node keeps its id across a press",
                () -> theIdSurvivesAPress(subject, runtime)));
        return cases;
    }

    private static void theNodeIsAPressableLeaf(LeafActionSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        AccessibleNode node = b.node();
        check(Shape.of(node) == Shape.LEAF_ACTION, b, "the node is a LEAF_ACTION shape, not "
                + Shape.of(node));
        check(offers(node, Action.PRESS), b, "and offers PRESS");
        check(node.firstChild() == AccessibleNode.NONE, b, "and is a leaf");
        check(!node.name().isBlank(), b, "and has a name");
    }

    private static void theInvariantsHold(LeafActionSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void pressReachesTheHandlerOnce(LeafActionSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int presses = subject.presses();
        check(b.perform(Action.PRESS), b, "PRESS is accepted");
        check(subject.presses() == presses + 1, b, "the handler heard it once: "
                + (subject.presses() - presses));
    }

    private static void aDisabledLeafReachesNothing(LeafActionSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        subject.widget().setEnabled(false);
        b.harness.frame();
        check(!offers(b.node(), Action.PRESS), b, "a disabled leaf offers no PRESS");
        int presses = subject.presses();
        b.perform(Action.PRESS);
        check(subject.presses() == presses, b, "and a press reaches nothing");
    }

    private static void theIdSurvivesAPress(LeafActionSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        long id = b.node().id();
        b.perform(Action.PRESS);
        check(b.node().id() == id, b, "the node kept its id across a press");
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
                if (Shape.of(node) == Shape.LEAF_ACTION) {
                    if (found != 0) {
                        throw new AssertionError("two leaves; the subject must publish one:"
                                + harness.describe());
                    }
                    found = node.id();
                }
            }
            if (found == 0) {
                throw new AssertionError("no pressable leaf:" + harness.describe());
            }
            id = found;
        }

        static Bound of(LeafActionSubject subject, UiRuntime runtime) {
            return new Bound(new AccessibleHarness(runtime, subject.build()));
        }

        AccessibleNode node() {
            return harness.node(id);
        }

        boolean perform(Action verb) {
            return harness.perform(id, verb, Accessible.Argument.NONE);
        }
    }
}
