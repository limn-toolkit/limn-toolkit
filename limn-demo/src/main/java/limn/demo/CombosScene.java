package limn.demo;

import limn.components.ComboBox;
import limn.components.Label;
import limn.components.SelectionMode;
import limn.components.Theme;
import limn.graphics.Icon;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Every kind of combo box side by side, enabled and disabled: one item chosen from a list, one
 * typed and filtered, many chosen from a list, and many filtered by typing, all
 * over the same order statuses with an icon each. {@code --scene combos}.
 */
final class CombosScene {

    /** An order status, what a sales report is filtered by. */
    record Status(String label, String icon) {
        Icon glyph() {
            return Tabler.outline(icon);
        }
    }

    static final List<Status> STATUSES = List.of(
            new Status("Open", "circle-dot"),
            new Status("Invoiced", "file-invoice"),
            new Status("Shipped", "truck"),
            new Status("Cancelled", "circle-x"),
            new Status("Returned", "arrow-back-up"));

    private CombosScene() {
    }

    /** Standalone {@code --scene combos}, with a status line saying what the handlers heard. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(24), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    static Widget<?> content() {
        Column column = new Column();
        column.gap(16).crossAlignment(Flex.CrossAlignment.START);
        column.add(new Label("Combo boxes").setStrong(true));
        Label status = new Label("Pick something: the handler's answer shows here.").setMuted(true);

        Grid grid = new Grid(3).gap(16).rowGap(14).crossAlignment(Flex.CrossAlignment.CENTER);
        grid.add(new Label(""));
        grid.add(new Label("Enabled").setMuted(true));
        grid.add(new Label("Disabled").setMuted(true));
        row(grid, "One item", false, false, status::setText);
        row(grid, "One item, typed", true, false, status::setText);
        row(grid, "Many items", false, true, status::setText);
        row(grid, "Many items, typed", true, true, status::setText);
        column.add(grid);
        column.add(status);
        return column;
    }

    private static void row(Grid grid, String caption, boolean editable, boolean many,
                            Consumer<String> say) {
        Label label = new Label(caption);
        grid.add(label);
        // The empty option alternates between the two single rows and between enabled and
        // disabled: worded with an icon, or blank with neither.
        boolean worded = !editable;
        ComboBox<Status> enabled = combo(editable, many, worded);
        enabled.onSelect(() -> say.accept(caption + ": " + (many
                ? enabled.selectedItems().stream().map(Status::label).toList()
                : enabled.selectedItem() == null ? "null" : enabled.selectedItem().label())));
        label.setLabelFor(enabled);
        if (many && !editable) {
            enabled.setSelectedIndices(0, 1); // chips from the start, to see them before a click
        }
        grid.add(new SizedBox(260, SizedBox.UNSET, enabled));
        ComboBox<Status> disabled = combo(editable, many, !worded);
        if (many) {
            disabled.setSelectedIndices(0, 2, 4);
        } else {
            disabled.clearSelection(); // the empty option, as it looks when it cannot be changed
        }
        disabled.setEnabled(false);
        disabled.setAccessibleName(caption + ", disabled");
        grid.add(new SizedBox(260, SizedBox.UNSET, disabled));
    }

    private static ComboBox<Status> combo(boolean editable, boolean many, boolean wordedEmpty) {
        // Both functions are asked for null too: that is the empty option's text and icon, here
        // either "Any status" with a dashed circle, or nothing at all.
        ComboBox<Status> combo = new ComboBox<>(STATUSES,
                status -> status == null ? (wordedEmpty ? "Any status" : "") : status.label())
                .setItemIcon(status -> status == null
                        ? (wordedEmpty ? Tabler.outline("circle-dashed") : null) : status.glyph());
        if (many) {
            combo.setSelectionMode(SelectionMode.MULTI);
        } else {
            combo.setEmptySelectionAllowed(true); // a first option that holds nothing (null)
        }
        return combo.setEditable(editable);
    }
}
