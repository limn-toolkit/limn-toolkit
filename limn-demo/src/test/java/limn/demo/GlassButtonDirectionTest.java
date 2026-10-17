package limn.demo;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The video player's glass button puts its icon where reading starts, as a {@code Button} does:
 * before its words reading left to right, after them reading right to left. It drew the icon on
 * the left in either direction, so an Arabic or Hebrew "Subtitles" read icon, then words, from
 * the wrong end.
 */
class GlassButtonDirectionTest {

    @Test
    void theIconLeadsTheWordsOnTheSideReadingStartsFrom() {
        try (HeadlessUi ui = new HeadlessUi()) {
            float[] ltr = iconAndTextX(LayoutDirection.LTR);
            assertTrue(ltr[0] < ltr[1], "left to right, the icon is before the words");
            float[] rtl = iconAndTextX(LayoutDirection.RTL);
            assertTrue(rtl[0] > rtl[1], "right to left, the icon is after the words");
        }
    }

    /** @return where the icon was painted and where the words were drawn, in that order */
    private static float[] iconAndTextX(LayoutDirection direction) {
        float[] at = {Float.NaN, Float.NaN};
        Icon icon = new Icon() {
            @Override
            public Image image(int pixelSize, boolean dark) {
                throw new UnsupportedOperationException("measure-only");
            }

            @Override
            public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
                at[0] = x;
            }
        };
        VideoScene.GlassButton button = new VideoScene.GlassButton().setIcon(icon).setText("Subtitles");
        button.setLayoutDirection(direction);
        Scene scene = new Scene(button);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(300, 40);
        scene.renderFrame(new NoopCanvas(300, 40) {
            @Override
            public void drawText(String text, float x, float y, Font font, Paint paint) {
                at[1] = x;
            }
        });
        return at;
    }
}
