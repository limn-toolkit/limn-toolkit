package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Separator;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.ControlSize;
import limn.scene.Insets;
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
 * The density axis, as one picture: the same five controls built five times and told
 * nothing except which size step to sit at.
 *
 * <p>The point the capture has to make is that <em>nothing else changes</em> (no widget
 * here is given a width, a font or a padding), so the rows are built by one loop from one
 * builder, and the only thing that differs between them is the {@link ControlSize} set on
 * the row.
 */
public final class ControlSizeExample {

    private ControlSizeExample() {
    }

    /** In presentation order. The page beside the picture names them; the picture does not. */
    private static final ControlSize[] STEPS = {
            ControlSize.XSMALL, ControlSize.SMALL, ControlSize.MEDIUM,
            ControlSize.LARGE, ControlSize.XLARGE,
    };

    /**
     * One size step is one call. It is inherited down the tree, so a row set to a step
     * hands it to every control inside it, and nothing below has to be told anything.
     */
    // #region guide:control-size
    static Widget atSize(ControlSize step) {
        Row controls = new Row();
        controls.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        controls.setControlSize(step);

        controls.add(new Button("Save"));
        controls.add(new Button("Cancel").setSecondary(true));
        controls.add(new SizedBox(120, SizedBox.UNSET, new TextField().setText("Ada")));
        controls.add(new ComboBox(List.of("Everyone", "My team", "Only me")));
        controls.add(new Checkbox(Checkbox.Variant.SWITCH, "Notify").setChecked(true));
        return controls;
    }
    // #endregion

    /**
     * The comparison: one row of controls per step, separated, and <b>nothing else</b>.
     *
     * <p>No title, no explanation and no legend beside the rows. A picture on the site sits
     * inside a page that already carries its heading and its prose, and the page also names
     * the steps in order; text rendered into the image is that text a second time, at a
     * size the page did not choose, in a language the page cannot translate. The only words
     * a capture may carry are the ones on the controls it is showing.
     */
    public static Widget board() {
        Column rows = new Column();
        rows.gap(0).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int i = 0; i < STEPS.length; i++) {
            if (i > 0) {
                rows.add(new Padding(Insets.symmetric(30, 0), Separator.horizontal()));
            }
            rows.add(entry(STEPS[i]));
        }
        return new Padding(Insets.symmetric(24, 56), rows);
    }

    /**
     * One step's row. Left-aligned, not centred: the five share a starting edge, and that is
     * what makes the comparison readable: centred rows stagger, and the eye reads the
     * stagger before it reads the size.
     */
    private static Widget entry(ControlSize step) {
        Row line = new Row();
        line.gap(16)
                .mainAlignment(Flex.MainAlignment.START)
                .crossAlignment(Flex.CrossAlignment.CENTER);
        line.add(atSize(step));
        return line;
    }

    /** The board on a canvas, for the capture the site shows. */
    public static Scene scene() {
        // Centred on the canvas: the board is a fixed five rows and the window it is
        // captured in is taller than that, so top-aligning it leaves half the picture empty.
        Column centred = new Column();
        centred.mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.STRETCH);
        centred.add(board());
        Scene scene = new Scene(centred);
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
