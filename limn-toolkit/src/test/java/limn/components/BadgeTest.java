package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Paint;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A badge: its one look at every tone, its three kinds, the count's digits and its "99+", the
 * mark's end in each direction, and the static text a reader is given.
 */
class BadgeTest extends AccessibleComponentTestBase {

    @AfterEach
    void englishAgain() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /** What one frame of a badge drew: its round fills in order, and its text with its ink. */
    private static final class Recorder extends FakeCanvas {
        record Fill(float x, float y, float w, float h, Paint paint) {
        }

        final List<Fill> fills = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Float> textXs = new ArrayList<>();
        final List<Paint> inks = new ArrayList<>();

        Recorder(float w, float h) {
            super(w, h);
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float r, Paint paint) {
            fills.add(new Fill(x, y, w, h, paint));
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            textXs.add(x);
            inks.add(paint);
        }
    }

    private Recorder paint(Badge badge, LayoutDirection direction) {
        badge.setLayoutDirection(direction);
        Scene s = new Scene(badge);
        s.setTextRuler(SCALED_RULER);
        Size size = badge.measure(Constraints.loose(400, 100));
        s.layoutPass(size.width(), size.height());
        Recorder canvas = new Recorder(size.width(), size.height());
        s.renderFrame(canvas);
        return canvas;
    }

    @Test
    void everyToneIsATintedPillADotInTheToneAndTheWordInTheTextColour() {
        Theme theme = Theme.current();
        for (Tone tone : Tone.values()) {
            Recorder drawn = paint(new Badge("Overdue").setTone(tone), LayoutDirection.LTR);
            Color c = tone.colorIn(theme);
            assertEquals(2, drawn.fills.size(), tone + ": the pill and the dot");
            assertEquals(c.withAlpha(c.a() * (theme.isDark() ? 0.26f : 0.16f)), drawn.fills.get(0).paint(),
                    tone + ": the pill is the tone's tint");
            assertEquals(c, drawn.fills.get(1).paint(), tone + ": the dot is the tone");
            assertEquals(List.of(theme.text()), drawn.inks, tone + ": the word is the palette's text");
        }
        assertEquals(Tone.NEUTRAL, new Badge("x").tone(), "neutral by default");
    }

    @Test
    void thePillIsRoundAndTheDotLeadsInEachDirection() {
        Recorder ltr = paint(new Badge("Paid"), LayoutDirection.LTR);
        Recorder.Fill pill = ltr.fills.get(0);
        Recorder.Fill dot = ltr.fills.get(1);
        assertTrue(dot.x() < ltr.textXs.get(0), "left to right, the dot is before the word");
        Recorder rtl = paint(new Badge("Paid"), LayoutDirection.RTL);
        Recorder.Fill rDot = rtl.fills.get(1);
        assertTrue(rDot.x() > rtl.textXs.get(0), "right to left, the dot is after the word, at the right");
        assertEquals(pill.w() - dot.x() - dot.w(), rDot.x(), 0.001f, "mirrored exactly");
    }

    @Test
    void anIconTakesTheDotsPlaceInTheTone() {
        List<Color> tints = new ArrayList<>();
        Icon icon = new Icon() {
            @Override
            public limn.graphics.Image image(int pixelSize, boolean dark) {
                throw new AssertionError("painted through paint, never as a bitmap here");
            }

            @Override
            public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
                tints.add(tint);
            }
        };
        Recorder drawn = paint(new Badge(icon, I18nString.literal("Synced")).setTone(Tone.SUCCESS),
                LayoutDirection.LTR);
        assertEquals(1, drawn.fills.size(), "the pill alone: no dot beside an icon");
        assertEquals(List.of(Theme.current().success()), tints);
    }

    @Test
    void aCountShowsItsNumberAndPast99ShowsNinetyNinePlus() {
        Badge badge = Badge.count(7);
        assertEquals("7", badge.text());
        assertEquals(List.of("7"), paint(badge, LayoutDirection.LTR).texts);
        badge.setCount(99);
        assertEquals("99", badge.text());
        badge.setCount(100);
        assertEquals("99+", badge.text());
        badge.setCount(12_345);
        assertEquals("99+", badge.text(), "the badge does not grow with its count");
        assertThrows(IllegalArgumentException.class, () -> badge.setCount(-1));
        assertThrows(IllegalStateException.class, () -> badge.setText("x"));
        assertThrows(IllegalStateException.class, () -> new Badge("x").setCount(1));
    }

    @Test
    void aCountIsWrittenInTheLanguagesDigits() {
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        Badge badge = Badge.count(7);
        assertEquals("٧", badge.text());
        badge.setCount(250);
        assertEquals("٩٩+", badge.text());
    }

    @Test
    void aOneDigitCountIsACircleAndADotHasNoPill() {
        Badge one = Badge.count(3);
        new Scene(one).setTextRuler(SCALED_RULER);
        Size box = one.measure(Constraints.loose(400, 100));
        assertEquals(box.height(), box.width(), 0.001f, "one digit: as wide as tall");

        Badge dot = Badge.dot(I18nString.literal("Unread")).setTone(Tone.ACCENT);
        Recorder drawn = paint(dot, LayoutDirection.LTR);
        assertEquals(1, drawn.fills.size(), "the dot alone");
        assertEquals(Theme.current().primaryText(), drawn.fills.get(0).paint());
        assertTrue(drawn.texts.isEmpty(), "a dot draws no word");
        assertEquals(2 * Theme.current().tokensFor(dot).spacingSmall(), drawn.fills.get(0).w(), 0.001f,
                "twice a text badge's dot");
    }

    @Test
    void aReaderHearsAStaticTextNamedByWhatItShows() {
        Row row = new Row();
        Badge word = new Badge("Overdue").setTone(Tone.DANGER);
        Badge count = Badge.count(3);
        Badge dot = Badge.dot(I18nString.literal("Unread"));
        row.add(word);
        row.add(count);
        row.add(dot);
        bind(row);
        for (String name : List.of("Overdue", "3", "Unread")) {
            AccessibleNode node = node(name);
            assertEquals(Accessible.Role.LABEL, node.role(), name);
            assertFalse(node.states().contains(Accessible.State.FOCUSABLE), name + " takes no focus");
        }
        count.setCount(120);
        frame();
        assertEquals(Accessible.Role.LABEL, node("99+").role(), "the number as shown");
        count.setAccessibleName("120 unread");
        frame();
        assertEquals(Accessible.Role.LABEL, node("120 unread").role(), "the application's name wins");
    }

    /** One frame of {@code s} after a change: the passes it repainted, and whether it cleared all. */
    private static RecordingTestCanvas frameAfter(Scene s, Runnable change) {
        change.run();
        RecordingTestCanvas into = new RecordingTestCanvas(640, 480);
        s.renderFrame(into);
        return into;
    }

    @Test
    void aChangedWordRepaintsTheBadgeAndNeverTheWindow() {
        Badge badge = new Badge("Paid");
        Row row = new Row();
        row.add(new Label("Status"));
        row.add(badge);
        Scene s = new Scene(new limn.scene.layout.Padding(limn.scene.Insets.all(40), row));
        s.setTextRuler(RULER);
        s.setPartialRendering(true);
        s.layoutPass(640, 480);
        // Settled first: the first frames are full by construction, the double buffer's included.
        for (int i = 0; i < 10; i++) {
            s.renderFrame(new RecordingTestCanvas(640, 480));
        }
        float width = badge.width();
        limn.math.Vec2 origin = badge.sceneToLocal(0, 0);
        limn.graphics.Rect box = new limn.graphics.Rect(-origin.x(), -origin.y(), badge.width(), badge.height())
                .inflate(8);

        // RULER is monospaced, so four letters are four letters: the box is kept.
        RecordingTestCanvas same = frameAfter(s, () -> badge.setText("Sent"));
        assertFalse(same.cleared, "not a full frame");
        for (limn.graphics.Rect pass : same.passClips()) {
            assertEquals(pass, pass.intersect(box), "a same-width word repaints inside the badge");
        }
        assertEquals(width, badge.width());

        RecordingTestCanvas wider = frameAfter(s, () -> badge.setText("Overdue"));
        assertFalse(wider.cleared, "a wider word lays the badge out in place, not the window");
        assertTrue(badge.width() > width, "and the badge took the width");
    }
}
