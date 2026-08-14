package limn.demo.site;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Path2D;
import limn.scene.Scene;

/**
 * The mouse pointer, drawn into the picture.
 *
 * <p>An animated capture of a button lighting up on hover shows a control changing by
 * itself, which is not what happens and not what a reader is being told. The arrow is what
 * says a person did it.
 *
 * <p>It is drawn into the scene rather than composited afterwards, for one reason: the capture
 * reads the window's framebuffer, so anything not drawn into that frame is not in the file.
 *
 * <p><b>It is the scene's front painter, not a widget.</b> It was a widget once, added last to
 * the root stack so that it painted over the content, and that is wrong for the one reason a
 * stack cannot fix: {@link Scene} paints its overlays after the root, so an in-scene dialog
 * covered the arrow completely. The theme editor's film opens a colour picker as one, and for
 * four seconds the picture showed a thumb moving and a window recolouring with no hand on
 * screen, which is the exact failure the paragraph above says a capture must never show.
 * Pushing it as an overlay instead fixes the paint and breaks the film: the topmost overlay is
 * the only layer {@code Scene.hitAt} offers a press to, this one answers {@code null}, and the
 * dialog underneath can never be driven. {@link Scene#setFrontPainter} has neither problem,
 * because it paints after every overlay and is not hit-tested at all.
 *
 * <p>Drawn in two passes, dark over light, because it has to stay visible over a violet
 * button and over a white card, which is the same reason every real pointer has an
 * outline.
 */
final class PointerLayer {

    /** Height of the arrow in points, at the size a desktop pointer actually is. */
    private static final float LENGTH = 21f;

    /** The white border's weight. Centred on the path, so half of it sits outside. */
    private static final float BORDER = 2.4f;

    private static final Color INK = Color.rgb(0x14121C);
    private static final Color EDGE = Color.rgb(0xFFFFFF);

    private float pointerX = -1000;
    private float pointerY = -1000;
    private boolean visible;
    private boolean pressed;

    /**
     * The scene this arrow draws over, so a move can mark it damaged. A front painter draws on
     * whatever the frame repaints and nothing tracks it, so moving the arrow without this leaves
     * the previous one on screen under a partial pass.
     */
    private Scene scene;

    /** Installs this as {@code scene}'s front painter. */
    void attachTo(Scene target) {
        this.scene = target;
        target.setFrontPainter(this::paint);
    }

    /** Moves the arrow. Coordinates are the scene's. */
    void setPointer(float x, float y, boolean shown, boolean down) {
        this.pointerX = x;
        this.pointerY = y;
        this.visible = shown;
        this.pressed = down;
        if (scene != null) {
            scene.requestRender();
        }
    }

    /**
     * Never the target of a pointer event, whatever it covers, and now by construction rather
     * than by an override: a front painter is not in the tree, so there is nothing to hit-test.
     *
     * <p>The override this replaced existed because the layer used to be the last child of the
     * root stack, and hit-testing gives later children the point first. Without it the arrow
     * answered every press and the component underneath saw nothing: the first films captured an
     * arrow travelling over a button that never lit up, and a slider whose thumb never moved,
     * which reads as a broken toolkit rather than as a broken harness.
     */
    private void paint(Canvas canvas) {
        if (!visible) {
            return;
        }
        float x = pointerX;
        float y = pointerY;
        if (pressed) {
            // A ring at the tip while the button is down, UNDER the arrow. Without it a press
            // is invisible in a still frame and the control looks like it changed unprovoked;
            // over the arrow it washes the arrow out instead.
            canvas.fillCircle(x, y, LENGTH * 0.58f, Color.rgba(0xFFFFFF, 0.20f));
            canvas.fillCircle(x, y, LENGTH * 0.34f, Color.rgba(0x14121C, 0.16f));
        }

        // The white border is a STROKE around the shape, not a bigger copy of it behind. The
        // bigger copy was scaled about the tip, so its border was hairline at the point and
        // heavy at the tail; that is what made the arrow read as lopsided rather than as a
        // pointer. A stroke is the same weight the whole way round, which is what every
        // platform's own cursor has.
        Path2D shape = arrow(x, y);
        canvas.drawPath(shape, BORDER, EDGE);
        canvas.fillPath(shape, INK);
        // The seam: a fill and a stroke meet on the path, and at this size the join leaves a
        // pale fringe inside the silhouette. Half a point of dark stroke covers it.
        canvas.drawPath(shape, 0.5f, INK);
    }

    /**
     * The standard desktop arrow, drawn from its tip outwards and scaled about it, so the
     * hotspot is the point, which is where a reader believes the click landed.
     *
     * <p>The outline is the shape every platform draws for {@link limn.backend.Cursor#DEFAULT}:
     * a shaft tilted about 20°, a notch, and a squared-off tail. The toolkit itself has no
     * artwork to copy ({@code Cursor} is an enum of shapes the operating system draws, and
     * {@code ImageCursor} takes whatever bitmap an application hands it), so this is drawn
     * to the proportions the platforms agree on rather than lifted from a file.
     */
    private static Path2D arrow(float x, float y) {
        float s = LENGTH;
        return new Path2D()
                .moveTo(x, y)
                .lineTo(x, y + s * 0.75f)
                .lineTo(x + s * 0.185f, y + s * 0.585f)
                .lineTo(x + s * 0.300f, y + s * 0.855f)
                .lineTo(x + s * 0.435f, y + s * 0.800f)
                .lineTo(x + s * 0.320f, y + s * 0.535f)
                .lineTo(x + s * 0.545f, y + s * 0.535f)
                .close();
    }
}
