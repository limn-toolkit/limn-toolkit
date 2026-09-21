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
 * What every widget of the {@code TEXT} shape owes a reader, as named cases over a
 * {@link TextSubject} (ADR 045 §4): the facet's string is the API's; {@code SET_TEXT} with a
 * text replaces it and with anything else changes nothing; {@code SET_CARET} with a collapsed
 * range moves the caret and {@code SET_SELECTION} with a range selects it, each read back off
 * the facet; and the node keeps its id as the text changes. The helper itself,
 * {@code limn.components.text.TextAccessibility}, predates the record and is what the shape
 * was modelled on.
 */
public final class TextContract {

    private TextContract() {
    }

    public static List<ContractCase> cases(TextSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the facet's string is the API's",
                () -> theFacetIsTheApis(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("SET_TEXT replaces the string, and with no text changes nothing",
                () -> setTextReplaces(subject, runtime)));
        cases.add(new ContractCase("SET_CARET moves the caret and SET_SELECTION selects a range",
                () -> caretAndSelectionMove(subject, runtime)));
        cases.add(new ContractCase("the node keeps its id as the text changes",
                () -> theIdSurvivesAnEdit(subject, runtime)));
        return cases;
    }

    private static void theFacetIsTheApis(TextSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        AccessibleNode node = b.node();
        check(Shape.of(node) == Shape.TEXT, b, "the node is a TEXT shape, not " + Shape.of(node));
        check(node.text().text().equals(subject.text()), b, "the facet's string is the API's: "
                + "facet \"" + node.text().text() + "\", API \"" + subject.text() + "\"");
        check(subject.text().equals(subject.initialText()), b, "and it is what the subject was "
                + "built with");
    }

    private static void theInvariantsHold(TextSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void setTextReplaces(TextSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.perform(Action.SET_TEXT, new Accessible.Argument.OfText("replaced"));
        check(subject.text().equals("replaced"), b, "SET_TEXT replaced the string: \""
                + subject.text() + "\"");
        check(b.node().text().text().equals("replaced"), b, "and the facet followed");
        b.perform(Action.SET_TEXT, Accessible.Argument.NONE);
        b.perform(Action.SET_TEXT, new Accessible.Argument.OfValue(3));
        check(subject.text().equals("replaced"), b, "SET_TEXT with no text changed nothing: \""
                + subject.text() + "\"");
    }

    private static void caretAndSelectionMove(TextSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int length = subject.initialText().length();
        b.perform(Action.SET_CARET, new Accessible.Argument.OfRange(2, 2));
        check(b.node().text().caretOffset() == 2, b, "SET_CARET moved the caret to 2: "
                + b.node().text().caretOffset());
        check(subject.text().equals(subject.initialText()), b, "and changed no text");
        b.perform(Action.SET_SELECTION, new Accessible.Argument.OfRange(1, length - 1));
        check(b.node().text().selectionStart() == 1
                && b.node().text().selectionEnd() == length - 1, b,
                "SET_SELECTION selected 1 to " + (length - 1) + ": "
                        + b.node().text().selectionStart() + " to "
                        + b.node().text().selectionEnd());
        b.perform(Action.SET_CARET, new Accessible.Argument.OfRange(0, 3));
        check(b.node().text().selectionStart() == 1, b, "SET_CARET with a range that is not "
                + "collapsed changed nothing");
    }

    private static void theIdSurvivesAnEdit(TextSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        long id = b.node().id();
        b.perform(Action.SET_TEXT, new Accessible.Argument.OfText("edited"));
        check(b.node().id() == id, b, "the node kept its id across an edit");
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
                if (node.text() != null) {
                    if (found != 0) {
                        throw new AssertionError("two text nodes; the subject must publish one:"
                                + harness.describe());
                    }
                    found = node.id();
                }
            }
            if (found == 0) {
                throw new AssertionError("no node carries a text facet:" + harness.describe());
            }
            id = found;
        }

        static Bound of(TextSubject subject, UiRuntime runtime) {
            return new Bound(new AccessibleHarness(runtime, subject.build()));
        }

        AccessibleNode node() {
            return harness.node(id);
        }

        void perform(Action verb, Accessible.Argument arg) {
            harness.perform(id, verb, arg);
        }
    }
}
