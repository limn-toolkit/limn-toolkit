package limn.backend.lwjgl;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A partial frame through a moving {@link Transform} draws what the whole frame drew, against a real
 * device (ADR 072 §2.6, §6, stages 1 to 3): the change damages where the child was and where it is, a
 * pass reaches the child wherever it is drawn, and nothing outside the passes is read or written.
 *
 * <p>Each case is a whole frame, then one change and a partial frame into the same target kept as a
 * buffer that holds its contents (age 1), then a whole frame of the same state to hold it against,
 * as {@code OpacityPassSeamTest} does for a fade. The container sits a fraction of a point off the
 * grid and moves by fractions, which is where a damage rounded the wrong way would show as a seam.
 */
class TransformPassSeamTest {

    private static final float SCALE = 2f;
    private static final int WIDTH = 360;
    private static final int HEIGHT = 200;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        Theme.setCurrent(Theme.dark());
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            canvas.dispose();
            canvas = null;
        }
        if (fonts != null) {
            fonts.close();
            fonts = null;
        }
        if (ui != null) {
            ui.close();
            ui = null;
        }
    }

    /** Bright stripes behind everything, so a stale pixel anywhere shows. */
    private static final class Picture extends Widget<Picture> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(WIDTH / SCALE, HEIGHT / SCALE);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            for (int band = 0; band * 6 < height(); band++) {
                canvas.fillRect(0, band * 6, width(), 6,
                        band % 2 == 0 ? Color.rgb(0xF2A65A) : Color.rgb(0x3050C8));
            }
        }
    }

    /** A card with a shadow-like rim and a highlight, its rim declared as its outset. */
    private static final class Card extends Widget<Card> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(50, 34);
        }

        @Override
        protected float paintOutset() {
            return 3;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(-3, -3, width() + 6, height() + 6, 9, Color.rgba(0x000000, 0.3f));
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), 6, Color.WHITE, 1, Color.rgb(0x808890));
            canvas.fillRoundRect(5, 12, width() - 10, 10, 4, Color.rgb(0xD6E4FF));
        }
    }

    /** A small thing that changes colour by itself, where the slid card now lies. */
    private static final class Thumb extends Widget<Thumb> {
        private Color color = Color.rgb(0x20A060);

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(6, 6);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), color);
        }

        void recolor() {
            color = Color.rgb(0xC02080);
            invalidate();
        }
    }

    /** The stripes, and the card in a container a fraction off the grid, in a padding of its own. */
    private static Stack layers(Widget<?> moving) {
        Stack layers = new Stack();
        layers.add(new Picture());
        layers.add(new Padding(new Insets(30.35f, 0, 0, 20.1f), moving));
        return layers;
    }

    @Test
    void aSlideStepDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setTranslation(7.3f, 4.6f);
        assertPartialIsWhole(layers(t), () -> t.setTranslation(23.15f, 9.25f));
    }

    @Test
    void aSlidePastAnUnclippedParentsBoxDrawsWhatTheWholeFrameDrew() {
        // The padding's box ends at the card's right edge, and the card goes three widths past it.
        Transform t = new Transform(new Card());
        assertPartialIsWhole(layers(t), () -> t.setTranslation(97.7f, 21.3f));
    }

    @Test
    void somethingRepaintingWhereASlidCardLiesDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setTranslation(97.7f, 21.3f);
        Thumb thumb = new Thumb();
        Stack root = layers(t);
        root.add(new Padding(new Insets(30.35f + 21.3f + 14, 0, 0, 20.1f + 97.7f + 20), thumb));
        assertPartialIsWhole(root, thumb::recolor);
    }

    @Test
    void aScaleStepDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setOrigin(0.2f, 0.7f).setScale(1.13f);
        assertPartialIsWhole(layers(t), () -> t.setScale(1.71f, 1.37f).setTranslation(3.3f, -2.1f));
    }

    @Test
    void aFadingSlideDrawsWhatTheWholeFrameDrewInEitherOrder() {
        Transform outer = new Transform(new Card()).setTranslation(7.3f, 4.6f);
        Opacity fadingOutside = new Opacity(outer).setOpacity(0.6f);
        assertPartialIsWhole(layers(fadingOutside), () -> outer.setTranslation(61.15f, 9.25f));

        Opacity fadingInside = new Opacity(new Card()).setOpacity(0.6f);
        Transform around = new Transform(fadingInside).setTranslation(7.3f, 4.6f);
        assertPartialIsWhole(layers(around), () -> around.setTranslation(61.15f, 9.25f));
    }

    // ------------------------------------------------------------------ turned (stage 2)

    @Test
    void aRotationStepDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setRotation(7.5f);
        assertPartialIsWhole(layers(t), () -> t.setRotation(23.25f).setTranslation(3.3f, -2.1f));
    }

    @Test
    void aSkewStepDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setMatrix(limn.math.Mat4.affine2D(1, 0, 0.2f, 1, 0, 0));
        assertPartialIsWhole(layers(t), () -> t.setMatrix(limn.math.Mat4.affine2D(1, 0.15f, -0.3f, 1, 0, 0)));
    }

    @Test
    void somethingRepaintingInsideATurnedCardDrawsWhatTheWholeFrameDrew() {
        // The pass is the thumb's box, turned: a small part of the card's picture, cut out of it
        // by the pass and laid at the angle, against the whole card's picture.
        Thumb thumb = new Thumb();
        Stack card = new Stack();
        card.add(new Card());
        card.add(new Padding(new Insets(14, 0, 0, 22), thumb));
        Transform t = new Transform(card).setRotation(-17.3f);
        assertPartialIsWhole(layers(t), thumb::recolor);
    }

    @Test
    void aFadingRotationDrawsWhatTheWholeFrameDrewInEitherOrder() {
        Transform outer = new Transform(new Card()).setRotation(11);
        Opacity fadingOutside = new Opacity(outer).setOpacity(0.6f);
        assertPartialIsWhole(layers(fadingOutside), () -> outer.setRotation(29.5f));

        Opacity fadingInside = new Opacity(new Card()).setOpacity(0.6f);
        Transform around = new Transform(fadingInside).setRotation(11);
        assertPartialIsWhole(layers(around), () -> around.setRotation(29.5f));
    }

    // ------------------------------------------------------------------ in depth (stage 3)

    @Test
    void aTurnInDepthStepDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setPerspective(140).setRotation3D(limn.math.Vec3.UNIT_Y, 25);
        assertPartialIsWhole(layers(t), () -> t.setRotation3D(new limn.math.Vec3(0.3f, 1, 0), 52.5f)
                .setTranslation(13.3f, -2.1f));
    }

    @Test
    void aTurnInDepthAtTheEdgeOfTheEyeDrawsWhatTheWholeFrameDrew() {
        // The eye 30 points in front of a card 50 wide: turned 70 degrees, its near part lies past
        // the eye and the rest is drawn magnified; a step on, the cut moves.
        Transform t = new Transform(new Card()).setPerspective(30).setRotation3D(limn.math.Vec3.UNIT_Y, 70);
        assertPartialIsWhole(layers(t), () -> t.setRotation3D(limn.math.Vec3.UNIT_Y, 74.5f));
    }

    @Test
    void somethingRepaintingInsideACardTurnedInDepthDrawsWhatTheWholeFrameDrew() {
        Thumb thumb = new Thumb();
        Stack card = new Stack();
        card.add(new Card());
        card.add(new Padding(new Insets(14, 0, 0, 22), thumb));
        Transform t = new Transform(card).setPerspective(160).setRotation3D(new limn.math.Vec3(1, 1, 0), -38);
        assertPartialIsWhole(layers(t), thumb::recolor);
    }

    @Test
    void somethingRepaintingInsideACardTurnedFarDrawsWhatTheWholeFrameDrewThroughItsMipmaps() {
        // Turned 72 degrees, the card's far edge is seen at a fifth of its picture's size, and each
        // frame's composite reads a mipmap chain: the pass's smaller picture must average the same
        // pixels into each level as the whole frame's (ADR 072 section 12).
        Thumb thumb = new Thumb();
        Stack card = new Stack();
        card.add(new Card());
        card.add(new Padding(new Insets(14, 0, 0, 22), thumb));
        Transform t = new Transform(card).setPerspective(160).setRotation3D(limn.math.Vec3.UNIT_Y, 72);
        assertPartialIsWhole(layers(t), thumb::recolor);
        assertTrue(canvas.mipmapsMade() >= 3, "each frame made the chain: " + canvas.mipmapsMade());
    }

    @Test
    void somethingRepaintingOverACardTurnedFarDrawsWhatTheWholeFrameDrewThroughItsMipmaps() {
        // The pass is a thumb's box laid over the turned card, not inside it: the card's picture is
        // drawn for that part alone, and its chain must average into each texel of each level the
        // pixels the whole frame's did, the part's corner on the grid of the coarsest level and its
        // reach past the pass that level's footprint.
        Transform t = new Transform(new Card()).setPerspective(160).setRotation3D(limn.math.Vec3.UNIT_Y, 72);
        Thumb thumb = new Thumb();
        Stack root = layers(t);
        root.add(new Padding(new Insets(30.35f + 15.3f, 0, 0, 20.1f + 21.7f), thumb));
        assertPartialIsWhole(root, thumb::recolor);
        assertTrue(canvas.mipmapsMade() >= 3, "each frame made the chain: " + canvas.mipmapsMade());
    }

    @Test
    void aBackFaceHiddenAndShownDrawsWhatTheWholeFrameDrew() {
        Transform t = new Transform(new Card()).setPerspective(200).setRotation3D(limn.math.Vec3.UNIT_X, 150);
        assertPartialIsWhole(layers(t), () -> t.setBackfaceVisible(false));
        Transform shown = new Transform(new Card()).setPerspective(200).setRotation3D(limn.math.Vec3.UNIT_X, 150)
                .setBackfaceVisible(false);
        assertPartialIsWhole(layers(shown), () -> shown.setBackfaceVisible(true));
    }

    // ------------------------------------------------------------------ moved by an in-place layout

    /** A plain block of colour, a sibling that hides or a child that does. */
    private static final class Block extends Widget<Block> {
        private final float w;
        private final float h;
        private final Color color;

        Block(float w, float h, Color color) {
            this.w = w;
            this.h = h;
            this.color = color;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), color);
        }
    }

    /** The four ways a container draws its child away from its box, each well past a wrapper's. */
    enum Kind {
        SLID {
            @Override
            Transform make() {
                return new Transform(new Card()).setTranslation(41.3f, 8.6f);
            }
        },
        SCALED {
            @Override
            Transform make() {
                return new Transform(new Card()).setOrigin(0.2f, 0.7f).setScale(1.37f, 1.21f).setTranslation(17.3f, 3.1f);
            }
        },
        ROTATED {
            @Override
            Transform make() {
                return new Transform(new Card()).setRotation(23.5f).setTranslation(31.3f, 6.1f);
            }
        },
        TURNED_IN_DEPTH {
            @Override
            Transform make() {
                return new Transform(new Card()).setPerspective(140).setRotation3D(limn.math.Vec3.UNIT_Y, 38)
                        .setTranslation(33.3f, 5.7f);
            }
        };

        abstract Transform make();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aWrappedTransformMovedByASiblingHiddenDrawsWhatTheWholeFrameDrew(Kind kind) {
        // A column of fixed size: a banner above, then a padding holding the container, which draws
        // its card past the padding's box. The banner hides, an in-place layout moves the padding
        // up, and the card's picture moves with it: where it was and where it is are both outside
        // the padding's box and the padding's own reach.
        Block banner = new Block(60, 18, Color.rgb(0x20A060));
        Column column = new Column();
        column.add(banner);
        column.add(new Padding(new Insets(0, 0, 0, 0), kind.make()));
        assertPartialIsWhole(layers(new SizedBox(150, 60, column)), () -> banner.setVisible(false),
                kind + ", sibling hidden");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aWrappedTransformMovedByASiblingsTextGrowingDrawsWhatTheWholeFrameDrew(Kind kind) {
        // A label beside the padding grows with its text, which lays the row out in place, and the
        // padding moves toward the end with the card drawn past it.
        limn.components.Label label = new limn.components.Label("Go");
        Row row = new Row();
        row.add(label);
        row.add(new Padding(new Insets(0, 0, 0, 0), kind.make()));
        assertPartialIsWhole(layers(new SizedBox(170, 60, row)), () -> label.setText("Go a good way further"),
                kind + ", sibling's text grown");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aWrapperHiddenRoundATransformDrawsWhatTheWholeFrameDrew(Kind kind) {
        // The padding round the container hides: the card it drew past the padding's box must go
        // with it, though by the frame the layout runs the branch is hidden and draws nothing.
        Padding wrapper = new Padding(new Insets(0, 0, 0, 0), kind.make());
        Column column = new Column();
        column.add(new Block(60, 18, Color.rgb(0x20A060)));
        column.add(wrapper);
        column.add(new Block(60, 8, Color.rgb(0xC02080)));
        assertPartialIsWhole(layers(new SizedBox(150, 70, column)), () -> wrapper.setVisible(false),
                kind + ", wrapper hidden");
    }

    /** A picture with stripes across it, so that one moved by a fraction shows. */
    private static final class Stripes extends Widget<Stripes> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(50, 40);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            for (int band = 0; band * 5 < height(); band++) {
                canvas.fillRect(0, band * 5, width(), 5, band % 2 == 0 ? Color.WHITE : Color.rgb(0x206040));
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPictureZoomedPastTheViewItScrollsInMovedByABannerDrawsWhatTheWholeFrameDrew(boolean shown) {
        // An image preview zoomed four times, larger than the scroll view round it on every side,
        // under a banner that hides or comes back: cut at the view, its picture is the same
        // rectangle before and after, and it still moved inside it.
        Block banner = new Block(60, 14, Color.rgb(0x20A060));
        banner.setVisible(!shown);
        Column column = new Column();
        column.add(banner);
        column.add(new Padding(new Insets(0, 0, 0, 0),
                new Transform(new Stripes()).setScale(4).setTranslation(30, 0)));
        assertPartialIsWhole(layers(new SizedBox(150, 90, new limn.components.ScrollView(column))),
                () -> banner.setVisible(shown), shown ? "banner shown" : "banner hidden");
    }

    @Test
    void theLastOfARowOfZoomedCellsHiddenDrawsWhatTheWholeFrameDrew() {
        // A row of cells, each card zoomed past its cell as a hover does; the last cell hides.
        Row row = new Row();
        Padding last = null;
        for (int i = 0; i < 3; i++) {
            last = new Padding(new Insets(0, 0, 0, 0), new Transform(new Card()).setScale(1.4f));
            row.add(last);
        }
        Padding hidden = last;
        assertPartialIsWhole(layers(new SizedBox(170, 60, row)), () -> hidden.setVisible(false),
                "zoomed cell hidden");
    }

    @Test
    void aTransformResizedByAChildHiddenInsideItDrawsWhatTheWholeFrameDrew() {
        // The container's own size changes: its origin is a fraction of its box, so its matrix moves
        // the card that stays, and the layout that set the new matrix damaged neither place.
        Block second = new Block(50, 20, Color.rgb(0xC02080));
        Column inner = new Column();
        inner.add(new Card());
        inner.add(second);
        Transform t = new Transform(inner).setScale(1.5f).setTranslation(30.3f, 12.2f);
        Column column = new Column();
        column.add(new Padding(new Insets(0, 0, 0, 0), t));
        assertPartialIsWhole(layers(new SizedBox(150, 60, column)), () -> second.setVisible(false),
                "scaled, a child inside hidden");
    }

    private void assertPartialIsWhole(Widget<?> root, Runnable change) {
        assertPartialIsWhole(root, change, "");
    }

    private void assertPartialIsWhole(Widget<?> root, Runnable change, String scenario) {
        // One instant for every frame: a scroll bar fades on the clock, and the whole frame drawn
        // after the partial one must show the same bar.
        Scene scene = new Scene(root, () -> 5_000_000_000L);
        scene.setTextRuler(new ShapingRuler(fonts)); // a label measures its text by the real faces
        scene.setPartialRendering(true);
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            bindTarget(texture, fbo);
            frame(scene, 0);
            byte[] before = readTopDown();
            change.run();
            frame(scene, 1);
            byte[] partial = readTopDown();
            frame(scene, 0);
            byte[] whole = readTopDown();
            assertTrue(differs(before, whole), scenario + ": the change moved something, or the case proves nothing");
            assertSame(whole, partial, scenario);
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
        }
    }

    private void frame(Scene scene, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
    }

    private static boolean differs(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return true;
            }
        }
        return false;
    }

    /** Every pixel within 2 of the reference, or the worst one and how many are not. */
    private static void assertSame(byte[] expected, byte[] actual, String scenario) {
        int bad = 0;
        int worst = 0;
        int where = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
            if (d > 2) {
                bad++;
            }
            if (d > worst) {
                worst = d;
                where = i / 4;
            }
        }
        if (bad > 0) {
            fail(scenario + ": the partial frame differs from the whole one in " + bad + " channel(s), worst " + worst
                    + " at " + (where % WIDTH) + "," + (where / WIDTH));
        }
        assertEquals(0, bad);
    }

    private static void bindTarget(int texture, int fbo) {
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER),
                "render target");
        Gl.glViewport(0, 0, WIDTH, HEIGHT);
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[WIDTH * HEIGHT * 4];
            int stride = WIDTH * 4;
            for (int row = 0; row < HEIGHT; row++) {
                pixels.position((HEIGHT - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
