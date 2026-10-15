package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.RangeSlider;
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

    /** Reusable subtree (a page of the showcase). */
    static Widget<?> content() {
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

        column.add(heading("Range slider"));
        column.add(new Label("Two thumbs that never cross: each is a Tab stop, and a press on the track "
                + "takes the nearer one.").setMuted(true).setWrap(true));
        RangeSlider price = new RangeSlider(0, 1000).setStep(10).setRange(200, 600);
        column.add(rangeRow("Price (step of 10)", price));
        RangeSlider nights = new RangeSlider(1, 30).setStep(1).setMinimumGap(2).setRange(3, 7);
        column.add(rangeRow("Nights, at least two apart", nights));

        column.add(heading("Spinner"));
        column.add(new Label("Click the arrows, use the keyboard, or type a value.").setMuted(true).setWrap(true));

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

    /** A range slider with a readout that follows both of its values, from any origin. */
    private static Widget<?> rangeRow(String caption, RangeSlider slider) {
        Label value = new Label("").setMuted(true);
        Runnable show = () -> value.setText(Math.round(slider.lower()) + "–" + Math.round(slider.upper()));
        show.run();
        slider.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                show.run();
            }
        });
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(caption).setMuted(true));
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(slider, 1));
        row.add(new SizedBox(80, SizedBox.UNSET, value));
        column.add(row);
        return column;
    }

    private static Widget<?> sliderRow(String caption, Slider slider, Label value) {
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
