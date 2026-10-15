package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Dialog;
import limn.components.Expander;
import limn.components.ExpanderGroup;
import limn.components.Label;
import limn.components.TabbedPane;
import limn.components.TextArea;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Tabs the user closes and moves, and sections that open and close: a page of the showcase.
 */
final class SectionsScene {

    private SectionsScene() {
    }

    /** Reusable subtree (a page of the showcase). */
    static Widget<?> content() {
        Column page = new Column();
        page.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);

        page.add(heading("Tabs that close and move"));
        page.add(new Label("Close a tab with its ×, a middle click or Ctrl+W (Cmd+W on macOS). Drag a tab "
                + "along the bar, or move the selected one with Ctrl+Shift+Page Up and Page Down. The draft "
                + "has unsaved changes and asks before it closes.").setMuted(true).setWrap(true));
        Label status = new Label("").setMuted(true);
        TabbedPane documents = new TabbedPane().setTabsClosable(true);
        for (String name : new String[] {"Report.txt", "Notes.txt", "Budget.txt"}) {
            documents.addTab(name, page(name + " holds the text of a document."));
        }
        TextArea draft = new TextArea();
        draft.setText("An unsaved draft: closing it asks first.");
        documents.addTab("Draft*", draft);
        int[] made = {0};
        // The draft has unsaved changes and asks first, in a modal dialog, as an editor does; the
        // request is answered when the dialog is, and the others close at once.
        documents.onTabClose(request -> {
            String title = documents.tabTitle(request.index());
            if (request.content() != draft) {
                status.setText("Closed " + title + ".");
                request.allow();
                return;
            }
            new Dialog("Close " + title + "?", "It has changes that were not saved. Closing it discards them.")
                    .addButton("Keep it open", "keep")
                    .addPrimaryButton("Discard and close", "discard")
                    .setCancelResult("keep")
                    .show(documents)
                    .thenAccept(answer -> {
                        if ("discard".equals(answer)) {
                            status.setText("Closed " + title + ", its changes discarded.");
                            request.allow();
                        } else {
                            status.setText(title + " kept open.");
                            request.refuse();
                        }
                    });
        });
        documents.onTabMove((from, to) -> status.setText("Moved from place " + (from + 1) + " to " + (to + 1) + "."));
        Button add = new Button("New tab").setSecondary(true);
        add.onAction(() -> {
            String name = "Untitled " + ++made[0];
            documents.addTab(name, page(name + " was opened just now."));
            documents.setSelectedIndex(documents.tabCount() - 1);
        });
        Row actions = new Row();
        actions.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        actions.add(add);
        actions.add(status);
        page.add(actions);
        page.add(new SizedBox(SizedBox.UNSET, 180, documents));

        page.add(heading("Sections that open and close"));
        page.add(new Label("An expander on its own, with a summary of what it holds, and three in a group "
                + "that keeps one open.").setMuted(true).setWrap(true));
        Column filters = new Column();
        filters.gap(8);
        filters.add(new Checkbox(Checkbox.Variant.BOX, "In stock").setChecked(true));
        filters.add(new Checkbox(Checkbox.Variant.BOX, "On sale").setChecked(true));
        filters.add(new Checkbox(Checkbox.Variant.BOX, "Free shipping"));
        page.add(new Expander("Filters", filters).setSummary("2 on").setExpanded(true));

        ExpanderGroup group = new ExpanderGroup();
        String[][] questions = {
                {"How long does shipping take?", "Three to five working days.", "Free"},
                {"Can I return an order?", "Within thirty days, unused, in its box.", "30 days"},
                {"Is there a warranty?", "Two years against defects of manufacture.", "2 years"}};
        for (String[] question : questions) {
            Expander section = new Expander(question[0], new Label(question[1]).setWrap(true))
                    .setSummary(question[2]);
            group.add(section);
            page.add(section);
        }
        group.setExpandedIndex(0);
        return page;
    }

    private static Widget<?> page(String text) {
        return new limn.scene.layout.Padding(limn.scene.Insets.all(12), new Label(text).setWrap(true));
    }

    private static Label heading(String text) {
        return new Label(text).setRole(Label.Role.TITLE);
    }
}
