package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A name and a description the application writes on a composite reach the part inside it that
 * carries its label (ADR 073 §2.7): the node a reader reaches, as a caption bound to the composite
 * does, and so does a tooltip, which names or describes the node it lands on. A pressable card's
 * area is that part; a date picker's field and an editable combo's text are the others. What the
 * part was given itself wins, and the composite keeps nothing it would otherwise have published
 * only because of what was written on it.
 */
class AccessibleCarriedOverridesTest extends AccessibleTestBase {

    /** A composite that says its inner part carries its label, and says nothing itself. */
    private static final class Composite extends Group {
        final Probe part = new Probe(Accessible.Role.BUTTON, "Inner");
        boolean redirects = true;

        Composite() {
            part.setFocusable(true);
            add(part);
        }

        @Override
        protected Widget<?> accessibleLabelTarget() {
            return redirects ? part : this;
        }
    }

    private Composite bindComposite() {
        Composite composite = new Composite();
        Group root = new Group();
        root.add(composite);
        bind(root);
        return composite;
    }

    private int nodesNamed(String name) {
        AccessibleTree tree = tree();
        int found = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (name.equals(tree.node(i).name())) {
                found++;
            }
        }
        return found;
    }

    @Test
    void aNameAndADescriptionWrittenOnTheCompositeLandOnThePartThatCarriesItsLabel() {
        Composite composite = bindComposite();
        composite.setAccessibleName("Harbor Lane Supplies");
        composite.setAccessibleDescription("Invoice 1042, due Friday");
        frame();
        AccessibleNode part = node("Harbor Lane Supplies");
        assertEquals(Accessible.Role.BUTTON, part.role(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, part.nameFrom());
        assertEquals("Invoice 1042, due Friday", part.description());
        assertEquals(1, nodesNamed("Harbor Lane Supplies"),
                "the composite publishes no node of its own for it: " + describe(tree()));
        assertNull(findGroup(), "and stays transparent: " + describe(tree()));
    }

    @Test
    void aTooltipOnTheCompositeDescribesThePartAndPublishesNoNodeOfItsOwn() {
        Composite composite = bindComposite();
        composite.setTooltip("Opens the invoice");
        frame();
        AccessibleNode part = node("Inner");
        assertEquals("Opens the invoice", part.description(), describe(tree()));
        assertNull(findGroup(), "a tooltip left on the composite named a group of its own: " + describe(tree()));
    }

    @Test
    void whatThePartWasGivenItselfWins() {
        Composite composite = bindComposite();
        composite.setAccessibleName("From the composite");
        composite.part.setAccessibleName("From the part");
        frame();
        assertEquals(1, nodesNamed("From the part"), describe(tree()));
        assertEquals(0, nodesNamed("From the composite"), describe(tree()));
    }

    @Test
    void aCompositeThatCarriesItsOwnLabelKeepsItsName() {
        Composite composite = bindComposite();
        composite.redirects = false;
        composite.setAccessibleName("Kept");
        frame();
        AccessibleNode kept = node("Kept");
        assertFalse(kept.role() == Accessible.Role.BUTTON, "the composite's own node: " + describe(tree()));
        assertEquals(1, nodesNamed("Inner"), "and the part keeps its own name");
    }

    private AccessibleNode findGroup() {
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode each = tree.node(i);
            if (each.role() == Accessible.Role.GROUP) {
                return each;
            }
        }
        return null;
    }
}
