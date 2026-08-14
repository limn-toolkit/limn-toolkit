package limn.scene;

/**
 * SPI for widgets that can scroll their content programmatically: scroll
 * containers ({@code ScrollView}), virtualized lists ({@code ListView}),
 * overflow strips, popup panels. The one required operation is
 * {@link #revealRect}: make a rectangle of content visible, scrolling as little
 * as possible (a no-op when it already is). Revealing a specific point is the
 * degenerate {@code revealRect(x, y, 0, 0)}.
 *
 * <p>This is what {@link Widget#revealInView()} talks to: it walks the ancestor
 * chain and asks every {@code Scrollable} on the way to bring the widget into
 * view, which the {@link Scene} triggers automatically whenever keyboard focus
 * moves, so Tab/Shift+Tab never land on an off-screen widget.
 */
public interface Scrollable {

    /**
     * Scrolls the minimum amount so the rectangle becomes visible. The rectangle
     * is given in this widget's <b>local (viewport) coordinates</b>, i.e. the same
     * space a child's {@code x()}/{@code y()} resolve to. Rectangles larger than
     * the viewport align their near edge. Implementations clamp to their content
     * bounds; already-visible rects are a no-op. UI thread only.
     */
    void revealRect(float x, float y, float width, float height);
}
