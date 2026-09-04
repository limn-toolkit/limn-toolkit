package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.i18n.I18nString;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A caption bound to the control it names.
 *
 * <p>The record's rule is that a {@code LABELLED_BY} relation is declared and never inferred from
 * where two widgets happen to sit, so what these pin is the declaration doing something and
 * proximity doing nothing. The combo is the control under test because it is the one that already
 * names itself from its own content, which makes it the case where the precedence has to be right:
 * a bound caption must beat what the widget derived, and an application's own name must beat both.
 */
class LabelForAccessibilityTest extends AccessibleComponentTestBase {

    private ComboBox combo;
    private Label caption;

    private void bindPair(boolean link) {
        Column root = new Column();
        caption = new Label("Colour theme");
        combo = new ComboBox(List.of("One", "Two", "Three"));
        root.add(caption);
        root.add(combo);
        if (link) {
            caption.setLabelFor(combo);
        }
        bind(root);
    }

    private AccessibleNode field() {
        return node(Accessible.Role.COMBO_BOX);
    }

    @Test
    void aCaptionThatMerelySitsBesideAControlDoesNotNameIt() {
        bindPair(false);

        assertEquals("One", field().name(),
                "proximity is a layout accident: the combo still names itself from its item"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, field().nameFrom(), describe(tree()));
    }

    @Test
    void aBoundCaptionNamesTheControlAndSaysWhereTheNameCameFrom() {
        bindPair(true);

        assertEquals("Colour theme", field().name(),
                "the declared link beats what the widget derived for itself" + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, field().nameFrom(),
                "and the provenance says it came from another node rather than from content, "
                        + "which is what stops a bridge writing it into the wrong attribute"
                        + describe(tree()));
    }

    @Test
    void theRelationIsDroppedWhileTheCaptionIsNotANodeOfItsOwn() {
        bindPair(true);

        assertEquals(List.of(), field().relations(),
                "a relation naming a node that was never published is worse than none, because "
                        + "every platform answers it with an element that does not resolve. A "
                        + "plain caption declares nothing and is deleted by the transparency "
                        + "rule, so there is nothing to point at -- and the name still crossed, "
                        + "which is the half that does not need a node" + describe(tree()));
    }

    @Test
    void theRelationResolvesOnceTheCaptionPublishesANode() {
        bindPair(true);
        // A different string from the caption's own text on purpose: the combo takes the text,
        // the caption's node takes this, and the two names being distinct is what makes the
        // lookup below name one node rather than either of two.
        caption.setAccessibleName("The caption itself");
        frame();

        AccessibleNode label = node("The caption itself");
        long target = 0;
        for (var relation : field().relations()) {
            if (relation.kind() == Accessible.Relation.LABELLED_BY) {
                target = relation.target();
            }
        }
        assertNotNull(label, "the caption now declares something and survives" + describe(tree()));
        assertEquals(label.id(), target,
                "so a client that would rather read the caption's own node can walk to it"
                        + describe(tree()));
    }

    @Test
    void changingTheCaptionsTextRenamesWhatItLabels() {
        bindPair(true);

        caption.setText("Theme");
        frame();

        assertEquals("Theme", field().name(),
                "the text is read at publish and not copied when the link was made, so there is "
                        + "nothing to keep in step" + describe(tree()));
    }

    @Test
    void anApplicationsOwnNameStillWinsOverABoundCaption() {
        bindPair(true);

        combo.setAccessibleName(I18nString.literal("Palette"));
        frame();

        assertEquals("Palette", field().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, field().nameFrom(),
                "the order is derived, then bound caption, then what the application wrote"
                        + describe(tree()));
    }

    @Test
    void aLabelNamesOneControlAtATime() {
        Column root = new Column();
        caption = new Label("Colour theme");
        combo = new ComboBox(List.of("One", "Two", "Three"));
        ComboBox other = new ComboBox(List.of("Alpha", "Beta"));
        root.add(caption);
        root.add(combo);
        root.add(other);
        caption.setLabelFor(combo);
        bind(root);

        // Selected by name and not by role: two combos are in this tree, which is the whole
        // point of the case, and the base's role lookup refuses an ambiguous one.
        assertNotNull(node("Colour theme"), "the caption names the first" + describe(tree()));

        caption.setLabelFor(other);
        frame();

        assertSame(other, caption.labelFor());
        assertNotNull(node("One"),
                "moving the link releases the widget it left, which goes back to naming itself"
                        + describe(tree()));
        assertNotNull(node("Colour theme"), "and names the one it moved to" + describe(tree()));

        caption.setLabelFor(null);
        frame();
        assertNull(caption.labelFor(), "and null removes it altogether");
        assertNotNull(node("Alpha"),
                "so the one it had moved to names itself again too" + describe(tree()));
    }
}
