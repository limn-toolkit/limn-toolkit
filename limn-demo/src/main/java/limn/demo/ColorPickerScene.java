package limn.demo;

import limn.components.ColorPicker;
import limn.components.ColorPickerButton;
import limn.components.Dialog;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.graphics.Color;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * The colour picker, both ways it is meant to be used: inline in a panel, and behind a
 * {@link ColorPickerButton}, the popover pattern, which is how an application usually asks
 * for a colour without giving up a panel to it.
 *
 * <p>The second one is also what {@link Dialog#setContent} is for, assembled once so that
 * nobody has to assemble it again: the button raises the dialog, OK and Cancel come from
 * the dialog, and the answer (the chosen colour, or the one Cancel puts back) arrives
 * through a single listener.
 */
final class ColorPickerScene {

    private ColorPickerScene() {
    }

    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(content())));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    static Widget content() {
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("Colour picker").setFont(theme.title).setStrong(true));
        col.add(new Label("Saturation/value field, hue ramp, a before/after swatch, a hex "
                + "field, and one line per channel in RGB, HSV or CMYK (letter, rail, "
                + "number), with alpha as one more line when the mode offers it. Drag the "
                + "field, the ramp or any rail; arrows nudge the stepper beside it, "
                + "Shift+arrow by ten.").setMuted(true).setWrap(true));

        ColorPicker inline = new ColorPicker();
        inline.setInitialColor(Color.rgb(0x3B82F6));
        // In a Row, not straight into the column: the column stretches its
        // children across, which would override the SizedBox and show the picker
        // at a width no panel would ever give it.
        Row inlineRow = new Row();
        inlineRow.mainAlignment(Flex.MainAlignment.START);
        inlineRow.add(new SizedBox(320, SizedBox.UNSET, inline));
        col.add(inlineRow);

        col.add(new Label("In a dialog, opened from a swatch: ColorPickerButton")
                .setStrong(true));
        col.add(new Label("One widget: the chip is the value, clicking it raises the picker "
                + "over the button, and the answer comes back through onChange, including "
                + "the one Cancel puts back, so the caller never has to remember what the "
                + "colour was. This one runs with alpha off, which is the right mode "
                + "whenever the thing being coloured cannot be translucent.")
                .setMuted(true).setWrap(true));
        col.add(swatchRow());
        return col;
    }

    /** The well, and a label that follows whatever it answers. */
    private static Widget swatchRow() {
        Label chosen = new Label("");
        ColorPickerButton well = new ColorPickerButton(Color.rgb(0xF59E0B));
        well.setAlphaEnabled(false);
        well.onChange(color -> chosen.setText("Chosen: " + color.toHex()));
        chosen.setText("Chosen: " + well.color().toHex());

        // The second form: a caption of the application's own instead of the hex, which is
        // what a dense inspector row wants beside a token name.
        ColorPickerButton captioned = new ColorPickerButton(Color.rgb(0x22C55E));
        captioned.setText("Choose colour…");

        Row row = new Row();
        row.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(well);
        row.add(captioned);
        row.add(chosen.setMuted(true));
        return row;
    }
}
