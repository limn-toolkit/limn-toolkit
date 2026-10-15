package limn.demo;

import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.ToolBar;
import limn.concurrent.Ui;
import limn.graphics.Icon;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.i18n.PluralString;
import limn.icons.tabler.Tabler;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * The controls every screen is made of, as the form they make: a new customer, with a toolbar
 * above it, its fields in two columns, the choices of its account, and the actions under it.
 */
final class BasicsScene {

    /** The longest delivery instruction a courier's label holds. */
    private static final int INSTRUCTIONS_LIMIT = 120;

    private BasicsScene() {
    }

    static Widget<?> content() {
        TextField name = new TextField().setText("Halcyon Foods").setLeadingIcon(Tabler.outline("building"));
        Column page = Page.column();
        page.add(toolbar());
        page.add(Page.section(Text.CUSTOMER));
        page.add(customer(name));
        page.add(Page.section(Text.ACCOUNT));
        page.add(account());
        page.add(Page.section(Text.ONBOARDING));
        page.add(onboarding());
        page.add(new SizedBox(SizedBox.UNSET, 8));
        page.add(actions(name));
        return Page.narrow(760, page);
    }

    /** The record's commands, named for whoever cannot see their icons, and a search at the end. */
    private static Widget<?> toolbar() {
        ToolBar bar = new ToolBar();
        bar.addItem(tool("user-plus", Text.NEW));
        bar.addItem(tool("copy", Text.DUPLICATE));
        bar.addItem(tool("printer", Text.PRINT));
        bar.addSeparator();
        bar.addItem(tool("file-export", Text.EXPORT));

        SearchField search = new SearchField();
        search.setPlaceholder(Text.SEARCH);
        search.setAccessibleName(Text.SEARCH);
        search.onSubmit(query -> Page.report(Text.SEARCHING.format(query)));

        Row row = Page.row(12, bar);
        row.add(Expanded.spacer(1));
        row.add(new SizedBox(260, SizedBox.UNSET, search));
        return row;
    }

    private static Button tool(String icon, I18nString name) {
        Button button = new Button("").setSecondary(true).setIcon(Tabler.outline(icon));
        button.setAccessibleName(name);
        button.setTooltip(name);
        button.onAction(() -> Page.report(name.get()));
        return button;
    }

    private static Widget<?> customer(TextField name) {
        TextField number = new TextField().setText("C-10482");
        number.setEnabled(false);

        TextField email = new TextField().setText("billing@halcyon").setLeadingIcon(Tabler.outline("mail"));
        Label emailLine = Page.message(I18nString.EMPTY, Theme::danger, null, email);
        Runnable checkEmail = () -> {
            String text = email.text().strip();
            int at = text.indexOf('@');
            boolean ok = text.isEmpty() || at > 0 && text.indexOf('.', at) > at + 1 && !text.endsWith(".");
            email.setError(!ok);
            say(emailLine, ok ? null : Text.EMAIL_INVALID, "alert-circle");
        };
        checkEmail.run();
        email.onChange(text -> checkEmail.run());

        TextField phone = new TextField().setText("+1 415").setLeadingIcon(Tabler.outline("phone"));
        phone.setValidation(TextField.Validation.INFO);
        Label phoneLine = Page.message(Text.PHONE_HINT, Theme::info, Tabler.outline("info-circle"), phone);

        TextField user = new TextField().setText("hal").setLeadingIcon(Tabler.outline("user"));
        Label userLine = Page.message(I18nString.EMPTY, Theme::warning, null, user);
        Runnable checkUser = () -> {
            boolean shortName = user.text().strip().length() < 4;
            user.setValidation(shortName ? TextField.Validation.WARNING : TextField.Validation.NONE);
            say(userLine, shortName ? Text.USERNAME_SHORT : null, "alert-triangle");
        };
        checkUser.run();
        user.onChange(text -> checkUser.run());

        TextField coupon = new TextField().setText("SPRING10");
        Label couponLine = Page.message(I18nString.EMPTY, Theme::success, null, coupon);
        coupon.setTrailingButton(Tabler.outline("rosette-discount-check"), Text.APPLY_COUPON, () -> {
            boolean known = coupon.text().strip().equalsIgnoreCase("SPRING10");
            coupon.setValidation(known ? TextField.Validation.SUCCESS : TextField.Validation.ERROR);
            Page.tint(couponLine, known ? Theme::success : Theme::danger);
            say(couponLine, known ? Text.COUPON_APPLIED : Text.COUPON_UNKNOWN,
                    known ? "circle-check" : "alert-circle");
        });
        coupon.onChange(text -> {
            coupon.setValidation(TextField.Validation.NONE);
            say(couponLine, null, null);
        });

        TextField serial = new TextField().setPlaceholder("AAA-0000-**").setMask("AAA-0000-**");
        serial.onChange(text -> {
            if (serial.isMaskComplete()) {
                Page.report(Text.SERIAL_COMPLETE.format(text));
            }
        });

        PasswordField password = new PasswordField();
        password.setText("harvest-2026");
        Checkbox reveal = new Checkbox(Checkbox.Variant.SWITCH, Text.REVEAL);
        reveal.onChange(password::setRevealed);
        Row passwordRow = Page.row(12, Expanded.of(password, 1), reveal);

        TextArea notes = new TextArea();
        notes.setText("""
                Prefers deliveries before 10 a.m. Dock 3 at the rear of the building.
                Invoices go to accounts payable, with the purchase order number on every page.
                Seasonal menu changes in March and September: expect larger orders of dry goods.
                Contact for complaints: the shift manager on duty.""");
        Checkbox wrap = new Checkbox(Checkbox.Variant.SWITCH, Text.WRAP);
        wrap.onChange(notes::setSoftWrap);
        Column notesBox = new Column();
        notesBox.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        notesBox.add(new SizedBox(SizedBox.UNSET, 104, notes));
        notesBox.add(Page.row(0, wrap));

        TextArea instructions = new TextArea().setSoftWrap(true);
        instructions.setText("Leave the boxes with the security desk if the loading dock is closed, "
                + "and call the number on the order so somebody comes down to sign for them.");
        Label instructionsLine = Page.message(I18nString.EMPTY, Theme::danger, null, instructions);
        Runnable checkInstructions = () -> {
            boolean tooLong = instructions.text().length() > INSTRUCTIONS_LIMIT;
            instructions.setError(tooLong);
            instructionsLine.setText(tooLong ? Text.INSTRUCTIONS_LONG.format(INSTRUCTIONS_LIMIT) : "");
            instructionsLine.setIcon(tooLong ? Tabler.outline("alert-circle") : null);
        };
        checkInstructions.run();
        instructions.onChange(text -> checkInstructions.run());

        Column form = new Column();
        form.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(Page.columns(Page.field(Text.NAME, name), Page.field(Text.NUMBER, number)));
        form.add(Page.columns(Page.field(Text.EMAIL, email, Page.withMessage(email, emailLine)),
                Page.field(Text.PHONE, phone, Page.withMessage(phone, phoneLine))));
        form.add(Page.columns(Page.field(Text.USERNAME, user, Page.withMessage(user, userLine)),
                Page.field(Text.COUPON, coupon, Page.withMessage(coupon, couponLine))));
        form.add(Page.columns(Page.field(Text.SERIAL, serial),
                Page.field(Text.PASSWORD, password, passwordRow)));
        form.add(Page.columns(Page.field(Text.NOTES, notes, notesBox),
                Page.field(Text.INSTRUCTIONS, instructions, Page.withMessage(
                        new SizedBox(SizedBox.UNSET, 104, instructions), instructionsLine))));
        return form;
    }

    /** Puts a message on its line with its glyph, or empties the line, glyph and all. */
    private static void say(Label line, I18nString text, String icon) {
        line.setText(text == null ? I18nString.EMPTY : text);
        line.setIcon(text == null ? null : Tabler.outline(icon));
    }

    private static Widget<?> account() {
        ButtonGroup type = new ButtonGroup();
        Row types = new Row();
        types.gap(20).crossAlignment(Flex.CrossAlignment.CENTER);
        for (I18nString name : new I18nString[] {Text.COMPANY, Text.PERSON, Text.PUBLIC_SECTOR}) {
            RadioButton radio = new RadioButton(name);
            radio.onChange(on -> {
                if (on) {
                    Page.report(Text.CHOSEN.format(Text.TYPE.get(), name.get()));
                }
            });
            type.add(radio);
            types.add(radio);
        }
        type.setSelectedIndex(0);
        type.members().get(2).setEnabled(false); // public bodies are opened by the back office

        SegmentedControl cycle = new SegmentedControl(Text.MONTHLY, Text.QUARTERLY, Text.YEARLY)
                .setSelectedIndex(0);
        cycle.onSelect(index -> Page.report(Text.CHOSEN.format(Text.CYCLE.get(), cycle.segment(index))));

        SegmentedControl prices = new SegmentedControl(Text.RETAIL, Text.WHOLESALE).setSelectedIndex(1);
        prices.setEnabled(false); // follows the customer type

        Column billing = group(Text.BILLING,
                new Checkbox(Text.EMAIL_INVOICES).setChecked(true),
                new Checkbox(Text.PAPER_INVOICES),
                disabled(new Checkbox(Text.TAX_EXEMPT).setChecked(true)));
        Column access = group(Text.ACCESS,
                new Checkbox(Checkbox.Variant.SWITCH, Text.ACTIVE).setChecked(true),
                new Checkbox(Checkbox.Variant.SWITCH, Text.PORTAL),
                disabled(new Checkbox(Checkbox.Variant.SWITCH, Text.CREDIT_HOLD)));

        Column form = new Column();
        form.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(Page.columns(group(Text.TYPE, types), Page.field(Text.CYCLE, cycle, Page.row(0, cycle))));
        form.add(Page.columns(billing, access));
        form.add(Page.columns(Page.field(Text.PRICES, prices, Page.row(0, prices)), new SizedBox(0, 0)));
        return form;
    }

    private static Checkbox disabled(Checkbox box) {
        box.setEnabled(false);
        return box;
    }

    /** A caption over a few controls that carry their own words, each on its line. */
    private static Column group(I18nString caption, Widget<?>... members) {
        Column column = new Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.START);
        column.add(new Label(caption));
        for (Widget<?> member : members) {
            column.add(member);
        }
        return column;
    }

    private static Widget<?> onboarding() {
        float done = 0.4f;
        ProgressBar profile = new ProgressBar().setProgress(done);
        Label percent = new Label(NumberFormats.percent(0).apply(done)).setMuted(true);
        ProgressBar credit = new ProgressBar();
        // Once the page is in a scene: the bar runs on the scene's clock, and has none before.
        Ui.postDelayed(() -> credit.setIndeterminate(true), 0);
        return Page.columns(
                Page.field(Text.PROFILE, profile, Page.row(12, Expanded.of(profile, 1), percent)),
                Page.field(Text.CREDIT, credit));
    }

    private static Widget<?> actions(TextField name) {
        Icon check = Tabler.outline("check");
        Button save = new Button(Text.SAVE).setIcon(check);
        save.onAction(() -> Page.report(Text.SAVED.format(name.text().strip())));
        Button saveNew = new Button(Text.SAVE_NEW).setSecondary(true);
        saveNew.onAction(() -> Page.report(Text.SAVED.format(name.text().strip())));
        Button delete = new Button(Text.DELETE).setSecondary(true).setIcon(Tabler.outline("trash"));
        delete.setEnabled(false); // nothing to delete before the first save
        return Page.row(12, save, saveNew, delete);
    }

    /** What this page says, ready for translation. */
    static final class Text {
        private Text() {
        }

        static final I18nString NEW = new I18nString("showcase.inputs.new", "New customer");
        static final I18nString DUPLICATE = new I18nString("showcase.inputs.duplicate", "Duplicate");
        static final I18nString PRINT = new I18nString("showcase.inputs.print", "Print");
        static final I18nString EXPORT = new I18nString("showcase.inputs.export", "Export");
        static final I18nString SEARCH = new I18nString("showcase.inputs.search", "Search customers");
        static final I18nString SEARCHING = new I18nString("showcase.inputs.searching", "Searching for “{0}”");

        static final I18nString CUSTOMER = new I18nString("showcase.inputs.customer", "Customer");
        static final I18nString NAME = new I18nString("showcase.inputs.name", "Name");
        static final I18nString NUMBER = new I18nString("showcase.inputs.number", "Customer number");
        static final I18nString EMAIL = new I18nString("showcase.inputs.email", "Billing email");
        static final I18nString EMAIL_INVALID =
                new I18nString("showcase.inputs.emailInvalid", "Enter an address like name@example.com.");
        static final I18nString PHONE = new I18nString("showcase.inputs.phone", "Phone");
        static final I18nString PHONE_HINT =
                new I18nString("showcase.inputs.phoneHint", "Include the area code.");
        static final I18nString USERNAME = new I18nString("showcase.inputs.username", "Portal user name");
        static final I18nString USERNAME_SHORT =
                new I18nString("showcase.inputs.usernameShort", "Use at least 4 characters.");
        static final I18nString COUPON = new I18nString("showcase.inputs.coupon", "Discount code");
        static final I18nString APPLY_COUPON = new I18nString("showcase.inputs.applyCoupon", "Apply code");
        static final I18nString COUPON_APPLIED =
                new I18nString("showcase.inputs.couponApplied", "10% off every order this quarter.");
        static final I18nString COUPON_UNKNOWN =
                new I18nString("showcase.inputs.couponUnknown", "This code does not exist.");
        static final I18nString SERIAL = new I18nString("showcase.inputs.serial", "Device serial number");
        static final I18nString SERIAL_COMPLETE =
                new I18nString("showcase.inputs.serialComplete", "Serial number {0} complete");
        static final I18nString PASSWORD = new I18nString("showcase.inputs.password", "Portal password");
        static final I18nString REVEAL = new I18nString("showcase.inputs.reveal", "Show");
        static final I18nString NOTES = new I18nString("showcase.inputs.notes", "Notes");
        static final I18nString WRAP = new I18nString("showcase.inputs.wrap", "Wrap lines");
        static final I18nString INSTRUCTIONS =
                new I18nString("showcase.inputs.instructions", "Delivery instructions");
        static final PluralString INSTRUCTIONS_LONG = PluralString.of("showcase.inputs.instructionsLong",
                "Keep it to {0} character.", "Keep it to {0} characters.");

        static final I18nString ACCOUNT = new I18nString("showcase.inputs.account", "Account");
        static final I18nString TYPE = new I18nString("showcase.inputs.type", "Customer type");
        static final I18nString COMPANY = new I18nString("showcase.inputs.company", "Company");
        static final I18nString PERSON = new I18nString("showcase.inputs.person", "Individual");
        static final I18nString PUBLIC_SECTOR = new I18nString("showcase.inputs.publicSector", "Public sector");
        static final I18nString CYCLE = new I18nString("showcase.inputs.cycle", "Billing cycle");
        static final I18nString MONTHLY = new I18nString("showcase.inputs.monthly", "Monthly");
        static final I18nString QUARTERLY = new I18nString("showcase.inputs.quarterly", "Quarterly");
        static final I18nString YEARLY = new I18nString("showcase.inputs.yearly", "Yearly");
        static final I18nString PRICES = new I18nString("showcase.inputs.prices", "Price list");
        static final I18nString RETAIL = new I18nString("showcase.inputs.retail", "Retail");
        static final I18nString WHOLESALE = new I18nString("showcase.inputs.wholesale", "Wholesale");
        static final I18nString BILLING = new I18nString("showcase.inputs.billing", "Invoices");
        static final I18nString EMAIL_INVOICES = new I18nString("showcase.inputs.emailInvoices", "Send by email");
        static final I18nString PAPER_INVOICES = new I18nString("showcase.inputs.paperInvoices", "Send on paper");
        static final I18nString TAX_EXEMPT = new I18nString("showcase.inputs.taxExempt", "Tax exempt");
        static final I18nString ACCESS = new I18nString("showcase.inputs.access", "Access");
        static final I18nString ACTIVE = new I18nString("showcase.inputs.active", "Active");
        static final I18nString PORTAL = new I18nString("showcase.inputs.portal", "Customer portal");
        static final I18nString CREDIT_HOLD = new I18nString("showcase.inputs.creditHold", "Credit hold");
        static final I18nString CHOSEN = new I18nString("showcase.inputs.chosen", "{0}: {1}");

        static final I18nString ONBOARDING = new I18nString("showcase.inputs.onboarding", "Onboarding");
        static final I18nString PROFILE = new I18nString("showcase.inputs.profile", "Profile complete");
        static final I18nString CREDIT = new I18nString("showcase.inputs.credit", "Credit check");

        static final I18nString SAVE = new I18nString("showcase.inputs.save", "Save customer");
        static final I18nString SAVE_NEW = new I18nString("showcase.inputs.saveNew", "Save and add another");
        static final I18nString DELETE = new I18nString("showcase.inputs.delete", "Delete");
        static final I18nString SAVED = new I18nString("showcase.inputs.saved", "Saved {0}");
    }
}
