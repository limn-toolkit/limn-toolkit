package limn.demo.site;

import limn.components.Label;
import limn.components.Theme;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.tree.Tree;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import java.time.LocalDate;
import java.util.List;

/**
 * The showcase screen behind the README's and the home page's "tables, trees and dates" picture:
 * the guides' own table, tree and date widgets in one window, so the picture that claims the
 * three exist is rendered from the same examples the guides compile.
 *
 * <p>Nothing here is a region: the samples a reader copies are {@link TableExample},
 * {@link TreeExample} and {@link DatesExample}, and this file only arranges what they build.
 * Deterministic for {@link DatesExample}'s reason — the dates are fixed, so two capture runs
 * produce the same pixels.
 */
public final class DataExample {

    private DataExample() {
    }

    /** The screen: a date field and a picker across the top, the table and the tree beneath. */
    public static Widget screen() {
        Label tableStatus = new Label("Nothing selected");
        Label treeStatus = new Label("Nothing selected");

        DateField ordered = new DateField();
        ordered.setDate(LocalDate.of(2026, 9, 9));
        DatePicker delivery = new DatePicker();
        delivery.setDate(LocalDate.of(2026, 9, 17));

        Row dates = new Row();
        dates.gap(28).crossAlignment(Flex.CrossAlignment.END);
        dates.add(field("Ordered", ordered));
        dates.add(field("Delivery", delivery));

        Column table = new Column();
        table.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        table.add(Expanded.of(TableExample.table(tableStatus)));
        table.add(tableStatus);

        // The guide's own entries, with the source folder open so the picture shows an outline
        // and not three closed roots.
        TreeExample.Entry src = TreeExample.Entry.folder("src",
                TreeExample.Entry.folder("main",
                        TreeExample.Entry.file("App.java"), TreeExample.Entry.file("Window.java")),
                TreeExample.Entry.folder("test", TreeExample.Entry.file("AppTest.java")));
        Tree<TreeExample.Entry> outline = TreeExample.tree(List.of(src,
                TreeExample.Entry.unread("build"), TreeExample.Entry.file("README.md")), treeStatus);
        outline.expand(src);
        outline.expand(src.children().get(0));

        Column tree = new Column();
        tree.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        tree.add(Expanded.of(outline));
        tree.add(treeStatus);

        Row below = new Row();
        below.gap(28).crossAlignment(Flex.CrossAlignment.STRETCH);
        below.add(Expanded.of(table));
        below.add(new SizedBox(360, SizedBox.UNSET, tree));

        Column column = new Column();
        column.gap(24).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(dates);
        column.add(Expanded.of(below));
        return new Padding(Insets.all(28), column);
    }

    /**
     * The screen as a scene, for the showcase capture and for the check that holds the guides'
     * own examples to the accessibility invariants the gallery's entries are held to.
     */
    public static Scene scene() {
        Scene scene = new Scene(screen());
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** A caption over a control, as a form lays one out. */
    private static Widget field(String caption, Widget control) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        Label label = new Label(caption);
        label.setLabelFor(control);
        column.add(label);
        column.add(control);
        return new SizedBox(220, SizedBox.UNSET, column);
    }
}
