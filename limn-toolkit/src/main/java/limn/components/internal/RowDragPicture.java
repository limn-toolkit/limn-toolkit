package limn.components.internal;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

/**
 * What follows the pointer while a row of a list or a node of a tree is dragged: the row's own
 * widget as it is painted in its place, on a ground of its own since its container's is not under
 * it any more; and, when it stands for several, a second card showing behind it and their count.
 */
public final class RowDragPicture {

    /** How far the card behind shows, under and beside the row in front, when several are dragged. */
    public static final float STACK = 4;

    private RowDragPicture() {
    }

    /**
     * Paints the picture at the canvas's origin.
     *
     * @param canvas a transparent canvas, {@code width} by {@code height}, plus {@link #STACK}
     *               each way when {@code count} is given
     * @param owner  the list or the tree, for its theme and its size step
     * @param cell   the row's widget, or null when it is no longer realized
     * @param width  the row's width
     * @param height the row's height
     * @param count  what to call several rows ("3 items"), or null for one
     * @param ruler  measures the count
     */
    public static void paint(Canvas canvas, Widget<?> owner, @Nullable Widget<?> cell, float width, float height,
            @Nullable String count, TextRuler ruler) {
        Theme theme = Theme.of(owner);
        float radius = theme.tokensFor(owner).radiusMedium();
        if (count != null) {
            canvas.fillRoundRectWithBorder(STACK, STACK, width, height, radius, theme.surface(), 1, theme.outline());
        }
        canvas.fillRoundRectWithBorder(0, 0, width, height, radius, theme.surface(), 1, theme.outline());
        if (cell != null) {
            canvas.save();
            try {
                canvas.clipRect(0, 0, width, height);
                cell.paintWidget(canvas);
            } finally {
                canvas.restore();
            }
        }
        if (count != null) {
            Font font = theme.label();
            TextMetrics m = ruler.measure(count, font);
            float pad = 6;
            float pillW = m.width() + 2 * pad;
            float pillH = m.height() + 2;
            float x = width - pillW - pad;
            float y = (height - pillH) / 2;
            canvas.fillRoundRect(x, y, pillW, pillH, pillH / 2, theme.primary());
            canvas.drawText(count, x + pad, y + 1 + m.ascent(), font, theme.onPrimary());
        }
    }
}
