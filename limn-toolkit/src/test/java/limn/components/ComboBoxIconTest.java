package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item's icon (ADR 052): it leads the item's text in the field and in every row of the list,
 * on the side reading starts from, and the field grows by the icon and its gap. A row whose item
 * has no icon keeps the column, so the labels still align.
 */
class ComboBoxIconTest extends ComponentTestBase {

    private static final float WIDTH = 400;
    private static final float HEIGHT = 300;

    private final RecordingIcon open = new RecordingIcon();
    private final RecordingIcon billed = new RecordingIcon();
    private final Map<String, Icon> iconOf = Map.of("Aberto", open, "Faturado", billed);

    private Scene scene;
    private ComboBox<String> combo;

    private void build(LayoutDirection direction, boolean withIcons) {
        combo = new ComboBox<>(List.of("Aberto", "Faturado", "Cancelado"))
                .setDisplayMode(DisplayMode.IN_SCENE);
        if (withIcons) {
            combo.setItemIcon(iconOf::get);
        }
        Column root = new Column();
        root.setLayoutDirection(direction);
        root.add(combo);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    private void paint() {
        open.xs.clear();
        billed.xs.clear();
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(new RecordingTestCanvas(WIDTH, HEIGHT));
    }

    @Test
    void theFieldGrowsByTheIconAndItsGap() {
        build(LayoutDirection.LTR, false);
        float plain = combo.width();
        build(LayoutDirection.LTR, true);
        SizeTokens t = Theme.of(combo).tokensFor(combo);
        assertEquals(plain + t.fieldIcon() + t.spacingSmall(), combo.width(), 0.001f);
    }

    @Test
    void theSelectedItemsIconLeadsTheFieldOnTheSideReadingStartsFrom() {
        build(LayoutDirection.LTR, true);
        paint();
        SizeTokens t = Theme.of(combo).tokensFor(combo);
        assertEquals(List.of(t.fieldPadH()), open.xs);

        build(LayoutDirection.RTL, true);
        paint();
        assertEquals(List.of(combo.width() - t.fieldPadH() - t.fieldIcon()), open.xs);
    }

    @Test
    void everyRowWithAnIconPaintsItAndARowWithoutKeepsTheColumn() {
        build(LayoutDirection.LTR, true);
        combo.open();
        paint();
        // The field's own icon (Aberto is selected) and its row, then Faturado's row; Cancelado has
        // none and paints none.
        assertEquals(2, open.xs.size(), "field and row");
        assertEquals(1, billed.xs.size(), "row");
        assertEquals(open.xs.get(1), billed.xs.get(0), 0.001f, "the rows share the icon column");
        assertTrue(open.xs.get(1) > 0, "past the list's marker column");
    }

    /**
     * Records where it was painted, in the painting widget's own coordinates (the recording canvas
     * does not translate); never rasterized.
     */
    private static final class RecordingIcon implements Icon {

        final List<Float> xs = new ArrayList<>();

        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                boolean mirrored) {
            xs.add(x);
        }
    }
}
