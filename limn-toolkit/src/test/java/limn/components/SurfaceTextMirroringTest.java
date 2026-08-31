package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.math.Ray;
import limn.render3d.CameraController;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VideoView} and {@link Viewport3D} read right to left: the two widgets whose content is a
 * surface rather than a sentence.
 *
 * <p>Almost every case here asserts that something does <b>not</b> move, and that is the point of
 * the file. A picture is content, a rendered scene is content, and a pointer is where the hand
 * physically is; a later sweep looking for "every horizontal coordinate" will find the letterbox,
 * the picking ray and the two centred notices, and each of them has a case here saying it is
 * already right. The one thing that does change is invisible in a screenshot: the notices are
 * <em>shaped</em> rather than measured blind, so a translated message is typeset for the direction
 * it reads in.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters. Both widgets fall
 * back to their notice when no backend is installed, which is what a headless test always is, so
 * the notice path is the one that runs here without a GPU.
 */
class SurfaceTextMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    /** Wide enough that neither notice reaches the clamp that would pin it to the box. */
    private static final float WIDTH = 600;
    private static final float HEIGHT = 300;
    /** The pill's horizontal padding, from the same row the widget resolves. */
    private static final float PILL_PAD_H = SizeTokens.of(ControlSize.MEDIUM).tooltipPadH();

    /** The notices are translated strings; pin the language so their widths are the ones below. */
    @BeforeEach
    void pinLocale() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Put it back where the suite expects it, for the one case below that changes it. */
    @AfterEach
    void restoreLocale() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /** What {@link #RULER} makes of a string: 10pt per code point. */
    private static float ruled(String text) {
        return 10f * text.codePointCount(0, text.length());
    }

    // ------------------------------------------------------------- fixtures

    private Viewport3D viewport;
    private Scene scene;
    private BaseRecordingRuler ruler;

    private void buildVideo(LayoutDirection direction) {
        VideoView video = new VideoView();
        video.setPreferredSize(WIDTH, HEIGHT);
        video.setLayoutDirection(direction);
        build(video);
    }

    private void buildViewport(LayoutDirection direction) {
        viewport = new Viewport3D();
        viewport.setPreferredSize(WIDTH, HEIGHT);
        viewport.setLayoutDirection(direction);
        build(viewport);
    }

    private void build(Widget root) {
        ruler = new BaseRecordingRuler();
        scene = new Scene(root);
        scene.setTextRuler(ruler);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    private Recorder painted() {
        Recorder canvas = new Recorder(WIDTH, HEIGHT);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** The x of the one string either widget draws, which is the left edge of its shaped run. */
    private float noticeX() {
        List<Float> xs = painted().textX;
        assertEquals(1, xs.size(), "each fixture draws exactly one notice");
        return xs.get(0);
    }

    /**
     * Asserts that the frame just painted shaped {@code text} and shaped it for {@code base}, and
     * that it asked for nothing else. Distinct rather than exact, so a frame split over two damage
     * rectangles reports the same thing as a frame painted in one.
     */
    private void assertShapedAs(String text, ShapedText.Direction base, String because) {
        assertEquals(List.of(text + "@" + base), ruler.shaped.stream().distinct().toList(),
                because);
    }

    // -------------------------------------------------- VideoView's notice pill

    @Test
    void theVideoNoticeIsCentredReadingLeftToRight() {
        buildVideo(LayoutDirection.LTR);
        float text = ruled(ComponentStrings.VIDEO_NO_BACKEND.get());
        // Centred text inside a pill that is itself centred: the two offsets collapse into one
        // centring of the text in the box, which is the arithmetic the default has always had.
        assertEquals((WIDTH - text) / 2, noticeX(), EPS, "the default is unchanged");
    }

    /**
     * DOES NOT MIRROR. The pill is centred on both axes, so there is no leading edge in its
     * arithmetic for a direction to reflect; reflecting a centred box about the middle of the box
     * it is centred in returns the box. A sweep that "mirrors every x" here either writes a no-op
     * or double-offsets a box that was already in the middle.
     */
    @Test
    void theVideoNoticeDoesNotMoveReadingRightToLeft() {
        buildVideo(LayoutDirection.LTR);
        Recorder ltr = painted();
        buildVideo(LayoutDirection.RTL);
        Recorder rtl = painted();
        assertEquals(ltr.textX.get(0), rtl.textX.get(0), EPS,
                "a centre is the same number in both directions");
        assertEquals(ltr.roundRectX.get(0), rtl.roundRectX.get(0), EPS,
                "and so is the pill around it");
    }

    /**
     * The pill is sized from the line that is actually drawn, not from a second measurement: one
     * shaping call feeds the pill's width and the text's x, so the two cannot disagree by the
     * fraction of a point a re-measure of the same string can cost.
     */
    @Test
    void theVideoPillIsSizedFromTheLineItDraws() {
        buildVideo(LayoutDirection.RTL);
        float text = ruled(ComponentStrings.VIDEO_NO_BACKEND.get());
        assertEquals((WIDTH - (text + 2 * PILL_PAD_H)) / 2, painted().roundRectX.get(0), EPS);
    }

    /**
     * Decision 7 is a fallback, not an imposition: a Latin notice in a right-to-left player still
     * reads left to right, because the first strong character decides everything it can decide and
     * the widget's own direction is consulted only where nothing strong has an opinion.
     */
    @Test
    void aLatinVideoNoticeStillReadsLeftToRightInARightToLeftView() {
        buildVideo(LayoutDirection.RTL);
        painted();
        assertShapedAs(ComponentStrings.VIDEO_NO_BACKEND.get(), ShapedText.Direction.LTR,
                "the string's own first strong character wins");
    }

    /** And a notice that does read right to left is shaped that way in a left-to-right player. */
    @Test
    void anArabicVideoNoticeReadsRightToLeftInALeftToRightView() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        buildVideo(LayoutDirection.LTR);
        String message = ComponentStrings.VIDEO_NO_BACKEND.get();
        assertEquals(ShapedText.Direction.RTL,
                ShapedText.Direction.of(message, ShapedText.Direction.LTR),
                "the premise: the shipped Arabic notice starts with a strong right-to-left "
                        + "character, and what was found was " + message);
        painted();
        assertShapedAs(message, ShapedText.Direction.RTL,
                "the notice is shaped, not measured against a left-to-right base");
    }

    // ------------------------------------------------ Viewport3D's placeholder

    @Test
    void theViewportPlaceholderIsCentredReadingLeftToRight() {
        buildViewport(LayoutDirection.LTR);
        float text = ruled(ComponentStrings.VIEWPORT3D_NO_BACKEND.get());
        assertEquals((WIDTH - text) / 2, noticeX(), EPS, "the default is unchanged");
    }

    /** DOES NOT MIRROR, for the same reason the pill does not: both coordinates are centres. */
    @Test
    void theViewportPlaceholderDoesNotMoveReadingRightToLeft() {
        buildViewport(LayoutDirection.LTR);
        Recorder ltr = painted();
        buildViewport(LayoutDirection.RTL);
        Recorder rtl = painted();
        assertEquals(ltr.textX.get(0), rtl.textX.get(0), EPS, "x is a centre");
        assertEquals(ltr.textY.get(0), rtl.textY.get(0), EPS, "and the baseline never had a side");
    }

    @Test
    void aLatinPlaceholderStillReadsLeftToRightInARightToLeftViewport() {
        buildViewport(LayoutDirection.RTL);
        painted();
        assertShapedAs(ComponentStrings.VIEWPORT3D_NO_BACKEND.get(), ShapedText.Direction.LTR,
                "the string's own first strong character wins");
    }

    // ------------------------------------------------------- the physical half

    /**
     * DOES NOT MIRROR. {@link Viewport3D#rayAt} takes viewport-local pixels, which are where the
     * pointer physically is; a reflected ray would pick the mirror image of the pixel the caller
     * named, so every pick would land on the far side of the scene from the cursor.
     */
    @Test
    void pickingIsTheSameRayInBothDirections() {
        buildViewport(LayoutDirection.LTR);
        Ray ltr = viewport.rayAt(50, 60);
        buildViewport(LayoutDirection.RTL);
        Ray rtl = viewport.rayAt(50, 60);
        assertEquals(ltr.origin().x(), rtl.origin().x(), EPS);
        assertEquals(ltr.direction().x(), rtl.direction().x(), EPS,
                "a pick reads no direction, because a camera space is not a reading axis");
        assertEquals(ltr.direction().y(), rtl.direction().y(), EPS);
        assertEquals(ltr.direction().z(), rtl.direction().z(), EPS);
    }

    /**
     * DOES NOT MIRROR. Direct manipulation follows the hand: a camera that orbited away from the
     * drag would be wrong in every language.
     */
    @Test
    void aDragOrbitsWithTheHandInBothDirections() {
        assertEquals(30, orbitDeltaX(LayoutDirection.LTR), 0.5f, "the default is unchanged");
        assertEquals(30, orbitDeltaX(LayoutDirection.RTL), 0.5f,
                "dragging right still orbits right");
    }

    private float orbitDeltaX(LayoutDirection direction) {
        List<Float> drags = new ArrayList<>();
        buildViewport(direction);
        viewport.setController(new CameraController() {
            @Override
            public void drag(float dx, float dy) {
                drags.add(dx);
            }

            @Override
            public void zoom(float amount) {
            }
        });
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 100, 100);
        scene.mouseMoved(130, 100);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 130, 100);
        scene.inputBatchEnded();
        assertEquals(1, drags.size(), "one drag was delivered");
        return drags.get(0);
    }

    /**
     * NOT A SITE. Neither of these widgets has an arrow-key decision to mirror: a viewport moves
     * its camera with the pointer and a player has no key handler at all, so there is nothing here
     * for a direction to reverse. Asserted rather than assumed, so that a later sweep adding key
     * handling has to come back to this file and decide on purpose.
     */
    @Test
    void neitherSurfaceWidgetAnswersTheArrowKeys() {
        List<String> moved = new ArrayList<>();
        buildViewport(LayoutDirection.RTL);
        viewport.setController(new CameraController() {
            @Override
            public void drag(float dx, float dy) {
                moved.add("drag");
            }

            @Override
            public void zoom(float amount) {
                moved.add("zoom");
            }
        });
        pressBothArrows();
        assertTrue(moved.isEmpty(), "the camera did not move: " + moved);

        buildVideo(LayoutDirection.RTL);
        pressBothArrows();
        float text = ruled(ComponentStrings.VIDEO_NO_BACKEND.get());
        assertEquals((WIDTH - text) / 2, noticeX(), EPS, "the player answered neither key");
    }

    private void pressBothArrows() {
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
    }

    // ---------------------------------------------------------------- fakes

    /** Records where each string and each rounded rectangle was drawn. */
    private static final class Recorder extends FakeCanvas {

        private final List<Float> textX = new ArrayList<>();
        private final List<Float> textY = new ArrayList<>();
        private final List<Float> roundRectX = new ArrayList<>();

        Recorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            textX.add(x);
            textY.add(y);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            roundRectX.add(roundRect.x());
        }
    }

    /**
     * {@link #RULER}, plus a note of the base direction every shaping was asked for. A font-blind
     * ruler measures a line the same width whichever direction it was shaped for, so the direction
     * cannot be recovered from the geometry and is caught at the call instead.
     */
    private static final class BaseRecordingRuler implements TextRuler {

        private final List<String> shaped = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            shaped.add(text + "@" + base);
            return TextRuler.super.shape(text, font, base);
        }
    }
}
