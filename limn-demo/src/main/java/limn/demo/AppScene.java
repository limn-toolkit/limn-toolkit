package limn.demo;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Dialog;
import limn.components.Field;
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
import limn.i18n.DateFormats;
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

        /**
         * What a search also finds this page by, comma separated: the components on it, by their
         * class names, and the other words people use for them. English, as the API is.
         */
        String keywords = "";

        /** Says what else a search finds this place by. */
        Place keywords(String words) {
            this.keywords = words;
            return this;
        }

        /**
         * Whether {@code query}, already {@linkplain #fold folded}, is in this place's name, in the
         * language shown or in English, or in one of its keywords.
         */
        boolean matches(String query) {
            if (fold(name.get()).contains(query) || fold(name.english()).contains(query)) {
                return true;
            }
            for (String word : keywords.split(",")) {
                if (fold(word).contains(query)) {
                    return true;
                }
            }
            return false;
        }

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
        Place orders = new Place(ShowcaseStrings.NAV_ORDERS, null, ordersPage).icon("receipt")
                        .keywords("Table, order, invoice, Dialog, drawer, SearchField, ComboBox, badge, status");
        Place components = new Place(ShowcaseStrings.NAV_COMPONENTS,
                new Place(ShowcaseStrings.NAV_INPUTS, "components/#button",
                        () -> scrolling(BasicsScene.content())).icon("forms")
                        .keywords("Button, Checkbox, check box, Switch, toggle, RadioButton, radio, ButtonGroup, TextField, text field, input, PasswordField, password, SearchField, search, TextArea, text area, mask, validation, error, ProgressBar, progress, ToolBar, toolbar, SegmentedControl, segmented, Tooltip, Label, Separator, form"),
                new Place(ShowcaseStrings.NAV_COMBOS, "components/#combo-box",
                        () -> scrolling(CombosScene.content())).icon("select")
                        .keywords("ComboBox, combo box, select, dropdown, drop-down, autocomplete, chips, multi-select, multiple choice"),
                new Place(ShowcaseStrings.NAV_SLIDERS, "components/#slider",
                        () -> scrolling(ControlsScene.content())).icon("adjustments-horizontal")
                        .keywords("Slider, RangeSlider, range, Spinner, number, stepper, numeric"),
                new Place(ShowcaseStrings.TAB_DATES, "components/#date-picker",
                        () -> scrolling(DatesScene.content())).icon("calendar")
                        .keywords("DateField, DatePicker, date picker, CalendarView, calendar, date, time, period, month, year, era"),
                new Place(ShowcaseStrings.NAV_LISTS, "docs/lists-and-scrolling/#long-lists",
                        () -> scrolling(ListScene.content())).icon("list")
                        .keywords("ListView, list, contacts, virtual list, selection"),
                new Place(ShowcaseStrings.NAV_TABLES, "docs/lists-and-scrolling/#tables",
                        () -> beside(TableScene.content())).icon("table")
                        .keywords("Table, grid, data grid, column, sort, footer, inventory, spreadsheet"),
                new Place(ShowcaseStrings.NAV_TREES, "docs/lists-and-scrolling/#trees",
                        () -> beside(TreeScene.content())).icon("binary-tree")
                        .keywords("Tree, tree view, outline, explorer, folder, hierarchy"),
                new Place(ShowcaseStrings.NAV_DIALOGS, "docs/menus-and-dialogs/#dialogs",
                        () -> scrolling(DialogsScene.dialogs(status))).icon("app-window")
                        .keywords("Dialog, alert, modal, confirm, message box, window, popup"),
                new Place(ShowcaseStrings.TAB_MENUS, "docs/menus-and-dialogs/#menus",
                        () -> scrolling(DialogsScene.menus(status))).icon("menu-2")
                        .keywords("Menu, MenuItem, PopupMenu, popup menu, ContextMenus, context menu, right click, MenuBar, menu bar, accelerator, shortcut, mnemonic"),
                new Place(ShowcaseStrings.NAV_DRAG_DROP, "docs/drag-and-drop/",
                        () -> scrolling(PickerScene.content())).icon("drag-drop")
                        .keywords("drag, drop, drag and drop, Drag, reorder, kanban, drop zone"),
                new Place(ShowcaseStrings.NAV_CLIPBOARD, "docs/drag-and-drop/#the-clipboard",
                        () -> scrolling(TransferScene.content())).icon("clipboard")
                        .keywords("Clipboard, copy, cut, paste"),
                new Place(ShowcaseStrings.NAV_TRAY, "docs/menus-and-dialogs/#an-icon-in-the-tray",
                        () -> scrolling(TrayScene.page(status))).icon("bell")
                        .keywords("Tray, tray icon, system tray, notification area, status bar icon"),
                new Place(ShowcaseStrings.NAV_CHARTS, "docs/charts/", () -> scrolling(ChartsScene.content())).icon("chart-bar")
                        .keywords("Chart, BarChart, bar chart, LineChart, line chart, PieChart, pie, donut, graph, dashboard, legend"),
                new Place(ShowcaseStrings.NAV_TEXT, "docs/text-and-languages/",
                        () -> scrolling(BidiScene.tabContent())).icon("language")
                        .keywords("Label, text, bidi, right to left, RTL, Arabic, Hebrew, CJK, Japanese, Chinese, Korean, font, typography, bold, italic, script"),
                new Place(ShowcaseStrings.NAV_ICONS, "docs/images-and-media/#icons",
                        () -> beside(IconsScene.content())).icon("icons")
                        .keywords("Icon, Tabler, SvgIcon, SVG, BitmapIcon, glyph"),
                new Place(ShowcaseStrings.TAB_FILES, "docs/menus-and-dialogs/#native-file-dialogs",
                        () -> beside(FilesScene.content())).icon("folder")
                        .keywords("FileDialogs, file, file chooser, open file, save file, folder, directory"),
                new Place(ShowcaseStrings.NAV_COLOURS, "components/#color-picker",
                        () -> scrolling(ColorPickerScene.content())).icon("palette")
                        .keywords("ColorPicker, color picker, ColorPickerButton, color, colour, swatch, Theme, Elevation, Surface, appearance"),
                new Place(ShowcaseStrings.NAV_SPLIT, "docs/lists-and-scrolling/#splitting-a-window",
                        () -> beside(SplitPaneScene.content())).icon("layout-columns")
                        .keywords("SplitPane, split pane, divider, splitter, panes, resize"),
                new Place(ShowcaseStrings.NAV_SECTIONS, "docs/lists-and-scrolling/#tabs",
                        () -> scrolling(SectionsScene.content())).icon("layout-navbar-expand")
                        .keywords("TabbedPane, tabs, tab, Expander, ExpanderGroup, accordion, collapsible, section, Card, card, tile, record"),
                new Place(ShowcaseStrings.NAV_MOTION, "docs/animations/",
                        () -> scrolling(AnimationsScene.content())).icon("player-play")
                        .keywords("Animation, animate, Tween, easing, transition, ColorTransition, motion, fade")).icon("components");
        Place media = new Place(ShowcaseStrings.NAV_MEDIA,
                new Place(ShowcaseStrings.NAV_VIDEO, "docs/images-and-media/#video",
                        () -> scrolling(VideoScene.pageContent())).icon("video")
                        .keywords("VideoView, video, MediaPlayer, MediaControls, Poster, player, movie"),
                new Place(ShowcaseStrings.NAV_VIEWPORT, "docs/images-and-media/#3d",
                        () -> scrolling(Viewport3DScene.tabContent())).icon("cube")
                        .keywords("Viewport3D, 3D, glTF, model, render, scene"),
                new Place(ShowcaseStrings.NAV_SOUND, "docs/images-and-media/#sound",
                        () -> scrolling(AudioScene.content())).icon("volume")
                        .keywords("Sounds, sound, audio, AudioClip, music, mixer, volume, pan"),
                new Place(ShowcaseStrings.NAV_SPRITES, "docs/images-and-media/#pictures",
                        () -> scrolling(SpritesScene.content())).icon("photo")
                        .keywords("ImageView, image, picture, sprite, GIF, PNG, WebP, animated image, blend, pixel art"),
                new Place(ShowcaseStrings.NAV_GLASS, "docs/theming/#glass",
                        () -> scrolling(GlassScene.content())).icon("blur")
                        .keywords("BackdropPanel, backdrop, glass, blur, frosted, translucent, tint")).icon("movie");
        return List.of(orders, components, media);
    }

    /** Standalone {@code --scene app}, and what the demo opens on when no scene is named. */
    static Built create() {
        MenuBar menuBar = new MenuBar();
        Label status = new Label(ShowcaseStrings.STATUS_READY).setMuted(true);
        Page.reportTo(status);
        ProgressBar busy = new ProgressBar().setThickness(6).setPreferredWidth(120);
        busy.setVisible(false);
        busy.setAccessibleName(ShowcaseStrings.STATUS_LOADING);
        PerfFooter performance = new PerfFooter();
        performance.setAccessibleName(ShowcaseStrings.PERFORMANCE_STRIP);
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

        // What a search leaves of the navigation: the places shown, and for a heading the pages
        // under it that matched. Empty query, everything.
        List<Place> shownRoots = new ArrayList<>(top);
        Map<Place, List<Place>> shownUnder = new HashMap<>();
        Tree<Place> navigation = new Tree<>(new Tree.Model<Place>() {
            @Override
            public List<Place> roots() {
                return shownRoots;
            }

            @Override
            public List<Place> children(Place place) {
                return shownUnder.getOrDefault(place, place.under);
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

        // ---- the search over the navigation: a page by its name, or by a component on it
        SearchField search = new SearchField();
        search.setPlaceholder(NavText.SEARCH);
        search.setAccessibleName(NavText.SEARCH);
        Label noMatch = new Label(NavText.NO_MATCH).setMuted(true).setWrap(true);
        noMatch.setVisible(false);
        java.util.function.Consumer<String> filter = text -> {
            String query = fold(text);
            shownRoots.clear();
            shownUnder.clear();
            for (Place place : top) {
                if (query.isEmpty() || place.matches(query)) {
                    shownRoots.add(place); // a heading that matches keeps every page under it
                    continue;
                }
                List<Place> kept = place.under.stream().filter(each -> each.matches(query)).toList();
                if (!kept.isEmpty()) {
                    shownRoots.add(place);
                    shownUnder.put(place, kept);
                }
            }
            navigation.refresh();
            for (Place place : shownRoots) {
                navigation.expand(place);
            }
            noMatch.setVisible(shownRoots.isEmpty());
            if (!query.isEmpty()) {
                // The first page left is shown as the word is typed, and the keyboard stays in
                // the field: typing "checkbox" is enough to see the check boxes.
                Place first = firstPage(shownRoots, shownUnder);
                if (first != null) {
                    navigation.setSelected(first);
                }
            }
        };
        search.onChange(filter);
        // What the Go menu opens may be filtered away: the search is cleared first.
        Runnable showAll = () -> {
            if (!search.text().isEmpty()) {
                search.setText("");
                filter.accept("");
            }
        };
        limn.scene.layout.Column side = new limn.scene.layout.Column();
        side.crossAlignment(Flex.CrossAlignment.STRETCH);
        side.add(new Padding(new Insets(8, 8, 4, 8), search));
        side.add(new Padding(Insets.symmetric(4, 12), noMatch));
        side.add(Expanded.of(navigation, 1));

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
        String typed = System.getProperty("limn.demo.search"); // lab: film the navigation searched
        if (typed != null) {
            search.setText(typed);
            filter.accept(typed);
        }

        // ---- the band: the title and the four switches that reach the whole process
        ComboBox<String> language = new ComboBox<>(Languages.NAMES);
        language.setSelectedIndex(Languages.indexOf(I18n.locale()));
        language.setAccessibleName(ShowcaseStrings.BAND_LANGUAGE);
        ComboBox<I18nString> direction = ComboBox.localized(ShowcaseStrings.DIRECTIONS);
        direction.setSelectedIndex(LayoutDirection.processDefault().ordinal());
        // A language brings its direction with it, as an application following its user's
        // language would: Arabic and Hebrew read right to left. The direction's own selector stays,
        // to try either direction in any language.
        language.onSelect(() -> {
            java.util.Locale chosen = Languages.LOCALES.get(language.selectedIndex());
            I18n.setLocale(chosen);
            LayoutDirection reads = LayoutDirection.forLocale(chosen);
            LayoutDirection.setProcessDefault(reads);
            direction.setSelectedIndex(reads.ordinal());
        });
        direction.onSelect(() -> LayoutDirection.setProcessDefault(
                LayoutDirection.values()[direction.selectedIndex()]));
        direction.setAccessibleName(ShowcaseStrings.BAND_DIRECTION);
        ComboBox<I18nString> size = ComboBox.localized(ShowcaseStrings.SIZES);
        size.setSelectedIndex(ControlSize.processDefault().ordinal());
        size.onSelect(() -> ControlSize.setProcessDefault(ControlSize.values()[size.selectedIndex()]));
        size.setAccessibleName(ShowcaseStrings.BAND_SIZE);
        // The first choice is the system's own appearance, followed as it changes; every other
        // is a palette picked by hand, kept whatever the system does.
        List<I18nString> themeNames = new ArrayList<>();
        themeNames.add(ShowcaseStrings.THEME_SYSTEM);
        Theme.builtins().forEach(each -> themeNames.add(each.displayName()));
        ComboBox<I18nString> theme = ComboBox.localized(themeNames);
        theme.setSelectedIndex(1 + Math.max(0, Theme.builtins().indexOf(Theme.current())));
        theme.setAccessibleName(ShowcaseStrings.BAND_THEME);

        Row band = new Row();
        band.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        band.add(new Label(TITLE).setRole(Label.Role.TITLE));
        band.add(Expanded.spacer(1));
        band.add(language);
        band.add(direction);
        band.add(size);
        band.add(theme);

        SplitPane body = SplitPane.horizontal(side, holder);
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

        // The call a theme picker makes: the palette becomes current, its face is applied, and
        // the scene clears to its background and repaints. A palette is a repaint, never a
        // re-measure; the face, when it changes, has every scene laid out again by itself.
        //
        // Two ways to a palette, and the picker says which is in force. "System" is the
        // system's light or dark appearance, taken now and again whenever it changes; any other
        // choice is that palette, and the system changing does not touch it.
        boolean[] following = {false};
        Runnable applySystemAppearance = () -> {
            SystemPreferences.ColorScheme scheme = SystemPreferences.current().colorScheme();
            if (!following[0] || scheme == SystemPreferences.ColorScheme.NO_PREFERENCE) {
                return; // a desktop that states no preference keeps the palette it has
            }
            Theme wanted = scheme == SystemPreferences.ColorScheme.DARK ? Theme.dark() : Theme.light();
            if (wanted != Theme.current()) {
                wanted.apply();
            }
        };
        theme.onSelect(() -> {
            following[0] = theme.selectedIndex() == 0;
            if (following[0]) {
                applySystemAppearance.run();
            } else {
                Theme.builtins().get(theme.selectedIndex() - 1).apply();
            }
        });
        // And when something else changes the theme, a launch option or the system's
        // appearance: the window repaints in it by itself, and a picker that names a palette
        // names this one.
        Theme.observeChanges(() -> {
            int now = Theme.builtins().indexOf(Theme.current());
            if (!following[0] && now >= 0 && now + 1 != theme.selectedIndex()) {
                theme.setSelectedIndex(now + 1);
            }
        });
        SystemPreferences.observeChanges(applySystemAppearance); // for the life of the demo
        Runnable followSystemAppearance = () -> {
            following[0] = true;
            theme.setSelectedIndex(0);
            applySystemAppearance.run();
        };

        Runnable newOrder = () -> {
            navigation.setSelected(ordersPlace);
            orders.openNewOrder();
        };
        // Each menu's access letter is marked in its title, in each language ("&File", "&Arquivo"),
        // and each command has its chord: Alt and the letter opens a menu where the system's menu
        // bar is not the window's, and a chord runs its command with every menu closed. The six
        // editing commands are the toolkit's own words and carry no letter here.
        menuBar.addMenu(ShowcaseStrings.MENU_FILE, new Menu()
                .add(MenuItem.of(ShowcaseStrings.MENU_NEW_ORDER, newOrder)
                        .setAccelerator(limn.components.Accelerator.command(limn.input.Keys.N)))
                .add(MenuItem.of(ShowcaseStrings.ORDERS_REFRESH, orders::refresh)
                        .setAccelerator(limn.components.Accelerator.of(limn.input.Keys.F5)))
                .addSeparator()
                // Where the system keeps Quit in a menu of its own, this item is not shown and the
                // system's asks the same question.
                .add(MenuItem.of(ShowcaseStrings.MENU_QUIT, () -> {
                    if (scene.window() != null) {
                        scene.window().backend().requestQuit();
                    }
                }).setRole(MenuItem.Role.QUIT)
                        .setAccelerator(limn.components.Accelerator.command(limn.input.Keys.Q))));
        // The six editing commands, each acting on whatever has the keyboard; each role brings
        // its platform's chord.
        menuBar.addMenu(ShowcaseStrings.MENU_EDIT, new Menu()
                .add(MenuItem.of(MenuItem.Role.UNDO))
                .add(MenuItem.of(MenuItem.Role.REDO))
                .addSeparator()
                .add(MenuItem.of(MenuItem.Role.CUT))
                .add(MenuItem.of(MenuItem.Role.COPY))
                .add(MenuItem.of(MenuItem.Role.PASTE))
                .add(MenuItem.of(MenuItem.Role.SELECT_ALL)));
        menuBar.addMenu(ShowcaseStrings.MENU_VIEW, new Menu()
                .add(MenuItem.check(ShowcaseStrings.MENU_PERFORMANCE, false, performanceStrip::setVisible)
                        )
                .addSeparator()
                .add(MenuItem.check(ShowcaseStrings.RENDER_PARTIAL, true, scene::setPartialRendering)
                        )
                .add(MenuItem.check(ShowcaseStrings.RENDER_DAMAGE, false, scene::setDamageDebug)
                        )
                .addSeparator()
                .add(MenuItem.submenu(ShowcaseStrings.MENU_FULLSCREEN, new Menu()
                        .add(MenuItem.of(ShowcaseStrings.MENU_CURRENT_RESOLUTION, () -> fullscreen(scene, status, 0, 0))
                                )
                        .addItem("1280×720", () -> fullscreen(scene, status, 1280, 720)))));
        menuBar.addMenu(ShowcaseStrings.MENU_GO, goMenu(navigation, top, showAll));
        Menu help = new Menu()
                .add(MenuItem.of(ShowcaseStrings.MENU_DOCUMENTATION, () -> openDocumentation(status, "docs/", status))
                        .setAccelerator(limn.components.Accelerator.of(limn.input.Keys.F1)))
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
        status.setText(ShowcaseStrings.HEAD_DOCUMENTATION.get() + ": " + address);
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
        Button link = new Button(ShowcaseStrings.HEAD_DOCUMENTATION).setSecondary(true);
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
        if (Boolean.getBoolean("limn.demo.accessLetters")) {
            // Film the access letters on a Mac, which has none: the system answered as Windows and
            // Linux do, and Alt held down from the first frames.
            limn.scene.internal.AccessLetterSystem.setPresentForTests(true);
            Ui.postDelayed(() -> limn.scene.internal.SceneAccess.input(scene)
                    .keyEvent(limn.input.Keys.LEFT_ALT, -1, true, false, limn.input.Keys.MOD_ALT), 300);
        }
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
                    scene.window().captureNextFrame(image -> {
                        limn.graphics.Images.save(image,
                                limn.graphics.ImageFormat.PNG, java.nio.file.Path.of(film, String.format("f%03d.png", n)));
                        if (Boolean.getBoolean("limn.demo.film.scroll")) {
                            // The page down by most of a screen after each picture, so a long page
                            // is filmed whole: the largest scroll view outside the navigation
                            // that has somewhere to go, the inner one where a page nests two.
                            ScrollView shown = largestScrollView(scene.root(), navigation, null);
                            if (shown != null) {
                                System.err.printf("film f%03d: offset %.0f of %.0f, viewport %.0f%n",
                                        n, shown.offsetY(), shown.maxOffsetY(), shown.viewportHeight());
                                shown.scrollBy(0, shown.height() * 0.85f);
                            }
                        }
                    });
                }
                Ui.postDelayed(take[0], Long.getLong("limn.demo.film.ms", 500));
            };
            Ui.postDelayed(take[0], 1500);
        }
    }

    /** The largest scroll view under {@code at} with room to scroll down, not inside {@code skip}. */
    private static ScrollView largestScrollView(Widget<?> at, Widget<?> skip, ScrollView best) {
        if (at == skip) {
            return best;
        }
        if (at instanceof ScrollView view && view.isShowing() && view.maxOffsetY() > 0
                && (best == null || view.width() * view.height() > best.width() * best.height())) {
            best = view;
        }
        for (Widget<?> child : at.children()) {
            best = largestScrollView(child, skip, best);
        }
        return best;
    }

    private static Menu goMenu(Tree<Place> navigation, List<Place> top, Runnable showAll) {
        Menu go = new Menu();
        for (Place place : top) {
            if (place.page != null) {
                go.addItem(place.name, () -> {
                    showAll.run();
                    navigation.setSelected(place);
                });
                continue;
            }
            Menu under = new Menu();
            for (Place each : place.under) {
                under.addItem(each.name, () -> {
                    showAll.run();
                    navigation.expand(place);
                    navigation.setSelected(each);
                });
            }
            go.addSubmenu(place.name, under);
        }
        return go;
    }

    /**
     * A word as a search compares it: lower case, accents off, and nothing but letters and digits,
     * so "check box", "Checkbox" and "check-box" are one word, and "seção" finds "secao".
     */
    static String fold(String text) {
        String bare = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder(bare.length());
        bare.codePoints().filter(Character::isLetterOrDigit)
                .forEach(c -> out.appendCodePoint(Character.toLowerCase(c)));
        return out.toString();
    }

    /** The first page a search left, in the navigation's order, or null. */
    private static Place firstPage(List<Place> roots, Map<Place, List<Place>> under) {
        for (Place place : roots) {
            if (place.page != null) {
                return place;
            }
            List<Place> pages = under.getOrDefault(place, place.under);
            if (!pages.isEmpty()) {
                return pages.get(0);
            }
        }
        return null;
    }

    /** The navigation's own strings. */
    static final class NavText {
        static final I18nString SEARCH =
                new I18nString("showcase.nav.search", "Page or component");
        static final I18nString NO_MATCH =
                new I18nString("showcase.nav.noMatch", "No page has that.");

        private NavText() {
        }
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
        /** What takes the keyboard back when Escape closes the panel: the main widget, or one inside it. */
        private Widget<?> focusHome;
        private final limn.components.Surface panel;
        private boolean open;
        /** How far in the panel is, from 0 to 1, in time; eased when it is laid out. */
        private float progress;
        private boolean ticking;

        Drawer(Widget<?> main, Widget<?> content) {
            this.main = main;
            this.focusHome = main;
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

        /** Names the widget Escape hands the keyboard back to, when the main one wraps it. */
        void focusHome(Widget<?> widget) {
            this.focusHome = widget;
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
                focusHome.requestFocus();
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
        // The day the sample is read on, five days after the last order was placed: an unpaid
        // order is overdue once its due date is behind it, and not before.
        LocalDate today = first.plusDays(44);
        for (int i = 0; i < 48; i++) {
            LocalDate placed = first.plusDays(i * 40L / 48);
            LocalDate due = placed.plusDays(new int[]{30, 7, 14}[i % 3]); // the order's payment terms
            Status status = statuses[(i * 7 + i / 5) % statuses.length];
            if (status == Status.OVERDUE && !due.isBefore(today)) {
                status = Status.OPEN;
            } else if (status == Status.OPEN && due.isBefore(today)) {
                status = Status.OVERDUE;
            }
            orders.add(new Order(1042 + i, customers[i * 5 % customers.length], placed, due,
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
                    .width(84);
            // The footer's words for what the sum under Total adds up: "48 orders".
            Column<Order> customer = Column.<Order>text(ShowcaseStrings.ORDERS_CUSTOMER, order -> order.customer)
                    .width(130).weight(1).footer(rows -> Text.COUNT.format(rows.size()));
            // DateFormats and not the platform's formatter, which writes ASCII digits in every
            // language: under ar-EG the dates read in other digits than the totals beside them.
            Column<Order> placed = Column.<Order, LocalDate>of(ShowcaseStrings.ORDERS_PLACED, order -> order.placed,
                    DateFormats.date(FormatStyle.SHORT)).width(118);
            Column<Order> due = Column.<Order, LocalDate>of(ShowcaseStrings.ORDERS_DUE, order -> order.due,
                    DateFormats.date(FormatStyle.SHORT)).width(118);
            // A pill per row, coloured by where the order stands; it sorts in the order an order
            // moves through, not by the word, which changes with the language.
            Column<Order> state = Column.<Order>widget(ShowcaseStrings.ORDERS_STATUS,
                    order -> new StatusBadge(order.status))
                    .comparator(java.util.Comparator.comparingInt(order -> order.status.ordinal())).width(128);
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
            }).width(64).sortable(false);
            table = new Table<>(List.of(number, customer, placed, due, state, total, open));
            // What a tool driving the application from outside finds these three by.
            table.setId("orders");
            table.setAccessibleName(ShowcaseStrings.NAV_ORDERS);
            table.setRows(shown);
            table.setSort(placed, SortOrder.DESCENDING);
            // Several at once, with Shift and Ctrl or Command, for what is done to many orders.
            table.setSelectionMode(SelectionMode.MULTI);

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

            // What the rows area says when the filter leaves nothing, and the way back.
            Button clearFilter = new Button(Text.CLEAR_FILTER).setSecondary(true);
            limn.scene.layout.Column nothing = new limn.scene.layout.Column();
            nothing.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            nothing.add(new Label(Text.NO_MATCH).setStrong(true)
                    .setIcon(limn.icons.tabler.TablerSystem.SEARCH.icon()));
            nothing.add(clearFilter);
            EmptyState rows = new EmptyState(table, nothing);
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
                rows.setEmpty(shown.isEmpty());
            };
            search.onChange(text -> filter.run());
            statuses.onSelect(filter);
            clearFilter.onAction(() -> {
                search.clear();
                statuses.clearSelection();
                filter.run();
                search.requestFocus();
            });

            // What is done to every selected order at once. Enabled while one of them is not
            // paid yet; the rows stay where they are, so the user sees each badge turn green.
            Button markPaid = new Button(Text.MARK_PAID).setSecondary(true)
                    .setIcon(limn.icons.tabler.TablerSystem.CHECKS.icon());
            markPaid.setId("orders-mark-paid");
            Runnable markable = () -> {
                boolean any = false;
                for (int row : table.selectedRows()) {
                    any |= row < shown.size() && shown.get(row).status != Status.PAID;
                }
                markPaid.setEnabled(any);
            };
            markPaid.onAction(() -> {
                int marked = 0;
                for (int row : table.selectedRows()) {
                    if (row < shown.size() && shown.get(row).status != Status.PAID) {
                        shown.get(row).status = Status.PAID;
                        marked++;
                    }
                }
                table.refresh();
                markable.run();
                status.setText(Text.MARKED_PAID.format(marked));
            });

            Row tools = new Row();
            tools.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
            tools.add(new Label(ShowcaseStrings.NAV_ORDERS).setRole(Label.Role.TITLE));
            tools.add(Expanded.spacer(1));
            tools.add(count);
            tools.add(new SizedBox(196, SizedBox.UNSET, search));
            tools.add(new SizedBox(170, SizedBox.UNSET, statuses));
            tools.add(markPaid);
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
            // The fields and their buttons, disabled as one while no order is chosen; the head
            // above them, with its close button, is not.
            limn.scene.layout.Column fields = new limn.scene.layout.Column();
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
                fields.setEnabled(has);
            };
            table.observeChanges((widget, change) -> {
                if (change.aspect() == Change.Aspect.SELECTION) {
                    load.run();
                    markable.run();
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
                markable.run();
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
            fields.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
            fields.add(new Field(ShowcaseStrings.ORDERS_CUSTOMER, customerField));
            fields.add(new Field(ShowcaseStrings.ORDERS_STATUS, statusField));
            fields.add(new Field(ShowcaseStrings.ORDERS_PLACED, placedField));
            fields.add(new Field(ShowcaseStrings.ORDERS_DUE, dueField));
            fields.add(new Field(ShowcaseStrings.ORDERS_NOTES, notes, new SizedBox(SizedBox.UNSET, 84, notes)));
            Row buttons = new Row();
            buttons.gap(8).mainAlignment(Flex.MainAlignment.END);
            buttons.add(revert);
            buttons.add(save);
            fields.add(buttons);
            form.add(fields);

            // The order is opened on demand: Enter, a double click or a row's button brings its
            // form in over the table's trailing edge, and the arrows go on walking the rows
            // with it open.
            Drawer drawer = new Drawer(rows, new ScrollView(new Padding(Insets.all(12), form)));
            drawer.focusHome(table);
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
            fields.add(new Field(ShowcaseStrings.ORDERS_CUSTOMER, customer));
            fields.add(new Field(ShowcaseStrings.ORDERS_STATUS, state));
            Dialog dialog = new Dialog(ShowcaseStrings.NEW_ORDER_TITLE, I18nString.EMPTY)
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

        /** What the orders screen says beyond the strings the frame shares. */
        static final class Text {
            static final limn.i18n.PluralString COUNT = limn.i18n.PluralString.of(
                    "showcase.orders.footerCount", "{0} order", "{0} orders");
            static final I18nString NO_MATCH =
                    new I18nString("showcase.orders.noMatch", "No orders match the filter");
            static final I18nString CLEAR_FILTER =
                    new I18nString("showcase.orders.clearFilter", "Clear filter");
            static final I18nString MARK_PAID =
                    new I18nString("showcase.orders.markPaid", "Mark as paid");
            static final limn.i18n.PluralString MARKED_PAID = limn.i18n.PluralString.of(
                    "showcase.orders.markedPaid", "{0} order marked as paid.", "{0} orders marked as paid.");

            private Text() {
            }
        }
    }

    /**
     * Where an order stands, as a pill tinted by it: the colour says it at a glance, the word
     * says it to everyone, a reader and a person who does not tell green from red included.
     */
    static final class StatusBadge extends Widget<StatusBadge> {
        private static final float PAD = 8;
        private static final float DOT = 6;
        private final Status status;
        private final Label label;

        StatusBadge(Status status) {
            this.status = status;
            this.label = new Label(status.label);
            label.setRole(Label.Role.LABEL);
            // Read as the word it shows: the pill is the label, and the word inside it is not
            // said a second time.
            label.setAccessibleIgnored(true);
            setAccessibleRole(limn.accessibility.Accessible.Role.LABEL);
            setAccessibleName(status.label);
            add(label);
        }

        private limn.graphics.Color tone(Theme theme) {
            return switch (status) {
                case OPEN -> theme.info();
                case SHIPPED -> theme.primary();
                case PAID -> theme.success();
                case OVERDUE -> theme.danger();
                case CANCELLED -> theme.textMuted();
            };
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            float room = Math.max(0, constraints.maxWidth() - 2 * PAD - DOT - PAD / 2);
            limn.scene.Size text = label.measure(limn.scene.Constraints.loose(room, constraints.maxHeight()));
            return constraints.constrain(text.width() + 2 * PAD + DOT + PAD / 2, text.height() + 4);
        }

        @Override
        protected void onLayout() {
            limn.scene.Size text = label.measure(limn.scene.Constraints.loose(
                    Math.max(0, width() - 2 * PAD - DOT - PAD / 2), height()));
            float start = PAD + DOT + PAD / 2;
            float x = isRightToLeft() ? width() - start - text.width() : start;
            label.layoutBox(x, (height() - text.height()) / 2, text.width(), text.height());
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            Theme theme = Theme.of(this);
            limn.graphics.Color tone = tone(theme);
            canvas.fillRoundRect(0, 0, width(), height(), height() / 2,
                    tone.withAlpha(theme.isDark() ? 0.26f : 0.16f));
            float dotX = isRightToLeft() ? width() - PAD - DOT : PAD;
            canvas.fillRoundRect(dotX, (height() - DOT) / 2, DOT, DOT, DOT / 2, tone);
        }
    }
}
