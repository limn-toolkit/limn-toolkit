package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.accessibility.ValueFacet;
import limn.concurrent.UiRuntime;
import limn.scene.Change;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;

import java.util.ArrayList;
import java.util.List;

/**
 * What every widget of the {@code VALUE} shape owes a reader, as named cases over a
 * {@link ValueSubject}: the number, its bounds and its step are the API's;
 * {@code INCREMENT} and {@code DECREMENT} move by the step the facet names, clamped at the
 * bounds, each heard once by the widget's own listener and announced, where announced, from the
 * user; {@code SET_VALUE} takes a finite number and moves nothing for anything else; a read-only
 * value offers no verb and moves for none; a disabled one withdraws its verbs and moves for
 * none; and the node keeps its id as the number moves.
 *
 * <p>A refusal is read as an effect, never as the host's answer: {@code Host#perform} answers
 * from the snapshot whether the node exists and posts the verb, so the hook's own refusal is
 * invisible to the caller. What a refused verb owes is that nothing moved and
 * nobody was told.
 */
public final class ValueContract {

    private static final double EPS = 1e-3;

    private ValueContract() {
    }

    /**
     * The cases, in the order above.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(ValueSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the node carries the number, its bounds and its step the "
                + "API holds", () -> theFacetIsTheApis(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("INCREMENT and DECREMENT move by the step, from the user, "
                + "and clamp at the bounds", () -> theStepsMoveAndClamp(subject, runtime)));
        cases.add(new ContractCase("SET_VALUE takes a finite number and refuses anything else",
                () -> setValueTakesAFiniteNumber(subject, runtime)));
        cases.add(new ContractCase("a disabled value withdraws its verbs and refuses them",
                () -> aDisabledValueRefuses(subject, runtime)));
        cases.add(new ContractCase("the node keeps its id as the number moves",
                () -> theIdSurvivesAMove(subject, runtime)));
        return cases;
    }

    private static void theFacetIsTheApis(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        AccessibleNode node = b.node();
        check(Shape.of(node) == Shape.VALUE, b, "the node is a VALUE shape, not " + Shape.of(node));
        ValueFacet facet = node.value();
        check(near(facet.value(), subject.value()), b, "the number is the API's: facet "
                + facet.value() + ", API " + subject.value());
        if (!Double.isNaN(subject.min())) {
            check(near(facet.min(), subject.min()), b, "the least value is the API's: facet "
                    + facet.min() + ", API " + subject.min());
        }
        if (!Double.isNaN(subject.max())) {
            check(near(facet.max(), subject.max()), b, "the greatest value is the API's: facet "
                    + facet.max() + ", API " + subject.max());
        }
        if (!Double.isNaN(subject.step())) {
            check(near(facet.step(), subject.step()), b, "the step is the API's: facet "
                    + facet.step() + ", API " + subject.step());
        }
        check(facet.readOnly() == subject.readOnly(), b, "the facet says read-only exactly where "
                + "the API does: facet " + facet.readOnly());
        boolean offersSteps = offers(node, Action.INCREMENT) && offers(node, Action.DECREMENT);
        check(offersSteps == !subject.readOnly(), b, subject.readOnly()
                ? "a read-only value offers no step verb"
                : "a settable value offers INCREMENT and DECREMENT");
        check(!offers(node, Action.SET_VALUE), b, "SET_VALUE is implied by the facet and never "
                + "listed as a verb");
    }

    private static void theInvariantsHold(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void theStepsMoveAndClamp(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        if (subject.readOnly()) {
            double held = subject.value();
            b.perform(Action.INCREMENT, Accessible.Argument.NONE);
            b.perform(Action.DECREMENT, Accessible.Argument.NONE);
            check(near(subject.value(), held), b, "a read-only value moves for no step verb: "
                    + subject.value());
            return;
        }
        double step = b.node().value().step();
        double before = subject.value();
        int changes = subject.changes();
        b.harness.clearObservations();
        check(b.perform(Action.INCREMENT, Accessible.Argument.NONE), b, "INCREMENT is accepted");
        double expected = Math.min(before + step, b.node().value().max());
        check(near(subject.value(), expected), b, "INCREMENT moved the API's number by the step: "
                + "was " + before + ", is " + subject.value() + ", step " + step);
        check(near(b.node().value().value(), subject.value()), b, "and the facet followed");
        check(subject.changes() == changes + 1, b, "the widget's own listener heard it once: "
                + (subject.changes() - changes));
        check(b.announcedOnlyFromTheUser(), b, "and the value change the scene announced, if any, "
                + "came from the user: " + b.harness.changes);

        double after = subject.value();
        check(b.perform(Action.DECREMENT, Accessible.Argument.NONE), b, "DECREMENT is accepted");
        check(near(subject.value(), after - step), b, "DECREMENT moved it back by the step: "
                + subject.value());

        double max = b.node().value().max();
        subject.set(max);
        b.harness.frame();
        b.perform(Action.INCREMENT, Accessible.Argument.NONE);
        check(near(subject.value(), max), b, "INCREMENT at the greatest value stays there: "
                + subject.value());
        double min = b.node().value().min();
        subject.set(min);
        b.harness.frame();
        b.perform(Action.DECREMENT, Accessible.Argument.NONE);
        check(near(subject.value(), min), b, "DECREMENT at the least value stays there: "
                + subject.value());
    }

    private static void setValueTakesAFiniteNumber(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        double before = subject.value();
        if (subject.readOnly()) {
            double other = before == 0 ? 1 : before / 2;
            b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(other));
            check(near(subject.value(), before), b, "a read-only value moves for no SET_VALUE: "
                    + subject.value());
            return;
        }
        ValueFacet facet = b.node().value();
        double target = facet.min() + (facet.max() - facet.min()) / 4;
        if (facet.step() > 0) {
            target = facet.min() + Math.round((target - facet.min()) / facet.step()) * facet.step();
        }
        check(b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(target)), b,
                "SET_VALUE with a finite number is accepted");
        check(near(subject.value(), target), b, "and sets it: asked " + target + ", API "
                + subject.value());
        double set = subject.value();
        int changes = subject.changes();
        b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        b.perform(Action.SET_VALUE, Accessible.Argument.NONE);
        check(near(subject.value(), set) && subject.changes() == changes, b,
                "SET_VALUE with NaN, an infinity or no argument moves nothing and tells nobody: "
                        + subject.value() + ", " + (subject.changes() - changes) + " changes");
        check(b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(facet.max() + 1)), b,
                "SET_VALUE past the greatest value is accepted");
        check(near(subject.value(), facet.max()), b, "and clamped to it: " + subject.value());
    }

    private static void aDisabledValueRefuses(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        if (subject.readOnly()) {
            return;
        }
        double before = subject.value();
        subject.widget().setEnabled(false);
        b.harness.frame();
        AccessibleNode node = b.node();
        check(!offers(node, Action.INCREMENT) && !offers(node, Action.DECREMENT), b,
                "a disabled value offers no step verb");
        int changes = subject.changes();
        b.perform(Action.INCREMENT, Accessible.Argument.NONE);
        b.perform(Action.DECREMENT, Accessible.Argument.NONE);
        b.perform(Action.SET_VALUE, new Accessible.Argument.OfValue(before + 1));
        check(near(subject.value(), before) && subject.changes() == changes, b,
                "and a step or a set moves nothing and tells nobody: " + subject.value() + ", "
                        + (subject.changes() - changes) + " changes");
    }

    private static void theIdSurvivesAMove(ValueSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        long id = b.node().id();
        if (!subject.readOnly()) {
            b.perform(Action.INCREMENT, Accessible.Argument.NONE);
        }
        double mid = (b.node().value().min() + b.node().value().max()) / 2;
        if (!subject.readOnly()) {
            subject.set(mid);
        }
        b.harness.frame();
        check(b.node().id() == id, b, "the node kept its id across a change of its number");
    }

    // ---------------------------------------------------------------------------- the reading

    private static boolean near(double a, double c) {
        return Math.abs(a - c) <= EPS;
    }

    private static boolean offers(AccessibleNode node, Action verb) {
        return node.actions() != null && node.actions().has(verb);
    }

    private static void check(boolean condition, Bound b, String rule) {
        if (!condition) {
            throw new AssertionError(rule + "\nthe tree published:" + b.harness.describe());
        }
    }

    /** A subject built and bound, with the one value node read off the tree. */
    private static final class Bound {
        final AccessibleHarness harness;

        private Bound(AccessibleHarness harness) {
            this.harness = harness;
        }

        static Bound of(ValueSubject subject, UiRuntime runtime) {
            return new Bound(new AccessibleHarness(runtime, subject.build()));
        }

        /** The one node carrying a value facet in the tree published last. */
        AccessibleNode node() {
            AccessibleTree tree = harness.tree();
            AccessibleNode found = null;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.value() != null) {
                    if (found != null) {
                        throw new AssertionError("two nodes carry a value facet; the subject "
                                + "must publish one:" + harness.describe());
                    }
                    found = node;
                }
            }
            if (found == null) {
                throw new AssertionError("no node carries a value facet:" + harness.describe());
            }
            return found;
        }

        boolean perform(Action verb, Accessible.Argument arg) {
            return harness.perform(node().id(), verb, arg);
        }

        /**
         * A scroll bar tells its host's model and announces nothing; a divider's move announces
         * the layout it caused as an adjustment. The value change itself, where announced, is
         * the user's.
         */
        boolean announcedOnlyFromTheUser() {
            for (Change change : harness.changes) {
                if (change.aspect() == Change.Aspect.VALUE && change.origin() != Change.Origin.USER) {
                    return false;
                }
            }
            return true;
        }
    }
}
