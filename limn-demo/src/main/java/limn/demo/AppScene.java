package limn.demo;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Dialog;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.MenuBar;
import limn.components.ProgressBar;
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
import limn.concurrent.Ui;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.Change;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.SystemPreferences;
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
 *
 * <p>The frame, the orders, the dialogs and the menus are translated. The pages of the gallery
 * keep their specimens in English: what they show is the components, and the language switch
 * is seen in the frame around them.
 */
final class AppScene {

    /** The window's name, the same in every language. */
    static final String TITLE = "Limn Showcase";

    /** Where the documentation is published; each page of the gallery links under it. */
    static final String SITE = "https://limn-toolkit.github.io/limn-toolkit/";

    /**
     * @param followSystemAppearance has the window take the system's light or dark appearance,
     *                               now and when it changes, until the user picks a theme
     * @param openNewOrder           opens the dialog a new order is made in, and answers it
     */
    record Built(Scene scene, Runnable followSystemAppearance, Supplier<Dialog> openNewOrder) {
    }

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
        OPEN(ShowcaseStrings.ORDERS_OPEN), SHIPPED(ShowcaseStrings.ORDERS_SHIPPED),
        PAID(ShowcaseStrings.ORDERS_PAID), OVERDUE(ShowcaseStrings.ORDERS_OVERDUE),
        CANCELLED(ShowcaseStrings.ORDERS_CANCELLED);

        final I18nString label;

        Status(I18nString label) {
            this.label = label;
        }

        static List<I18nString> labels() {
            return Arrays.stream(values()).map(status -> status.label).toList();
        }
    }

    /** A place the navigation leads to: a page, or a heading over some. */
    static final class Place {
        final I18nString name;
        final List<Place> under = new ArrayList<>();
        final Supplier<Widget<?>> page;
        /** Where on the site this page's subject is documented, under {@link #SITE}; null for none. */
        final String documentation;

        Place(I18nString name, String documentation, Supplier<Widget<?>> page) {
            this.name = name;
            this.documentation = documentation;
            this.page = page;
        }

        Place(I18nString name, Place... under) {
            this.name = name;
            this.documentation = null;
            this.page = null;
            this.under.addAll(List.of(under));
        }

        /** The icon beside the name in the navigation: a name of the icon pack, or null. */
        String icon;

        /** Says which icon stands beside this place's name. */
        Place icon(String name) {
            this.icon = name;
            return this;
        }

        /** The name the lab's switches know this page by: its English one. */
        String id() {
            return name.english();
        }
    }

    private AppScene() {
    }

    /**
     * The pages, by what they are for. A list of its own so that a test can read where each
     * links without building a window.
     */
    static List<Place> places(Label status, Supplier<Widget<?>> ordersPage) {
        Place orders = new Place(ShowcaseStrings.NAV_ORDERS, null, ordersPage).icon("receipt");
        Place components = new Place(ShowcaseStrings.NAV_COMPONENTS,
                new Place(ShowcaseStrings.NAV_INPUTS, "components/#button",
                        () -> scrolling(BasicsScene.content())).icon("forms"),
                new Place(ShowcaseStrings.NAV_COMBOS, "components/#combo-box",
                        () -> scrolling(CombosScene.content())).icon("select"),
                new Place(ShowcaseStrings.NAV_SLIDERS, "components/#slider", () -> {
                    limn.scene.layout.Column both = new limn.scene.layout.Column();
                    both.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
                    both.add(ControlsScene.content());
                    both.add(Separator.horizontal());
                    both.add(NewControlsScene.content());
                    return scrolling(both);
                }).icon("adjustments-horizontal"),
                new Place(ShowcaseStrings.TAB_DATES, "components/#date-picker",
                        () -> scrolling(DatesScene.content())).icon("calendar"),
                new Place(ShowcaseStrings.NAV_LISTS, "docs/lists-and-scrolling/#long-lists",
                        () -> beside(ListScene.buildList(status::setText))).icon("list"),
                new Place(ShowcaseStrings.NAV_TABLES, "docs/lists-and-scrolling/#tables",
                        () -> beside(TableScene.content())).icon("table"),
                new Place(ShowcaseStrings.NAV_TREES, "docs/lists-and-scrolling/#trees",
                        () -> scrolling(TreeScene.content())).icon("binary-tree"),
                new Place(ShowcaseStrings.NAV_DIALOGS, "docs/menus-and-dialogs/#dialogs",
                        () -> scrolling(DialogsScene.dialogs(status))).icon("app-window"),
                new Place(ShowcaseStrings.TAB_MENUS, "docs/menus-and-dialogs/#menus",
                        () -> scrolling(DialogsScene.menus(status))).icon("menu-2"),
                new Place(ShowcaseStrings.NAV_DRAG_DROP, "docs/drag-and-drop/",
                        () -> scrolling(PickerScene.content())).icon("drag-drop"),
                new Place(ShowcaseStrings.NAV_CLIPBOARD, "docs/drag-and-drop/#the-clipboard",
                        () -> scrolling(TransferScene.content())).icon("clipboard"),
                new Place(ShowcaseStrings.NAV_TRAY, "docs/menus-and-dialogs/#an-icon-in-the-tray",
                        () -> scrolling(TrayScene.page(status))).icon("bell"),
                new Place(ShowcaseStrings.NAV_CHARTS, "docs/charts/", () -> scrolling(ChartsScene.content())).icon("chart-bar"),
                new Place(ShowcaseStrings.NAV_TEXT, "docs/text-and-languages/",
                        () -> scrolling(BidiScene.tabContent())).icon("language"),
                new Place(ShowcaseStrings.NAV_ICONS, "docs/images-and-media/#icons",
                        () -> beside(IconsScene.content())).icon("icons"),
                new Place(ShowcaseStrings.TAB_FILES, "docs/menus-and-dialogs/#native-file-dialogs",
                        () -> beside(FilesScene.content())).icon("folder"),
                new Place(ShowcaseStrings.NAV_COLOURS, "components/#color-picker",
                        () -> scrolling(ColorPickerScene.content())).icon("palette"),
                new Place(ShowcaseStrings.NAV_SPLIT, "docs/lists-and-scrolling/#splitting-a-window",
                        () -> beside(SplitPaneScene.content())).icon("layout-columns"),
                new Place(ShowcaseStrings.NAV_MOTION, "docs/animations/",
                        () -> scrolling(AnimationsScene.content())).icon("player-play")).icon("components");
        Place media = new Place(ShowcaseStrings.NAV_MEDIA,
                new Place(ShowcaseStrings.NAV_VIDEO, "docs/images-and-media/#video",
                        () -> scrolling(VideoScene.pageContent())).icon("video"),
                new Place(ShowcaseStrings.NAV_VIEWPORT, "docs/images-and-media/#3d",
                        () -> scrolling(Viewport3DScene.tabContent())).icon("cube"),
                new Place(ShowcaseStrings.NAV_SOUND, "docs/images-and-media/#sound",
                        () -> scrolling(AudioScene.content())).icon("volume"),
                new Place(ShowcaseStrings.NAV_SPRITES, "docs/images-and-media/#pictures",
                        () -> scrolling(SpritesScene.content())).icon("photo"),
                new Place(ShowcaseStrings.NAV_GLASS, "docs/theming/#glass",
                        () -> scrolling(GlassScene.content())).icon("blur")).icon("movie");
        return List.of(orders, components, media);
    }

    /** Standalone {@code --scene app}, and what the demo opens on when no scene is named. */
    static Built create() {
        MenuBar menuBar = new MenuBar();
        Label status = new Label(ShowcaseStrings.STATUS_READY).setMuted(true);
        ProgressBar busy = new ProgressBar().setThickness(6).setPreferredWidth(120);
        busy.setVisible(false);
        busy.setAccessibleName(ShowcaseStrings.STATUS_LOADING);
        PerfFooter performance = new PerfFooter();
        performance.setAccessibleName(ShowcaseStrings.MENU_PERFORMANCE);
        // Set in from the window's edges as the lines of text above it are, with a line over it.
        // The strip is what is shown and hidden: hidden alone, the charts would leave their
        // padding and their line behind.
        limn.scene.layout.Column performanceStrip = new limn.scene.layout.Column();
        performanceStrip.crossAlignment(Flex.CrossAlignment.STRETCH);
        performanceStrip.add(Separator.horizontal());
        performanceStrip.add(new Padding(Insets.symmetric(10, 12), performance));
        performanceStrip.setVisible(false);

        Orders orders = new Orders(status, busy);
        List<Place> top = places(status, () -> orders.page);
        Place ordersPlace = top.get(0);

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
                if (place.icon != null) {
                    label.setIcon(limn.icons.tabler.Tabler.outline(place.icon));
                }
                return new Padding(Insets.symmetric(6, 6), label);
            }

            @Override
            public I18nString nameOf(Place place) {
                return place.name;
            }

            @Override
            public float maxCellWidth() {
                // Two levels in a narrow strip: declared narrow, so the outline is never wider
                // than its box and choosing a page never moves it sideways; a name too long for
                // the strip ends in an ellipsis, as a navigation's does.
                return 156;
            }
        });
        navigation.setAccessibleName(ShowcaseStrings.NAV_PAGES);
        for (Place place : top) {
            navigation.expand(place);
        }

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
            holder.show(built.computeIfAbsent(place, p -> framed(p, status)));
            status.setText(place.name);
        });
        navigation.setSelected(ordersPlace);
        status.setText(ShowcaseStrings.STATUS_READY);

        // ---- the band: the title and the four switches that reach the whole process
        ComboBox<String> language = new ComboBox<>(Languages.NAMES);
        language.setSelectedIndex(Languages.indexOf(I18n.locale()));
        language.onSelect(() -> I18n.setLocale(Languages.LOCALES.get(language.selectedIndex())));
        language.setAccessibleName(ShowcaseStrings.BAND_LANGUAGE);
        ComboBox<String> direction = new ComboBox<>(
                Arrays.stream(LayoutDirection.values()).map(Enum::name).toList());
        direction.setSelectedIndex(LayoutDirection.processDefault().ordinal());
        direction.onSelect(() -> LayoutDirection.setProcessDefault(
                LayoutDirection.values()[direction.selectedIndex()]));
        direction.setAccessibleName(ShowcaseStrings.BAND_DIRECTION);
        ComboBox<String> size = new ComboBox<>(Arrays.stream(ControlSize.values()).map(Enum::name).toList());
        size.setSelectedIndex(ControlSize.processDefault().ordinal());
        size.onSelect(() -> ControlSize.setProcessDefault(ControlSize.values()[size.selectedIndex()]));
        size.setAccessibleName(ShowcaseStrings.BAND_SIZE);
        ComboBox<I18nString> theme = ComboBox.localized(
                Theme.builtins().stream().map(Theme::displayName).toList());
        theme.setSelectedIndex(Math.max(0, Theme.builtins().indexOf(Theme.current())));
        theme.setAccessibleName(ShowcaseStrings.BAND_THEME);

        Row band = new Row();
        band.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        band.add(new Label(TITLE).setRole(Label.Role.TITLE));
        band.add(Expanded.spacer(1));
        band.add(language);
        band.add(direction);
        band.add(size);
        band.add(theme);

        SplitPane body = SplitPane.horizontal(navigation, holder);
        body.setRatio(0.2f);
        body.setMinimums(232, 420);
        body.setAccessibleName(ShowcaseStrings.NAV_PAGES);

        Row statusRow = new Row();
        statusRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        statusRow.add(Expanded.of(status, 1));
        statusRow.add(busy);

        limn.scene.layout.Column page = new limn.scene.layout.Column();
        // Nothing around the window's content: the menu bar and the panes go from edge to edge,
        // as a window's do, and only the two lines of text between them are set in from it.
        page.crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(menuBar);
        page.add(new Padding(Insets.symmetric(8, 12), band));
        // A line under the band and one over the status line: where the window's own strips end
        // and the page begins, which the two backgrounds being the same colour did not say.
        page.add(Separator.horizontal());
        page.add(Expanded.of(body, 1));
        page.add(Separator.horizontal());
        page.add(new Padding(Insets.symmetric(6, 12), new SizedBox(SizedBox.UNSET, 18, statusRow)));
        page.add(performanceStrip);

        Scene scene = new Scene(page);
        scene.setBackground(Theme.current().background());

        // The call a theme picker makes: the palette becomes current, its face is applied, and
        // the scene clears to its background and repaints. A palette is a repaint, never a
        // re-measure; the face, when it changes, has every scene laid out again by itself.
        //
        // The window follows the system's light or dark appearance until the user picks a
        // theme; the picker following a theme applied for the system is not a pick.
        boolean[] userPicked = {false};
        boolean[] applyingSystem = {false};
        theme.onSelect(() -> {
            if (!applyingSystem[0]) {
                userPicked[0] = true;
            }
            Theme.builtins().get(theme.selectedIndex()).apply(scene);
        });
        // And when something else changes the theme, a launch option or the system's
        // appearance: the picker says which it is, and the window repaints in it.
        Theme.observeChanges(() -> {
            int now = Theme.builtins().indexOf(Theme.current());
            if (now >= 0 && now != theme.selectedIndex()) {
                boolean was = applyingSystem[0];
                applyingSystem[0] = true;
                try {
                    theme.setSelectedIndex(now);
                } finally {
                    applyingSystem[0] = was;
                }
            }
            scene.setBackground(Theme.current().background());
            scene.root().invalidate();
        });
        Runnable applySystemAppearance = () -> {
            SystemPreferences.ColorScheme scheme = SystemPreferences.current().colorScheme();
            if (userPicked[0] || scheme == SystemPreferences.ColorScheme.NO_PREFERENCE) {
                return;
            }
            Theme wanted = scheme == SystemPreferences.ColorScheme.DARK ? Theme.dark() : Theme.light();
            if (wanted != Theme.current()) {
                applyingSystem[0] = true;
                try {
                    wanted.apply(scene);
                } finally {
                    applyingSystem[0] = false;
                }
            }
        };
        Runnable followSystemAppearance = () -> {
            applySystemAppearance.run();
            SystemPreferences.observeChanges(applySystemAppearance); // for the life of the demo
        };

        Runnable newOrder = () -> {
            navigation.setSelected(ordersPlace);
            orders.openNewOrder();
        };
        menuBar.addMenu(ShowcaseStrings.MENU_FILE, new Menu()
                .addItem(ShowcaseStrings.MENU_NEW_ORDER, newOrder)
                .addItem(ShowcaseStrings.ORDERS_REFRESH, orders::refresh)
                .addSeparator()
                // Where the system keeps Quit in a menu of its own, this item is not shown and the
                // system's asks the same question.
                .add(MenuItem.of(ShowcaseStrings.MENU_QUIT, () -> {
                    if (scene.window() != null) {
                        scene.window().backend().requestQuit();
                    }
                }).setRole(MenuItem.Role.QUIT)));
        // The six editing commands, each acting on whatever has the keyboard.
        menuBar.addMenu(ShowcaseStrings.MENU_EDIT, new Menu()
                .add(MenuItem.of(MenuItem.Role.UNDO))
                .add(MenuItem.of(MenuItem.Role.REDO))
                .addSeparator()
                .add(MenuItem.of(MenuItem.Role.CUT))
                .add(MenuItem.of(MenuItem.Role.COPY))
                .add(MenuItem.of(MenuItem.Role.PASTE))
                .add(MenuItem.of(MenuItem.Role.SELECT_ALL)));
        menuBar.addMenu(ShowcaseStrings.MENU_VIEW, new Menu()
                .addCheck(ShowcaseStrings.MENU_PERFORMANCE, false, performanceStrip::setVisible)
                .addSeparator()
                .addCheck(ShowcaseStrings.RENDER_PARTIAL, true, scene::setPartialRendering)
                .addCheck(ShowcaseStrings.RENDER_DAMAGE, false, scene::setDamageDebug)
                .addSeparator()
                .addSubmenu(ShowcaseStrings.MENU_FULLSCREEN, new Menu()
                        .addItem(ShowcaseStrings.MENU_CURRENT_RESOLUTION, () -> fullscreen(scene, status, 0, 0))
                        .addItem("1280×720", () -> fullscreen(scene, status, 1280, 720))));
        menuBar.addMenu(ShowcaseStrings.MENU_GO, goMenu(navigation, top));
        Menu help = new Menu()
                .addItem(ShowcaseStrings.MENU_DOCUMENTATION, () -> openDocumentation(status, "docs/", status))
                .addSeparator()
                .add(MenuItem.of(ShowcaseStrings.TAB_ABOUT, () -> new Dialog(I18nString.literal(TITLE),
                        ShowcaseStrings.ABOUT_BLURB)
                        .addPrimaryButton(ShowcaseStrings.ACTION_CLOSE, "close")
                        .setCancelResult("close")
                        .show(scene)).setRole(MenuItem.Role.ABOUT));
        menuBar.addMenu(ShowcaseStrings.MENU_HELP, help);
        menuBar.setHelpMenu(help);
        if (Boolean.getBoolean("limn.demo.tour")) {
            tour(scene, navigation, top);
        }
        lab(scene, navigation, top, performanceStrip);
        if (Boolean.getBoolean("limn.demo.docs")) {
            // The lab's: the documentation asked for by itself, from this window, to see whether
            // what the system opens comes in front of the window that asked.
            Ui.postDelayed(() -> openDocumentation(status, "docs/", status), 3000);
        }
        String popup = System.getProperty("limn.demo.popup");
        if (popup != null) {
            // The lab's: a popup opened by itself, to photograph its window as the system's
            // compositor shows it: the size picker's list, a menu with a submenu, or the calendar
            // of the order's panel.
            Ui.postDelayed(() -> {
                switch (popup) {
                    case "menu" -> new limn.components.PopupMenu(new Menu()
                            .addItem(ShowcaseStrings.MENU_COPY, () -> { })
                            .addItem(ShowcaseStrings.MENU_PASTE, () -> { })
                            .addSeparator()
                            .addCheck(ShowcaseStrings.MENU_BOLD, true, on -> { })
                            .addSubmenu(ShowcaseStrings.MENU_EXPORT, new Menu()
                                    .addItem("PDF", () -> { }).addItem("PNG", () -> { })))
                            .showAt(scene, 420, 260);
                    case "dialog" -> new Dialog(I18nString.literal(TITLE), ShowcaseStrings.ABOUT_BLURB)
                            .addPrimaryButton(ShowcaseStrings.ACTION_CLOSE, "close")
                            .setCancelResult("close").show(scene);
                    case "calendar" -> {
                        orders.drawer.setOpen(true);
                        Ui.postDelayed(orders.placedField::open, 600);
                    }
                    default -> size.open();
                }
            }, 1800);
        }
        if (Boolean.getBoolean("limn.demo.order")) {
            // The lab's: the order under the cursor opened by itself, to photograph the panel.
            Ui.postDelayed(() -> orders.drawer.setOpen(true), 1800);
            if (Boolean.getBoolean("limn.demo.order.trace")) {
                // And closed and opened again: the first slide also lays the form out for the
                // first time, and the ones after it are what a user goes on seeing.
                Ui.postDelayed(() -> orders.drawer.setOpen(false), 2800);
                Ui.postDelayed(() -> orders.drawer.setOpen(true), 3800);
            }
        }
        return new Built(scene, followSystemAppearance, () -> {
            navigation.setSelected(ordersPlace);
            return orders.openNewOrder();
        });
    }

    /** Both ways toggle: into fullscreen at that resolution, or the display's own for zero, and out. */
    private static void fullscreen(Scene scene, Label status, int width, int height) {
        limn.backend.NativeWindow window = scene.window();
        if (window == null) {
            return;
        }
        if (window.isFullscreen()) {
            window.exitFullscreen();
            status.setText(ShowcaseStrings.STATUS_WINDOW_RESTORED);
        } else {
            window.enterFullscreen(width, height, 0);
            status.setText(width <= 0 ? ShowcaseStrings.STATUS_FULLSCREEN_CURRENT.get()
                    : ShowcaseStrings.STATUS_FULLSCREEN_AT.format(String.valueOf(width), String.valueOf(height)));
        }
    }

    /**
     * Opens a page of the site in the system's browser, and says in the status line which: the
     * address stays there to copy where the system opened nothing.
     */
    private static void openDocumentation(Widget<?> asker, String path, Label status) {
        java.net.URI address = java.net.URI.create(SITE + path);
        Scene scene = asker.scene();
        if (scene != null && scene.window() != null) {
            scene.window().launcher().openAddress(address);
        }
        status.setText(ShowcaseStrings.MENU_DOCUMENTATION.get() + ": " + address);
    }

    /**
     * A page of the gallery in the window: its name, the link to where the site documents it, and
     * the page under them. The orders are an application's screen and carry their own head.
     */
    private static Widget<?> framed(Place place, Label status) {
        Widget<?> content = place.page.get();
        if (place.documentation == null) {
            return content;
        }
        Button link = new Button(ShowcaseStrings.MENU_DOCUMENTATION).setSecondary(true);
        link.setControlSize(ControlSize.SMALL);
        link.setTooltip(SITE + place.documentation);
        link.onAction(() -> openDocumentation(link, place.documentation, status));
        Row head = new Row();
        head.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        head.add(new Label(place.name).setRole(Label.Role.TITLE));
        head.add(Expanded.spacer(1));
        head.add(link);
        limn.scene.layout.Column framed = new limn.scene.layout.Column();
        framed.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        framed.add(new Padding(Insets.symmetric(0, 12), head));
        framed.add(Expanded.of(content, 1));
        return framed;
    }

    private static List<Place> pages(List<Place> top) {
        List<Place> pages = new ArrayList<>();
        for (Place place : top) {
            if (place.page != null) {
                pages.add(place);
            }
            pages.addAll(place.under);
        }
        return pages;
    }

    /**
     * {@code -Dlimn.demo.tour=true}: every page in turn, each for most of a second, and then the
     * window closes. A lab's way of finding the page that throws once it is in this window, which
     * its own scene never showed; what it finds is on standard error.
     */
    private static void tour(Scene scene, Tree<Place> navigation, List<Place> top) {
        List<Place> pages = pages(top);
        pages.add(top.get(0)); // and back to where it opens, so leaving a page is seen too
        long step = Long.getLong("limn.demo.tour.ms", 800);
        for (int i = 0; i < pages.size(); i++) {
            Place place = pages.get(i);
            Ui.postDelayed(() -> {
                System.err.println("tour: " + place.id());
                navigation.setSelected(place);
            }, 1500 + i * step);
            String shots = System.getProperty("limn.demo.tour.shots");
            if (shots != null) {
                int at = i;
                // A picture of each page as it stands when the tour is about to leave it.
                Ui.postDelayed(() -> {
                    if (scene.window() != null) {
                        scene.window().captureNextFrame(frame -> limn.graphics.Images.save(frame,
                                limn.graphics.ImageFormat.PNG, java.nio.file.Path.of(shots, String.format(
                                        "%02d-%s.png", at, place.id().toLowerCase(Locale.ROOT).replace(' ', '-')))));
                        scene.root().invalidate(); // a frame to take the picture of
                    }
                }, 1500 + i * step + step * 4 / 5);
            }
        }
        Ui.postDelayed(() -> {
            System.err.println("tour: done");
            if (scene.window() != null) {
                scene.window().requestClose();
            }
        }, 1500 + pages.size() * step);
    }

    /**
     * The lab's switches, none of them the application's: {@code -Dlimn.demo.page=Video} opens on
     * that page, by its English name, {@code -Dlimn.demo.order=true} with the order's panel open,
     * {@code -Dlimn.demo.perf=true} with the performance footer
     * shown, and {@code -Dlimn.demo.film=dir} writes a picture of the window there twice a
     * second, which is how a page is looked at while something on it moves.
     */
    private static void lab(Scene scene, Tree<Place> navigation, List<Place> top, Widget<?> performance) {
        String page = System.getProperty("limn.demo.page");
        if (page != null) {
            for (Place each : pages(top)) {
                if (each.id().equalsIgnoreCase(page)) {
                    navigation.setSelected(each);
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
                Ui.postDelayed(take[0], Long.getLong("limn.demo.film.ms", 500));
            };
            Ui.postDelayed(take[0], 1500);
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

    /** A page that fills its place and scrolls itself, set in from the divider as the others are. */
    private static Widget<?> beside(Widget<?> content) {
        return new Padding(Insets.symmetric(0, 12), content);
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

    /**
     * A panel that comes in over what it is about and goes away again: the main widget keeps
     * the whole box, and the panel slides over its trailing edge as a surface lifted above it,
     * with the shadow the theme gives one that covers. Nothing under it is laid out again while
     * it moves. The slide takes about a fifth of a second and no time at all when the user has
     * asked the system for less motion. Trailing is the right in a layout that reads from the
     * left and the left in one that reads from the right. Escape, from anywhere inside the
     * panel, closes it.
     */
    static final class Drawer extends Widget<Drawer> {
        private static final float PANEL_WIDTH = 320;
        private static final double SECONDS = 0.22;
        private static final boolean TRACE = Boolean.getBoolean("limn.demo.order.trace");
        private final long traceStart = System.nanoTime();

        private final Widget<?> main;
        private final limn.components.Surface panel;
        private boolean open;
        /** How far in the panel is, from 0 to 1, in time; eased when it is laid out. */
        private float progress;
        private boolean ticking;

        Drawer(Widget<?> main, Widget<?> content) {
            this.main = main;
            // Square: it meets the box's edge on three sides, and a corner there would show the
            // table through it.
            this.panel = new limn.components.Surface(content)
                    .setElevation(limn.components.Elevation.OVERLAY).setCornerRadius(0);
            add(main);
            add(panel);
            panel.setVisible(false);
        }

        boolean isOpen() {
            return open;
        }

        void setOpen(boolean value) {
            if (open == value) {
                return;
            }
            open = value;
            if (scene() == null || limn.animation.Motion.reduced()) {
                progress = open ? 1 : 0;
                settle();
                return;
            }
            panel.setVisible(true);
            if (!ticking) {
                ticking = true;
                // On the wall clock: the panel's arrival settles what can take the keyboard.
                scene().addRealTimeTicker(dt -> {
                    float step = (float) (dt / SECONDS);
                    progress = open ? Math.min(1, progress + step) : Math.max(0, progress - step);
                    if (TRACE) {
                        // The lab's: each frame of the slide, when it came and how far in the panel is.
                        System.out.printf(Locale.ROOT, "slide t=%.1f ms dt=%.1f ms in=%.3f%n",
                                (System.nanoTime() - traceStart) / 1e6, dt * 1000,
                                limn.animation.Easing.EASE_IN_OUT.apply(progress));
                    }
                    boolean arrived = progress == (open ? 1f : 0f);
                    if (arrived) {
                        ticking = false;
                        settle();
                    } else {
                        markNeedsLayout();
                        invalidate();
                    }
                    return !arrived;
                });
            }
        }

        private void settle() {
            panel.setVisible(progress > 0);
            markNeedsLayout();
            invalidate();
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            main.measure(limn.scene.Constraints.tight(width(), height()));
            main.layoutBox(0, 0, width(), height());
            if (panel.isVisible()) {
                float panelWidth = Math.min(PANEL_WIDTH, width() * 0.6f);
                float shown = panelWidth * limn.animation.Easing.EASE_IN_OUT.apply(progress);
                panel.measure(limn.scene.Constraints.tight(panelWidth, height()));
                panel.layoutBox(isRightToLeft() ? shown - panelWidth : width() - shown, 0, panelWidth, height());
            }
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected void paintChildren(limn.graphics.Canvas canvas) {
            canvas.save();
            try {
                canvas.clipRect(0, 0, width(), height());
                super.paintChildren(canvas);
            } finally {
                canvas.restore();
            }
        }

        @Override
        protected void onKeyEvent(limn.scene.event.KeyEvent event) {
            if (open && event.isPressed() && event.key() == limn.input.Keys.ESCAPE) {
                setOpen(false);
                main.requestFocus();
                event.consume();
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
            LocalDate placed = first.plusDays(i * 40L / 48);
            Status status = statuses[(i * 7 + i / 5) % statuses.length];
            orders.add(new Order(1042 + i, customers[i * 5 % customers.length], placed, placed.plusDays(30),
                    status, 180 + (i * 977 % 4200) + (i % 4) * 0.25, ""));
        }
        orders.get(0).notes = "Deliver to the side entrance; ask for the warehouse lead.";
        return orders;
    }

    /**
     * The orders screen: a table to read, sort and filter, the order the cursor is on in a form
     * beside it, a dialog a new one is made in, and a refresh that works off the UI thread
     * while the screen stays alive.
     */
    private static final class Orders {
        final Widget<?> page;
        private final List<Order> all = sampleOrders();
        private final List<Order> shown = new ArrayList<>(all);
        private final Table<Order> table;
        private final Label status;
        private final ProgressBar busy;
        private final Runnable filter;
        private final Drawer drawer;
        private final DatePicker placedField;
        private boolean refreshing;

        Orders(Label status, ProgressBar busy) {
            this.status = status;
            this.busy = busy;
            Column<Order> number = Column.<Order>text(ShowcaseStrings.ORDERS_ORDER, order -> "#" + order.number)
                    .width(98).footerCount();
            Column<Order> customer = Column.<Order>text(ShowcaseStrings.ORDERS_CUSTOMER, order -> order.customer)
                    .width(156).weight(1);
            Column<Order> placed = Column.<Order, LocalDate>of(ShowcaseStrings.ORDERS_PLACED, order -> order.placed,
                    (date, locale) -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
                            .format(date)).width(116);
            Column<Order> state = Column.<Order>text(ShowcaseStrings.ORDERS_STATUS,
                    order -> order.status.label.get()).width(108);
            Column<Order> total = Column.<Order>currency(ShowcaseStrings.ORDERS_TOTAL, order -> order.total,
                    Currency.getInstance("USD")).width(150).footerSum();
            // What a pointer opens an order with, where the keyboard has Enter: a button at the
            // end of each row, named by its order for whoever hears the row instead of seeing it.
            Drawer[] panel = new Drawer[1];
            Column<Order> open = Column.<Order>widget(I18nString.EMPTY, order -> {
                Button edit = new Button("").setSecondary(true)
                        .setIcon(limn.icons.tabler.TablerDesign.PENCIL.icon());
                edit.setControlSize(ControlSize.SMALL);
                edit.setAccessibleName(ShowcaseStrings.ORDERS_HEADING.format(String.valueOf(order.number)));
                edit.onAction(() -> {
                    int row = shown.indexOf(order);
                    if (row >= 0) {
                        table().setSelectedRow(row);
                        panel[0].setOpen(true);
                    }
                });
                return edit;
            }).width(72).sortable(false);
            table = new Table<>(List.of(number, customer, placed, state, total, open));
            // What a tool driving the application from outside finds these three by.
            table.setId("orders");
            table.setAccessibleName(ShowcaseStrings.NAV_ORDERS);
            table.setRows(shown);
            table.setSort(placed, SortOrder.DESCENDING);

            // ---- the filter: what is typed, and the statuses chosen
            SearchField search = new SearchField();
            search.setId("orders-search");
            search.setPlaceholder(ShowcaseStrings.ORDERS_SEARCH_HINT);
            search.setAccessibleName(ShowcaseStrings.ORDERS_SEARCH_HINT);
            ComboBox<I18nString> statuses = ComboBox.localized(Status.labels());
            statuses.setSelectionMode(SelectionMode.MULTI);
            statuses.setPlaceholder(ShowcaseStrings.ORDERS_ANY_STATUS);
            statuses.setAccessibleName(ShowcaseStrings.ORDERS_STATUS);
            Label count = new Label("").setMuted(true);
            Runnable counted = () -> count.setText(ShowcaseStrings.ORDERS_COUNT.format(
                    String.valueOf(shown.size()), String.valueOf(all.size())));
            filter = () -> {
                String text = search.text().strip().toLowerCase(Locale.ROOT);
                List<Status> wanted = new ArrayList<>();
                for (int index : statuses.selectedIndices()) {
                    wanted.add(Status.values()[index]);
                }
                shown.clear();
                for (Order order : all) {
                    boolean matches = text.isEmpty() || order.customer.toLowerCase(Locale.ROOT).contains(text)
                            || String.valueOf(order.number).contains(text);
                    if (matches && (wanted.isEmpty() || wanted.contains(order.status))) {
                        shown.add(order);
                    }
                }
                table.refresh();
                counted.run();
            };
            search.onChange(text -> filter.run());
            statuses.onSelect(filter);

            Row tools = new Row();
            tools.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
            tools.add(new Label(ShowcaseStrings.NAV_ORDERS).setRole(Label.Role.TITLE));
            tools.add(Expanded.spacer(1));
            tools.add(count);
            tools.add(new SizedBox(200, SizedBox.UNSET, search));
            tools.add(new SizedBox(200, SizedBox.UNSET, statuses));
            tools.add(new Button(ShowcaseStrings.MENU_NEW_ORDER).onAction(this::openNewOrder));

            // ---- the order the cursor is on, in a form that never moves
            Label heading = new Label("").setStrong(true);
            TextField customerField = new TextField();
            ComboBox<I18nString> statusField = ComboBox.localized(Status.labels());
            DatePicker placedField = new DatePicker();
            this.placedField = placedField;
            DatePicker dueField = new DatePicker();
            TextArea notes = new TextArea().setSoftWrap(true);
            Button save = new Button(ShowcaseStrings.ACTION_SAVE);
            save.setId("order-save");
            Button revert = new Button(ShowcaseStrings.ORDERS_REVERT).setSecondary(true);
            Order[] editing = {null};
            Runnable headed = () -> heading.setText(editing[0] == null ? ShowcaseStrings.ORDERS_NONE.get()
                    : ShowcaseStrings.ORDERS_HEADING.format(String.valueOf(editing[0].number)));
            Runnable load = () -> {
                int row = table.selectedRow();
                Order order = row < 0 || row >= shown.size() ? null : shown.get(row);
                editing[0] = order;
                boolean has = order != null;
                headed.run();
                customerField.setText(has ? order.customer : "");
                if (has) {
                    // With no order the combo keeps what it showed, disabled below: one that
                    // chooses a single item always holds one, and refuses an index that is none.
                    statusField.setSelectedIndex(order.status.ordinal());
                }
                placedField.setDate(has ? order.placed : null);
                dueField.setDate(has ? order.due : null);
                notes.setText(has ? order.notes : "");
                for (Widget<?> field : List.of(customerField, statusField, placedField, dueField, notes, save,
                        revert)) {
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
                int chosen = statusField.selectedIndex();
                order.status = chosen < 0 ? order.status : Status.values()[chosen];
                order.placed = placedField.date() == null ? order.placed : placedField.date();
                order.due = dueField.date() == null ? order.due : dueField.date();
                order.notes = notes.text();
                table.refresh(); // the selection is by record, and stays on the order just saved
                status.setText(ShowcaseStrings.ORDERS_SAVED.format(String.valueOf(order.number)));
            });
            revert.onAction(load);
            // What this screen wrote out itself is in the language it was written in: the cells
            // of the status column, the count and the form's heading are said again in the new one.
            I18n.observeChanges(() -> {
                table.refresh();
                counted.run();
                headed.run();
            });

            limn.scene.layout.Column form = new limn.scene.layout.Column();
            form.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
            Button close = new Button("").setSecondary(true).setIcon(limn.icons.tabler.TablerSystem.X.icon());
            close.setControlSize(ControlSize.SMALL);
            close.setAccessibleName(ShowcaseStrings.ACTION_CLOSE);
            close.setTooltip(ShowcaseStrings.ACTION_CLOSE);
            Row head = new Row();
            head.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
            head.add(Expanded.of(heading, 1));
            head.add(close);
            form.add(head);
            form.add(Separator.horizontal());
            form.add(Labelled.above(ShowcaseStrings.ORDERS_CUSTOMER, customerField));
            form.add(Labelled.above(ShowcaseStrings.ORDERS_STATUS, statusField));
            form.add(Labelled.above(ShowcaseStrings.ORDERS_PLACED, placedField));
            form.add(Labelled.above(ShowcaseStrings.ORDERS_DUE, dueField));
            form.add(Labelled.above(ShowcaseStrings.ORDERS_NOTES, notes, new SizedBox(SizedBox.UNSET, 84, notes)));
            Row buttons = new Row();
            buttons.gap(8).mainAlignment(Flex.MainAlignment.END);
            buttons.add(revert);
            buttons.add(save);
            form.add(buttons);

            // The order is opened on demand: Enter, a double click or a row's button brings its
            // form in over the table's trailing edge, and the arrows go on walking the rows
            // with it open.
            Drawer drawer = new Drawer(table, new ScrollView(new Padding(Insets.all(12), form)));
            table.onActivate(row -> drawer.setOpen(true));
            close.onAction(() -> {
                drawer.setOpen(false);
                table.requestFocus();
            });
            this.drawer = drawer;
            panel[0] = drawer;

            limn.scene.layout.Column column = new limn.scene.layout.Column();
            column.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
            column.add(tools);
            column.add(Expanded.of(drawer, 1));
            page = new Padding(Insets.symmetric(0, 12), column);
            filter.run();
            table.setSelectedRow(table.viewToModel(0)); // the newest, which the sort shows first
            load.run();
        }

        private Table<Order> table() {
            return table;
        }

        /** Asks for a new order in a dialog over the window, and lists it when the answer is yes. */
        Dialog openNewOrder() {
            TextField customer = new TextField();
            ComboBox<I18nString> state = ComboBox.localized(Status.labels());
            state.setSelectedIndex(Status.OPEN.ordinal());
            limn.scene.layout.Column fields = new limn.scene.layout.Column();
            fields.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
            fields.add(Labelled.above(ShowcaseStrings.ORDERS_CUSTOMER, customer));
            fields.add(Labelled.above(ShowcaseStrings.ORDERS_STATUS, state));
            Dialog dialog = new Dialog(ShowcaseStrings.MENU_NEW_ORDER, I18nString.EMPTY)
                    .setContent(new SizedBox(320, SizedBox.UNSET, fields))
                    .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                    .addPrimaryButton(ShowcaseStrings.ORDERS_CREATE, "create")
                    .setCancelResult("cancel");
            dialog.show(page).thenAccept(answer -> {
                if (!"create".equals(answer) || customer.text().isBlank()) {
                    return;
                }
                int next = all.stream().mapToInt(order -> order.number).max().orElse(1041) + 1;
                LocalDate today = LocalDate.now();
                Order order = new Order(next, customer.text().strip(), today, today.plusDays(30),
                        Status.values()[Math.max(0, state.selectedIndex())], 0, "");
                all.add(order);
                filter.run();
                int row = shown.indexOf(order);
                if (row >= 0) {
                    table.setSelectedRow(row);
                }
                status.setText(ShowcaseStrings.ORDERS_CREATED.format(String.valueOf(next)));
            });
            return dialog;
        }

        /**
         * Fetches the orders again, as a real screen would from a server: the work runs on a
         * worker thread for a second and a half while the spinner turns and every field stays
         * usable, and its answer comes back on the UI thread.
         */
        void refresh() {
            if (refreshing) {
                return;
            }
            refreshing = true;
            busy.setVisible(true);
            busy.setIndeterminate(true);
            status.setText(ShowcaseStrings.STATUS_LOADING);
            int records = all.size();
            Ui.async(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return records;
            }).thenAccept(count -> {
                refreshing = false;
                busy.setIndeterminate(false);
                busy.setVisible(false);
                status.setText(ShowcaseStrings.STATUS_LOADED.format(String.valueOf(count)));
            });
        }
    }
}
