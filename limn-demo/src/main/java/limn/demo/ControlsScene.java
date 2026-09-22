package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.Theme;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Showcase for the {@link Slider} and {@link Spinner} components: continuous and
 * stepped sliders (with live value read-outs), plus numeric, fractional and
 * {@code HH:MM} time spinners, and a disabled one of each.
 */
final class ControlsScene {

    private ControlsScene() {
    }

    /** Standalone {@code --scene controls}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** Reusable subtree (kitchen-sink tab). */
    static Widget content() {
        Column column = new Column();
        column.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);

        column.add(heading("Slider"));
        // The readouts mirror the sliders, so they watch rather than handle: one call reads the
        // slider and then follows it, whoever moves it, and the starting literal written twice
        // is gone.
        Slider volume = new Slider(0, 100).setValue(30);
        column.add(sliderRow("Continuous (0–100)", volume, readoutOf(volume)));

        Slider level = new Slider(0, 10).setStep(1).setValue(5);
        column.add(sliderRow("Step of 1 (0–10)", level, readoutOf(level)));

        Slider disabledSlider = new Slider(0, 100).setValue(40);
        disabledSlider.setEnabled(false);
        column.add(sliderRow("Disabled", disabledSlider, new Label("40").setMuted(true)));

        column.add(heading("Spinner"));
        column.add(new Label("Buttons and keyboard (arrows, PageUp/Down, Home/End), or type the "
                + "value: a digit starts an edit with the old number selected, Enter commits, "
                + "Escape puts it back, and copy/paste work either way. The wheel is "
                + "deliberately not a value gesture.").setMuted(true).setWrap(true));

        Spinner disabledSpinner = new Spinner(0, 10, 1).setValue(3);
        disabledSpinner.setEnabled(false);
        Row spinners = new Row();
        spinners.gap(24).crossAlignment(Flex.CrossAlignment.START);
        spinners.add(Labelled.below("Quantity (0–99)", new Spinner(0, 99, 1).setValue(1)));
        spinners.add(Labelled.below("Fraction (step 0.25)", new Spinner(0, 1, 0.25).setValue(0.5)));
        spinners.add(Labelled.below("Time (HH:MM)", Spinner.time().setValue(7 * 60 + 30)));
        spinners.add(Labelled.below("Disabled", disabledSpinner));
        column.add(spinners);

        return new ScrollView(column);
    }

    // Role, not setFont(theme.title): setFont pins MEDIUM's 20 pt whatever step the
    // subtree resolves to. The role picks the title token OF the resolved step.
    private static Label heading(String text) {
        return new Label(text).setRole(Label.Role.TITLE);
    }

    /** A label that reads the slider now and follows every move of it, from any origin. */
    private static Label readoutOf(Slider slider) {
        Label value = new Label(Integer.toString(Math.round(slider.value()))).setMuted(true);
        slider.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                value.setText(Integer.toString(Math.round(slider.value())));
            }
        });
        return value;
    }

    private static Widget sliderRow(String caption, Slider slider, Label value) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(caption).setMuted(true));
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(slider, 1));
        row.add(new SizedBox(44, SizedBox.UNSET, value));
        column.add(row);
        return column;
    }

}
