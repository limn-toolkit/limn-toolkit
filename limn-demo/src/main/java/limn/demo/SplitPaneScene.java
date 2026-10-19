package limn.demo;

import limn.components.Button;
import limn.components.Label;
import limn.components.ListView;
import limn.components.ScrollView;
import limn.components.Separator;
import limn.components.SplitPane;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.icons.tabler.Tabler;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A mail client's window, the shape split panes take in an application: folders beside a list
 * of messages beside the message being read, filling the page's height.
 *
 * <p>Two splitters rather than one three-pane control, because that is the whole design: nesting
 * is how a split pane grows. Each pane has a floor it cannot be dragged under, and the page's
 * toolbar puts both dividers back where they started, from code.
 */
final class SplitPaneScene {

    /** The strings of this page, ready for translation; senders and addresses are data. */
    static final class Text {
        static final I18nString HINT = new I18nString("showcase.split.hint",
                "Drag a divider, or use the arrow keys on it.");
        static final I18nString RESET = new I18nString("showcase.split.reset", "Reset layout");
        static final I18nString LAYOUT_RESET = new I18nString("showcase.split.layoutReset", "Layout reset");
        static final I18nString FOLDERS = new I18nString("showcase.split.folders", "Folders");
        static final I18nString MESSAGES = new I18nString("showcase.split.messages", "Messages");
        static final I18nString INBOX = new I18nString("showcase.split.inbox", "Inbox");
        static final I18nString SENT = new I18nString("showcase.split.sent", "Sent");
        static final I18nString DRAFTS = new I18nString("showcase.split.drafts", "Drafts");
        static final I18nString ARCHIVE = new I18nString("showcase.split.archive", "Archive");
        static final I18nString TRASH = new I18nString("showcase.split.trash", "Trash");
        static final I18nString NO_MESSAGES = new I18nString("showcase.split.noMessages", "No messages");
        static final I18nString FROM_TO = new I18nString("showcase.split.fromTo", "{0} to {1} · {2}");
        static final I18nString ME = new I18nString("showcase.split.me", "me");

        static final I18nString S_INVOICE = new I18nString("showcase.split.mail.invoice.subject",
                "Invoice 2026-1042 is due on Friday");
        static final I18nString B_INVOICE = new I18nString("showcase.split.mail.invoice.body",
                "Hello,\n\nA reminder that invoice 2026-1042, for $4,250.00, is due on Friday. "
                        + "The PDF is attached again for your records.\n\nAccounts receivable");
        static final I18nString S_SHIPMENT = new I18nString("showcase.split.mail.shipment.subject",
                "Order 58213 has shipped");
        static final I18nString B_SHIPMENT = new I18nString("showcase.split.mail.shipment.body",
                "Your order of 40 office chairs left the North warehouse this morning. "
                        + "Expected delivery: three to five working days.");
        static final I18nString S_MEETING = new I18nString("showcase.split.mail.meeting.subject",
                "Quarterly review moved to Thursday");
        static final I18nString B_MEETING = new I18nString("showcase.split.mail.meeting.body",
                "The quarterly sales review moves to Thursday at 10:00, in the large meeting room. "
                        + "Please bring the regional figures.");
        static final I18nString S_PRICES = new I18nString("showcase.split.mail.prices.subject",
                "New price list for 2027");
        static final I18nString B_PRICES = new I18nString("showcase.split.mail.prices.body",
                "The 2027 price list is ready. Paper and toner rise by 3%; furniture prices stay the same "
                        + "until March.");
        static final I18nString S_CONTRACT = new I18nString("showcase.split.mail.contract.subject",
                "Signed supply contract");
        static final I18nString B_CONTRACT = new I18nString("showcase.split.mail.contract.body",
                "Attached is the supply contract, signed on our side. Please countersign and return a copy.");
        static final I18nString S_QUOTE = new I18nString("showcase.split.mail.quote.subject",
                "Quote for 12 standing desks");
        static final I18nString B_QUOTE = new I18nString("showcase.split.mail.quote.body",
                "As discussed, 12 standing desks at $389.00 each, delivery and assembly included.");
        static final I18nString S_DRAFT = new I18nString("showcase.split.mail.draft.subject",
                "Re: Return of damaged items");
        static final I18nString B_DRAFT = new I18nString("showcase.split.mail.draft.body",
                "We are sorry the items arrived damaged. A courier will collect them on");

        private Text() {
        }
    }

    /** Where the dividers start, and where Reset layout puts them back. */
    private static final float FOLDERS_RATIO = 0.2f;
    private static final float LIST_RATIO = 0.42f;

    /**
     * One folder of the mailbox, and whether its icon means a direction and so turns around where
     * the interface reads right to left: the paper plane of what was sent flies the way the
     * interface reads.
     */
    private record Folder(I18nString name, String icon, limn.graphics.Icon.Mirroring mirroring) {
        Folder(I18nString name, String icon) {
            this(name, icon, limn.graphics.Icon.Mirroring.NEVER);
        }
    }

    /** One message. */
    private record Mail(Folder folder, String sender, String address, I18nString subject, I18nString body,
                        LocalDateTime when, boolean unread) {
    }

    private SplitPaneScene() {
    }

    /** Standalone {@code --scene split}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        return scene;
    }

    /** Reusable subtree (a page of the showcase). */
    static Widget<?> content() {
        Folder inbox = new Folder(Text.INBOX, "inbox");
        Folder sent = new Folder(Text.SENT, "send", limn.graphics.Icon.Mirroring.IN_RTL);
        Folder drafts = new Folder(Text.DRAFTS, "file-pencil");
        Folder archive = new Folder(Text.ARCHIVE, "archive");
        Folder trash = new Folder(Text.TRASH, "trash");
        List<Folder> folders = List.of(inbox, sent, drafts, archive, trash);
        List<Mail> mail = List.of(
                new Mail(inbox, "Accounts receivable", "billing@ridgeway-supply.example", Text.S_INVOICE,
                        Text.B_INVOICE, LocalDateTime.of(2026, 10, 15, 9, 42), true),
                new Mail(inbox, "Dispatch", "dispatch@coastline-cargo.example", Text.S_SHIPMENT,
                        Text.B_SHIPMENT, LocalDateTime.of(2026, 10, 15, 8, 5), true),
                new Mail(inbox, "Lena Okafor", "lena.okafor@example.com", Text.S_MEETING, Text.B_MEETING,
                        LocalDateTime.of(2026, 10, 14, 16, 20), true),
                new Mail(inbox, "Purchasing", "purchasing@bluepeak.example", Text.S_PRICES, Text.B_PRICES,
                        LocalDateTime.of(2026, 10, 13, 11, 3), false),
                new Mail(inbox, "Marco Ventura", "m.ventura@example.com", Text.S_CONTRACT, Text.B_CONTRACT,
                        LocalDateTime.of(2026, 10, 9, 14, 48), false),
                new Mail(sent, "Sales", "sales@example.com", Text.S_QUOTE, Text.B_QUOTE,
                        LocalDateTime.of(2026, 10, 14, 10, 15), false),
                new Mail(drafts, "Support", "support@example.com", Text.S_DRAFT, Text.B_DRAFT,
                        LocalDateTime.of(2026, 10, 15, 7, 55), false));

        Reading reading = new Reading();

        ListView<Mail> messages = new ListView<>(SplitPaneScene::messageRow);
        messages.setItemName(m -> I18nString.literal(m.sender() + ", " + m.subject().get()));
        messages.setAccessibleName(Text.MESSAGES);
        Page.afterLanguageChange(() -> {
            // A message's time and date are written as the language writes them, and so is the
            // line under the subject of the one being read.
            messages.refresh();
            reading.show(reading.shown);
        });
        messages.onSelect(() -> {
            int index = messages.selectedIndex();
            reading.show(index < 0 ? null : messages.items().get(index));
        });

        ListView<Folder> folderList = new ListView<>(folder -> folderRow(folder, mail));
        folderList.setItemName(Folder::name);
        folderList.setAccessibleName(Text.FOLDERS);
        folderList.setItems(folders);
        Runnable openFolder = () -> {
            int index = folderList.selectedIndex();
            Folder chosen = index < 0 ? inbox : folders.get(index);
            List<Mail> in = new ArrayList<>();
            for (Mail each : mail) {
                if (each.folder() == chosen) {
                    in.add(each);
                }
            }
            messages.setItems(in);
            if (!in.isEmpty()) {
                messages.setSelectedIndex(0);
            }
            reading.show(in.isEmpty() ? null : in.get(0));
        };
        folderList.onSelect(openFolder);
        // Chosen from code, which a list does not answer with its handler: the inbox opens here.
        folderList.setSelectedIndex(0);
        openFolder.run();

        SplitPane inner = SplitPane.horizontal(
                new Pane(new Padding(Insets.all(6), messages)),
                new Pane(reading.view));
        inner.setRatio(LIST_RATIO).setMinimums(220, 260).setDividerFocusable(true);

        SplitPane outer = SplitPane.horizontal(new Pane(new Padding(Insets.all(6), folderList)), inner);
        outer.setRatio(FOLDERS_RATIO).setMinimums(140, 500).setDividerFocusable(true);

        Button reset = new Button(Text.RESET).setStyle(Button.Style.SECONDARY);
        reset.setIcon(Tabler.outline("layout-columns"));
        reset.onAction(() -> {
            outer.setRatio(FOLDERS_RATIO);
            inner.setRatio(LIST_RATIO);
            Page.report(Text.LAYOUT_RESET.get());
        });

        Column page = Page.column();
        page.add(Page.row(12, reset, Page.hint(Text.HINT)));
        page.add(Expanded.of(outer, 1));
        return new Padding(new Insets(0, 0, 12, 0), page);
    }

    /** A folder: its icon and name, and how many messages in it are unread, at the end. */
    private static Widget<?> folderRow(Folder folder, List<Mail> mail) {
        long unread = mail.stream().filter(m -> m.folder() == folder && m.unread()).count();
        Label name = new Label(folder.name()).setOverflow(Label.Overflow.ELLIPSIS);
        name.setIcon(Tabler.outline(folder.icon()), folder.mirroring());
        Label count = new Label(unread == 0 ? "" : NumberFormats.number().apply(unread))
                .setStrong(true).setAlign(Label.HAlign.END, Label.VAlign.CENTER);
        Row row = new Row();
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(name, 1));
        row.add(count);
        return new Padding(Insets.symmetric(8, 8), row);
    }

    /** A message: sender and time, then the subject, the unread ones in bold. */
    private static Widget<?> messageRow(Mail mail) {
        Label sender = new Label(mail.sender()).setStrong(mail.unread()).setOverflow(Label.Overflow.ELLIPSIS);
        Label when = new Label(when(mail.when())).setMuted(true);
        Row top = new Row();
        top.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        top.add(Expanded.of(sender, 1));
        top.add(when);
        Column row = new Column();
        row.gap(2).crossAlignment(Flex.CrossAlignment.STRETCH);
        row.add(top);
        row.add(new Label(mail.subject()).setMuted(!mail.unread()).setOverflow(Label.Overflow.ELLIPSIS));
        return new Padding(Insets.symmetric(8, 8), row);
    }

    /** Today's messages by their time, older ones by their date, as the language writes either. */
    private static String when(LocalDateTime at) {
        return at.toLocalDate().equals(LocalDateTime.of(2026, 10, 15, 0, 0).toLocalDate())
                ? Formats.time(at)
                : Formats.date(at.toLocalDate());
    }

    /** The reading pane: the message's subject, who sent it to whom and when, and its text. */
    private static final class Reading {

        private final Label subject = new Label("").setRole(Label.Role.TITLE).setWrap(true);
        private final Label from = new Label("").setStrong(true).setOverflow(Label.Overflow.ELLIPSIS);
        private final Label address = new Label("").setMuted(true).setOverflow(Label.Overflow.ELLIPSIS);
        private final Column body = new Column();
        private final Label empty = new Label(Text.NO_MESSAGES).setMuted(true)
                .setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER);
        private final Column message = new Column();
        private final Widget<?> scroll;
        private final Widget<?> placeholder;
        private final Column holder = new Column();
        final Widget<?> view = holder;
        /** The message shown, or null for none. */
        Mail shown;

        Reading() {
            message.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
            body.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
            message.add(subject);
            message.add(from);
            message.add(address);
            message.add(new Padding(Insets.symmetric(8, 0), Separator.horizontal()));
            message.add(body);
            holder.crossAlignment(Flex.CrossAlignment.STRETCH);
            scroll = Expanded.of(new ScrollView(new Padding(Insets.all(16), message)), 1);
            placeholder = Expanded.of(empty, 1);
            holder.add(placeholder);
        }

        void show(Mail mail) {
            shown = mail;
            Widget<?> wanted = mail == null ? placeholder : scroll;
            if (holder.children().get(0) != wanted) {
                holder.remove(holder.children().get(0));
                holder.add(wanted);
            }
            if (mail == null) {
                return;
            }
            subject.setText(mail.subject());
            from.setText(mail.sender());
            address.setText(Text.FROM_TO.format(mail.address(), Text.ME.get(), when(mail.when())));
            // A paragraph to a label: a label's wrap breaks lines where they are full and not at
            // the line breaks in its text.
            for (Widget<?> each : List.copyOf(body.children())) {
                body.remove(each);
            }
            for (String paragraph : mail.body().get().split("\n\n")) {
                body.add(new Label(paragraph).setWrap(true));
            }
        }
    }

    /** A surface, so each pane reads as a pane rather than as more background. */
    private static final class Pane extends Widget<Pane> {

        private final Widget<?> child;

        Pane(Widget<?> child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            child.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, width(), height());
        }

        /** A surface behind the pane's controls: a reader loses nothing by never hearing of it. */
        @Override
        protected boolean paintsDecoration() {
            return true;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            // No border of its own: the divider is what separates two panes, and
            // a framed pane beside a framed pane hides the line doing the work.
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface());
        }
    }
}
