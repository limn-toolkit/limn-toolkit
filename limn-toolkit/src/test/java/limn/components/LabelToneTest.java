package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A label's tone: one of the palette's colours, asked of the theme on every paint, so a change of
 * palette re-colours the text with no code of the application's. The defect it replaces is a colour
 * read once while a page is built, which is the palette of that moment.
 */
class LabelToneTest extends ComponentTestBase {

    /** Records the ink of every line of text drawn. */
    private static final class InkRecorder extends FakeCanvas {
        final List<Paint> inks = new ArrayList<>();

        InkRecorder() {
            super(200, 40);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            inks.add(paint);
        }
    }

    private static Paint inkOf(Scene scene) {
        InkRecorder canvas = new InkRecorder();
        scene.requestRender();
        scene.renderFrame(canvas);
        assertEquals(1, canvas.inks.size(), "one line drawn");
        return canvas.inks.get(0);
    }

    @Test
    void aToneIsTheThemesColourAtPaintTimeAndFollowsAChangeOfPalette() {
        Label line = new Label("Saved").setTone(Tone.SUCCESS);
        Scene scene = new Scene(line);
        scene.layoutPass(200, 40);
        assertEquals(Theme.dark().success(), inkOf(scene));

        Theme.setCurrent(Theme.light());
        assertEquals(Theme.light().success(), inkOf(scene), "the palette changed and the line followed");

        for (Tone tone : Tone.values()) {
            line.setTone(tone);
            assertEquals(tone.colorIn(Theme.light()), inkOf(scene), tone.name());
        }
    }

    @Test
    void anExplicitColourWinsAndADisabledLabelIsDrawnDisabled() {
        Label line = new Label("Refused").setTone(Tone.DANGER).setMuted(true);
        Scene scene = new Scene(line);
        scene.layoutPass(200, 40);
        assertEquals(Theme.dark().danger(), inkOf(scene), "a tone wins over muted");

        Color pinned = Color.rgb(0x123456);
        line.setColor(pinned);
        assertEquals(pinned, inkOf(scene), "a colour the application pinned wins over the tone");
        line.setColor(null);

        line.setEnabled(false);
        assertEquals(Theme.dark().disabledText(), inkOf(scene));
        line.setEnabled(true);

        line.setTone(null);
        assertNull(line.tone());
        assertEquals(Theme.dark().textMuted(), inkOf(scene), "no tone: muted, as before");
    }

    @Test
    void eachToneNamesTheTokenItReads() {
        Theme theme = Theme.limnLight();
        assertEquals(theme.textMuted(), Tone.NEUTRAL.colorIn(theme));
        assertEquals(theme.primaryText(), Tone.ACCENT.colorIn(theme));
        assertEquals(theme.info(), Tone.INFO.colorIn(theme));
        assertEquals(theme.success(), Tone.SUCCESS.colorIn(theme));
        assertEquals(theme.warning(), Tone.WARNING.colorIn(theme));
        assertEquals(theme.danger(), Tone.DANGER.colorIn(theme));
    }
}
