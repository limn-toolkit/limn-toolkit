package limn.demo;

import limn.animation.ColorTransition;
import limn.animation.Easing;
import limn.animation.Motion;
import limn.animation.Transition;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.WindowStyle;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.icons.tabler.TablerDocument;
import limn.icons.tabler.TablerMath;
import limn.icons.tabler.TablerShapes;
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
import limn.scene.layout.SizedBox;

import java.util.Currency;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The Motion page: what an application animates. The scene's clock on top, which slows or stops
 * everything below it; an easing track; a list of orders that opens a row as it arrives and
 * closes it as it leaves; an invoice that unfolds its lines; a notification that slides in after
 * a save; a status whose colour changes with a {@link ColorTransition}; animated icons; and a
 * window that fades in and out. Everything is driven by the toolkit's {@code limn.animation}
 * classes and the scene's tickers; nothing hand-rolls a clock.
 */
final class AnimationsScene {

    /** What the page says, ready for translation. */
    static final class Text {
        static final I18nString SCENE_TIME = new I18nString("showcase.motion.sceneTime", "Scene time");
        static final I18nString PAUSED = new I18nString("showcase.motion.paused", "Paused");
        static final I18nString EASING = new I18nString("showcase.motion.easing", "Easing");
        static final I18nString LINEAR = new I18nString("showcase.motion.linear", "Linear");
        static final I18nString EASE_OUT = new I18nString("showcase.motion.easeOut", "Ease out");
        static final I18nString EASE_IN_OUT = new I18nString("showcase.motion.easeInOut", "Ease in and out");
        static final I18nString BOUNCE = new I18nString("showcase.motion.bounce", "Bounce");
        static final I18nString RUBBER = new I18nString("showcase.motion.rubber", "Rubber");
        static final I18nString FAST = new I18nString("showcase.motion.fast", "Fast");
        static final I18nString NORMAL = new I18nString("showcase.motion.normal", "Normal");
        static final I18nString SLOW = new I18nString("showcase.motion.slow", "Slow");
        static final I18nString ANIMATE = new I18nString("showcase.motion.animate", "Animate");
        static final I18nString REPEAT = new I18nString("showcase.motion.repeat", "Repeat");
        static final I18nString ORDERS = new I18nString("showcase.motion.orders", "Orders");
        static final I18nString NEW_ORDER = new I18nString("showcase.motion.newOrder", "New order");
        static final I18nString ARCHIVE = new I18nString("showcase.motion.archive", "Archive");
        static final I18nString ORDER_ADDED = new I18nString("showcase.motion.orderAdded", "Order {0} added");
        static final I18nString ORDER_ARCHIVED =
                new I18nString("showcase.motion.orderArchived", "Order {0} archived");
        static final I18nString INVOICE = new I18nString("showcase.motion.invoice", "Invoice");
        static final I18nString INVOICE_NUMBER = new I18nString("showcase.motion.invoiceNumber", "Invoice {0}");
        static final I18nString DETAILS = new I18nString("showcase.motion.details", "Details");
        static final I18nString HIDE_DETAILS = new I18nString("showcase.motion.hideDetails", "Hide details");
        static final I18nString TOTAL = new I18nString("showcase.motion.total", "Total");
        static final I18nString NOTIFICATION = new I18nString("showcase.motion.notification", "Notification");
        static final I18nString SAVE = new I18nString("showcase.motion.save", "Save");
        static final I18nString SAVED = new I18nString("showcase.motion.saved", "Changes to {0} saved");
        static final I18nString CUSTOMER_SINCE =
                new I18nString("showcase.motion.customerSince", "Customer since {0}");
        static final I18nString STATUS = new I18nString("showcase.motion.status", "Status");
        static final I18nString PENDING = new I18nString("showcase.motion.pending", "Pending");
        static final I18nString APPROVED = new I18nString("showcase.motion.approved", "Approved");
        static final I18nString SHIPPED = new I18nString("showcase.motion.shipped", "Shipped");
        static final I18nString DELIVERED = new I18nString("showcase.motion.delivered", "Delivered");
        static final I18nString ICONS = new I18nString("showcase.motion.icons", "Animated icons");
        static final I18nString SYNCING = new I18nString("showcase.motion.syncing", "Syncing");
        static final I18nString CONNECTING = new I18nString("showcase.motion.connecting", "Connecting");
        static final I18nString FAVOURITE = new I18nString("showcase.motion.favourite", "Favourite");
        static final I18nString WINDOW = new I18nString("showcase.motion.window", "Window fade");
        static final I18nString OPEN_WINDOW = new I18nString("showcase.motion.openWindow", "Open window");
        static final I18nString REMINDER = new I18nString("showcase.motion.reminder", "Reminder");
        static final I18nString REMINDER_TEXT = new I18nString("showcase.motion.reminderText",
                "Call the supplier about order {0} before 3 pm.");
        static final I18nString CLOSE = new I18nString("showcase.motion.close", "Close");

        private Text() {
        }
    }

    /** An order of the list: a number, a customer, an amount in dollars. */
    private record Order(String number, String customer, double amount) {
    }

    /** The orders the list starts with, and the ones "New order" brings in turn. */
    private static final List<Order> ORDERS = List.of(
            new Order("SO-10481", "Harbor Lane Supplies", 1240.00),
            new Order("SO-10482", "Meadowbrook Clinic", 386.50),
            new Order("SO-10483", "Northgate Builders", 4915.20),
            new Order("SO-10484", "Pine Street Bakery", 212.75),
            new Order("SO-10485", "Riverside Dental", 1078.00),
            new Order("SO-10486", "Summit Print Shop", 649.90),
            new Order("SO-10487", "Willow Creek Farms", 2310.40),
            new Order("SO-10488", "Lakeview Logistics", 8840.00));

    private static final int FIRST_SHOWN = 3;

    private static final DoubleFunction<String> DOLLARS = NumberFormats.currency(Currency.getInstance("USD"));

    private AnimationsScene() {
    }

    /** Standalone {@code --scene animations}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        Column column = Page.column();

        // --- The scene's clock: the page's own toolbar, since it governs everything below it.
        SegmentedControl speed = new SegmentedControl(times(0.25), times(0.5), times(1), times(2));
        double[] scales = {0.25, 0.5, 1, 2};
        speed.setSelectedIndex(2);
        speed.setAccessibleName(Text.SCENE_TIME);
        speed.onSelect(i -> {
            Scene s = speed.scene();
            if (s != null) {
                s.setTimeScale(scales[i]);
            }
        });
        Checkbox pause = new Checkbox(Checkbox.Variant.SWITCH, Text.PAUSED);
        pause.onChange(on -> {
            Scene s = pause.scene();
            if (s != null) {
                s.setPaused(on);
            }
        });
        Row clock = Page.row(12, new Label(Text.SCENE_TIME).setMuted(true), speed, pause);
        clock.add(Expanded.spacer(1));
        column.add(clock);

        // --- Easing ------------------------------------------------------------------------
        column.add(Page.section(Text.EASING));
        EasingTrack track = new EasingTrack();
        column.add(Page.narrow(640, track));

        Easing[] curves = {Easing.LINEAR, Easing.EASE_OUT, Easing.EASE_IN_OUT, Easing.BOUNCE, Easing.RUBBER};
        ComboBox<I18nString> easing = ComboBox.localized(List.of(
                Text.LINEAR, Text.EASE_OUT, Text.EASE_IN_OUT, Text.BOUNCE, Text.RUBBER));
        easing.setAccessibleName(Text.EASING);
        easing.setSelectedIndex(1);
        track.setEasing(curves[1]);
        easing.onSelect(() -> track.setEasing(curves[easing.selectedIndex()]));

        double[] durations = {0.4, 0.8, 1.5};
        ComboBox<I18nString> timing = ComboBox.localized(List.of(Text.FAST, Text.NORMAL, Text.SLOW));
        timing.setSelectedIndex(1);
        timing.onSelect(() -> track.setDuration(durations[timing.selectedIndex()]));

        Button animate = new Button(Text.ANIMATE).setSecondary(true).onAction(track::play);
        // On from the start: a dot that keeps crossing the rail shows the curve without a press.
        Checkbox repeat = new Checkbox(Checkbox.Variant.SWITCH, Text.REPEAT).setChecked(true);
        repeat.onChange(track::setRepeat);
        track.setRepeat(true);
        Row controls = Page.row(12, new SizedBox(170, SizedBox.UNSET, easing),
                new SizedBox(130, SizedBox.UNSET, timing), animate, repeat);
        controls.add(Expanded.spacer(1));
        column.add(controls);

        // --- A list whose rows open as they arrive and close as they leave -----------------
        column.add(Page.section(Text.ORDERS));
        column.add(Page.narrow(640, orderList()));

        // --- An invoice that unfolds its lines ----------------------------------------------
        column.add(Page.section(Text.INVOICE));
        column.add(Page.narrow(640, invoiceCard()));

        // --- A notification that slides in after a save -------------------------------------
        column.add(Page.section(Text.NOTIFICATION));
        column.add(Page.narrow(640, new ToastHost()));

        // --- A status whose colour moves with it ---------------------------------------------
        column.add(Page.section(Text.STATUS));
        column.add(Page.row(0, new StatusChip()));

        // --- Animated icons --------------------------------------------------------------------
        column.add(Page.section(Text.ICONS));
        column.add(animatedIcons());

        // --- A window that fades in and out --------------------------------------------------
        column.add(Page.section(Text.WINDOW));
        Button openWindow = new Button(Text.OPEN_WINDOW).setSecondary(true);
        openWindow.onAction(() -> openFloatingWindow(openWindow));
        column.add(Page.row(0, openWindow));

        return column; // the page scrolls it
    }


    /** A speed as the segment reads it: the multiplier in the language's digits, then "×". */
    private static I18nString times(double value) {
        return I18nString.literal(NumberFormats.number(2).apply(value) + "×");
    }

    // ------------------------------------------------------------------ the order list

    /** The orders, a "New order" button above them, an "Archive" on each. */
    private static Widget<?> orderList() {
        Column rows = new Column();
        rows.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        int[] next = {FIRST_SHOWN};
        for (int i = FIRST_SHOWN - 1; i >= 0; i--) {
            rows.add(orderRow(rows, ORDERS.get(i), true));
        }
        Button add = new Button(Text.NEW_ORDER).setSecondary(true).setIcon(TablerMath.PLUS.icon());
        add.onAction(() -> {
            Order order = ORDERS.get(next[0] % ORDERS.size());
            next[0]++;
            Unfold row = orderRow(rows, order, false);
            rows.add(0, row);
            row.animateTo(1, null);
            Page.report(Text.ORDER_ADDED.format(order.number()));
        });
        Column list = new Column();
        list.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        list.add(Page.row(0, add));
        list.add(rows);
        return list;
    }

    private static Unfold orderRow(Column rows, Order order, boolean open) {
        Row line = Page.row(12,
                new SizedBox(84, SizedBox.UNSET, new Label(order.number()).setStrong(true)),
                Expanded.of(new Label(order.customer()), 1),
                Page.label(() -> DOLLARS.apply(order.amount())));
        Button archive = new Button(Text.ARCHIVE).setSecondary(true).setIcon(TablerDocument.ARCHIVE.icon());
        archive.setControlSize(limn.scene.ControlSize.SMALL);
        line.add(archive);
        Unfold row = new Unfold(Page.card(line), open);
        archive.onAction(() -> {
            archive.setEnabled(false);
            row.animateTo(0, () -> rows.remove(row));
            Page.report(Text.ORDER_ARCHIVED.format(order.number()));
        });
        return row;
    }

    // ------------------------------------------------------------------ the invoice card

    /** One invoice: its head always, its lines and total unfolding under it. */
    private static Widget<?> invoiceCard() {
        Column lines = new Column();
        lines.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        String[][] items = {
                {"Office chair, mesh back", "4", "1196.00"},
                {"Standing desk, 140 cm", "2", "1380.00"},
                {"Monitor arm, dual", "4", "356.00"},
        };
        double total = 0;
        for (String[] item : items) {
            double amount = Double.parseDouble(item[2]);
            total += amount;
            lines.add(Page.row(12, Expanded.of(new Label(item[0]), 1),
                    new SizedBox(40, SizedBox.UNSET, new Label("× " + item[1]).setMuted(true)),
                    new SizedBox(110, SizedBox.UNSET, Page.label(() -> DOLLARS.apply(amount))
                            .setAlign(Label.HAlign.END, Label.VAlign.CENTER))));
        }
        double sum = total;
        lines.add(limn.components.Separator.horizontal());
        lines.add(Page.row(12, Expanded.of(new Label(Text.TOTAL).setStrong(true), 1),
                new SizedBox(110, SizedBox.UNSET, Page.label(() -> DOLLARS.apply(sum)).setStrong(true)
                        .setAlign(Label.HAlign.END, Label.VAlign.CENTER))));
        Unfold body = new Unfold(new Padding(new Insets(12, 0, 0, 0), lines), false);

        Button toggle = new Button(Text.DETAILS).setSecondary(true);
        toggle.setControlSize(limn.scene.ControlSize.SMALL);
        toggle.onAction(() -> {
            boolean opening = body.target() < 0.5f;
            body.animateTo(opening ? 1 : 0, null);
            toggle.setText(opening ? Text.HIDE_DETAILS : Text.DETAILS);
        });
        Column head = new Column();
        head.gap(2);
        head.add(Page.label(() -> Text.INVOICE_NUMBER.format("INV-2041")).setStrong(true));
        head.add(new Label("Harbor Lane Supplies").setMuted(true));
        Row top = Page.row(12, head);
        top.add(Expanded.spacer(1));
        top.add(Page.label(() -> DOLLARS.apply(sum)));
        top.add(toggle);

        Column card = new Column();
        card.crossAlignment(Flex.CrossAlignment.STRETCH);
        card.add(top);
        card.add(body);
        return Page.card(card);
    }

    // ------------------------------------------------------------------ animated icons

    private static final String SPINNER = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"
            + "<g><circle cx='12' cy='12' r='9' fill='none' stroke='currentColor' stroke-width='2.5' "
            + "stroke-dasharray='42 15' stroke-linecap='round'/>"
            + "<animateTransform attributeName='transform' type='rotate' from='0 12 12' to='360 12 12' "
            + "dur='1s' repeatCount='indefinite'/></g></svg>";

    private static final String HEART = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'>"
            + "<style>@keyframes beat{0%,100%{transform:scale(1)}50%{transform:scale(1.2)}}"
            + ".heart{transform-origin:32px 34px;animation:beat 0.9s ease-in-out infinite}</style>"
            + "<path class='heart' fill='#E5484D' d='M32 54 C10 38 6 26 12 18 C18 10 28 12 32 20 "
            + "C36 12 46 10 52 18 C58 26 54 38 32 54Z'/></svg>";

    /** An SVG spinner animated in SMIL, a WebP as a button's icon, an SVG animated in CSS. */
    private static Widget<?> animatedIcons() {
        Row row = Page.row(16,
                new Button(Text.SYNCING).setSecondary(true).setIcon(limn.graphics.SvgIcon.of(SPINNER)),
                new Button(Text.CONNECTING).setSecondary(true)
                        .setIcon(limn.graphics.BitmapIcon.picture(
                                limn.graphics.Images.fromResource("/limn/demo/images/orbit.webp"))),
                new ImageView(limn.graphics.SvgIcon.of(HEART)).setPreferredSize(40, 40)
                        .setAccessibleName(Text.FAVOURITE));
        row.add(Expanded.spacer(1));
        return row;
    }

    // ------------------------------------------------------------------ the floating window

    /** Opens a plain native window centred over the host that fades in, and fades out on close. */
    private static void openFloatingWindow(Widget<?> anchor) {
        Scene host = anchor.scene();
        if (host == null || host.window() == null) {
            return; // headless / not shown
        }
        NativeWindow parent = host.window();
        int w = 340;
        int h = 170;

        Column body = new Column();
        body.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(new Label(Text.REMINDER).setRole(Label.Role.TITLE));
        body.add(new Label(Text.REMINDER_TEXT.format("SO-10482")).setWrap(true));
        Button close = new Button(Text.CLOSE);
        Row footer = new Row();
        footer.mainAlignment(Flex.MainAlignment.END);
        footer.add(close);
        body.add(footer);

        Scene scene = new Scene(new FloatingPanel(new Padding(Insets.all(18), body)));
        scene.setBackground(Color.TRANSPARENT);
        // Owned by the host, so it closes when the host closes, and centred over it.
        NativeWindow win = parent.backend().createWindow(WindowConfig.styled(
                Text.REMINDER.get(), w, h, WindowStyle.UNDECORATED_TRANSLUCENT, true, true)
                .ownedBy(parent).atInOwner((parent.logicalWidth() - w) / 2f, (parent.logicalHeight() - h) / 2f));
        scene.bind(win);

        close.onAction(() -> scene.fadeWindowOut(Theme.current().animWindow, win::requestClose));

        scene.fadeWindowIn(Theme.current().animWindow); // transparent before it maps
        win.show();
        win.requestFrame();
    }

    // ------------------------------------------------------------- widgets

    /**
     * A child shown from none of its height to all of it, on the scene's clock: a row arriving
     * opens, a row leaving closes, and what is below moves with it. The child keeps its full size
     * throughout and is clipped and faded, so nothing inside it reflows per frame.
     */
    private static final class Unfold extends Widget<Unfold> {
        private static final double SECONDS = 0.3;
        private final Widget<?> child;
        private float shown;
        private float from;
        private float to;
        private double elapsed;
        private boolean running;
        private Runnable done;

        Unfold(Widget<?> child, boolean open) {
            this.child = child;
            this.shown = this.to = open ? 1 : 0;
            add(child);
        }

        float target() {
            return to;
        }

        /** Opens (1) or closes (0); {@code onDone} runs once it has arrived, or never if null. */
        void animateTo(float target, Runnable onDone) {
            done = onDone;
            if (scene() == null || Motion.reduced()) {
                shown = from = to = target;
                markNeedsLayout();
                finish();
                return;
            }
            from = shown;
            to = target;
            elapsed = 0;
            if (running) {
                return;
            }
            running = true;
            scene().addTicker(dt -> {
                // The page, not this row: a row arriving has no height yet, so it is not showing.
                Widget<?> page = parent() != null ? parent() : this;
                if (scene() == null || !page.isShowing()) {
                    shown = to; // a hidden page does not keep a row half open
                    running = false;
                    markNeedsLayout();
                    finish();
                    return false;
                }
                elapsed += dt;
                float t = (float) Math.min(1, elapsed / SECONDS);
                shown = from + (to - from) * Easing.EASE_IN_OUT.apply(t);
                markNeedsLayout();
                invalidate();
                if (t >= 1) {
                    running = false;
                    finish();
                    return false;
                }
                return true;
            });
        }

        private void finish() {
            Runnable then = done;
            done = null;
            if (then != null) {
                then.run();
            }
        }

        private Size full(float width) {
            return child.measure(new Constraints(width, width, 0, Constraints.UNBOUNDED_LIMIT));
        }

        @Override
        protected Size onMeasure(Constraints c) {
            float width = c.hasBoundedWidth() ? c.maxWidth() : child.measure(c).width();
            return c.constrain(width, full(width).height() * shown);
        }

        @Override
        protected void onLayout() {
            Size whole = full(width());
            child.layoutBox(0, 0, width(), whole.height());
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            if (shown <= 0) {
                return;
            }
            canvas.save();
            try {
                canvas.clipRect(0, 0, width(), height());
                canvas.setOpacity(canvas.opacity() * Math.min(1, shown * 1.5f));
                super.paintChildren(canvas);
            } finally {
                canvas.restore();
            }
        }
    }

    /** A dot the user sends along a drawn rail with a chosen easing and timing. */
    private static final class EasingTrack extends Widget<EasingTrack> {
        private final Transition pos =
                new Transition(this).duration(0.8).easing(Easing.EASE_OUT).sceneTime(true);

        EasingTrack() {
            setAccessibleIgnored(true); // the controls under it say what it does
        }

        void setEasing(Easing easing) {
            pos.easing(easing);
            play();
        }

        void setDuration(double seconds) {
            pos.duration(seconds);
        }

        void setRepeat(boolean value) {
            pos.repeat(value);
            play();
        }

        @Override
        protected void onAttached() {
            super.onAttached();
            if (pos.repeats()) {
                play(); // set before the page was in a scene, where a transition only jumps
            }
        }

        /**
         * Runs the whole rail, from the far end: a run started from wherever the dot happened to be
         * (a curve changed mid-run) showed the curve over part of the rail, and repeating then
         * swung between that point and the end for good.
         */
        void play() {
            float target = pos.target() >= 0.5f ? 0 : 1;
            pos.snap(1 - target);
            pos.to(target);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 480, 48);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float pad = 14;
            float cy = height() / 2;
            float start = pad;
            float end = width() - pad;
            boolean rtl = layoutDirection().isRightToLeft();
            canvas.fillRoundRect(start, cy - 2, end - start, 4, 2, theme.outline());
            canvas.fillCircle(start, cy, 5, theme.textMuted());
            canvas.fillCircle(end, cy, 5, theme.textMuted());
            // Rubber and bounce overshoot the rail on purpose: the dot leaves it and comes back.
            float along = pos.value();
            float cx = rtl ? end - along * (end - start) : start + along * (end - start);
            canvas.fillCircle(cx, cy, 11, theme.primary());
        }
    }

    /**
     * A notification area: the head of an invoice and its Save; saving slides a notification in
     * at the end corner, holds it, and slides it out.
     */
    private static final class ToastHost extends Widget<ToastHost> {
        private static final float HEIGHT = 128;
        private static final float MARGIN = 12;
        private static final double HOLD_SECONDS = 2.5;
        private static final String CUSTOMER = "Meadowbrook Clinic";
        private final Widget<?> content;
        private final Label message = new Label("");
        private final Widget<?> toast;
        private final Transition slide = new Transition(this).duration(0.28).easing(Easing.EASE_OUT)
                .sceneTime(true);
        private double held = -1;

        ToastHost() {
            Button save = new Button(Text.SAVE);
            save.onAction(this::saved);
            Column head = new Column();
            head.gap(2);
            head.add(new Label(CUSTOMER).setStrong(true));
            head.add(Page.label(() -> Text.CUSTOMER_SINCE.format("2019")).setMuted(true));
            Row row = Page.row(12, head);
            row.add(Expanded.spacer(1));
            row.add(save);
            content = new Padding(Insets.all(16), row);
            message.setIcon(TablerShapes.CIRCLE_CHECK.icon());
            limn.components.Surface plate = new limn.components.Surface(
                    new Padding(Insets.symmetric(10, 14), message));
            plate.setElevation(limn.components.Elevation.OVERLAY);
            toast = plate;
            toast.setVisible(false);
            add(content);
            add(toast);
        }

        private void saved() {
            message.setText(Text.SAVED.format(CUSTOMER));
            toast.setVisible(true);
            boolean arming = held < 0;
            held = 0;
            slide.to(1);
            if (!arming || scene() == null) {
                return; // a second save while it shows only restarts the hold
            }
            scene().addTicker(dt -> {
                if (!isShowing()) {
                    held = -1;
                    slide.snap(0);
                    toast.setVisible(false);
                    return false;
                }
                if (held >= 0 && !slide.isAnimating()) {
                    held += dt;
                    if (held >= HOLD_SECONDS) {
                        held = -1;
                        slide.to(0);
                    }
                }
                if (held < 0 && !slide.isAnimating()) {
                    toast.setVisible(false); // gone: out of the reader's tree as well
                    return false;
                }
                return true;
            });
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 480, HEIGHT);
        }

        @Override
        protected void onLayout() {
            Size top = content.measure(Constraints.loose(width(), height()));
            content.layoutBox(0, 0, width(), top.height());
            // At rest; the slide is an offset applied when it is painted.
            Size plate = toast.measure(Constraints.loose(Math.max(0, width() - 2 * MARGIN), height()));
            float x = layoutDirection().isRightToLeft() ? MARGIN : width() - plate.width() - MARGIN;
            toast.layoutBox(x, height() - plate.height() - MARGIN, plate.width(), plate.height());
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surface(), 1, theme.outline());
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            paintChild(canvas, content, 0);
            if (toast.isVisible()) {
                canvas.save();
                try {
                    canvas.clipRect(0, 0, width(), height());
                    float v = slide.value();
                    canvas.setOpacity(canvas.opacity() * v);
                    paintChild(canvas, toast, (1 - v) * (toast.height() + MARGIN));
                } finally {
                    canvas.restore();
                }
            }
        }

        private static void paintChild(Canvas canvas, Widget<?> child, float offsetY) {
            canvas.save();
            try {
                canvas.translate(child.x(), child.y() + offsetY);
                child.paintWidget(canvas);
            } finally {
                canvas.restore();
            }
        }
    }

    /** A status chip whose colour follows its status with a {@link ColorTransition}. */
    private static final class StatusChip extends Widget<StatusChip> {
        private static final I18nString[] NAMES = {Text.PENDING, Text.APPROVED, Text.SHIPPED, Text.DELIVERED};
        private static final Color[] PALETTE = {
                Color.rgb(0xB7791F), Color.rgb(0x2B6CB0), Color.rgb(0x6B46C1), Color.rgb(0x2F855A),
        };
        private final ColorTransition fill =
                new ColorTransition(this, PALETTE[0]).duration(0.6).easing(Easing.EASE_IN_OUT)
                        .sceneTime(true);
        private int index;
        private double since;
        private boolean started;

        StatusChip() {
            setAccessibleRole(limn.accessibility.Accessible.Role.LABEL);
            setAccessibleName(NAMES[0]);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            float widest = 0;
            Font font = Theme.of(this).tokensFor(this).label();
            for (I18nString name : NAMES) {
                widest = Math.max(widest, textRuler().measure(name.get(), font).width());
            }
            return c.constrain(widest + 40, 30);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (!started && scene() != null) {
                started = true;
                scene().addTicker(dt -> {
                    if (!isShowing()) {
                        started = false;
                        return false;
                    }
                    since += dt;
                    if (since >= 1.4 && !fill.isAnimating()) {
                        since = 0;
                        index = (index + 1) % PALETTE.length;
                        fill.to(PALETTE[index]);
                        setAccessibleName(NAMES[index]);
                        invalidate();
                    }
                    return true;
                });
            }
            Theme theme = Theme.of(this);
            canvas.fillRoundRect(0, 0, width(), height(), height() / 2, fill.value());
            Font font = theme.tokensFor(this).label();
            String text = NAMES[index].get();
            var m = textRuler().measure(text, font);
            canvas.drawText(text, (width() - m.width()) / 2, (height() - m.height()) / 2 + m.ascent(),
                    font, Color.WHITE);
        }
    }

    /** Rounded translucent card that hosts the floating window's content. */
    private static final class FloatingPanel extends Widget<FloatingPanel> {
        private final Widget<?> child;

        FloatingPanel(Widget<?> child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusLarge(),
                    theme.surface().withAlpha(0.98f), 1, theme.outline());
        }
    }
}
