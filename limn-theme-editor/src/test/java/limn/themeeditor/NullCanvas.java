package limn.themeeditor;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;

/**
 * A canvas that draws nothing, so a full frame can run with no GL. Only the save/restore
 * depth is real: the toolkit balances it and complains when it does not, and a preview that
 * clipped without restoring would otherwise leak into whatever painted after it.
 */
class NullCanvas implements Canvas {

    private final float width;
    private final float height;
    private int depth;

    NullCanvas(float width, float height) {
        this.width = width;
        this.height = height;
    }

    @Override public float width() {
        return width;
    }

    @Override public float height() {
        return height;
    }

    @Override public float contentScale() {
        return 1;
    }

    @Override public void clear(Color color) {
    }

    @Override public void save() {
        depth++;
    }

    @Override public void restore() {
        if (depth > 0) {
            depth--;
        }
    }

    @Override public int saveCount() {
        return depth;
    }

    @Override public void restoreToCount(int count) {
        depth = Math.max(0, Math.min(depth, count));
    }

    @Override public void translate(float dx, float dy) {
    }

    @Override public void scale(float sx, float sy) {
    }

    @Override public void rotate(float angleRadians) {
    }

    @Override public void setOpacity(float opacity) {
    }

    @Override public float opacity() {
        return 1;
    }

    @Override public void clipRect(float x, float y, float w, float h) {
    }

    @Override public void clipRoundRect(RoundRect roundRect) {
    }

    @Override public void fillRect(float x, float y, float w, float h, Paint paint) {
    }

    @Override public void drawRect(float x, float y, float w, float h, float stroke, Paint paint) {
    }

    @Override public void fillRoundRect(RoundRect roundRect, Paint paint) {
    }

    @Override public void drawRoundRect(RoundRect roundRect, float stroke, Paint paint) {
    }

    @Override public void fillCircle(float cx, float cy, float radius, Paint paint) {
    }

    @Override public void drawCircle(float cx, float cy, float radius, float stroke, Paint paint) {
    }

    @Override public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) {
    }

    @Override public void drawEllipse(float cx, float cy, float rx, float ry, float stroke,
                                      Paint paint) {
    }

    @Override public void drawLine(float x1, float y1, float x2, float y2, float stroke,
                                   Paint paint) {
    }

    @Override public void fillPath(Path2D path, Paint paint) {
    }

    @Override public void drawPath(Path2D path, float stroke, Paint paint) {
    }

    @Override public void drawText(String text, float x, float y, Font font, Paint paint) {
    }

    @Override public TextMetrics measureText(String text, Font font) {
        return EditorTestBase.RULER.measure(text, font);
    }

    @Override public void drawImage(Image image, float x, float y, float w, float h) {
    }

    @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
    }

    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH) {
    }

    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH, Color tint) {
    }
}
