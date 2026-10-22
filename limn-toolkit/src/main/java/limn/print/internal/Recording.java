package limn.print.internal;

import limn.graphics.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One page's drawing, kept as the calls that made it: the one picture of a page (ADR 078 §7.1). The
 * PDF writer replays it into a canvas that writes PDF, and the preview replays it into a window's
 * canvas, so the page on screen and the page in the file come from the same calls.
 *
 * <p>Every call kept holds its own arguments: a path is copied as it is recorded, since the widget
 * that drew it may reset it for its next frame; text, images and paints are immutable values.
 * Immutable once recorded, and replayed on the thread of the canvas it is replayed into.
 */
public final class Recording {

    /** One kept call. */
    @FunctionalInterface
    public interface Op {
        void replay(Canvas canvas);
    }

    private final float width;
    private final float height;
    private final List<Op> ops;

    Recording(float width, float height, List<Op> ops) {
        this.width = width;
        this.height = height;
        this.ops = List.copyOf(ops);
    }

    /** @return the page's width, in document units (1/96 inch) */
    public float width() {
        return width;
    }

    /** @return the page's height, in document units */
    public float height() {
        return height;
    }

    /** @return how many calls the page keeps */
    public int size() {
        return ops.size();
    }

    /**
     * Draws the page into {@code canvas}, in the canvas's current coordinates: the page's top left
     * corner at the origin, one document unit to one unit of the canvas. The canvas's state is the
     * same after the call as before it.
     *
     * @param canvas where to draw it
     */
    public void replay(Canvas canvas) {
        Objects.requireNonNull(canvas, "canvas");
        int depth = canvas.saveCount();
        canvas.save();
        try {
            for (Op op : ops) {
                op.replay(canvas);
            }
        } finally {
            canvas.restoreToCount(depth);
        }
    }

    /** Builds a recording; what {@link RecordingCanvas} fills. */
    static final class Builder {
        private final float width;
        private final float height;
        private final List<Op> ops = new ArrayList<>();

        Builder(float width, float height) {
            this.width = width;
            this.height = height;
        }

        void add(Op op) {
            ops.add(op);
        }

        Recording build() {
            return new Recording(width, height, ops);
        }
    }
}
