package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A message bound to the field it explains.
 *
 * <p>The mirror of {@link LabelForAccessibilityTest} for the description: a {@code DESCRIBED_BY}
 * relation is declared and never inferred from where two widgets sit, so a message beneath a
 * field describes nothing until it is bound, and once bound the field's description is the
 * message's text, read at publish, with the relation resolving to the message's own node. The
 * precedence is the one the walk gives the name -- a bound label beats what the walk would have
 * derived, here the tooltip default, and what the application wrote beats both.
 */
class DescribedByAccessibilityTest extends AccessibleComponentTestBase {

    private static final String MESSAGE = "Enter an address like ada@example.com";

    private TextField field;
    private Label message;

    private void bindPair(boolean link) {
        Column root = new Column();
        field = new TextField();
        field.setAccessibleName("Email");
        message = new Label(MESSAGE);
        root.add(field);
        root.add(message);
        if (link) {
            message.setDescriptionFor(field);
        }
        bind(root);
    }

    private AccessibleNode email() {
        return node(Accessible.Role.TEXT_FIELD);
    }

    /**
     * The text field named {@code name}, in a tree where a caption's own node carries the same
     * name as the field it labels, so a lookup by name alone is ambiguous.
     */
    private AccessibleNode fieldNamed(String name) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode candidate = tree().node(i);
            if (candidate.role() == Accessible.Role.TEXT_FIELD && candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("no text field named " + name + describe(tree()));
    }

    /** @return the id the field's one {@code DESCRIBED_BY} relation points at */
    private long describedBy() {
        long target = AccessibleNode.NONE;
        int count = 0;
        for (var relation : email().relations()) {
            if (relation.kind() == Accessible.Relation.DESCRIBED_BY) {
                target = relation.target();
                count++;
            }
        }
        assertEquals(1, count, "one DESCRIBED_BY on the field: " + email().relations());
        return target;
    }

    @Test
    void aMessageThatMerelySitsBeneathAFieldDoesNotDescribeIt() {
        bindPair(false);

        assertEquals("", email().description(),
                "proximity is a layout accident: the field has no description" + describe(tree()));
        for (var relation : email().relations()) {
            assertNotEquals(Accessible.Relation.DESCRIBED_BY, relation.kind(),
                    "and no relation was inferred" + describe(tree()));
        }
    }

    @Test
    void aBoundMessageDescribesTheFieldAndTheRelationResolvesToItsOwnNode() {
        bindPair(true);

        assertEquals(MESSAGE, email().description(),
                "the declared link is what makes the message the description" + describe(tree()));
        AccessibleNode label = node(MESSAGE);
        assertEquals(Accessible.Role.LABEL, label.role(), describe(tree()));
        assertEquals(label.id(), describedBy(),
                "so a client that would rather read the message's own node can walk to it"
                        + describe(tree()));
        assertEquals("Email", email().name(),
                "and describing is not naming: the name is untouched" + describe(tree()));
    }

    @Test
    void changingTheMessagesTextChangesWhatItDescribes() {
        bindPair(true);

        message.setText("That address is taken");
        frame();

        assertEquals("That address is taken", email().description(),
                "the text is read at publish and not copied when the link was made" + describe(tree()));
    }

    @Test
    void aBoundMessageBeatsTheTooltipAndAnExplicitDescriptionBeatsBoth() {
        bindPair(false);
        field.setTooltip("Where the receipt goes");
        frame();
        assertEquals("Where the receipt goes", email().description(),
                "a named field's tooltip is its description by default" + describe(tree()));

        message.setDescriptionFor(field);
        frame();
        assertEquals(MESSAGE, email().description(),
                "a bound message is what the application declared, and beats the default"
                        + describe(tree()));

        field.setAccessibleDescription("Explicit");
        frame();
        assertEquals("Explicit", email().description(),
                "and what the application wrote beats the bound message" + describe(tree()));
        describedBy(); // the relation still stands: it is the truth about the tree
    }

    @Test
    void aLabelDescribesOneWidgetAtATimeAndMayNameAnother() {
        Column root = new Column();
        Label caption = new Label("Email");
        field = new TextField();
        TextField other = new TextField();
        other.setAccessibleName("Backup email");
        message = new Label(MESSAGE);
        root.add(caption);
        root.add(field);
        root.add(other);
        root.add(message);
        caption.setLabelFor(field);
        message.setDescriptionFor(field);
        bind(root);

        assertEquals(MESSAGE, fieldNamed("Email").description(), describe(tree()));
        assertEquals("", fieldNamed("Backup email").description(), describe(tree()));

        message.setDescriptionFor(other);
        frame();
        assertSame(other, message.descriptionFor());
        assertEquals("", fieldNamed("Email").description(),
                "moving the link releases the field it left" + describe(tree()));
        assertEquals(MESSAGE, fieldNamed("Backup email").description(),
                "and describes the one it moved to" + describe(tree()));

        message.setDescriptionFor(null);
        frame();
        assertNull(message.descriptionFor(), "and null removes it altogether");
        assertEquals("", fieldNamed("Backup email").description(), describe(tree()));

        // A caption names and a message describes; the same label could do both, and the two
        // links are independent of each other.
        caption.setDescriptionFor(other);
        frame();
        assertEquals("Email", fieldNamed("Email").name(),
                "still the caption of the first" + describe(tree()));
        assertEquals("Email", fieldNamed("Backup email").description(),
                "and now the description of the second" + describe(tree()));
    }
}
