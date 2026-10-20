package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.chart.ChartPalette;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleTrees;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An avatar and a group of them: the initials across scripts, the stable colour, the picture's
 * clip, the silhouette, the size, the overlap and its "+k" in both directions, and what a reader is
 * given for each.
 */
class AvatarTest extends AccessibleComponentTestBase {

    // ------------------------------------------------------------------ initials

    @Test
    void theInitialsAreTheFirstGraphemeOfTheFirstWordAndOfTheLast() {
        assertEquals("AL", Avatar.initialsOf("Ana Lima", Locale.ENGLISH));
        assertEquals("AL", Avatar.initialsOf("ana maria de lima", Locale.ENGLISH), "first and last word");
        assertEquals("A", Avatar.initialsOf("Ana", Locale.ENGLISH), "one word, one grapheme");
        assertEquals("", Avatar.initialsOf("   ", Locale.ENGLISH), "nothing to take");
        assertEquals("AL", Avatar.initialsOf("  Ana \t Lima  ", Locale.ENGLISH), "spacing does not count");
    }

    @Test
    void anAccentAJoinedEmojiAndAScriptWithoutCaseAreKeptWhole() {
        // A combining accent travels with its letter.
        assertEquals("ÉZ", Avatar.initialsOf("émile zola", Locale.FRENCH));
        // A joined emoji (woman, zero-width joiner, laptop) is one grapheme, not its first code point.
        String coder = "👩‍💻";
        assertEquals(coder + "D", Avatar.initialsOf(coder + " dev", Locale.ENGLISH));
        // No case: taken as written.
        assertEquals("山太", Avatar.initialsOf("山田 太郎", Locale.JAPANESE));
        assertEquals("مع", Avatar.initialsOf("محمد علي", Locale.forLanguageTag("ar")));
    }

    @Test
    void theInitialsAreUpperCasedInTheAvatarsLocale() {
        assertEquals("İA", Avatar.initialsOf("ilknur aksoy", Locale.forLanguageTag("tr")),
                "Turkish dotted capital I");
        assertEquals("IA", Avatar.initialsOf("ilknur aksoy", Locale.ENGLISH));
        Avatar avatar = new Avatar("ilknur aksoy");
        avatar.setLocale(Locale.forLanguageTag("tr"));
        assertEquals("İA", avatar.shownInitials());
        avatar.setInitials("IK");
        assertEquals("IK", avatar.shownInitials(), "given initials are drawn as given");
        avatar.setInitials(null);
        assertEquals("İA", avatar.shownInitials());
    }

    // ------------------------------------------------------------------ colour and paint

    @Test
    void theColourIsTheChartPalettesByTheNamesHashAndCanBeChosen() {
        Avatar avatar = new Avatar("Ana Lima");
        int size = ChartPalette.defaultFor(Theme.current()).size();
        assertEquals(Avatar.colorIndexOf("Ana Lima", size), avatar.colorIndex(),
                "the same name has the same colour on every run");
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (String person : List.of("Ana Lima", "Rui Costa", "Mei Chen", "Omar Haddad", "Lena Vogel",
                "Kofi Mensah", "Ines Duarte")) {
            used.add(Avatar.colorIndexOf(person, size));
        }
        assertTrue(used.size() >= 5, "seven names spread over the palette, not one colour: " + used);
        avatar.setColorIndex(3);
        assertEquals(3, avatar.colorIndex());
        assertThrows(IllegalArgumentException.class, () -> avatar.setColorIndex(-1));
    }

    /** What one frame drew: circles, ellipses, texts, images, round clips. */
    private static final class Recorder extends FakeCanvas {
        record Circle(float cx, float cy, float r, Paint paint) {
        }

        final List<Circle> circles = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Float> xs = new ArrayList<>();
        final List<Font> fonts = new ArrayList<>();
        final List<Paint> inks = new ArrayList<>();
        final List<RoundRect> clips = new ArrayList<>();
        final List<float[]> images = new ArrayList<>();
        int ellipses;

        Recorder(float w, float h) {
            super(w, h);
        }

        @Override
        public void fillCircle(float cx, float cy, float r, Paint paint) {
            circles.add(new Circle(cx, cy, r, paint));
        }

        @Override
        public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) {
            ellipses++;
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            xs.add(x);
            fonts.add(font);
            inks.add(paint);
        }

        @Override
        public void clipRoundRect(RoundRect roundRect) {
            clips.add(roundRect);
        }

        @Override
        public void drawImage(Image image, float sx, float sy, float sw, float sh,
                float dx, float dy, float dw, float dh) {
            images.add(new float[] {sx, sy, sw, sh, dx, dy, dw, dh});
        }
    }

    private static Recorder paint(Widget<?> root, float w, float h) {
        Scene s = new Scene(root);
        s.setTextRuler(SCALED_RULER);
        s.layoutPass(w, h);
        Recorder canvas = new Recorder(w, h);
        s.renderFrame(canvas);
        return canvas;
    }

    @Test
    void initialsAreTheTextColourOverATintOfThePersonsColour() {
        Avatar avatar = new Avatar("Ana Lima");
        Theme theme = Theme.current();
        float d = theme.tokensFor(avatar).controlHeight();
        Recorder drawn = paint(avatar, d, d);
        Color colour = ChartPalette.defaultFor(theme).color(avatar.colorIndex());
        assertEquals(1, drawn.circles.size());
        assertEquals(colour.withAlpha(theme.isDark() ? 0.34f : 0.22f), drawn.circles.get(0).paint());
        assertEquals(d / 2, drawn.circles.get(0).r(), 0.001f, "a circle of the control height");
        assertEquals(List.of("AL"), drawn.texts);
        assertEquals(List.of(theme.text()), drawn.inks);
    }

    @Test
    void aPictureCoversTheCircleFromItsMiddleSquareClippedRound() {
        Avatar avatar = new Avatar("Ana Lima").setImage(new Image(200, 100, new byte[200 * 100 * 4]));
        Recorder drawn = paint(new SizedBox(64, 64, avatar), 64, 64);
        assertEquals(1, drawn.clips.size(), "clipped to the circle");
        RoundRect clip = drawn.clips.get(0);
        assertEquals(32, clip.width() / 2, 0.001f, "a SizedBox makes the circle larger");
        assertEquals(1, drawn.images.size());
        float[] at = drawn.images.get(0);
        assertEquals(50, at[0], 0.001f, "the middle square of a wide picture");
        assertEquals(0, at[1], 0.001f);
        assertEquals(100, at[2], 0.001f);
        assertEquals(100, at[3], 0.001f);
        assertEquals(64, at[6], 0.001f, "scaled to the diameter");
        assertTrue(drawn.texts.isEmpty(), "no initials over a picture");
    }

    @Test
    void withNeitherLettersNorAPictureASilhouetteIsDrawn() {
        Avatar avatar = new Avatar(" ");
        Recorder drawn = paint(avatar, 32, 32);
        assertTrue(drawn.texts.isEmpty());
        assertEquals(1, drawn.ellipses, "the shoulders");
        assertEquals(2, drawn.circles.size(), "the tint and the head");
        assertEquals(Theme.current().textMuted(), drawn.circles.get(1).paint());
    }

    /** A widget painted after the avatar in the same frame, counting its paints. */
    private static final class After extends Widget<After> {
        int painted;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(10, 10);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            painted++;
        }
    }

    @Test
    void anAvatarSqueezedUnderAPointPaintsSafelyAndTheFrameGoesOn() {
        // The audit's scenario: under about 1.2 pt the initials' size rounded to 0 and the font
        // refused it, so the paint threw on every frame and what came after it was never drawn.
        for (float d : new float[] {0, 0.5f, 1.2f}) {
            for (Avatar avatar : List.of(new Avatar("Ada Lovelace"), new Avatar(" "),
                    new Avatar("Ada Lovelace").setImage(new Image(2, 2, new byte[16])))) {
                Column root = new Column();
                root.add(new SizedBox(d, d, avatar));
                After after = new After();
                root.add(after);
                Recorder drawn = paint(root, 100, 100);
                assertEquals(1, after.painted, "d=" + d + ", " + avatar.name() + ": the frame went on");
                assertTrue(drawn.texts.isEmpty(), "no initials under a point: " + drawn.texts);
            }
        }
    }

    @Test
    void aGroupInABoxUnderAPointPaintsSafelyAndTheFrameGoesOn() {
        for (float d : new float[] {0, 0.5f, 1.2f}) {
            Column root = new Column();
            root.add(new SizedBox(d, d, group(7)));
            After after = new After();
            root.add(after);
            paint(root, 100, 100);
            assertEquals(1, after.painted, "d=" + d);
        }
    }

    @Test
    void initialsStayDrawnAsLongAsTheyAreAPointTall() {
        // The clamp is at the font, not at the circle: a small avatar keeps its letters.
        Avatar avatar = new Avatar("Ada Lovelace");
        Recorder drawn = paint(new SizedBox(4, 4, avatar), 4, 4);
        assertEquals(List.of("AL"), drawn.texts);
    }

    @Test
    void aReaderHearsAnImageNamedByThePerson() {
        Row row = new Row();
        row.add(new Avatar("Ana Lima"));
        Avatar quiet = new Avatar("Rui Costa").setAccessibleIgnored(true);
        row.add(quiet);
        bind(row);
        AccessibleNode node = node("Ana Lima");
        assertEquals(Accessible.Role.IMAGE, node.role());
        assertEquals(Accessible.NameFrom.EXPLICIT, node.nameFrom(), "text about the picture");
        assertNull(AccessibleTrees.named(tree(), "Rui Costa"), "ignored beside the name it repeats");
    }

    // ------------------------------------------------------------------ groups

    private static AvatarGroup group(int n) {
        AvatarGroup g = new AvatarGroup();
        for (int i = 0; i < n; i++) {
            g.add(new Avatar("Person " + (char) ('A' + i)));
        }
        return g;
    }

    @Test
    void aGroupOverlapsEachByAQuarterFromTheLeadingEdge() {
        AvatarGroup g = group(3);
        Scene s = new Scene(g);
        s.setTextRuler(SCALED_RULER);
        Size size = g.measure(Constraints.loose(600, 100));
        float d = Theme.current().tokensFor(g).controlHeight();
        assertEquals(d + 2 * 0.75f * d, size.width(), 0.001f);
        s.layoutPass(size.width(), size.height());
        List<Widget<?>> circles = g.children();
        assertEquals(0, circles.get(0).x(), 0.001f);
        assertEquals(0.75f * d, circles.get(1).x(), 0.001f);

        g.setLayoutDirection(LayoutDirection.RTL);
        s.layoutPass(size.width(), size.height());
        assertEquals(size.width() - d, circles.get(0).x(), 0.001f, "right to left, the first is at the right");
        assertEquals(size.width() - d - 0.75f * d, circles.get(1).x(), 0.001f);
    }

    @Test
    void pastItsLimitTheLastPlaceCountsTheRestWithTheirNamesAsATooltip() {
        AvatarGroup g = group(7);
        assertEquals(4, g.maxShown(), "four by default");
        assertEquals(4, g.hiddenCount(), "three drawn, four counted");
        assertEquals(4, g.children().size(), "three avatars and the count's circle");
        Widget<?> more = g.children().get(3);
        assertFalse(more instanceof Avatar);
        assertEquals("Person D, Person E, Person F, Person G", more.tooltip());
        Recorder drawn = paint(g, 400, 60);
        assertTrue(drawn.texts.contains("+4"), "the count, as a combo box writes its own: " + drawn.texts);

        g.setMaxShown(7);
        assertEquals(0, g.hiddenCount());
        assertEquals(7, g.children().size(), "everyone fits: no count");
        assertThrows(IllegalArgumentException.class, () -> g.setMaxShown(0));
        Avatar first = g.members().get(0);
        g.remove(first);
        assertEquals(6, g.members().size());
        assertNull(first.parent());
        assertThrows(IllegalArgumentException.class, () -> g.add(g.members().get(1)),
                "an avatar is in one group");
    }

    // ------------------------------------------------------------------ initials under the overlap

    private static final String CODER = "\uD83D\uDC69\u200D\uD83D\uDCBB"; // woman, joiner, laptop

    /**
     * Asserts that each circle the next one covers keeps its initials' whole run inside the part it
     * shows, and that the last circle keeps them centred; the run's width is the ruler's, at the
     * font the circle drew with.
     */
    private static void assertInitialsClearTheOverlap(AvatarGroup g, boolean rtl) {
        if (rtl) {
            g.setLayoutDirection(LayoutDirection.RTL);
        }
        Recorder drawn = paint(g, 400, 60);
        List<Widget<?>> circles = g.children();
        assertEquals(circles.size(), drawn.texts.size(), "one run per circle: " + drawn.texts);
        for (int i = 0; i < circles.size(); i++) {
            Widget<?> circle = circles.get(i);
            float d = Math.min(circle.width(), circle.height());
            float r = d / 2;
            float left = (circle.width() - d) / 2; // the recorder hears the circle's own coordinates
            String text = drawn.texts.get(i);
            float x = drawn.xs.get(i);
            limn.graphics.TextMetrics m = SCALED_RULER.measure(text, drawn.fonts.get(i));
            String which = (rtl ? "rtl " : "ltr ") + i + " \"" + text + "\"";
            if (i == circles.size() - 1) {
                assertEquals(left + r, x + m.width() / 2, 0.01f, which + ": the last is centred");
                continue;
            }
            // The next circle and its ring cover the far quarter: what shows is the near three
            // quarters less the ring, and at the run's height the circle's edge on the near side.
            float shown = d * 0.75f - Avatar.RING;
            float half = Math.min(r, m.height() / 2);
            float edge = r - (float) Math.sqrt(r * r - half * half);
            float lo = rtl ? left + d - shown : left + edge;
            float hi = rtl ? left + d - edge : left + shown;
            assertTrue(x >= lo - 0.01f && x + m.width() <= hi + 0.01f,
                    which + ": run " + x + ".." + (x + m.width()) + " outside the part shown " + lo + ".." + hi);
        }
    }

    @Test
    void initialsUnderTheNextCircleStayInThePartTheCircleShowsLeftToRight() {
        // The audit's crop: "AL" lost the foot of its L under the next circle's ring. A wide pair
        // and a joined emoji with a letter are the hard cases: the emoji is four code points wide
        // to the ruler, wider than the part shown, and is drawn smaller to fit.
        assertInitialsClearTheOverlap(new AvatarGroup(new Avatar("Ana Lima"), new Avatar("Mei Wong"),
                new Avatar(CODER + " Dev"), new Avatar("Rui Costa")), false);
    }

    @Test
    void initialsUnderTheNextCircleStayInThePartTheCircleShowsRightToLeft() {
        assertInitialsClearTheOverlap(new AvatarGroup(new Avatar("Ana Lima"), new Avatar("Mei Wong"),
                new Avatar(CODER + " Dev"), new Avatar("Rui Costa")), true);
    }

    @Test
    void theCircleBeforeTheCountIsCoveredAndTheCountIsCentred() {
        for (boolean rtl : new boolean[] {false, true}) {
            AvatarGroup g = new AvatarGroup(new Avatar("Ana Lima"), new Avatar("Mei Wong"),
                    new Avatar("Rui Costa"), new Avatar("Lena Vogel"), new Avatar("Omar Haddad"));
            assertInitialsClearTheOverlap(g, rtl);
            assertFalse(g.children().get(g.children().size() - 1) instanceof Avatar, "the count is last");
        }
    }

    @Test
    void anAvatarAloneKeepsItsInitialsCentredAtTheirSize() {
        Avatar alone = new Avatar("Mei Wong");
        float d = Theme.current().tokensFor(alone).controlHeight();
        Recorder drawn = paint(alone, d, d);
        limn.graphics.TextMetrics m = SCALED_RULER.measure("MW", drawn.fonts.get(0));
        assertEquals(d / 2, drawn.xs.get(0) + m.width() / 2, 0.01f);
        assertEquals(Theme.current().tokensFor(alone).label().size(), drawn.fonts.get(0).size(), 0.01f);
    }

    @Test
    void aReaderHearsOneGroupDescribedByEveryNameTheHiddenOnesIncluded() {
        AvatarGroup g = group(6).setAccessibleName("Approvers");
        bind(g);
        AccessibleNode node = node("Approvers");
        assertEquals(Accessible.Role.GROUP, node.role());
        assertEquals("Person A, Person B, Person C, Person D, Person E, Person F", node.description());
        assertTrue(childrenOf(node).isEmpty(), "the circles are not published: " + childrenOf(node));
        g.members().get(4).setName("Eva Melo");
        frame();
        assertEquals("Person A, Person B, Person C, Person D, Eva Melo, Person F", node("Approvers").description(),
                "a hidden member's new name reaches the description");
        assertNotEquals(-1, node.id());
    }
}
