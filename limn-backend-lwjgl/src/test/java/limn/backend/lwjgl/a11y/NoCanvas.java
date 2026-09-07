package limn.backend.lwjgl.a11y;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A canvas that draws nothing: the probe publishes a tree and paints no pixel. */
public final class NoCanvas implements limn.graphics.Canvas {
    private final float width;
    private final float height;

    public NoCanvas() {
        this.width = 800;
        this.height = 600;
    }

    @Override
    public float width() {
        return width;
    }

    @Override
    public float height() {
        return height;
    }

    @Override
    public float contentScale() {
        return 1;
    }

    @Override
    public void clear(Color color) {
    }
private int savedDepth;

@Override public void save() { savedDepth++; }
@Override public void restore() { if (savedDepth > 0) { savedDepth--; } }
@Override public int saveCount() { return savedDepth; }
@Override public void restoreToCount(int count) { savedDepth = Math.max(0, Math.min(savedDepth, count)); }

    @Override
    public void translate(float dx, float dy) {
    }

    @Override
    public void scale(float sx, float sy) {
    }

    @Override
    public void rotate(float angleRadians) {
    }

    @Override
    public void setOpacity(float opacity) {
    }

    @Override
    public float opacity() {
        return 1;
    }

    @Override
    public void clipRect(float x, float y, float w, float h) {
    }

    @Override
    public void clipRoundRect(RoundRect roundRect) {
    }

    @Override
    public void fillRect(float x, float y, float w, float h, Paint paint) {
    }

    @Override
    public void drawRect(float x, float y, float w, float h, float strokeWidth, Paint paint) {
    }

    @Override
    public void fillRoundRect(RoundRect roundRect, Paint paint) {
    }

    @Override
    public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
    }

    @Override
    public void fillCircle(float cx, float cy, float radius, Paint paint) {
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
    }

    @Override
    public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) {
    }

    @Override
    public void drawEllipse(float cx, float cy, float rx, float ry, float strokeWidth, Paint paint) {
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
    }

    @Override
    public void fillPath(Path2D path, Paint paint) {
    }

    @Override
    public void drawPath(Path2D path, float strokeWidth, Paint paint) {
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
    }

    @Override
    public TextMetrics measureText(String text, Font font) {
        return new TextMetrics(0.6f * font.size() * (int) text.codePoints().count(),
                0.8f * font.size(), 0.2f * font.size(), font.size());
    }

    @Override
    public void drawImage(limn.graphics.Image image, float x, float y, float w, float h) {
    }

    @Override
    public void drawImage(limn.graphics.Image image, float x, float y, float w, float h, Color tint) {
    }

    @Override
    public void drawImage(limn.graphics.Image image,
                          float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH) {
    }

    @Override
    public void drawImage(limn.graphics.Image image,
                          float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH, Color tint) {
    }
}
