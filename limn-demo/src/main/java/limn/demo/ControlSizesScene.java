package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.util.List;

/**
 * The control-size ramp, five steps at once: the scene a reviewer reads to judge the axis.
 *
 * <p>Three things it is built to show:
 * <ul>
 *   <li><b>The ramp.</b> One row per step, same widgets, so the type / height / spacing ramps
 *       can be compared against each other rather than described.</li>
 *   <li><b>Coexistence.</b> The last row mixes steps in a single {@code Row}, which is the
 *       feature's actual mandate and the case a global switch could never express.</li>
 *   <li><b>The pixel-locked rule.</b> Every border and focus ring in the picture is the same
 *       weight. If one of them thickens with its row, the rule broke.</li>
 * </ul>
 *
 * <p>Until the components consume tokens, every row renders identically, which is exactly the
 * baseline this scene exists to capture first.
 */
final class ControlSizesScene {

    private ControlSizesScene() {
    }

    static Scene create() {
        Column page = new Column();
        page.gap(18).crossAlignment(Flex.CrossAlignment.STRETCH);

        // Role, not setFont(theme.title). This scene's own heading sits outside every
        // stepRow scope, so it stays at the scene default, but pinning theme.title would
        // also freeze it under ControlSize.setProcessDefault, which is the one switch a
        // reviewer of this scene is most likely to throw.
        page.add(new Label("Control sizes").setRole(Label.Role.TITLE));
        page.add(new Label("One row per step. Borders and focus rings keep one weight throughout.")
                .setMuted(true).setWrap(true));

        for (ControlSize step : ControlSize.values()) {
            page.add(stepRow(step));
        }

        page.add(new Label("Coexistence: one row, three steps").setMuted(true));
        page.add(mixedRow());

        Scene scene = new Scene(new Padding(Insets.all(20), page));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** One labelled row of the standard form cluster, all of it at {@code step}. */
    private static Widget stepRow(ControlSize step) {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Label(step.name()).setMuted(true));
        row.add(new Button("Action"));
        row.add(new TextField().setPlaceholder("Text"));
        row.add(new ComboBox(List.of("One", "Two", "Three")));
        row.add(new Checkbox(Checkbox.Variant.BOX, "Toggle"));
        // The whole row is one scope: every descendant that declares nothing inherits this.
        row.setControlSize(step);
        return row;
    }

    /**
     * A row that mixes steps. Uses BASELINE: the cross-step baseline offset is
     * {@code body * 0.341796875}, a function of the type ramp alone, so no choice of control
     * heights can align these boxes' text; only a baseline can.
     */
    private static Widget mixedRow() {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.BASELINE);

        Button small = new Button("Small");
        small.setControlSize(ControlSize.SMALL);
        Button medium = new Button("Medium"); // inherits: the scene default
        Button large = new Button("Large");
        large.setControlSize(ControlSize.LARGE);

        row.add(small);
        row.add(medium);
        row.add(large);
        return row;
    }
}
