package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.i18n.I18nString;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a field gives a reader, on the node the reader lands on: the caption as its name, the line
 * under the control as its description, required and invalid as its states. One case per input
 * of the forms guide's table, so an input whose composite sends its caption somewhere else, and
 * the states not with it, shows up as its own failure.
 */
class FieldAccessibilityTest extends AccessibleComponentTestBase {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    /** The inputs of the forms guide's table, and a compound control, each built fresh. */
    private static Map<String, Supplier<Widget<?>>> inputs() {
        Map<String, Supplier<Widget<?>>> all = new LinkedHashMap<>();
        all.put("TextField", TextField::new);
        all.put("PasswordField", PasswordField::new);
        all.put("TextArea", TextArea::new);
        all.put("SearchField", SearchField::new);
        all.put("ComboBox", () -> new ComboBox<>(List.of("Personal", "Team")));
        all.put("editable ComboBox", () -> new ComboBox<>(List.of("Personal", "Team")).setEditable(true));
        all.put("ComboBox of many", () -> new ComboBox<>(List.of("Open", "Shipped"))
                .setSelectionMode(SelectionMode.MULTI));
        all.put("Spinner", () -> new Spinner(0, 10, 1));
        all.put("DateField", () -> new DateField().setClock(TODAY));
        all.put("DatePicker", () -> new DatePicker().setClock(TODAY));
        all.put("CalendarView", () -> new CalendarView().setClock(TODAY));
        all.put("Slider", () -> new Slider(0, 100));
        all.put("RangeSlider", () -> new RangeSlider(0, 100));
        all.put("Checkbox", () -> new Checkbox("Opt in"));
        all.put("a column of radio buttons", () -> {
            Column radios = new Column();
            ButtonGroup group = new ButtonGroup();
            for (String each : List.of("Monthly", "Yearly")) {
                RadioButton radio = new RadioButton(each);
                group.add(radio);
                radios.add(radio);
            }
            return radios;
        });
        all.put("a row of an amount and its currency", () -> {
            Row amount = new Row();
            amount.add(new TextField().setAccessibleName("Amount"));
            amount.add(new ComboBox<>(List.of("BRL", "EUR")).setAccessibleName("Currency"));
            return amount;
        });
        return all;
    }

    /** The one node the caption names, which is the node a reader lands on. */
    private AccessibleNode named(String caption) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.name().equals(caption) && node.role() != Accessible.Role.LABEL) {
                found.add(node);
            }
        }
        assertEquals(1, found.size(), "one node named \"" + caption + "\"" + describe(tree()));
        return found.get(0);
    }

    @Test
    void everyInputCarriesTheCaptionTheLineAndTheStatesOnTheNodeAReaderLandsOn() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Supplier<Widget<?>>> input : inputs().entrySet()) {
            Field field = new Field("Caption", input.getValue().get())
                    .setHelperText("How to fill it")
                    .setRequired(true);
            Column root = new Column();
            root.add(field);
            bind(root);
            try {
                AccessibleNode node = named("Caption");
                check(failures, input.getKey(), "helper as description", "How to fill it", node.description());
                check(failures, input.getKey(), "required", true, node.has(Accessible.State.REQUIRED));
                check(failures, input.getKey(), "not invalid", false, node.has(Accessible.State.INVALID));

                field.setValidation(Validation.ERROR, "What is wrong");
                frame();
                node = named("Caption");
                check(failures, input.getKey(), "message as description", "What is wrong", node.description());
                check(failures, input.getKey(), "invalid", true, node.has(Accessible.State.INVALID));

                field.setValidation(Validation.WARNING, "A doubt");
                frame();
                node = named("Caption");
                check(failures, input.getKey(), "a warning is not invalid", false,
                        node.has(Accessible.State.INVALID));
            } catch (AssertionError e) {
                failures.add(input.getKey() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void check(List<String> failures, String input, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures.add(input + ": " + what + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @Test
    void theAsteriskIsNotInTheNameAndTheAccessLetterReachesTheControl() {
        boolean system = limn.scene.internal.AccessLetterSystem.setPresentForTests(true);
        try {
            asteriskAndLetter();
        } finally {
            limn.scene.internal.AccessLetterSystem.setPresentForTests(system);
        }
    }

    private void asteriskAndLetter() {
        TextField email = new TextField();
        Column root = new Column();
        root.add(new Field(I18nString.literal("&Email"), email).setRequired(true));
        bind(root);

        AccessibleNode field = node(Accessible.Role.TEXT_FIELD);
        assertEquals("Email", field.name(), "the caption's words alone: no asterisk, no mark" + describe(tree()));
        assertNotNull(field.actions(), describe(tree()));
        assertNotNull(field.actions().accessKey(), "Alt and the caption's letter reach the field" + describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            assertFalse(tree().node(i).name().contains("*"), "the asterisk is drawn, never read" + describe(tree()));
        }
    }

    @Test
    void aCheckBoxWithNoCaptionKeepsItsOwnWordsAndGainsTheRest() {
        Checkbox terms = new Checkbox("I accept the terms");
        Column root = new Column();
        Field field = new Field(null, terms, terms).setRequired(true);
        root.add(field);
        bind(root);
        field.setValidation(Validation.ERROR, "Accept them to go on");
        frame();

        AccessibleNode box = node(Accessible.Role.CHECK_BOX);
        assertEquals("I accept the terms", box.name(), describe(tree()));
        assertEquals("Accept them to go on", box.description(), describe(tree()));
        assertTrue(box.has(Accessible.State.REQUIRED), describe(tree()));
        assertTrue(box.has(Accessible.State.INVALID), describe(tree()));
    }

    @Test
    void anEmptyLineIsNotANodeAndDescribesNothing() {
        TextField email = new TextField();
        Column root = new Column();
        Field field = new Field("Email", email);
        root.add(field);
        bind(root);
        field.setValidation(Validation.ERROR, "Enter an address");
        frame();
        field.clearValidation();
        frame();

        AccessibleNode text = node(Accessible.Role.TEXT_FIELD);
        assertEquals("", text.description(), describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.LABEL && node.name().isEmpty(),
                    "the reserved, empty line publishes no empty label" + describe(tree()));
        }
    }
}
