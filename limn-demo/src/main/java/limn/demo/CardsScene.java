package limn.demo;

import limn.components.Button;
import limn.components.Card;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
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

/**
 * Cards: the three looks, a picture and an icon at their own size, stretched and out to the
 * edges, a card with its picture beside the text, and cards clicked as a whole or above their
 * actions. A part of the "Tabs and sections" page, and a scene of the lab on its own.
 */
final class CardsScene {

    /** The strings of these cards, ready for translation; customers' names and amounts are data. */
    static final class Text {
        static final I18nString CARDS = new I18nString("showcase.cards.cards", "Cards");
        static final I18nString LOOKS = new I18nString("showcase.cards.looks", "Raised, outlined and filled");
        static final I18nString FITS = new I18nString("showcase.cards.fits",
                "A picture at its own size, stretched, and out to the edges");
        static final I18nString ICONS = new I18nString("showcase.cards.icons",
                "An icon at its own size, stretched, and out to the edges");
        static final I18nString BESIDE = new I18nString("showcase.cards.beside", "The picture beside the text");
        static final I18nString CLICKED = new I18nString("showcase.cards.clicked",
                "Click a card, or the actions under it");

        static final I18nString CUSTOMER_SINCE = new I18nString("showcase.cards.customerSince", "Customer since {0}");
        static final I18nString FURNITURE = new I18nString("showcase.cards.furniture",
                "Office furniture, delivered every Monday.");
        static final I18nString PAPER = new I18nString("showcase.cards.paper", "Paper and toner, paid in thirty days.");
        static final I18nString CLEANING = new I18nString("showcase.cards.cleaning",
                "Cleaning supplies, on hold since March.");

        static final I18nString STOCK_COUNT = new I18nString("showcase.cards.stockCount", "Stock count");
        static final I18nString SALES_BY_REGION = new I18nString("showcase.cards.salesByRegion", "Sales by region");
        static final I18nString DELIVERY_ROUTES = new I18nString("showcase.cards.deliveryRoutes", "Delivery routes");
        static final I18nString DAILY = new I18nString("showcase.cards.daily", "Updated every day");
        static final I18nString WEEKLY = new I18nString("showcase.cards.weekly", "Updated every week");
        static final I18nString HOURLY = new I18nString("showcase.cards.hourly", "Updated every hour");

        static final I18nString MONTH_END = new I18nString("showcase.cards.monthEnd", "Month-end close");
        static final I18nString PAYROLL = new I18nString("showcase.cards.payroll", "Payroll");
        static final I18nString AUDIT = new I18nString("showcase.cards.audit", "Audit trail");
        static final I18nString DUE_FIFTH = new I18nString("showcase.cards.dueFifth", "Due on the 5th");
        static final I18nString RUNS_25TH = new I18nString("showcase.cards.runs25th", "Runs on the 25th");
        static final I18nString SEVEN_YEARS = new I18nString("showcase.cards.sevenYears", "Kept for seven years");

        static final I18nString DESK = new I18nString("showcase.cards.desk", "Standing desk");
        static final I18nString DESK_DETAILS = new I18nString("showcase.cards.deskDetails", "Oak top, 160 × 80 cm");
        static final I18nString DESK_STOCK = new I18nString("showcase.cards.deskStock",
                "Twelve in stock at the main warehouse.");
        static final I18nString ADD_TO_ORDER = new I18nString("showcase.cards.addToOrder", "Add to order");
        static final I18nString ADDED = new I18nString("showcase.cards.added", "Added {0} to the order");
        static final I18nString SUPPORT = new I18nString("showcase.cards.support", "Support desk");
        static final I18nString SUPPORT_HOURS = new I18nString("showcase.cards.supportHours",
                "Open from 08:00 to 18:00, two people per shift.");

        static final I18nString INVOICE = new I18nString("showcase.cards.invoice", "Invoice");
        static final I18nString QUOTE = new I18nString("showcase.cards.quote", "Quote");
        static final I18nString DELIVERY_NOTE = new I18nString("showcase.cards.deliveryNote", "Delivery note");
        static final I18nString FROM_ORDER = new I18nString("showcase.cards.fromOrder", "From a customer's order");
        static final I18nString PRICES_HELD = new I18nString("showcase.cards.pricesHeld", "Prices held for thirty days");
        static final I18nString SIGNED = new I18nString("showcase.cards.signed", "Signed on delivery");
        static final I18nString NEW_DOCUMENT = new I18nString("showcase.cards.newDocument", "New: {0}");

        static final I18nString INVOICE_NUMBER = new I18nString("showcase.cards.invoiceNumber", "Invoice {0}");
        static final I18nString DUE_FRIDAY = new I18nString("showcase.cards.dueFriday", "Three items, due on Friday.");
        static final I18nString DUE_MONTH_END = new I18nString("showcase.cards.dueMonthEnd",
                "One item, due at the end of the month.");
        static final I18nString APPROVE = new I18nString("showcase.cards.approve", "Approve");
        static final I18nString HOLD = new I18nString("showcase.cards.hold", "Hold");
        static final I18nString OPENED = new I18nString("showcase.cards.opened", "Opened {0}");
        static final I18nString APPROVED = new I18nString("showcase.cards.approved", "Approved {0}");
        static final I18nString HELD = new I18nString("showcase.cards.held", "Put {0} on hold");

        private Text() {
        }
    }

    /** Amounts are in dollars, written the way the language shown writes money. */
    private static final java.util.function.DoubleFunction<String> DOLLARS =
            limn.i18n.NumberFormats.currency(java.util.Currency.getInstance("USD"));

    private CardsScene() {
    }

    /**
     * The lab's scenes: the cards alone, the looks and the media in the first, the cards beside
     * their picture and the cards that are clicked in the second, each fitting the lab's window.
     *
     * @param second whether to show the second half
     */
    static Scene create(boolean second) {
        Column page = Page.column();
        if (second) {
            besideAndClicked(page);
        } else {
            page.add(Page.firstSection(Text.CARDS));
            looksAndMedia(page);
        }
        return new Scene(new ScrollView(new Padding(Insets.all(20), page)));
    }

    /**
     * The lab's scene of the states a clicked card is drawn in: three clicked cards, the middle one
     * with a footer, for {@link #pose} to focus, hover and press.
     */
    static Scene states() {
        Column page = Page.column();
        page.add(Page.firstSection(Text.CARDS));
        page.add(Page.hint(Text.CLICKED));
        page.add(band(
                template(Text.INVOICE, Text.FROM_ORDER, "file-invoice"),
                invoice("1042", "Harbor Lane Supplies", 1240, Text.DUE_FRIDAY),
                template(Text.DELIVERY_NOTE, Text.SIGNED, "truck-delivery")));
        return new Scene(new Padding(Insets.all(20), page));
    }

    /**
     * Puts the states scene in a state a print shows: the first card focused and the pointer over
     * the second's area, or the second focused, its ring inside the card above the footer, and the
     * third held pressed.
     *
     * @param scene  the states scene
     * @param second which of the two poses
     */
    static void pose(Scene scene, boolean second) {
        limn.backend.spi.WindowInput input = limn.scene.internal.SceneAccess.input(scene);
        Row band = (Row) ((Column) ((Padding) scene.root()).children().get(0)).children().get(2);
        Widget<?> middle = band.children().get(1);
        Widget<?> last = band.children().get(2);
        Widget<?> pointed = second ? last : middle;
        float x = pointed.localToScene(0, 0).x() + pointed.width() / 2;
        float y = pointed.localToScene(0, 0).y() + 30;
        input.mouseMoved(x, y);
        if (second) {
            // Pressed and held; the press takes the keyboard there, and Tab then goes round from
            // the last card to the first and on to the second.
            input.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, x, y, 1);
        }
        for (int i = 0; i < (second ? 2 : 1); i++) {
            input.keyEvent(limn.input.Keys.TAB, true, false, 0);
            input.keyEvent(limn.input.Keys.TAB, false, false, 0);
        }
        input.inputBatchEnded();
    }

    /**
     * Adds the cards to a page, under a section heading.
     *
     * @param page  the page's column
     * @param first whether they open the page, which puts no room above their heading
     */
    static void cards(Column page, boolean first) {
        page.add(first ? Page.firstSection(Text.CARDS) : Page.section(Text.CARDS));
        looksAndMedia(page);
        besideAndClicked(page);
    }

    /** The three looks, and a picture and an icon at each of the three fits. */
    private static void looksAndMedia(Column page) {
        page.add(Page.hint(Text.LOOKS));
        page.add(band(
                customer("Harbor Lane Supplies", 2019, Text.FURNITURE, 48200, Card.Style.ELEVATED),
                customer("Northgate Stationers", 2021, Text.PAPER, 12950, Card.Style.OUTLINED),
                customer("Riverside Facilities", 2016, Text.CLEANING, 7310, Card.Style.FILLED)));

        page.add(Page.hint(Text.FITS));
        page.add(band(
                report(Text.STOCK_COUNT, Text.DAILY, new ImageView(Pictures.STOCK).setPreferredSize(64, 40),
                        Card.MediaFit.NATURAL),
                report(Text.SALES_BY_REGION, Text.WEEKLY, cover(Pictures.SALES), Card.MediaFit.STRETCH),
                report(Text.DELIVERY_ROUTES, Text.HOURLY, cover(Pictures.ROUTES), Card.MediaFit.COVER)));

        page.add(Page.hint(Text.ICONS));
        page.add(band(
                new Card().setMedia(Tabler.outline("calendar-stats")).setTitle(Text.MONTH_END)
                        .setSubtitle(Text.DUE_FIFTH),
                new Card().setMedia(Tabler.outline("cash")).setMediaFit(Card.MediaFit.STRETCH)
                        .setMediaAspectRatio(3).setTitle(Text.PAYROLL).setSubtitle(Text.RUNS_25TH),
                new Card().setMedia(Tabler.outline("shield-check")).setMediaFit(Card.MediaFit.COVER)
                        .setMediaAspectRatio(3).setTitle(Text.AUDIT).setSubtitle(Text.SEVEN_YEARS)));

    }

    /** A card with its picture beside the text, and cards clicked as a whole or above their actions. */
    private static void besideAndClicked(Column page) {
        page.add(Page.hint(Text.BESIDE));
        Button add = new Button(Text.ADD_TO_ORDER).setSecondary(true);
        add.onAction(() -> Page.report(Text.ADDED.format(Text.DESK.get())));
        Card desk = new Card(text(Text.DESK_STOCK))
                .setOrientation(Card.Orientation.HORIZONTAL)
                .setMedia(cover(Pictures.DESK)).setMediaFit(Card.MediaFit.COVER)
                .setTitle(Text.DESK).setSubtitle(Text.DESK_DETAILS)
                .setHeaderSuffix(money(640))
                .setFooter(add);
        Card support = new Card(text(Text.SUPPORT_HOURS))
                .setOrientation(Card.Orientation.HORIZONTAL)
                .setMedia(Tabler.outline("headset")).setMediaFit(Card.MediaFit.STRETCH)
                .setTitle(Text.SUPPORT).setStyle(Card.Style.OUTLINED);
        page.add(band(desk, support));

        page.add(Page.hint(Text.CLICKED));
        page.add(band(
                template(Text.INVOICE, Text.FROM_ORDER, "file-invoice"),
                template(Text.QUOTE, Text.PRICES_HELD, "file-dollar"),
                template(Text.DELIVERY_NOTE, Text.SIGNED, "truck-delivery")));
        page.add(band(
                invoice("1042", "Harbor Lane Supplies", 1240, Text.DUE_FRIDAY),
                invoice("1043", "Northgate Stationers", 310.5, Text.DUE_MONTH_END)));
    }

    /** Cards side by side, as wide as one another and as tall as the tallest, their footers on one line. */
    private static Widget<?> band(Widget<?>... cards) {
        Row row = new Row();
        row.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (Widget<?> card : cards) {
            row.add(Expanded.of(card, 1));
        }
        return row;
    }

    /** A customer: their initials before the name, the year to date after it, a line on what they buy. */
    private static Card customer(String name, int since, I18nString about, double amount, Card.Style style) {
        Card card = new Card(text(about))
                .setStyle(style)
                .setHeaderPrefix(new Initials(name))
                .setTitle(name)
                .setHeaderSuffix(money(amount));
        // The year as written, not as a number of thousands.
        Page.followLanguage(() -> card.setSubtitle(Text.CUSTOMER_SINCE.format(String.valueOf(since))));
        return card;
    }

    /** A report with its picture. */
    private static Card report(I18nString title, I18nString updated, Widget<?> picture, Card.MediaFit fit) {
        return new Card().setMedia(picture).setMediaFit(fit).setMediaAspectRatio(16f / 7f)
                .setTitle(title).setSubtitle(updated);
    }

    /** A paragraph from the top of the room the card gives it, however tall that is. */
    private static Label text(I18nString words) {
        return new Label(words).setWrap(true).setAlign(Label.HAlign.START, Label.VAlign.TOP);
    }

    /** A picture that fills the box the card gives it, cropped. */
    private static ImageView cover(Image picture) {
        return new ImageView(picture).setFit(ImageView.Fit.COVER);
    }

    /** A document to start, clicked as a whole. */
    private static Card template(I18nString title, I18nString about, String icon) {
        Card card = new Card().setMedia(Tabler.outline(icon)).setMediaFit(Card.MediaFit.STRETCH)
                .setMediaAspectRatio(3).setTitle(title).setSubtitle(about);
        card.onActivate(() -> Page.report(Text.NEW_DOCUMENT.format(title.get())));
        return card;
    }

    /** An invoice to look at, clicked above its actions, approved or held with them. */
    private static Card invoice(String number, String customer, double amount, I18nString due) {
        Button approve = new Button(Text.APPROVE);
        approve.onAction(() -> Page.report(Text.APPROVED.format(Text.INVOICE_NUMBER.format(number))));
        Button hold = new Button(Text.HOLD).setSecondary(true);
        hold.onAction(() -> Page.report(Text.HELD.format(Text.INVOICE_NUMBER.format(number))));
        Card card = new Card(text(due))
                .setSubtitle(customer)
                .setHeaderSuffix(money(amount))
                .setFooter(hold, approve);
        // The title is the card's name to a reader, in the language shown.
        Page.followLanguage(() -> card.setTitle(Text.INVOICE_NUMBER.format(number)));
        card.onActivate(() -> Page.report(Text.OPENED.format(Text.INVOICE_NUMBER.format(number))));
        return card;
    }

    /** An amount in dollars, written as the language shown writes money, strong. */
    private static Label money(double amount) {
        return Page.label(() -> DOLLARS.apply(amount)).setStrong(true);
    }

    /** A customer's initials on a disc of the accent: what an avatar would be, drawn here. */
    private static final class Initials extends Widget<Initials> {
        private final String initials;

        Initials(String name) {
            StringBuilder letters = new StringBuilder();
            for (String word : name.split(" ")) {
                if (!word.isEmpty() && letters.length() < 2) {
                    letters.appendCodePoint(word.codePointAt(0));
                }
            }
            initials = letters.toString();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(36, 36);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float side = Math.min(width(), height());
            canvas.fillCircle(width() / 2, height() / 2, side / 2,
                    theme.primary().withAlpha(theme.isDark() ? 0.26f : 0.16f));
            ShapedText text = shapeText(initials, theme.tokensFor(this).label().bold());
            canvas.drawText(text, (width() - text.metrics().width()) / 2,
                    (height() - text.metrics().height()) / 2 + text.metrics().ascent(), theme.primary());
        }

        /** The initials repeat the name beside them. */
        @Override
        protected boolean paintsDecoration() {
            return true;
        }
    }
}
