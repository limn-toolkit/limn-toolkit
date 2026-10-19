package limn.demo.site;

import limn.components.Avatar;
import limn.components.AvatarGroup;
import limn.components.Badge;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Dialog;
import limn.components.Field;
import limn.components.Label;
import limn.components.Popover;
import limn.components.TextField;
import limn.components.Tone;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.concurrent.Ui;
import limn.scene.Widget;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The guides' examples for the button styles, a status badge, people as
 * avatars, a table that says it has nothing to show or is loading, a filter popover, and a dialog
 * whose destructive answer is not the default. Each region is pasted into a guide as it stands,
 * and {@code StatusExampleTest} runs it.
 */
final class StatusExample {

    private StatusExample() {
    }

    // #region guide:button-styles
    static Row actions() {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        // The one thing the view is for, filled; a second choice, outlined; a quiet one, plain.
        row.add(new Button("Approve"));
        row.add(new Button("Save draft").setStyle(Button.Style.SECONDARY));
        row.add(new Button("Skip").setStyle(Button.Style.TERTIARY));
        // What deletes or discards says so in the palette's danger colour, at any style.
        row.add(new Button("Delete order").setStyle(Button.Style.SECONDARY).setDestructive(true));
        return row;
    }
    // #endregion

    /** An order's state, as the orders table shows it. */
    enum Status { OPEN, PAID, OVERDUE }

    // #region guide:badge
    static Badge statusBadge(Status status) {
        return switch (status) {
            case OPEN -> new Badge("Open").setTone(Tone.INFO);
            case PAID -> new Badge("Paid").setTone(Tone.SUCCESS);
            case OVERDUE -> new Badge("Overdue").setTone(Tone.DANGER);
        };
    }

    static Badge unread(int count) {
        // "99+" past 99, in the digits of the language shown; named for a reader by the sentence.
        return Badge.ofCount(count).setTone(Tone.ACCENT).setAccessibleName(count + " unread");
    }
    // #endregion

    // #region guide:avatars
    static Row approvers(List<String> names) {
        AvatarGroup group = new AvatarGroup();
        for (String name : names) {
            group.add(new Avatar(name)); // initials and a colour of their own, from the name
        }
        group.setMaxShown(4); // three faces and "+k", the rest in its tooltip
        group.setAccessibleName("Approvers"); // read as one group described by every name

        Row row = new Row();
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Label("Approvers"));
        row.add(group);
        return row;
    }
    // #endregion

    // #region guide:table-empty
    static Table<String> orders(List<String> customers, Runnable clearFilter) {
        Table<String> table = new Table<>(List.of(Column.text("Customer", (String c) -> c)));
        table.setRows(customers);

        // Shown by the table whenever it has no row, between its header and its footer.
        Label noMatch = new Label("No orders match the filter").setStrong(true);
        Button clear = new Button("Clear filter").setStyle(Button.Style.SECONDARY);
        clear.onAction(clearFilter);
        limn.scene.layout.Column nothing = new limn.scene.layout.Column();
        nothing.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        nothing.add(noMatch);
        nothing.add(clear);
        table.setEmptyContent(nothing, noMatch); // the label is what describes the empty table
        return table;
    }

    static CompletionStage<Void> reload(Table<String> table, List<String> customers,
                                        Supplier<List<String>> fetch) {
        table.setLoading(true); // busy, "Loading…" in place of the rows, and said to a reader
        customers.clear();
        table.refresh();
        return Ui.async(fetch).thenAccept(found -> { // back on the UI thread
            customers.addAll(found);
            table.refresh();
            table.setLoading(false); // the rows first, then the end: "Orders, 12 items"
        });
    }
    // #endregion

    // #region guide:popover
    static Button filterButton(TextField customer, Checkbox overdueOnly) {
        limn.scene.layout.Column panel = new limn.scene.layout.Column();
        panel.gap(12);
        panel.add(new Field("Customer contains", customer));
        panel.add(overdueOnly);

        Button filter = new Button("Filter").setStyle(Button.Style.SECONDARY);
        // The button opens and closes it; the field inside takes the keyboard; Escape or a
        // press outside closes it, and the focus comes back to the button.
        new Popover(panel).setAccessibleName("Filter orders").attachTo(filter);
        return filter;
    }
    // #endregion

    // #region guide:dialog-destructive
    static Dialog deleteOrder(int number) {
        return new Dialog("Delete order " + number + "?", "This cannot be undone.")
                .addPrimaryButton("Keep", "keep")        // the default: Return keeps the order
                .addDestructiveButton("Delete", "delete") // never the default
                .setCancelResult("keep");
    }
    // #endregion

    /** Everything above on one page, for a print. */
    static Widget<?> page() {
        limn.scene.layout.Column page = new limn.scene.layout.Column();
        page.gap(16);
        page.add(actions());
        Row badges = new Row();
        badges.gap(8);
        for (Status status : Status.values()) {
            badges.add(statusBadge(status));
        }
        badges.add(unread(3));
        page.add(badges);
        page.add(approvers(List.of("Ana Lima", "Rui Costa", "Mei Chen", "Omar Haddad", "Lena Vogel")));
        page.add(filterButton(new TextField(), new Checkbox(Checkbox.Variant.BOX, "Overdue only")));
        return page;
    }
}
