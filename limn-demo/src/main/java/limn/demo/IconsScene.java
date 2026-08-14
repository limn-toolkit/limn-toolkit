package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.components.ListView;
import limn.components.SearchField;
import limn.components.TokenColumn;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.icons.tabler.Tabler;
import limn.icons.tabler.TablerSystem;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The whole Tabler pack, searchable.
 *
 * <p>It is here as much to be a load test as a catalogue: several thousand rows, each
 * carrying a vector icon that rasterizes at the device size it is drawn at. Nothing but the
 * rows on screen is ever built, because {@link ListView} mounts what it shows and recycles
 * what it scrolls past, so what this really demonstrates is that the pack costs what is
 * visible rather than what exists.
 */
final class IconsScene {

    /** Tall enough to read as a catalogue rather than as a preview of one. */
    private static final float LIST_HEIGHT = 460;

    private IconsScene() {
    }

    /** One row: the icon at a fixed size, then its name. Recycled, so it is repopulated. */
    private static final class Row extends TokenRow {

        // A plain Label carrying only an icon: the demo has no icon-only widget, and a Button
        // would put a hit target on every row of a list that is not clickable.
        private final Label glyph = new Label("");
        private final Label name = new Label("");

        Row() {
            super(Tokens.Role.MEDIUM);
            crossAlignment(Flex.CrossAlignment.CENTER);
            // Built once, in final order: the icon first. A Flex appends, so a row that added
            // the glyph at bind time had to remove and re-add the name to get behind it: four
            // child-list mutations and a fresh Label on every bind, in the scene that exists to
            // show the pack NOT allocating while it scrolls.
            add(glyph);
            add(name);
        }

        void show(String iconName) {
            glyph.setIcon(Tabler.outline(iconName));
            name.setText(iconName);
        }
    }

    /** The filtered view of the catalogue, plus a pool so scrolling stops allocating. */
    private static final class Icons implements ListView.Adapter {

        private final List<String> all = Tabler.names();
        private List<String> shown = all;
        private final Deque<Row> pool = new ArrayDeque<>();

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

        String nameAt(int index) {
            return shown.get(index);
        }

        @Override
        public int rowCount() {
            return shown.size();
        }

        @Override
        public Widget rowAt(int index) {
            Row row = pool.isEmpty() ? new Row() : pool.pop();
            row.show(shown.get(index));
            return row;
        }

        @Override
        public void recycle(Widget widget) {
            if (widget instanceof Row row) {
                pool.push(row);
            }
        }
    }

    /**
     * The tab's own window. The Kitchen Sink tab body is a couple of hundred points tall at
     * the size this repository captures at, and a five-thousand-row catalogue shown five
     * rows at a time verifies nothing, so the scene that has to show the list is this one,
     * and the tab is there to prove the module composes.
     */
    static Scene create(boolean light) {
        return create(light, "");
    }

    /** @param query pre-filled into the search box, so a capture can show it filtering */
    static Scene create(boolean light, String query) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content(query)));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    static Widget content() {
        return content("");
    }

    static Widget content(String query) {
        Icons icons = new Icons();
        ListView list = new ListView(icons);

        Label count = new Label("").setMuted(true);
        Label detail = new Label("Click a row for its constant.").setMuted(true);

        SearchField search = new SearchField();
        search.onChange(typed -> {
            icons.filter(typed);
            list.refresh();
            count.setText(summary(icons));
        });
        if (!query.isEmpty()) {
            search.setText(query);
            icons.filter(query);
        }
        count.setText(summary(icons));

        list.onSelect(index -> detail.setText(index < 0 ? "Click a row for its constant."
                : "Tabler.outline(\"" + icons.nameAt(index) + "\")"
                        + (Tabler.hasFilled(icons.nameAt(index)) ? " · has a filled twin" : "")));

        Label heading = new Label("Tabler icons: the whole pack, virtualized")
                .setRole(Label.Role.TITLE);
        Label blurb = new Label("Every icon of an opt-in module that nothing else depends on. "
                + "The list mounts only the rows on screen, so scrolling the catalogue rasterizes "
                + "what you can see and no more. Names are checked by the compiler through one "
                + "enum per upstream category; this row is TablerSystem.SEARCH.")
                .setWrap(true).setMuted(true);

        // A compiled-in constant beside the searchable names, which is the point of the enums:
        // this one cannot be misspelled without failing the build.
        Label sample = new Label("TablerSystem.SEARCH");
        sample.setIcon(TablerSystem.SEARCH.icon());

        TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(heading);
        column.add(blurb);
        column.add(sample);
        column.add(search);
        column.add(count);
        // A fixed box rather than Expanded: the tab panel measures its content against an
        // unbounded height, and a Flex only shares its main axis out when that axis is
        // bounded: a flex child there measures to nothing at all. Tall enough that a
        // catalogue of five thousand is visibly a catalogue, which is the point of the tab.
        column.add(new SizedBox(Float.POSITIVE_INFINITY, LIST_HEIGHT, list));
        column.add(detail);
        return column;
    }

    private static String summary(Icons icons) {
        int shown = icons.rowCount();
        int all = Tabler.names().size();
        return shown == all
                ? all + " icons · tinted from the theme, so they follow light and dark"
                : shown + " of " + all + " icons";
    }
}
