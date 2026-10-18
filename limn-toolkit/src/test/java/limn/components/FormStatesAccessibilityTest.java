package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.components.date.DatePicker;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the walk does with a describer and with the states a field says of its control, on the node
 * a caption would name.
 *
 * <p>A caption bound to a composite has long been carried to the part the keyboard lands on (a
 * date picker's caption names its field). A message bound to the same picker was not, and described
 * a group that publishes no node, so a reader on the field heard no message at all; and nothing in
 * the toolkit published {@code REQUIRED}. These pin both, through the internal hook a field uses,
 * with no field in the tree: the walk's half is the walk's.
 */
class FormStatesAccessibilityTest extends AccessibleComponentTestBase {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    /** The field a single date picker holds: the one node with the picker's spin buttons under it. */
    private AccessibleNode dateField() {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.GROUP && node.firstChild() != AccessibleNode.NONE
                    && tree().node(node.firstChild()).role() == Accessible.Role.SPIN_BUTTON) {
                return node;
            }
        }
        throw new AssertionError("no date field in " + describe(tree()));
    }

    /** @return the node of that role carrying that name, since a caption shares its name with what it labels */
    private AccessibleNode node(Accessible.Role role, String name) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode candidate = tree().node(i);
            if (candidate.role() == role && candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("no " + role + " named \"" + name + "\" in " + describe(tree()));
    }

    private static long describedBy(AccessibleNode node) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == Accessible.Relation.DESCRIBED_BY) {
                return relation.target();
            }
        }
        return AccessibleNode.NONE;
    }

    @Test
    void aMessageBoundToADatePickerDescribesTheFieldAReaderIsOn() {
        Column root = new Column();
        DatePicker picker = new DatePicker().setClock(TODAY);
        Label caption = new Label("Delivery date").setLabelFor(picker);
        Label message = new Label("Weekdays only").setDescriptionFor(picker);
        root.add(caption);
        root.add(picker);
        root.add(message);
        bind(root);

        AccessibleNode field = dateField();
        assertEquals("Delivery date", field.name(), "the caption, as before" + describe(tree()));
        assertEquals("Weekdays only", field.description(),
                "the message follows the caption onto the field" + describe(tree()));
        assertEquals(node(Accessible.Role.LABEL, "Weekdays only").id(), describedBy(field),
                "and the field points at it" + describe(tree()));

        message.setText("Saturdays too");
        frame();
        assertEquals("Saturdays too", dateField().description(),
                "read at publish, so a message that changes follows" + describe(tree()));
    }

    @Test
    void aDescriberOfTheInnerFieldsOwnWinsOverOneSentDown() {
        Column root = new Column();
        DatePicker picker = new DatePicker().setClock(TODAY);
        root.add(picker);
        root.add(new Label("Sent down").setDescriptionFor(picker));
        root.add(new Label("Its own").setDescriptionFor(picker.field()));
        bind(root);

        assertEquals("Its own", dateField().description(), describe(tree()));
    }

    @Test
    void aFieldsStatesLandWhereItsCaptionLands() {
        Column root = new Column();
        DatePicker picker = new DatePicker().setClock(TODAY);
        root.add(new Label("Delivery date").setLabelFor(picker));
        root.add(picker);
        bind(root);
        assertFalse(dateField().has(Accessible.State.REQUIRED), describe(tree()));

        SceneAccess.fieldControl(picker, true, true, false);
        frame();
        assertTrue(dateField().has(Accessible.State.REQUIRED),
                "required on the field a reader is on, not on the picker's group" + describe(tree()));
        assertFalse(dateField().has(Accessible.State.INVALID), describe(tree()));

        SceneAccess.fieldControl(picker, true, true, true);
        frame();
        assertTrue(dateField().has(Accessible.State.INVALID), describe(tree()));

        SceneAccess.fieldControl(picker, false, false, false);
        frame();
        assertFalse(dateField().has(Accessible.State.REQUIRED), "let go, and both are gone" + describe(tree()));
        assertFalse(dateField().has(Accessible.State.INVALID), describe(tree()));
    }

    @Test
    void aTextFieldKeepsItsOwnInvalidAndGainsRequired() {
        Column root = new Column();
        TextField email = new TextField().setValidation(Validation.ERROR);
        root.add(new Label("Email").setLabelFor(email));
        root.add(email);
        bind(root);
        SceneAccess.fieldControl(email, true, true, false);
        frame();

        AccessibleNode field = node(Accessible.Role.TEXT_FIELD);
        assertTrue(field.has(Accessible.State.REQUIRED), describe(tree()));
        assertTrue(field.has(Accessible.State.INVALID),
                "the field's own error is not taken away by a field that says nothing of one"
                        + describe(tree()));
        assertEquals(Accessible.Role.TEXT_FIELD, field.role(), "and its role is its own");
    }

    @Test
    void aControlThatSaysNothingOfItselfBecomesAGroupThatCarriesTheStates() {
        Column root = new Column();
        Row amount = new Row();
        amount.add(new TextField().setAccessibleName("Amount"));
        amount.add(new ComboBox<>(java.util.List.of("BRL", "EUR")).setAccessibleName("Currency"));
        root.add(amount);
        bind(root);
        assertTrue(nodesWith(Accessible.State.REQUIRED).isEmpty(), describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            assertFalse(tree().node(i).role() == Accessible.Role.GROUP,
                    "a row says nothing of itself and is not published" + describe(tree()));
        }

        SceneAccess.fieldControl(amount, true, true, true);
        frame();
        AccessibleNode group = node(Accessible.Role.GROUP);
        assertTrue(group.has(Accessible.State.REQUIRED), describe(tree()));
        assertTrue(group.has(Accessible.State.INVALID), describe(tree()));
        assertEquals(group.id(), tree().node(node(Accessible.Role.TEXT_FIELD).parent()).id(),
                "its parts are inside it, and keep their own names" + describe(tree()));
        assertEquals("Amount", node(Accessible.Role.TEXT_FIELD).name(), describe(tree()));
    }
}
