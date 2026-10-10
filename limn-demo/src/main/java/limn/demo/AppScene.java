package limn.demo;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.ScrollView;
import limn.components.SearchField;
import limn.components.SelectionMode;
import limn.components.Separator;
import limn.components.SplitPane;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.date.DatePicker;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.i18n.I18n;
import limn.scene.Change;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the demo opens on: a business application, and beside it the way to everything else.
 *
 * <p>The window is a menu bar, a band with the four switches that reach the whole process
 * (language, direction, size, theme), a navigation down the leading side and the page it
 * chose. The first page is a screen of orders: a table to read, sort and filter, and beside it
 * the order the cursor is on, in a form. It is there because that is what the toolkit is for,
 * and a visitor should meet an application before a catalogue. Under it the navigation lists
 * the components by what they are for, and last the media and the 3D viewport.
 *
 * <p>A page is built the first time it is chosen and kept: the video page decodes, the 3D page
 * holds a scene, and building all of them to show one would make the first frame wait for
 * every other.
 */
final class AppScene {

    /** An order: what the first page lists and edits. Mutable, as a record being edited is. */
    static final class Order {
        final int number;
        String customer;
        LocalDate placed;
        LocalDate due;
        Status status;
        double total;
        String notes;

        Order(int number, String customer, LocalDate placed, LocalDate due, Status status, double total,
                String notes) {
            this.number = number;
            this.customer = customer;
            this.placed = placed;
            this.due = due;
            this.status = status;
            this.total = total;
            this.notes = notes;
        }
    }

    /** Where an order stands. */
    enum Status {
        OPEN("Open"), SHIPPED("Shipped"), PAID("Paid"), OVERDUE("Overdue"), CANCELLED("Cancelled");

        final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A place the navigation leads to: a page, or a heading over some. */
    private static final class Place {
        final String name;
        final List<Place> under = new ArrayList<>();
        final Supplier<Widget<?>> page;

        Place(String name, Supplier<Widget<?>> page) {
            this.name = name;
            this.page = page;
        }

        Place(String name, Place... under) {
            this.name = name;
            this.page = null;
            this.under.addAll(List.of(under));
        }
    }

    private AppScene() {
    }

    /** Standalone {@code --scene app}. */
    static Scene create() {
        MenuBar menuBar = new MenuBar();
        Label status = new Label("Ready.").setMuted(true);
        PerfFooter performance = new PerfFooter();
        performance.setVisible(false);

        // ---- the pages, by what they are for
        Place orders = new Place("Orders", () -> ordersPage(status));
        Place components = new Place("Components",
                new Place("Buttons and inputs", () -> scrolling(ControlsScene.content())),
                new Place("Combo boxes", () -> scrolling(CombosScene.content())),
                new Place("Sliders and more", () -> scrolling(NewControlsScene.content())),
                new Place("Dates", () -> scrolling(DatesScene.content())),
                new Place("Lists", () -> ListScene.buildList(status::setText)),
                new Place("Tables", TableScene::content),
                new Place("Trees", () -> scrolling(TreeScene.content())),
                new Place("Drag and drop", () -> scrolling(PickerScene.content())),
                new Place("Clipboard", () -> scrolling(TransferScene.content())),
                new Place("Charts", () -> scrolling(ChartsScene.content())),
                new Place("Text and scripts", () -> scrolling(BidiScene.tabContent())),
                new Place("Icons", IconsScene::content),
                new Place("Files", FilesScene::content),
                new Place("Colours", () -> scrolling(ColorPickerScene.content())),
                new Place("Split panes", SplitPaneScene::content),
                new Place("Motion", () -> scrolling(AnimationsScene.content())));
        Place media = new Place("Media and 3D",
                new Place("Video", () -> scrolling(VideoScene.pageContent())),
                new Place("3D viewport", () -> scrolling(Viewport3DScene.tabContent())),
                new Place("Sound", () -> scrolling(AudioScene.content())),
                new Place("Sprites", () -> scrolling(SpritesScene.content())),
                new Place("Glass", () -> scrolling(GlassScene.content())));
        List<Place> top = List.of(orders, components, media);

        Tree<Place> navigation = new Tree<>(new Tree.Model<Place>() {
            @Override
            public List<Place> roots() {
                return top;
            }

            @Override
            public List<Place> children(Place place) {
                return place.under;
            }

            @Override
            public Widget<?> cellFor(Place place) {
                Label label = new Label(place.name);
                if (place.page == null) {
                    label.setStrong(true);
                }
                return new Padding(Insets.symmetric(6, 6), label);
            }

            @Override
            public limn.i18n.I18nString nameOf(Place place) {
                return limn.i18n.I18nString.literal(place.name);
            }
        });
        navigation.setAccessibleName("Pages");
        navigation.expand(components);
        navigation.expand(media);

        // ---- the page in view: built the first time it is chosen, and kept
        Map<Place, Widget<?>> built = new HashMap<>();
        PageHolder holder = new PageHolder();
        navigation.observeChanges((widget, change) -> {
            if (change.aspect() != Change.Aspect.SELECTION) {
                return;
            }
            List<Place> chosen = navigation.selectedNodes();
            if (chosen.isEmpty() || chosen.get(0).page == null) {
                return; // a heading: the page in view stays
            }
            Place place = chosen.get(0);
            holder.show(built.computeIfAbsent(place, p -> p.page.get()));
            status.setText(place.name);
        });
        navigation.setSelected(orders);

        // ---- the band: the title and the four switches that reach the whole process
        ComboBox<String> language = new ComboBox<>(Languages.NAMES);
        language.setSelectedIndex(Languages.indexOf(I18n.locale()));
        language.onSelect(() -> I18n.setLocale(Languages.LOCALES.get(language.selectedIndex())));
        language.setAccessibleName("Language");
        ComboBox<String> direction = new ComboBox<>(
                Arrays.stream(LayoutDirection.values()).map(Enum::name).toList());
        direction.setSelectedIndex(LayoutDirection.processDefault().ordinal());
        direction.onSelect(() -> LayoutDirection.setProcessDefault(
                LayoutDirection.values()[direction.selectedIndex()]));
        direction.setAccessibleName("Direction");
        ComboBox<String> size = new ComboBox<>(Arrays.stream(ControlSize.values()).map(Enum::name).toList());
        size.setSelectedIndex(ControlSize.processDefault().ordinal());
        size.onSelect(() -> ControlSize.setProcessDefault(ControlSize.values()[size.selectedIndex()]));
        size.setAccessibleName("Size");
        ComboBox<limn.i18n.I18nString> theme = ComboBox.localized(
                Theme.builtins().stream().map(Theme::displayName).toList());
        theme.setSelectedIndex(Math.max(0, Theme.builtins().indexOf(Theme.current())));
        theme.setAccessibleName("Theme");

        Row band = new Row();
        band.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        band.add(new Label("Limn Showcase").setRole(Label.Role.TITLE));
        band.add(Expanded.spacer(1));
        band.add(language);
        band.add(direction);
        band.add(size);
        band.add(theme);

        SplitPane body = SplitPane.horizontal(navigation, holder);
        body.setRatio(0.17f);
        body.setMinimums(160, 420);

        limn.scene.layout.Column page = new limn.scene.layout.Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(menuBar);
        page.add(band);
        page.add(Expanded.of(body, 1));
        page.add(status);
        page.add(performance);

        Scene scene = new Scene(new Padding(Insets.all(16), page));
        scene.setBackground(Theme.current().background());
        theme.onSelect(() -> Theme.setCurrent(Theme.builtins().get(theme.selectedIndex())));
        // Whoever changes the theme, this picker or a launch option or the system: the picker
        // says which it is, and the colour cleared behind the widgets is that palette's.
        Theme.observeChanges(() -> {
            int now = Theme.builtins().indexOf(Theme.current());
            if (now >= 0 && now != theme.selectedIndex()) {
                theme.setSelectedIndex(now);
            }
            scene.setBackground(Theme.current().background());
        });

        menuBar.addMenu("File", new Menu()
                .addItem("New order", () -> status.setText("A new order would open here."))
                .addSeparator()
                .addItem("Quit", () -> {
                    if (scene.window() != null) {
                        scene.window().requestClose();
                    }
                }));
        menuBar.addMenu("View", new Menu()
                .addCheck("Performance", false, on -> {
                    performance.setVisible(on);
                    status.setText(on ? "Frame times, memory and what each frame repainted." : "Ready.");
                })
                .addSeparator()
                .addCheck("Repaint only what changed", true, scene::setPartialRendering)
                .addCheck("Show what is repainted", false, scene::setDamageDebug));
        menuBar.addMenu("Go", goMenu(navigation, top));
        if (Boolean.getBoolean("limn.demo.tour")) {
            tour(scene, navigation, top);
        }
        lab(scene, navigation, top, performance);
        return scene;
    }

    /**
     * {@code -Dlimn.demo.tour=true}: every page in turn, each for most of a second, and then the
     * window closes. A lab's way of finding the page that throws once it is in this window, which
     * its own scene never showed; what it finds is on standard error.
     */
    private static void tour(Scene scene, Tree<Place> navigation, List<Place> top) {
        List<Place> pages = new ArrayList<>();
        for (Place place : top) {
            if (place.page != null) {
                pages.add(place);
            }
            pages.addAll(place.under);
        }
        pages.add(top.get(0)); // and back to where it opens, so leaving a page is seen too
        long step = Long.getLong("limn.demo.tour.ms", 800);
        for (int i = 0; i < pages.size(); i++) {
            Place place = pages.get(i);
            limn.concurrent.Ui.postDelayed(() -> {
                System.err.println("tour: " + place.name);
                navigation.setSelected(place);
            }, 1500 + i * step);
            String shots = System.getProperty("limn.demo.tour.shots");
            if (shots != null) {
                int at = i;
                // A picture of each page as it stands when the tour is about to leave it.
                limn.concurrent.Ui.postDelayed(() -> {
                    if (scene.window() != null) {
                        scene.window().captureNextFrame(frame -> limn.graphics.Images.save(frame,
                                limn.graphics.ImageFormat.PNG, java.nio.file.Path.of(shots, String.format(
                                        "%02d-%s.png", at, place.name.toLowerCase(Locale.ROOT).replace(' ', '-')))));
                        scene.root().invalidate(); // a frame to take the picture of
                    }
                }, 1500 + i * step + step * 4 / 5);
            }
        }
        limn.concurrent.Ui.postDelayed(() -> {
            System.err.println("tour: done");
            if (scene.window() != null) {
                scene.window().requestClose();
            }
        }, 1500 + pages.size() * step);
    }

    /**
     * The lab's switches, none of them the application's: {@code -Dlimn.demo.page=Video} opens on
     * that page, {@code -Dlimn.demo.perf=true} with the performance footer shown, and
     * {@code -Dlimn.demo.film=dir} writes a picture of the window there twice a second, which is
     * how a page is looked at while something on it moves.
     */
    private static void lab(Scene scene, Tree<Place> navigation, List<Place> top, Widget<?> performance) {
        String page = System.getProperty("limn.demo.page");
        if (page != null) {
            for (Place place : top) {
                for (Place each : place.under) {
                    if (each.name.equalsIgnoreCase(page)) {
                        navigation.setSelected(each);
                    }
                }
            }
        }
        if (Boolean.getBoolean("limn.demo.perf")) {
            performance.setVisible(true);
        }
        String film = System.getProperty("limn.demo.film");
        if (film != null) {
            int[] frame = {0};
            Runnable[] take = new Runnable[1];
            take[0] = () -> {
                if (scene.window() != null) {
                    int n = frame[0]++;
                    scene.window().captureNextFrame(image -> limn.graphics.Images.save(image,
                            limn.graphics.ImageFormat.PNG, java.nio.file.Path.of(film, String.format("f%03d.png", n))));
                }
                limn.concurrent.Ui.postDelayed(take[0], 500);
            };
            limn.concurrent.Ui.postDelayed(take[0], 1500);
        }
    }

    private static Menu goMenu(Tree<Place> navigation, List<Place> top) {
        Menu go = new Menu();
        for (Place place : top) {
            if (place.page != null) {
                go.addItem(place.name, () -> navigation.setSelected(place));
                continue;
            }
            Menu under = new Menu();
            for (Place each : place.under) {
                under.addItem(each.name, () -> {
                    navigation.expand(place);
                    navigation.setSelected(each);
                });
            }
            go.addSubmenu(place.name, under);
        }
        return go;
    }

    private static Widget<?> scrolling(Widget<?> content) {
        return new ScrollView(new Padding(Insets.all(12), content));
    }

    /** Holds the page in view and swaps it. */
    private static final class PageHolder extends Widget<PageHolder> {
        private Widget<?> shown;

        void show(Widget<?> page) {
            if (page == shown) {
                return;
            }
            if (shown != null) {
                remove(shown);
            }
            shown = page;
            add(page);
            markNeedsLayout();
            invalidate();
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            if (shown != null) {
                shown.measure(constraints);
            }
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            if (shown != null) {
                shown.measure(limn.scene.Constraints.tight(width(), height()));
                shown.layoutBox(0, 0, width(), height());
            }
        }
    }

    // ------------------------------------------------------------------ the orders

    private static List<Order> sampleOrders() {
        String[] customers = {"Meridian Logistics", "Halcyon Foods", "Corvid Print", "Lumen Optics",
                "Tessera Tiles", "Orrery Instruments", "Vantage Cycles", "Kestrel Outdoor", "Sable Paper",
                "Aurum Jewellers", "Quill Stationery", "Zephyr Air"};
        Status[] statuses = Status.values();
        LocalDate first = LocalDate.of(2026, 9, 1);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            LocalDate placed = first.plusDays(i * 3L % 40);
            Status status = statuses[(i * 7 + i / 5) % statuses.length];
            orders.add(new Order(1042 + i, customers[i * 5 % customers.length], placed, placed.plusDays(30),
                    status, 180 + (i * 977 % 4200) + (i % 4) * 0.25, ""));
        }
        orders.get(0).notes = "Deliver to the side entrance; ask for the warehouse lead.";
        return orders;
    }

    private static Widget<?> ordersPage(Label status) {
        List<Order> all = sampleOrders();
        List<Order> shown = new ArrayList<>(all);

        Column<Order> number = Column.<Order>text("Order", order -> "#" + order.number).width(92)
                .footerCount();
        Column<Order> customer = Column.<Order>text("Customer", order -> order.customer).width(150).weight(1);
        Column<Order> placed = Column.<Order, LocalDate>of("Placed", order -> order.placed,
                (date, locale) -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
                        .format(date)).width(104);
        Column<Order> state = Column.<Order>text("Status", order -> order.status.label).width(104);
        Column<Order> total = Column.<Order>currency("Total", order -> order.total,
                Currency.getInstance("USD")).width(118).footerSum();
        Table<Order> table = new Table<>(List.of(number, customer, placed, state, total));
        table.setAccessibleName("Orders");
        table.setRows(shown);
        table.setSort(placed, SortOrder.DESCENDING);

        // ---- the filter: what is typed, and the statuses chosen
        SearchField search = new SearchField();
        search.setPlaceholder("Customer or number");
        search.setAccessibleName("Search orders");
        ComboBox<Status> statuses = new ComboBox<>(List.of(Status.values()));
        statuses.setSelectionMode(SelectionMode.MULTI);
        statuses.setPlaceholder("Any status");
        statuses.setAccessibleName("Status");
        Label count = new Label("").setMuted(true);
        Runnable filter = () -> {
            String text = search.text().strip().toLowerCase(Locale.ROOT);
            List<Status> wanted = statuses.selectedItems();
            shown.clear();
            for (Order order : all) {
                boolean matches = text.isEmpty() || order.customer.toLowerCase(Locale.ROOT).contains(text)
                        || String.valueOf(order.number).contains(text);
                if (matches && (wanted.isEmpty() || wanted.contains(order.status))) {
                    shown.add(order);
                }
            }
            table.refresh();
            count.setText(shown.size() + " of " + all.size());
        };
        search.onChange(text -> filter.run());
        statuses.onSelect(filter);

        Row tools = new Row();
        tools.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        tools.add(new Label("Orders").setRole(Label.Role.TITLE));
        tools.add(Expanded.spacer(1));
        tools.add(count);
        tools.add(new SizedBox(220, SizedBox.UNSET, search));
        tools.add(new SizedBox(220, SizedBox.UNSET, statuses));
        tools.add(new Button("New order")
                .onAction(() -> status.setText("A new order would open here.")));

        // ---- the order the cursor is on, in a form that never moves
        Label heading = new Label("").setStrong(true);
        TextField customerField = new TextField();
        ComboBox<Status> statusField = new ComboBox<>(List.of(Status.values()));
        DatePicker placedField = new DatePicker();
        DatePicker dueField = new DatePicker();
        TextArea notes = new TextArea().setSoftWrap(true);
        Button save = new Button("Save");
        Button revert = new Button("Revert").setSecondary(true);
        Order[] editing = {null};
        Runnable load = () -> {
            int row = table.selectedRow();
            Order order = row < 0 || row >= shown.size() ? null : shown.get(row);
            editing[0] = order;
            boolean has = order != null;
            heading.setText(has ? "Order #" + order.number : "No order selected");
            customerField.setText(has ? order.customer : "");
            statusField.setSelectedIndex(has ? order.status.ordinal() : -1);
            placedField.setDate(has ? order.placed : null);
            dueField.setDate(has ? order.due : null);
            notes.setText(has ? order.notes : "");
            for (Widget<?> field : List.of(customerField, statusField, placedField, dueField, notes, save, revert)) {
                field.setEnabled(has);
            }
        };
        table.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                load.run();
            }
        });
        save.onAction(() -> {
            Order order = editing[0];
            if (order == null) {
                return;
            }
            order.customer = customerField.text().strip();
            Status chosen = statusField.selectedItem();
            order.status = chosen == null ? order.status : chosen;
            order.placed = placedField.date() == null ? order.placed : placedField.date();
            order.due = dueField.date() == null ? order.due : dueField.date();
            order.notes = notes.text();
            table.refresh(); // the selection is by record, and stays on the order just saved
            status.setText("Order #" + order.number + " saved.");
        });
        revert.onAction(load);

        limn.scene.layout.Column form = new limn.scene.layout.Column();
        form.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(heading);
        form.add(Separator.horizontal());
        form.add(Labelled.above("Customer", customerField));
        form.add(Labelled.above("Status", statusField));
        Row dates = new Row();
        dates.gap(10).crossAlignment(Flex.CrossAlignment.START);
        dates.add(Expanded.of(Labelled.above("Placed", placedField), 1));
        dates.add(Expanded.of(Labelled.above("Due", dueField), 1));
        form.add(dates);
        form.add(Labelled.above("Notes", new SizedBox(SizedBox.UNSET, 96, notes)));
        Row buttons = new Row();
        buttons.gap(8).mainAlignment(Flex.MainAlignment.END);
        buttons.add(revert);
        buttons.add(save);
        form.add(buttons);

        SplitPane split = SplitPane.horizontal(table, new ScrollView(new Padding(Insets.all(12), form)));
        split.setRatio(0.64f);
        split.setMinimums(320, 260);

        limn.scene.layout.Column page = new limn.scene.layout.Column();
        page.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(tools);
        page.add(Expanded.of(split, 1));
        Padding padded = new Padding(Insets.symmetric(0, 12), page);
        filter.run();
        table.setSelectedRow(table.viewToModel(0)); // the newest, which the sort shows first
        load.run();
        return padded;
    }
}
