package limn.demo.site;

import limn.components.Button;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.ScrollView;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.Separator;
import limn.components.SplitPane;
import limn.components.Theme;
import limn.components.ToolBar;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

import java.util.ArrayList;
import java.util.List;

/**
 * The worked window the guide's Layout page is built from: an asset library with a toolbar,
 * a sidebar, a tile grid and a status bar, assembled out of the primitives that page
 * teaches, and nothing else.
 *
 * <p>Every region marked here becomes a code block on that page, and this file is compiled
 * by {@code ./gradlew check}. Each helper is used by {@link #shell()}, so the screenshot on
 * the page shows every snippet on it doing its job.
 */
public final class LayoutExample {

    private LayoutExample() {
    }

    private static final List<String> COLLECTIONS =
            List.of("All assets", "Textures", "Models", "HDRIs", "Fonts", "Archive");

    /** name · overlay badge · size on disk · the two ends of the swatch's ramp. */
    private static final String[][] ASSETS = {
            {"Concrete 04", "2048 × 2048", "4.2 MB", "1E1230", "6D00E0"},
            {"Brushed steel", "1024 × 1024", "1.8 MB", "10131E", "5A6E8C"},
            {"Oak planks", "2048 × 2048", "3.6 MB", "241206", "9A5A22"},
            {"Sand dunes", "4096 × 4096", "18.4 MB", "2A1A08", "C79A4B"},
            {"Rust patina", "2048 × 2048", "5.1 MB", "2B0D08", "A8442A"},
            {"Marble tile", "2048 × 2048", "3.9 MB", "141626", "8E93C8"},
            {"Moss bank", "2048 × 2048", "4.7 MB", "0C1A0E", "3F8A46"},
            // Eight, not nine: the last line of the grid is deliberately short, so the
            // picture on the guide page shows what a short line does to the tile width.
            {"Sea ice", "4096 × 4096", "21.0 MB", "081A22", "6FBFD6"},
    };

    /**
     * A column stacks its children top to bottom with one gutter between them. STRETCH is the
     * cross-axis rule that makes each child as wide as the column; the default, START, would
     * leave every child at its own intrinsic width.
     *
     * <p>The region marker sits BELOW this comment on purpose, here and in every other
     * example: the site publishes the marked text verbatim, and a Javadoc block with
     * {@code @link} tags in it is documentation for a reader of this file, not a code
     * sample for a reader of the guide.
     */
    // #region guide:layout-column
    static Column column(float gap, Widget... children) {
        Column column = new Column();
        column.gap(gap).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (Widget child : children) {
            column.add(child);
        }
        return column;
    }
    // #endregion

    /**
     * Leftover space along a row goes to whatever is {@link Expanded}, in proportion to the
     * flex given. A bare spacer is the version of that with nothing in it, and it is how a
     * trailing group is pushed to the far edge without measuring anything.
     */
    // #region guide:layout-row
    static Row spread(Widget leading, Widget trailing) {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(leading);
        row.add(Expanded.spacer(1));
        row.add(trailing);
        return row;
    }
    // #endregion

    /**
     * A stack draws its children in order against one shared box, so it is what an overlay
     * is: the content, then the thing on top of it. All children take the stack's alignment;
     * wrap one in a {@link Padding} to nudge it off a corner.
     */
    // #region guide:layout-stack
    static Widget overlay(Widget content, Widget on) {
        Stack stack = new Stack();
        stack.alignment(Stack.Alignment.CENTER);
        stack.add(content);
        stack.add(on);
        return stack;
    }
    // #endregion

    /**
     * A grid, built out of the two widgets that already exist rather than a widget of its
     * own. Every tile on a line gets flex 1, so the line splits its width evenly however
     * many tiles there are, and a short last line is padded with spacers, so its two tiles
     * keep the column width the full lines set instead of stretching across the pane.
     */
    // #region guide:layout-grid
    static Column grid(int perRow, List<Widget> tiles) {
        Column rows = new Column();
        rows.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int start = 0; start < tiles.size(); start += perRow) {
            Row line = new Row();
            line.gap(14).crossAlignment(Flex.CrossAlignment.START);
            for (int i = start; i < Math.min(start + perRow, tiles.size()); i++) {
                line.add(Expanded.of(tiles.get(i), 1));
            }
            for (int i = tiles.size(); i < start + perRow; i++) {
                line.add(Expanded.spacer(1));
            }
            rows.add(line);
        }
        return rows;
    }
    // #endregion

    /**
     * The window: a fixed-height bar, a pair of panes that take everything left over, and a
     * status line. The middle child is the only {@link Expanded} one, which is what makes
     * the window's spare height land there and nowhere else.
     */
    // #region guide:layout-shell
    public static Widget shell() {
        ToolBar bar = new ToolBar();
        bar.addItem(new Button("Import"));
        bar.addSeparator();
        bar.addItem(new Button("New collection").setSecondary(true));
        bar.addItem(new Button("Share").setSecondary(true));

        SplitPane panes = SplitPane.horizontal(collections(), library());
        panes.setRatio(0.24f).setMinimums(150, 320);

        Row status = spread(
                new Label("18 assets · 3 selected").setMuted(true),
                new Label("Synced 2 minutes ago").setMuted(true));

        return column(0,
                bar,
                Expanded.of(panes, 1),
                Separator.horizontal(),
                new Padding(Insets.symmetric(8, 14), status));
    }
    // #endregion

    /** The sidebar: a list whose rows are ordinary widgets, supplied on demand. */
    private static Widget collections() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return COLLECTIONS.size();
            }

            @Override
            public Widget rowAt(int index) {
                return new Padding(Insets.symmetric(9, 14), new Label(COLLECTIONS.get(index)));
            }
        });
        list.setSelectedIndex(1);
        return list;
    }

    /**
     * The pane that grows: a header row, then the grid taking whatever height is left. The
     * grid scrolls rather than being clipped, so a narrow window loses nothing.
     */
    private static Widget library() {
        SearchField search = new SearchField();
        SegmentedControl view = new SegmentedControl(List.of("Grid", "List"));
        view.setSelectedIndex(0);

        Row tools = new Row();
        tools.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        tools.add(new SizedBox(190, SizedBox.UNSET, search));
        tools.add(view);

        List<Widget> tiles = new ArrayList<>();
        for (String[] asset : ASSETS) {
            tiles.add(tile(asset));
        }

        return new Padding(Insets.all(20), column(18,
                spread(new Label("Textures").setRole(Label.Role.TITLE), tools),
                Expanded.of(new ScrollView(grid(3, tiles)), 1)));
    }

    /** One tile: a swatch with its resolution over it, then the name and the size. */
    private static Widget tile(String[] asset) {
        Label badge = new Label(asset[1]);
        badge.setColor(Color.WHITE).setStrong(true);
        return column(6,
                overlay(swatch(asset[3], asset[4]), badge),
                new Label(asset[0]).setStrong(true),
                new Label(asset[2]).setMuted(true));
    }

    /**
     * A generated ramp rather than a file: the capture must not depend on an asset a
     * checkout might not carry. The preferred width is deliberately larger than any tile:
     * an image measures to {@code min(preferred, available)}, so an oversized one fills its
     * tile instead of sitting in the middle of it, and {@code COVER} crops the overflow.
     */
    private static Widget swatch(String from, String to) {
        int width = 96;
        int height = 64;
        int a = (int) Long.parseLong(from, 16);
        int b = (int) Long.parseLong(to, 16);
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float t = (x / (float) (width - 1) + y / (float) (height - 1)) / 2f;
                int i = (y * width + x) * 4;
                pixels[i] = (byte) mix((a >> 16) & 255, (b >> 16) & 255, t);
                pixels[i + 1] = (byte) mix((a >> 8) & 255, (b >> 8) & 255, t);
                pixels[i + 2] = (byte) mix(a & 255, b & 255, t);
                pixels[i + 3] = (byte) 0xFF;
            }
        }
        return new ImageView(new Image(width, height, pixels))
                .setFit(ImageView.Fit.COVER)
                .setPreferredSize(600, 86);
    }

    private static int mix(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /** The shell on a canvas, for the capture the guide page shows. */
    public static Scene scene() {
        Scene scene = new Scene(shell());
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
