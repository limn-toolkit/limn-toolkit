package limn.components;

import limn.scene.Size;

import java.util.Objects;

/**
 * How much of a scrolling widget's box its scrollbars take, and the settling that
 * decides it. Owned by the widget, one instance each: {@link ScrollView},
 * {@link ListView} and {@link TextArea} all hold one, and so should anything else
 * that scrolls.
 *
 * <pre>{@code
 * // in onLayout, where `content` re-measures into a viewport:
 * Size size = gutters.resolve(width(), height(), vBar, hBar,
 *         (viewW, viewH) -> child.measure(Constraints.loose(viewW, viewH)));
 * float viewW = gutters.viewportWidth(width());
 * }</pre>
 *
 * <p>The class exists because three widgets needed the same three paragraphs of
 * reasoning, and a rule copied three times is a rule that will differ three ways
 * within a year. What is genuinely per-widget (how content is measured, where
 * rows are placed, what gets clipped) stays with the widget; what is identical
 * (<em>when</em> a strip is reserved and <em>how wide</em>) lives here.
 */
public final class ScrollGutters {

    /** Whether a bar floats over the content or takes a strip out of it. */
    public enum Layout {
        /**
         * Over the content, which keeps the whole box for it. The default, and the
         * only coherent choice under the fading policies ({@link
         * ScrollBar.Policy#AUTO}, {@link ScrollBar.Policy#ON_SCROLL}): a strip held
         * open for a bar that is usually not there is dead space. Right for an
         * image, a video, a page of prose: anything that wants every point of
         * width and loses nothing to a bar passing over it.
         */
        OVERLAY,
        /**
         * A strip of the bar's own thickness on whichever axis overflows, with the
         * content narrowed to match, so nothing is ever painted under a bar. Right
         * for a table, a form or a tree, where the last column is the one being
         * read. Pair it with {@link ScrollBar.Policy#ALWAYS} unless a bar that
         * fades out of an otherwise empty strip is what you want.
         */
        RESERVED
    }

    /**
     * How the content is measured for a candidate viewport. Called once, or twice
     * when reserving a strip changes the answer; see {@link #resolve}.
     */
    @FunctionalInterface
    public interface Content {
        /** @return the content's size when laid out in this viewport */
        Size measure(float viewportWidth, float viewportHeight);
    }

    /**
     * Overflow under this is not overflow. A locked dead-band, like every other
     * threshold in the toolkit: without it a content exactly as tall as its
     * viewport reserves a strip, which shortens the viewport, which is still
     * exactly the content, and the layout flickers between two answers.
     */
    private static final float OVERFLOW_SLOP = 0.5f;

    private Layout layout = Layout.OVERLAY;
    private float verticalStrip;
    private float horizontalStrip;

    /** Sets the mode (default {@link Layout#OVERLAY}); the host asks for the relayout. */
    public void setLayout(Layout newLayout) {
        this.layout = Objects.requireNonNull(newLayout, "layout");
    }

    /** Whether the bars overlay the content or reserve a gutter beside it. */
    public Layout layout() {
        return layout;
    }

    /** Width taken by the vertical bar, or {@code 0} when it floats or is not needed. */
    public float verticalStrip() {
        return verticalStrip;
    }

    /** Height taken by the horizontal bar, likewise. */
    public float horizontalStrip() {
        return horizontalStrip;
    }

    /** Width left for content, the full box when the bars overlay it. */
    public float viewportWidth(float boxWidth) {
        return Math.max(0, boxWidth - verticalStrip);
    }

    /** Height left for content, the full box when the bars overlay it. */
    public float viewportHeight(float boxHeight) {
        return Math.max(0, boxHeight - horizontalStrip);
    }

    /**
     * Settles the strips for a box and returns the content's size in the viewport
     * they leave. Pass {@code null} for an axis the widget does not scroll.
     *
     * <p><b>Two passes, and it refuses a third.</b> Reserving a strip narrows the
     * content, which can make it taller (wrapped text is the everyday case) and
     * turn an axis that fitted into one that overflows; the second pass sees that.
     * A third is where content whose two axes disagree about which bar they need
     * would oscillate forever, and one bar more than strictly necessary is a better
     * outcome than a layout that never settles.
     *
     * <p>The strips key on <b>overflow</b>, not on whether a bar is currently
     * drawn. Under the fading policies a bar comes and goes constantly, and
     * reserving by visibility would rewrap the content under the pointer every time
     * it did.
     */
    public Size resolve(float boxWidth, float boxHeight,
                        ScrollBar vBar, ScrollBar hBar, Content content) {
        Objects.requireNonNull(content, "content");
        verticalStrip = 0;
        horizontalStrip = 0;
        Size size = content.measure(boxWidth, boxHeight);
        if (layout != Layout.RESERVED) {
            return size;
        }
        for (int pass = 0; pass < 2; pass++) {
            float wantV = stripFor(vBar, size.height(), boxHeight - horizontalStrip);
            float wantH = stripFor(hBar, size.width(), boxWidth - verticalStrip);
            if (wantV == verticalStrip && wantH == horizontalStrip) {
                break;
            }
            verticalStrip = wantV;
            horizontalStrip = wantH;
            size = content.measure(boxWidth - verticalStrip, boxHeight - horizontalStrip);
        }
        return size;
    }

    /**
     * The strip one axis needs. A hidden bar reserves nothing: nothing will ever be
     * drawn there, so holding the room open is pure loss.
     */
    private static float stripFor(ScrollBar bar, float contentLength, float viewport) {
        if (bar == null || bar.policy() == ScrollBar.Policy.HIDDEN) {
            return 0;
        }
        return contentLength > viewport + OVERFLOW_SLOP ? ScrollBar.thickness() : 0;
    }
}
