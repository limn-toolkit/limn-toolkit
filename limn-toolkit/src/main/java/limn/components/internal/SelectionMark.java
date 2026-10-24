package limn.components.internal;

import limn.components.Strokes;
import limn.graphics.Canvas;
import limn.graphics.Color;

/**
 * The bar at a selected row's leading edge, shared by the list, the table and the tree: the left
 * edge reading left to right, the right edge reading right to left.
 */
public final class SelectionMark {

    private SelectionMark() {
    }

    /**
     * Paints the mark of a selected row whose box is {@code left, top, width, height}.
     *
     * @param rtl    whether the row reads right to left
     * @param accent the palette's {@code primary}, or its disabled tone
     */
    public static void paint(Canvas canvas, float left, float top, float width, float height, boolean rtl,
                             Color accent) {
        float x = rtl ? left + width - Strokes.SELECTION_MARK : left;
        canvas.fillRect(x, top, Strokes.SELECTION_MARK, height, accent);
    }
}
