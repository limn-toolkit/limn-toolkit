package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A combo box's item icon that means a direction, a back arrow for "Returned", turns around where
 * the combo reads right to left, as a {@link Button}'s or a {@link Label}'s does; every other item
 * icon is drawn as authored. The combo drew every item icon unflipped, so a right-to-left
 * interface showed its "Returned" arrow pointing the left-to-right way.
 */
class ComboBoxIconMirroringTest extends ComponentTestBase {

    private static final float WIDTH = 240;
    private static final float HEIGHT = 40;

    private final RecordingIcon back = new RecordingIcon();
    private final RecordingIcon truck = new RecordingIcon();

    private void paintChosen(LayoutDirection direction, String chosen) {
        ComboBox<String> combo = new ComboBox<>(List.of("Shipped", "Returned"));
        combo.setItemIcon(item -> "Returned".equals(item) ? back : truck,
                item -> "Returned".equals(item) ? Icon.Mirroring.IN_RTL : Icon.Mirroring.NEVER);
        combo.setSelectedIndex(chosen.equals("Returned") ? 1 : 0);
        combo.setLayoutDirection(direction);
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(new FakeCanvas(WIDTH, HEIGHT));
    }

    @Test
    void aDirectionalItemIconTurnsAroundReadingRightToLeft() {
        paintChosen(LayoutDirection.RTL, "Returned");
        assertEquals(Boolean.TRUE, back.mirrored, "the back arrow points the way the field reads");
    }

    @Test
    void aDirectionalItemIconIsDrawnAsAuthoredReadingLeftToRight() {
        paintChosen(LayoutDirection.LTR, "Returned");
        assertEquals(Boolean.FALSE, back.mirrored, "the flag alone is not the answer");
    }

    @Test
    void anItemIconThatSaysNothingNeverTurnsAround() {
        paintChosen(LayoutDirection.RTL, "Shipped");
        assertEquals(Boolean.FALSE, truck.mirrored, "an unclassified icon is drawn as authored");
    }

    @Test
    void theOneArgumentFormMirrorsNothing() {
        ComboBox<String> combo = new ComboBox<>(List.of("Returned"));
        combo.setItemIcon(item -> back);
        combo.setSelectedIndex(0);
        combo.setLayoutDirection(LayoutDirection.RTL);
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(new FakeCanvas(WIDTH, HEIGHT));
        assertEquals(Boolean.FALSE, back.mirrored);
    }

    /** Records whether it was told to turn around; never rasterized. */
    private static final class RecordingIcon implements Icon {

        private Boolean mirrored;

        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                          boolean mirrored) {
            this.mirrored = mirrored;
        }
    }
}
