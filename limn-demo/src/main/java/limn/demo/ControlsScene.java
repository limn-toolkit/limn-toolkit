package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.Theme;
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
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** Reusable subtree (kitchen-sink tab). */
    static Widget content() {
        Column column = new Column();
        column.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);

        column.add(heading("Slider"));
        Label volumeValue = new Label("30").setMuted(true);
        Slider volume = new Slider(0, 100).setValue(30);
        volume.onChange(v -> volumeValue.setText(Integer.toString(Math.round(v))));
        column.add(sliderRow("Continuous (0–100)", volume, volumeValue));

        Label levelValue = new Label("5").setMuted(true);
        Slider level = new Slider(0, 10).setStep(1).setValue(5);
        level.onChange(v -> levelValue.setText(Integer.toString(Math.round(v))));
        column.add(sliderRow("Step of 1 (0–10)", level, levelValue));

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
        spinners.add(labelled("Quantity (0–99)", new Spinner(0, 99, 1).setValue(1)));
        spinners.add(labelled("Fraction (step 0.25)", new Spinner(0, 1, 0.25).setValue(0.5)));
        spinners.add(labelled("Time (HH:MM)", Spinner.time().setValue(7 * 60 + 30)));
        spinners.add(labelled("Disabled", disabledSpinner));
        column.add(spinners);

        return new ScrollView(column);
    }

    // Role, not setFont(theme.title): setFont pins MEDIUM's 20 pt whatever step the
    // subtree resolves to. The role picks the title token OF the resolved step.
    private static Label heading(String text) {
        return new Label(text).setRole(Label.Role.TITLE);
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

    private static Widget labelled(String caption, Widget control) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.START);
        column.add(control);
        column.add(new Label(caption).setMuted(true));
        return column;
    }
}
