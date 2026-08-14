package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * The screen the home page's mosaic is cut from, and nothing else.
 *
 * <p>It exists because the mosaic's tiles are <b>close crops laid edge to edge</b>, and no screen
 * built for its own sake survives that treatment. An application window has a menu bar, a title
 * and margins: tile six crops of one and the seams show six copies of the same chrome, while the
 * crops that land on a margin show nothing at all. So this board carries no chrome, no title, and
 * no empty region: every part of it is controls, at a density that lets an arbitrary rectangle
 * come out looking composed rather than clipped.
 *
 * <p>Two rules follow from that, and breaking either is what makes a tile look like a mistake:
 *
 * <ul>
 *   <li><b>Fill the canvas.</b> Rows stretch and the board is sized to the capture window, so a
 *       crop never runs off the edge of the content into background.
 *   <li><b>No sentences.</b> The words here are the ones on controls. A caption rendered into a
 *       tile is page prose at a size the page did not choose, in a language it cannot translate.
 *       In a mosaic it would appear cut in half.
 * </ul>
 *
 * <p>What varies between tiles is the theme, the size step and the typeface, never this layout.
 * That is the whole claim the mosaic makes: one screen, unrecognisably different dressings.
 */
public final class MosaicExample {

    private MosaicExample() {
    }

    /**
     * Gap between cells, in points. Deliberately tight: a mosaic tile is a narrow slice of this
     * board, and a generous gap spends that slice on background instead of on controls.
     */
    private static final float GAP = 9;

    /**
     * How many bands the board stacks. Enough that the column is TALLER than the capture window
     * at every size step, including the smallest: the board is top-aligned and the surplus is
     * clipped, which is what guarantees no crop can land on empty background. A count tuned to
     * fit exactly would leave a strip of background at XSMALL and the bottom row of tiles would
     * publish it.
     */
    private static final int BANDS = 34;

    /**
     * The board: bands of controls, cycling through shapes, filling the canvas and then some.
     *
     * <p>The cycle matters: any crop has to land on a MIX of controls, so no single kind may run
     * down the page in a column of its own, and no band may be given the column's slack; a
     * widget that expands takes a third of every tile with it.
     *
     * <p><b>Only widgets that follow the theme belong here.</b> A chart is the counter-example and
     * it is not an oversight that there is none: its series colours come from the chart palette,
     * not from the {@link Theme}, so a chart placed on this board is a region that renders
     * identically in every tile; the mosaic would spend its largest area arguing against the one
     * thing it exists to show.
     */
    public static Widget board() {
        Column bands = new Column();
        bands.gap(GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int i = 0; i < BANDS; i++) {
            bands.add(band(i));
        }
        return new Padding(Insets.all(12), bands);
    }

    /**
     * The cells a band is built from, and the only place a control is named.
     *
     * <p>Each entry says whether it takes the row's slack. That is what keeps a band from being a
     * fixed set of columns: the wide cells move along the row as the rotation advances, so no
     * vertical slice of the board is one widget repeated.
     */
    private record Cell(boolean wide, java.util.function.Supplier<Widget> build) {
    }

    private static final List<Cell> CELLS = List.of(
            new Cell(true, () -> new TextField().setText("Ada Lovelace")),
            new Cell(false, () -> new SegmentedControl(List.of("Day", "Week", "Month"))),
            new Cell(false, () -> new Checkbox(Checkbox.Variant.SWITCH, "Notify").setChecked(true)),
            new Cell(true, MosaicExample::slider),
            new Cell(false, () -> new ComboBox(List.of("Everyone", "My team"))),
            new Cell(false, () -> new Button("Save changes")),
            new Cell(true, SearchField::new),
            new Cell(false, MosaicExample::automatic),
            new Cell(true, MosaicExample::progress),
            new Cell(false, () -> new Button("Duplicate").setSecondary(true)),
            new Cell(false, () -> new Spinner(0, 100, 1)),
            new Cell(false, () -> new Checkbox(Checkbox.Variant.BOX, "Include drafts")
                    .setChecked(true)),
            new Cell(false, () -> new Label("14 items")));

    /**
     * How many cells a band holds. Six rather than four because the tiles are vertical slices:
     * a slice one eighth of the canvas wide has to land on whole controls, and a band of four
     * stretches its wide cells until a slice shows nothing but the middle of a text field.
     */
    private static final int CELLS_PER_BAND = 6;

    /**
     * One band: four cells, taken from {@link #CELLS} at an offset that advances by a number
     * coprime with the list length.
     *
     * <p>Coprime is the whole trick: with a stride that divides the list, the rotation returns to
     * where it started every few bands and the board becomes a repeating block, which is exactly
     * the columnar picture this replaced: the right-hand third was one combo box and one spinner,
     * twenty-four times, so two tiles of the mosaic came out as a column of identical widgets.
     */
    private static Widget band(int index) {
        Row row = new Row();
        row.gap(GAP).crossAlignment(Flex.CrossAlignment.CENTER)
                .mainAlignment(Flex.MainAlignment.START);
        for (int i = 0; i < CELLS_PER_BAND; i++) {
            Cell cell = CELLS.get((index * 5 + i) % CELLS.size());
            Widget widget = cell.build().get();
            row.add(cell.wide() ? Expanded.of(widget) : widget);
        }
        return row;
    }

    private static Widget slider() {
        Slider volume = new Slider(0, 100);
        volume.setValue(64);
        return volume;
    }

    private static Widget progress() {
        ProgressBar bar = new ProgressBar();
        bar.setProgress(0.42f);
        return bar;
    }

    /**
     * A radio, selected. {@code select()} rather than a setter: a radio has no "unselect", so the
     * toolkit exposes only the act (see {@link RadioButton#select()}).
     */
    private static Widget automatic() {
        RadioButton radio = new RadioButton("Automatic");
        radio.select();
        return radio;
    }

    /**
     * The board on a canvas, top-aligned and overflowing it. See {@link #BANDS}.
     *
     * <p>Not centred and not expanded: centring a column taller than the canvas crops it at BOTH
     * ends, which costs the top band, and expanding hands the surplus to a child instead of
     * letting it run off the bottom where nothing needs it.
     */
    public static Scene scene() {
        Column fill = new Column();
        fill.crossAlignment(Flex.CrossAlignment.STRETCH)
                .mainAlignment(Flex.MainAlignment.START);
        fill.add(board());
        Scene scene = new Scene(fill);
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
