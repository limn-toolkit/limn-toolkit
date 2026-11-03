package limn.graphics;

import limn.print.internal.Recording;
import limn.print.internal.RecordingCanvas;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A drawing recorded once and drawn any number of times: the calls a painter made on a canvas,
 * kept, and replayed onto any canvas, at any scale and under any transform, the screen's, a printed
 * page's or another picture's. A diagram drawn once and shown in many places, a symbol stamped a
 * thousand times, the same drawing sent to the window and to a PDF.
 *
 * <pre>{@code
 * Picture pin = Picture.record(24, 32, c -> {
 *     c.fillPath(Path2D.parse("M12 0C5 0 0 5 0 12c0 9 12 20 12 20s12-11 12-20C24 5 19 0 12 0z"), red);
 *     c.fillCircle(12, 12, 4, Color.WHITE);
 * });
 * for (Vec2 p : stops) {
 *     canvas.save();
 *     canvas.translate(p.x() - 12, p.y() - 32);
 *     canvas.drawPicture(pin);
 *     canvas.restore();
 * }
 * }</pre>
 *
 * <p>What the painter drew is what the picture holds: a path recorded is copied, so a painter may
 * reuse its own for the next frame, and text, images and paints are immutable values. The canvas it
 * records with answers the picture's size as its width and height and a content scale chosen when
 * it is recorded, which is the resolution an icon drawn as a bitmap is picked at. It clears nothing:
 * {@code clear} and {@code clearRect} wipe a window's frame, and a picture has none. A video frame or
 * a 3D view drawn into it is kept and drawn where the picture is drawn on screen, and left out of a
 * printed page.
 *
 * <p>Immutable once recorded, and drawn on the thread of the canvas it is drawn onto. Recorded on
 * the UI thread, as every painter draws: the text a painter shapes is shaped by the UI thread's
 * ruler.
 */
public final class Picture {

    /** The content scale a picture is recorded at unless one is given. */
    public static final float DEFAULT_SCALE = 2f;

    private final Recording recording;
    private final float width;
    private final float height;

    private Picture(Recording recording, float width, float height) {
        this.recording = recording;
        this.width = width;
        this.height = height;
    }

    /**
     * Records what {@code painter} draws on a canvas {@code width} by {@code height} units, of a
     * content scale of {@link #DEFAULT_SCALE}.
     *
     * @throws IllegalArgumentException for a size below 0 or not finite
     */
    public static Picture record(float width, float height, Consumer<Canvas> painter) {
        return record(width, height, DEFAULT_SCALE, painter);
    }

    /**
     * Records what {@code painter} draws on a canvas {@code width} by {@code height} units that
     * answers {@code scale} as its content scale.
     *
     * @throws IllegalArgumentException for a size below 0 or not finite, or a scale not above 0
     */
    public static Picture record(float width, float height, float scale, Consumer<Canvas> painter) {
        Objects.requireNonNull(painter, "painter");
        if (!(width >= 0 && height >= 0) || !Float.isFinite(width) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("a picture's size must be finite and not below 0: " + width + " x "
                    + height);
        }
        if (!(scale > 0) || !Float.isFinite(scale)) {
            throw new IllegalArgumentException("a picture's scale must be above 0: " + scale);
        }
        RecordingCanvas canvas = RecordingCanvas.picture(width, height, scale);
        painter.accept(canvas);
        return new Picture(canvas.finish(), width, height);
    }

    /**
     * Draws the picture onto {@code canvas} in its current coordinates, the picture's origin at
     * theirs; the canvas's state is the same after the call as before it.
     */
    public void draw(Canvas canvas) {
        recording.replay(canvas);
    }

    /** @return the width it was recorded at */
    public float width() {
        return width;
    }

    /** @return the height it was recorded at */
    public float height() {
        return height;
    }

    /** @return how many calls it keeps */
    public int size() {
        return recording.size();
    }
}
