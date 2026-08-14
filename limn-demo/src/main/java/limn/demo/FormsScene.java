package limn.demo;

import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.Spinner;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * Form: TextField with placeholder, PasswordField with reveal switch,
 * ComboBox whose popup is a translucent native window, and a TextArea with
 * long content exercising both draggable scrollbars. A status label echoes
 * component events.
 *
 * <p>Closes with the flex-floor pair: the same cramped row twice, once letting the
 * weighted split take the stepper under its own chrome and once declaring
 * {@link Expanded#atLeast} so it cannot be. The two together are the regression net:
 * a floor that stops working makes the second row look like the first.
 */
final class FormsScene {

    /** Scene plus the pieces the demo drives programmatically. */
    record Built(Scene scene, ComboBox combo) {
    }

    /**
     * The floor the stepper row declares, in points. A number chosen here rather than
     * asked of the widget: a flex child is measured with a tight main axis, so nothing
     * the stepper returns from its own measurement survives, and the container has no
     * protocol for asking it what it needs. This much covers the stepper's padding, its
     * button column and a three-digit value.
     */
    private static final float STEPPER_FLOOR = 116;

    /** The cramped slot both stepper rows are given, in points. */
    private static final float SLOT_WIDTH = 200;

    private FormsScene() {
    }

    /**
     * A caption and a stepper sharing one row, with {@code floor} declared on the stepper
     * ({@code 0} for none). The caption is weighted four to one deliberately: a fifth of
     * the slot is less than the stepper's padding and button column together, so the
     * unfloored row shows the defect this API answers (the value painted underneath the
     * buttons) and the floored row shows the trade, an ellipsised caption and a whole
     * stepper.
     */
    private static Row flexRow(String caption, Spinner stepper, float floor) {
        Label label = new Label(caption).setOverflow(Label.Overflow.ELLIPSIS);
        Row row = new Row();
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(label, 4));
        row.add(Expanded.of(stepper, 1).atLeast(floor));
        return row;
    }

    /**
     * The unfloored row beside the floored one, in slots of equal width so the only
     * difference between them is the floor. Each slot is a {@link SizedBox} inside this
     * row rather than in the form column, because the column stretches its children
     * across the cross axis and would hand either slot the column's full width whatever
     * it measured.
     */
    private static Widget floorComparison() {
        Row slots = new Row();
        slots.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        slots.add(new SizedBox(SLOT_WIDTH, SizedBox.UNSET,
                flexRow("Quantity on hand", new Spinner(0, 999, 1).setValue(250), 0)));
        slots.add(new SizedBox(SLOT_WIDTH, SizedBox.UNSET,
                flexRow("Quantity on hand", new Spinner(0, 999, 1).setValue(250), STEPPER_FLOOR)));
        return slots;
    }

    static Built create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());

        Label status = new Label("Interact with the form…").setMuted(true);

        TextField name = new TextField()
                .setPlaceholder("Type your name")
                .onChange(text -> status.setText("name: " + text));

        PasswordField password = new PasswordField();
        password.setPlaceholder("Password");
        password.setText("secret123");
        Checkbox reveal = new Checkbox(Checkbox.Variant.SWITCH, "reveal");
        reveal.onChange(password::setRevealed);
        Row passwordRow = new Row();
        passwordRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        passwordRow.add(Expanded.of(password, 1));
        passwordRow.add(reveal);

        ComboBox combo = new ComboBox(List.of(
                "Limn dark", "Limn light", "High contrast", "Sepia", "System"));
        combo.onSelect(index -> status.setText("theme picked: " + combo.selectedItem()));

        TextArea notes = new TextArea().setPreferredSize(0, 150);
        notes.setText("""
                The TextArea navigates across lines with the arrow keys (sticky column), \
                selects with Shift+arrows and mouse, and copies/pastes through the clipboard.
                Long lines like this one (with no automatic wrapping) exercise the \
                draggable horizontal scrollbar, while many lines exercise the vertical one.
                Line 3
                Line 4
                Line 5
                Line 6
                Line 7
                Line 8: scroll down here with the wheel or by dragging the thumb.""");
        notes.onChange(text -> status.setText("notes: " + text.length() + " chars"));

        Column form = new Column();
        form.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(new Label("Form").setFont(Theme.current().title));
        form.add(new Label("Name").setMuted(true));
        form.add(name);
        form.add(new Label("Password").setMuted(true));
        form.add(passwordRow);
        form.add(new Label("Favorite theme (the popup is a translucent window)").setMuted(true));
        form.add(combo);
        form.add(new Label("Notes").setMuted(true));
        form.add(new SizedBox(SizedBox.UNSET, 150, notes));
        form.add(new SizedBox(SizedBox.UNSET, 18, status));

        form.add(new Label("Flex floors").setFont(Theme.current().title));
        form.add(new Label("Same caption, same stepper, same 200pt slot, caption outweighing "
                + "the stepper four to one. Left takes the weighted share as given and the "
                + "value lands under the buttons; right declares Expanded.atLeast, so the "
                + "caption is the one that gives way.")
                .setMuted(true).setWrap(true));
        form.add(floorComparison());

        Column page = new Column();
        page.crossAlignment(Flex.CrossAlignment.START);
        page.add(new SizedBox(520, SizedBox.UNSET, form));

        Scene scene = new Scene(Padding.all(20, page));
        scene.setBackground(Theme.current().background);
        return new Built(scene, combo);
    }
}
