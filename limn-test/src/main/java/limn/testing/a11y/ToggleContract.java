package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.accessibility.ToggleFacet;
import limn.concurrent.UiRuntime;
import limn.scene.Change;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;

import java.util.ArrayList;
import java.util.List;

/**
 * What every widget of the {@code TOGGLE} shape owes a reader, as named cases over a
 * {@link ToggleSubject}: the facet is the API's state and the verb is offered
 * exactly where the flip is; {@code TOGGLE} flips it, heard once by the widget's own listener
 * and announced, where announced, from the user, and flips it back; a disabled toggle withdraws
 * the verb and moves for nothing; and the node keeps its id as it flips. A refusal is read as
 * an effect (see {@link ValueContract}).
 */
public final class ToggleContract {

    private ToggleContract() {
    }

    /**
     * The cases, in the order the class description gives them.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(ToggleSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the facet is the API's state and the verb is offered where "
                + "the flip is", () -> theFacetIsTheApis(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("TOGGLE flips it, from the user, and flips it back",
                () -> toggleFlips(subject, runtime)));
        cases.add(new ContractCase("a disabled toggle withdraws the verb and moves for nothing",
                () -> aDisabledToggleMovesForNothing(subject, runtime)));
        cases.add(new ContractCase("the node keeps its id as it flips",
                () -> theIdSurvivesAFlip(subject, runtime)));
        return cases;
    }

    private static void theFacetIsTheApis(ToggleSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        AccessibleNode node = b.node();
        check(Shape.of(node) == Shape.TOGGLE, b, "the node is a TOGGLE shape, not "
                + Shape.of(node));
        check((node.toggle().state() == ToggleFacet.State.ON) == subject.isOn(), b,
                "the facet is the API's state: facet " + node.toggle().state() + ", API "
                        + subject.isOn());
        check(offers(node, Action.TOGGLE) == subject.operable(), b, subject.operable()
                ? "a toggle that can be flipped offers TOGGLE"
                : "a toggle that cannot be flipped offers no verb");
    }

    private static void theInvariantsHold(ToggleSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void toggleFlips(ToggleSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        boolean was = subject.isOn();
        int changes = subject.changes();
        b.harness.clearObservations();
        b.perform(Action.TOGGLE);
        if (!subject.operable()) {
            check(subject.isOn() == was && subject.changes() == changes, b,
                    "a toggle that cannot be flipped moves for nothing and tells nobody");
            return;
        }
        check(subject.isOn() != was, b, "TOGGLE flipped the API's state: " + subject.isOn());
        check((b.node().toggle().state() == ToggleFacet.State.ON) == subject.isOn(), b,
                "and the facet followed");
        check(subject.changes() == changes + 1, b, "the widget's own listener heard it once: "
                + (subject.changes() - changes));
        for (Change change : b.harness.changes) {
            check(change.origin() == Change.Origin.USER, b, "what the scene announced came from "
                    + "the user: " + b.harness.changes);
        }
        b.perform(Action.TOGGLE);
        check(subject.isOn() == was, b, "a second TOGGLE flipped it back: " + subject.isOn());
    }

    private static void aDisabledToggleMovesForNothing(ToggleSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        boolean was = subject.isOn();
        subject.widget().setEnabled(false);
        b.harness.frame();
        check(!offers(b.node(), Action.TOGGLE), b, "a disabled toggle offers no verb");
        int changes = subject.changes();
        b.perform(Action.TOGGLE);
        check(subject.isOn() == was && subject.changes() == changes, b,
                "and TOGGLE moves nothing and tells nobody");
    }

    private static void theIdSurvivesAFlip(ToggleSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        long id = b.node().id();
        b.perform(Action.TOGGLE);
        check(b.node().id() == id, b, "the node kept its id across a flip");
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

        private Bound(AccessibleHarness harness) {
            this.harness = harness;
        }

        static Bound of(ToggleSubject subject, UiRuntime runtime) {
            return new Bound(new AccessibleHarness(runtime, subject.build()));
        }

        /** The one node carrying a toggle facet in the tree published last. */
        AccessibleNode node() {
            AccessibleTree tree = harness.tree();
            AccessibleNode found = null;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.toggle() != null) {
                    if (found != null) {
                        throw new AssertionError("two nodes carry a toggle facet; the subject "
                                + "must publish one:" + harness.describe());
                    }
                    found = node;
                }
            }
            if (found == null) {
                throw new AssertionError("no node carries a toggle facet:" + harness.describe());
            }
            return found;
        }

        void perform(Action verb) {
            harness.perform(node().id(), verb, Accessible.Argument.NONE);
        }
    }
}
