package limn.demo;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Button;
import limn.components.ColorPickerButton;
import limn.components.Label;
import limn.components.ListView;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.PopupMenu;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.SelectionMode;
import limn.components.Separator;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.i18n.I18nString;
import limn.i18n.PluralString;
import limn.icons.tabler.Tabler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.transfer.DataFormat;
import limn.transfer.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The whole Tabler pack as a grid of tiles, searchable, at four sizes, outline or filled, in a
 * tint of the user's choosing, with the chosen icon's call, its filled twin and the icon at work
 * in a button and a menu.
 *
 * <p>It is a load test as much as a catalogue: several thousand tiles, each a vector icon
 * rasterized at the size it is drawn at. Only the rows on screen are ever built, because the
 * list mounts what it shows and recycles what it scrolls past, so the pack costs what is visible
 * rather than what exists.
 */
final class IconsScene {

    /** The strings of this page, ready for translation; icon names are data and stay literal. */
    static final class Text {
        static final I18nString SEARCH = new I18nString("showcase.icons.search", "Search icons");
        static final I18nString SIZE = new I18nString("showcase.icons.size", "Icon size");
        static final I18nString STYLE = new I18nString("showcase.icons.style", "Style");
        static final I18nString OUTLINE = new I18nString("showcase.icons.outline", "Outline");
        static final I18nString FILLED = new I18nString("showcase.icons.filled", "Filled");
        static final I18nString TINT = new I18nString("showcase.icons.tint", "Tint");
        static final PluralString COUNT = PluralString.of("showcase.icons.count", "{0} icon", "{0} icons");
        static final I18nString COPY = new I18nString("showcase.icons.copy", "Copy");
        static final I18nString COPIED = new I18nString("showcase.icons.copied", "Copied {0}");
        static final I18nString FILLED_TWIN = new I18nString("showcase.icons.filledTwin", "Filled");
        static final I18nString NO_FILLED = new I18nString("showcase.icons.noFilled", "No filled version");
        static final I18nString IN_USE = new I18nString("showcase.icons.inUse", "In a button and a menu");
        static final I18nString ACTIONS = new I18nString("showcase.icons.actions", "Actions");
        static final I18nString DUPLICATE = new I18nString("showcase.icons.duplicate", "Duplicate");
        static final I18nString ARCHIVE = new I18nString("showcase.icons.archive", "Archive");
        static final I18nString CHOSE = new I18nString("showcase.icons.chose", "Chose {0}");

        private Text() {
        }
    }

    /** Tiles across a row of the grid. */
    private static final int COLUMNS = 6;

    /** The sizes offered, in points. */
    private static final int[] SIZES = {16, 20, 24, 32};

    /** The detail panel's width, and the size its icon is shown at. */
    private static final float DETAIL_WIDTH = 260;
    private static final float DETAIL_ICON = 72;

    private IconsScene() {
    }

    /** What every tile and the detail panel read: the filter, the look, and the chosen icon. */
    private static final class Catalogue {
        final List<String> all = Tabler.names();
        List<String> shown = all;
        float size = 24;
        boolean filled;
        @Nullable Color tint; // null: the theme's text colour
        String chosen = "file-invoice";

        void filter(String query) {
            String needle = query.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                shown = all;
                return;
            }
            List<String> matches = new ArrayList<>();
            for (String name : all) {
                if (name.contains(needle)) {
                    matches.add(name);
                }
            }
            shown = matches;
        }

        /** The shown names, a row of {@link #COLUMNS} at a time. */
        List<List<String>> rows() {
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < shown.size(); i += COLUMNS) {
                rows.add(shown.subList(i, Math.min(shown.size(), i + COLUMNS)));
            }
            return rows;
        }

        Icon icon(String name) {
            return filled && Tabler.hasFilled(name) ? Tabler.filled(name) : Tabler.outline(name);
        }
    }

    /** {@code --scene icons}. */
    static Scene create(boolean light) {
        return create(light, "");
    }

    /** @param query pre-filled into the search box, so a capture can show it filtering */
    static Scene create(boolean light, String query) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content(query)));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    static Widget<?> content() {
        return content("");
    }

    static Widget<?> content(String query) {
        Catalogue catalogue = new Catalogue();
        Detail detail = new Detail(catalogue);

        // Pooled, so scrolling a five-thousand-icon catalogue stops allocating: a row that
        // scrolls out is bound to the next row that scrolls in.
        ListView<List<String>> grid = ListView.pooled(() -> new TileRow(catalogue, detail), TileRow::show);
        grid.setSelectionMode(SelectionMode.NONE);
        grid.setAccessibleName(ShowcaseStrings.NAV_ICONS);
        detail.grid = grid;

        Label count = new Label("").setMuted(true);
        Runnable refill = () -> {
            grid.setItems(catalogue.rows());
            count.setText(Text.COUNT.format(catalogue.shown.size()));
        };

        SearchField search = new SearchField();
        search.setPlaceholder(Text.SEARCH);
        search.onChange(typed -> {
            catalogue.filter(typed);
            refill.run();
        });
        if (!query.isEmpty()) {
            search.setText(query);
            catalogue.filter(query);
        }

        List<String> sizes = new ArrayList<>();
        for (int size : SIZES) {
            sizes.add(Integer.toString(size));
        }
        SegmentedControl size = new SegmentedControl(sizes);
        size.setSelectedIndex(2);
        size.setAccessibleName(Text.SIZE);
        size.onSelect(index -> {
            catalogue.size = SIZES[index];
            refill.run();
        });

        SegmentedControl style = new SegmentedControl(Text.OUTLINE, Text.FILLED);
        style.setAccessibleName(Text.STYLE);
        style.onSelect(index -> {
            catalogue.filled = index == 1;
            refill.run();
            detail.show();
        });

        ColorPickerButton tint = new ColorPickerButton(Theme.current().text());
        tint.setText(Text.TINT);
        tint.setAlphaEnabled(false);
        // Until the user picks one, the chip shows the theme's ink, which is what the icons are
        // drawn in: the palette in force when the page is built is not the one it ends up in.
        Theme.observeChanges(() -> {
            if (catalogue.tint == null) {
                tint.setColor(Theme.current().text());
            }
        });
        tint.onChange(colour -> {
            catalogue.tint = colour;
            grid.invalidate();
            detail.show();
        });

        Row tools = Page.row(10, search, size, style, tint, count);
        tools.remove(search);
        tools.add(0, Expanded.of(search, 1));
        refill.run();
        detail.show();

        Row body = new Row();
        body.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(Expanded.of(grid, 1));
        body.add(Separator.vertical());
        body.add(new SizedBox(DETAIL_WIDTH, SizedBox.UNSET, detail.panel));

        Column page = Page.column();
        page.add(tools);
        page.add(Expanded.of(body, 1));
        return new Padding(new Insets(0, 0, 12, 0), page);
    }

    /** One row of the grid: {@link #COLUMNS} tiles, each an equal share of the width. */
    private static final class TileRow extends Row {

        private final Tile[] tiles = new Tile[COLUMNS];

        TileRow(Catalogue catalogue, Detail detail) {
            gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
            for (int i = 0; i < COLUMNS; i++) {
                tiles[i] = new Tile(catalogue, detail);
                add(Expanded.of(tiles[i], 1));
            }
        }

        void show(List<String> names) {
            for (int i = 0; i < COLUMNS; i++) {
                tiles[i].show(i < names.size() ? names.get(i) : null);
            }
        }
    }

    /**
     * An icon above its name, chosen by a click, Space or Enter, or a reader's press. Recycled, so
     * it is rebound rather than rebuilt.
     */
    private static final class Tile extends Widget<Tile> {

        private final Catalogue catalogue;
        private final Detail detail;
        private final IconView glyph = new IconView();
        private final Label name = new Label("")
                .setOverflow(Label.Overflow.ELLIPSIS)
                .setAlign(Label.HAlign.CENTER, Label.VAlign.TOP);
        private final Column column = new Column();
        private @Nullable String shown;
        private long revision;
        private boolean hovered;

        Tile(Catalogue catalogue, Detail detail) {
            this.catalogue = catalogue;
            this.detail = detail;
            column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
            column.add(glyph);
            column.add(name);
            add(new Padding(Insets.symmetric(10, 4), column));
            setFocusable(true);
        }

        void show(@Nullable String icon) {
            shown = icon;
            revision++;
            setVisible(icon != null);
            if (icon != null) {
                glyph.set(catalogue.icon(icon), catalogue.size, catalogue);
                name.setText(icon);
            }
            notifyChange(limn.scene.Change.of(limn.scene.Change.Aspect.NAME, limn.scene.Change.Origin.CODE));
        }

        private void choose() {
            if (shown != null) {
                detail.choose(shown);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size inner = children().get(0).measure(constraints);
            return constraints.constrain(constraints.maxWidth(), inner.height());
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float radius = theme.tokensFor(this).radiusMedium();
            if (shown != null && shown.equals(catalogue.chosen)) {
                canvas.fillRoundRect(0, 0, width(), height(), radius, theme.primary().withAlpha(0.16f));
            } else if (hovered) {
                canvas.fillRoundRect(0, 0, width(), height(), radius, theme.text().withAlpha(0.06f));
            }
            if (isFocused()) {
                canvas.drawRoundRect(1, 1, width() - 2, height() - 2, radius, 2, theme.focusRing());
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    hovered = true;
                    invalidate();
                }
                case EXIT -> {
                    hovered = false;
                    invalidate();
                }
                case PRESS -> event.consume();
                case CLICK -> {
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                        choose();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if ((event.key() == Keys.SPACE || event.key() == Keys.ENTER) && event.isPressed() && !event.isRepeat()) {
                event.consume();
                choose();
            }
        }

        @Override
        protected void onFocusGained() {
            super.onFocusGained();
            invalidate();
        }

        @Override
        protected void onFocusLost() {
            super.onFocusLost();
            invalidate();
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            a.name(shown, revision, Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            if (action != Accessible.Action.PRESS || shown == null) {
                return false;
            }
            choose();
            return true;
        }
    }

    /** An icon at a size of its own, in the chosen tint or the theme's text colour. */
    private static final class IconView extends Widget<IconView> {

        private @Nullable Icon icon;
        private float size = 24;
        private @Nullable Catalogue tintFrom;

        IconView set(@Nullable Icon newIcon, float newSize, @Nullable Catalogue tint) {
            boolean resized = newSize != size;
            icon = newIcon;
            size = newSize;
            tintFrom = tint;
            if (resized) {
                markNeedsLayout();
            }
            invalidate();
            return this;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(size, size);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (icon == null) {
                return;
            }
            Theme theme = Theme.of(this);
            Color tint = tintFrom != null && tintFrom.tint != null ? tintFrom.tint : theme.text();
            icon.paint(canvas, (width() - size) / 2, (height() - size) / 2, size, tint, theme.isDark());
        }
    }

    /** The chosen icon: large, its call with a copy button, its filled twin, and the icon at work. */
    private static final class Detail {

        final Catalogue catalogue;
        final Column panel = new Column();
        @Nullable ListView<?> grid;
        private final IconView large = new IconView();
        private final Label name = new Label("").setStrong(true).setOverflow(Label.Overflow.ELLIPSIS);
        private final Label call = new Label("").setOverflow(Label.Overflow.ELLIPSIS);
        private final IconView twin = new IconView();
        private final Label noTwin = new Label(Text.NO_FILLED).setMuted(true);
        private final Button inButton = new Button("").setSecondary(true);

        Detail(Catalogue catalogue) {
            this.catalogue = catalogue;
            panel.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.START);

            Button copy = new Button(Text.COPY).setSecondary(true);
            copy.setIcon(Tabler.outline("copy"));
            copy.onAction(() -> {
                copy.scene().clipboard().set(Transfer.of(DataFormat.TEXT, call.text()));
                Page.report(Text.COPIED.format(call.text()));
            });

            Button actions = new Button(Text.ACTIONS).setSecondary(true);
            // Built as it opens, so its first item carries the icon chosen now.
            actions.onAction(() -> openBelow(actions, new Menu()
                    .add(MenuItem.of(inButton.text(), () -> Page.report(inButton.text()))
                            .setIcon(catalogue.icon(catalogue.chosen)))
                    .add(MenuItem.of(Text.DUPLICATE, () -> Page.report(Text.DUPLICATE.get()))
                            .setIcon(Tabler.outline("copy")))
                    .add(MenuItem.of(Text.ARCHIVE, () -> Page.report(Text.ARCHIVE.get()))
                            .setIcon(Tabler.outline("archive")))));
            inButton.onAction(() -> Page.report(inButton.text()));

            panel.add(large);
            panel.add(name);
            Column code = new Column();
            code.gap(6).crossAlignment(Flex.CrossAlignment.START);
            code.add(call);
            code.add(copy);
            panel.add(new SizedBox(DETAIL_WIDTH, SizedBox.UNSET, code));
            panel.add(Page.section(Text.FILLED_TWIN));
            panel.add(twin);
            panel.add(noTwin);
            panel.add(Page.section(Text.IN_USE));
            panel.add(Page.row(8, inButton, actions));
        }

        void choose(String icon) {
            catalogue.chosen = icon;
            Page.report(Text.CHOSE.format(icon));
            show();
        }

        void show() {
            String icon = catalogue.chosen;
            large.set(catalogue.icon(icon), DETAIL_ICON, catalogue);
            name.setText(icon);
            boolean useFilled = catalogue.filled && Tabler.hasFilled(icon);
            call.setText("Tabler." + (useFilled ? "filled" : "outline") + "(\"" + icon + "\")");
            boolean hasTwin = Tabler.hasFilled(icon);
            twin.set(hasTwin ? Tabler.filled(icon) : null, 32, catalogue);
            twin.setVisible(hasTwin);
            noTwin.setVisible(!hasTwin);
            String words = words(icon);
            inButton.setText(words);
            inButton.setIcon(catalogue.icon(icon));
            if (grid != null) {
                grid.invalidate();
            }
        }
    }

    /** An icon's name as words for a caption: {@code file-invoice} reads "File invoice". */
    private static String words(String icon) {
        String spaced = icon.replace('-', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Opens {@code menu} as a drop-down under {@code anchor}. */
    private static void openBelow(Widget<?> anchor, Menu menu) {
        float sx = 0;
        float sy = 0;
        for (Widget<?> w = anchor; w != null; w = w.parent()) {
            sx += w.x();
            sy += w.y();
        }
        new PopupMenu(menu).showAnchored(anchor, sx, sy, anchor.width(), anchor.height());
    }
}
