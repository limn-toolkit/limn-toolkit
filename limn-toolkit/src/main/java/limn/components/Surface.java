package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A surface lifted above what is around it: a fill, the shadow its {@link Elevation} has in the
 * theme, and one child inside. For a panel that slides in over content, a card with no edge, a
 * bar that floats; the toolkit's own menus, lists, calendars and dialogs draw theirs themselves.
 *
 * <p>The surface is as large as its child and lays it out over its whole box; put a
 * {@code Padding} inside for a margin. The shadow is painted outside the box, so whatever holds
 * the surface must leave it room: a parent that clips to the surface's own box clips the shadow
 * away.
 */
public final class Surface extends Widget<Surface> {

    private final Widget<?> content;
    private Elevation elevation = Elevation.RAISED;
    private @Nullable Color fill;
    private float cornerRadius = -1;

    /**
     * @param content what the surface holds
     */
    public Surface(Widget<?> content) {
        this.content = Objects.requireNonNull(content, "content");
        add(content);
    }

    /**
     * Sets how far the surface is lifted (default {@link Elevation#RAISED}). UI thread only.
     *
     * @param level the level
     * @return this surface
     */
    public Surface setElevation(Elevation level) {
        Ui.checkUiThread();
        Objects.requireNonNull(level, "level");
        if (elevation != level) {
            invalidate(); // with the reach it had, which may be the larger
            elevation = level;
            invalidate();
        }
        return this;
    }

    /** @return how far the surface is lifted */
    public Elevation elevation() {
        return elevation;
    }

    /**
     * Sets the surface's colour, or null (the default) for the theme's: {@code surface} when
     * raised or flat, {@code surfaceRaised} when it covers. UI thread only.
     *
     * @param color the fill, or null to follow the theme
     * @return this surface
     */
    public Surface setFill(@Nullable Color color) {
        Ui.checkUiThread();
        fill = color;
        invalidate();
        return this;
    }

    /**
     * Sets the corner radius in points, or a negative number (the default) for the theme's
     * medium radius at this widget's size step; zero for a surface that meets an edge of the
     * window. UI thread only.
     *
     * @param radius the radius
     * @return this surface
     */
    public Surface setCornerRadius(float radius) {
        Ui.checkUiThread();
        cornerRadius = radius;
        invalidate();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return content.measure(constraints);
    }

    @Override
    protected void onLayout() {
        content.measure(Constraints.tight(width(), height()));
        content.layoutBox(0, 0, width(), height());
    }

    /**
     * The surface's fill and shadow are background to its child, which is what a reader is
     * told: the surface itself is not in the accessible tree.
     *
     * @return {@code true}, always
     */
    @Override
    protected boolean paintsDecoration() {
        return true;
    }

    @Override
    protected float paintOutset() {
        return Theme.of(this).elevation(elevation).reach();
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        float radius = cornerRadius >= 0 ? cornerRadius : theme.tokensFor(this).radiusMedium();
        RoundRect shape = RoundRect.of(0, 0, width(), height(), radius);
        theme.drawElevation(canvas, shape, elevation);
        Color color = fill != null ? fill
                : elevation.ordinal() >= Elevation.OVERLAY.ordinal() ? theme.surfaceRaised() : theme.surface();
        canvas.fillRoundRect(shape, color);
    }
}
