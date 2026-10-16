package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Dialog;
import limn.components.Expander;
import limn.components.ExpanderGroup;
import limn.components.Label;
import limn.components.TabbedPane;
import limn.components.TextArea;
import limn.i18n.I18nString;
import limn.i18n.PluralString;
import limn.icons.tabler.Tabler;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * Tabs the user opens, closes and moves, a preferences card with its tabs centred, and sections
 * that open and close: a page of the showcase.
 */
final class SectionsScene {

    /** The strings of this page, ready for translation; document names are data. */
    static final class Text {
        static final I18nString DOCUMENTS = new I18nString("showcase.sections.documents", "Documents");
        static final I18nString SHORTCUTS = new I18nString("showcase.sections.shortcuts",
                "{0}+W closes a tab · drag a tab, or {0}+Shift+Page Up or Page Down, to move it");
        static final I18nString NEW_DOCUMENT = new I18nString("showcase.sections.newDocument", "New document");
        static final I18nString OVERVIEW = new I18nString("showcase.sections.overview", "Overview");
        static final I18nString CLOSED = new I18nString("showcase.sections.closed", "Closed {0}");
        static final I18nString DISCARDED = new I18nString("showcase.sections.discarded",
                "Closed {0}, its changes discarded");
        static final I18nString KEPT = new I18nString("showcase.sections.kept", "Kept {0} open");
        static final I18nString MOVED = new I18nString("showcase.sections.moved", "Moved {0} to place {1}");
        static final I18nString CLOSE_TITLE = new I18nString("showcase.sections.closeTitle", "Close {0}?");
        static final I18nString CLOSE_MESSAGE = new I18nString("showcase.sections.closeMessage",
                "It has changes that were not saved. Closing it discards them.");
        static final I18nString KEEP_OPEN = new I18nString("showcase.sections.keepOpen", "Keep it open");
        static final I18nString DISCARD = new I18nString("showcase.sections.discard", "Discard and close");

        static final I18nString BODY_OVERVIEW = new I18nString("showcase.sections.body.overview",
                "Your open documents, one to a tab. This overview stays open; the others close.");
        static final I18nString BODY_REPORT = new I18nString("showcase.sections.body.report",
                "Third-quarter sales reached $633,400, up 9% on last year. Partners grew fastest; "
                        + "the online store held steady.");
        static final I18nString BODY_NOTES = new I18nString("showcase.sections.body.notes",
                "Weekly meeting, 14 October. Agreed: move the stock count to the 28th, and send the "
                        + "new price list to every partner by Friday.");
        static final I18nString BODY_BUDGET = new I18nString("showcase.sections.body.budget",
                "Budget 2027: travel down 12%, training up 5%, equipment unchanged. Final figures are due "
                        + "on 30 November.");
        static final I18nString BODY_CONTRACTS = new I18nString("showcase.sections.body.contracts",
                "Four supplier contracts renew in January. Two include a price review clause.");
        static final I18nString BODY_PRICES = new I18nString("showcase.sections.body.prices",
                "Paper and toner rise by 3% from 1 January. Furniture prices stay the same until March.");
        static final I18nString BODY_SHIPPING = new I18nString("showcase.sections.body.shipping",
                "Orders placed before 14:00 ship the same working day. Delivery takes three to five days.");
        static final I18nString BODY_ROTA = new I18nString("showcase.sections.body.rota",
                "The support desk is staffed from 08:00 to 18:00, two people per shift.");
        static final I18nString BODY_NEW = new I18nString("showcase.sections.body.new", "A new, empty document.");
        static final String DRAFT_TEXT = "Dear customer,\n\nWe are sorry the items arrived damaged.";

        static final I18nString PREFERENCES = new I18nString("showcase.sections.preferences", "Preferences");
        static final I18nString GENERAL = new I18nString("showcase.sections.general", "General");
        static final I18nString ACCOUNTS = new I18nString("showcase.sections.accounts", "Accounts");
        static final I18nString NOTIFICATIONS = new I18nString("showcase.sections.notifications", "Notifications");
        static final I18nString OPEN_LAST = new I18nString("showcase.sections.openLast",
                "Open the last documents at start");
        static final I18nString AUTOSAVE = new I18nString("showcase.sections.autosave", "Save drafts every minute");
        static final I18nString SIGNED_IN = new I18nString("showcase.sections.signedIn",
                "Signed in as sales@example.com");
        static final I18nString NOTIFY_ORDERS = new I18nString("showcase.sections.notifyOrders",
                "Tell me about new orders");
        static final I18nString NOTIFY_PAYMENTS = new I18nString("showcase.sections.notifyPayments",
                "Tell me about late payments");

        static final I18nString SEARCH = new I18nString("showcase.sections.search", "Search");
        static final I18nString FILTERS = new I18nString("showcase.sections.filters", "Filters");
        static final PluralString FILTERS_ON = PluralString.of("showcase.sections.filtersOn", "{0} on", "{0} on");
        static final I18nString IN_STOCK = new I18nString("showcase.sections.inStock", "In stock");
        static final I18nString ON_SALE = new I18nString("showcase.sections.onSale", "On sale");
        static final I18nString FREE_SHIPPING = new I18nString("showcase.sections.freeShipping", "Free shipping");

        static final I18nString QUESTIONS = new I18nString("showcase.sections.questions",
                "Frequently asked questions");
        static final I18nString Q_SHIPPING = new I18nString("showcase.sections.q.shipping",
                "How long does shipping take?");
        static final I18nString A_SHIPPING = new I18nString("showcase.sections.a.shipping",
                "Three to five working days from the day the order ships.");
        static final I18nString S_SHIPPING = new I18nString("showcase.sections.s.shipping", "3–5 days");
        static final I18nString Q_RETURNS = new I18nString("showcase.sections.q.returns", "Can I return an order?");
        static final I18nString A_RETURNS = new I18nString("showcase.sections.a.returns",
                "Within thirty days, unused, in its box.");
        static final I18nString S_RETURNS = new I18nString("showcase.sections.s.returns", "30 days");
        static final I18nString Q_WARRANTY = new I18nString("showcase.sections.q.warranty", "Is there a warranty?");
        static final I18nString A_WARRANTY = new I18nString("showcase.sections.a.warranty",
                "Two years against defects of manufacture.");
        static final I18nString S_WARRANTY = new I18nString("showcase.sections.s.warranty", "2 years");

        private Text() {
        }
    }

    /** About as wide as a paragraph reads well: the cap on the preferences and the sections. */
    private static final float NARROW = 600;

    /** What "New document" opens next, in turn. */
    private static final List<String> MORE_DOCUMENTS = List.of("Customer list", "Inventory count", "Holiday plan");

    private SectionsScene() {
    }

    /** Reusable subtree (a page of the showcase). */
    static Widget<?> content() {
        Column page = Page.column();
        documents(page);
        preferences(page);
        sections(page);
        return page;
    }

    /** Documents in closable, movable tabs; the draft asks before it closes. */
    private static void documents(Column page) {
        page.add(Page.firstSection(Text.DOCUMENTS));

        TabbedPane documents = new TabbedPane();
        documents.addTab(Text.OVERVIEW, Tabler.outline("layout-dashboard"), body(Text.BODY_OVERVIEW));
        documents.setTabsClosable(true); // every tab from here on; the overview stays
        documents.addTab("Q3 sales report", Tabler.outline("chart-bar"), body(Text.BODY_REPORT));
        documents.addTab("Meeting notes", Tabler.outline("notes"), body(Text.BODY_NOTES));
        documents.addTab("Budget 2027", Tabler.outline("calculator"), body(Text.BODY_BUDGET));
        documents.addTab("Supplier contracts", Tabler.outline("file-certificate"), body(Text.BODY_CONTRACTS));
        documents.addTab("Price list", Tabler.outline("tag"), body(Text.BODY_PRICES));
        documents.addTab("Shipping policy", Tabler.outline("truck"), body(Text.BODY_SHIPPING));
        documents.addTab("Support rota", Tabler.outline("calendar-user"), body(Text.BODY_ROTA));
        TextArea draft = new TextArea();
        draft.setText(Text.DRAFT_TEXT);
        String draftTitle = "Coastline Cargo reply*";
        Widget<?> draftTab = Page.card(draft); // the tab's content, which a close request names
        documents.addTab(draftTitle, Tabler.outline("mail"), draftTab);
        documents.setTabClosable(0, false);

        // The draft has unsaved changes and asks first, in a modal dialog, as an editor does; the
        // request is answered when the dialog is, and the others close at once.
        documents.onTabClose(request -> {
            String title = documents.tabTitle(request.index());
            if (request.content() != draftTab) {
                Page.report(Text.CLOSED.format(title));
                request.allow();
                return;
            }
            new Dialog(Text.CLOSE_TITLE.format(title), Text.CLOSE_MESSAGE.get())
                    .addButton(Text.KEEP_OPEN, "keep")
                    .addPrimaryButton(Text.DISCARD, "discard")
                    .setCancelResult("keep")
                    .show(documents)
                    .thenAccept(answer -> {
                        if ("discard".equals(answer)) {
                            Page.report(Text.DISCARDED.format(title));
                            request.allow();
                        } else {
                            Page.report(Text.KEPT.format(title));
                            request.refuse();
                        }
                    });
        });
        documents.onTabMove((from, to) -> Page.report(Text.MOVED.format(documents.tabTitle(to), to + 1)));

        int[] opened = {0};
        Button add = new Button(Text.NEW_DOCUMENT).setSecondary(true);
        add.setIcon(Tabler.outline("file-plus"));
        add.onAction(() -> {
            int next = opened[0]++;
            if (next < MORE_DOCUMENTS.size()) {
                documents.addTab(MORE_DOCUMENTS.get(next), Tabler.outline("file-text"), body(Text.BODY_NEW));
            } else {
                documents.addTab(Text.NEW_DOCUMENT, Tabler.outline("file-text"), body(Text.BODY_NEW));
            }
            documents.setSelectedIndex(documents.tabCount() - 1);
        });

        String command = limn.backend.Platform.current().isMacOs() ? "Cmd" : "Ctrl";
        page.add(Page.row(12, add, Page.hint(I18nString.literal(Text.SHORTCUTS.format(command)))));
        page.add(new SizedBox(SizedBox.UNSET, 200, documents));
    }

    /** A preferences card whose tabs sit in the middle of its strip. */
    private static void preferences(Column page) {
        page.add(Page.section(Text.PREFERENCES));
        TabbedPane tabs = new TabbedPane().setAlignment(TabbedPane.TabAlignment.CENTER);
        tabs.addTab(Text.GENERAL, Tabler.outline("settings"), options(
                new Checkbox(Text.OPEN_LAST).setChecked(true),
                new Checkbox(Text.AUTOSAVE).setChecked(true)));
        tabs.addTab(Text.ACCOUNTS, Tabler.outline("user"), options(new Label(Text.SIGNED_IN)));
        tabs.addTab(Text.NOTIFICATIONS, Tabler.outline("bell"), options(
                new Checkbox(Text.NOTIFY_ORDERS).setChecked(true),
                new Checkbox(Text.NOTIFY_PAYMENTS)));
        page.add(Page.narrow(NARROW, Page.card(new SizedBox(SizedBox.UNSET, 130, tabs))));
    }

    /** An expander of filters, and a card of questions that keeps one answer open. */
    private static void sections(Column page) {
        page.add(Page.section(Text.SEARCH));
        Checkbox inStock = new Checkbox(Checkbox.Variant.BOX, Text.IN_STOCK).setChecked(true);
        Checkbox onSale = new Checkbox(Checkbox.Variant.BOX, Text.ON_SALE).setChecked(true);
        Checkbox freeShipping = new Checkbox(Checkbox.Variant.BOX, Text.FREE_SHIPPING);
        Column filters = new Column();
        filters.gap(8);
        filters.add(inStock);
        filters.add(onSale);
        filters.add(freeShipping);
        Expander filter = new Expander(Text.FILTERS, filters).setExpanded(true);
        filter.setIcon(Tabler.outline("filter"));
        Runnable summarize = () -> {
            int on = (inStock.isChecked() ? 1 : 0) + (onSale.isChecked() ? 1 : 0) + (freeShipping.isChecked() ? 1 : 0);
            filter.setSummary(Text.FILTERS_ON.format(on));
        };
        for (Checkbox each : List.of(inStock, onSale, freeShipping)) {
            each.onChange(checked -> summarize.run());
        }
        summarize.run();
        page.add(Page.narrow(NARROW, filter));

        page.add(Page.section(Text.QUESTIONS));
        ExpanderGroup group = new ExpanderGroup();
        Column questions = new Column();
        questions.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        questions.add(question(group, Text.Q_SHIPPING, Text.A_SHIPPING, Text.S_SHIPPING, "truck-delivery"));
        questions.add(question(group, Text.Q_RETURNS, Text.A_RETURNS, Text.S_RETURNS, "arrow-back-up"));
        questions.add(question(group, Text.Q_WARRANTY, Text.A_WARRANTY, Text.S_WARRANTY, "shield-check"));
        group.setExpandedIndex(0);
        page.add(Page.narrow(NARROW, Page.card(questions)));
    }

    private static Expander question(ExpanderGroup group, I18nString question, I18nString answer,
                                     I18nString summary, String icon) {
        Expander section = new Expander(question, new Label(answer).setWrap(true)).setSummary(summary);
        section.setIcon(Tabler.outline(icon));
        group.add(section);
        return section;
    }

    /** A tab's body: a surface holding the document's text. */
    private static Widget<?> body(I18nString text) {
        return Page.card(new Label(text).setWrap(true).setAlign(Label.HAlign.START, Label.VAlign.TOP));
    }

    /** A preferences tab's body: its options, one under the other. */
    private static Widget<?> options(Widget<?>... options) {
        Column column = new Column();
        column.gap(10).crossAlignment(Flex.CrossAlignment.START);
        for (Widget<?> option : options) {
            column.add(option);
        }
        return new limn.scene.layout.Padding(limn.scene.Insets.symmetric(14, 4), column);
    }
}
