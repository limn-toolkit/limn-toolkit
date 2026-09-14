package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.SelectionItemFacet;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.HeadlessWindow;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Semantics 6 of the 2026-09-13 pass, ratcheted over the whole gallery: <b>a member of a selection
 * says which of how many it is, and a position of zero is published by nothing but a member that
 * has no set</b>. A {@code positionInSet} or {@code sizeOfSet} of 0 publishes nothing on any
 * platform (ADR 039 §1.2's amendment of 2026-09-14) — it is the facet's spelling of "none", the
 * standalone radio's — so a tab, a menu row, a segment, a list row, a combo option, a table row, a
 * tree row or a calendar day that published 0 would be one a reader could enumerate and never
 * place. Every gallery entry is built, and every node carrying a {@code SelectionItemFacet} is held
 * to a one-based position no larger than its set; a {@code containerless} member may say 0 of 0
 * (the facet's own contract), and nothing else may.
 *
 * <p>The spoken numbers themselves are each widget's own test's business — a tree row counts its
 * siblings (decision 4), a calendar day its month (decision 37) — this is only the floor under all
 * of them.
 */
class SelectionItemPositionTest {

    @TestFactory
    Stream<DynamicTest> everyMemberOfASelectionSaysWhichOfHowManyItIs() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> check(entry)));
        }
        return tests.stream();
    }

    private static void check(Entry entry) {
        List<String> violations = new ArrayList<>();
        try (Harness harness = new Harness(Palette.LIGHT)) {
            for (HeadlessWindow window : harness.show(entry)) {
                AccessibleTree tree = window.bridge().tree();
                for (int i = 0; i < tree.nodeCount(); i++) {
                    AccessibleNode node = tree.node(i);
                    SelectionItemFacet item = node.selectionItem();
                    if (item == null) {
                        continue;
                    }
                    if (item.containerless() && item.positionInSet() == 0 && item.sizeOfSet() == 0) {
                        continue; // a member with no set: the one shape that may say none
                    }
                    if (item.positionInSet() < 1 || item.sizeOfSet() < item.positionInSet()) {
                        violations.add(node.role() + " \"" + node.name() + "\" publishes item "
                                + item.positionInSet() + " of " + item.sizeOfSet());
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\": " + violations.size()
                    + " member(s) of a selection publish a position no platform can speak "
                    + "(semantics 6: one-based, no larger than the set, and 0 only for a member "
                    + "with no set):\n  " + String.join("\n  ", violations));
        }
    }
}
