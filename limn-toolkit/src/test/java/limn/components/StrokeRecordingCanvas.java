package limn.components;

import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every stroke width a widget paints, so a test can assert the {@link Strokes}
 * contract directly: paint the same widget at all five {@link limn.scene.ControlSize} steps
 * and the recorded multiset must be <em>identical</em>. That is the whole pixel-locked rule,
 * checked mechanically rather than by reading the diff.
 *
 * <p><b>Paint at a settled transition state.</b> Four components ({@code TextField},
 * {@code TextArea}, {@code ComboBox}, {@code Spinner}) draw one rounded rect whose width is
 * {@code BORDER + (FOCUS_RING - BORDER) * focus}, so the border thickens continuously as the
 * focus fade runs. Recording mid-fade captures a frame-dependent fractional width and the test
 * is flaky by construction. Drive {@code focus} to exactly 0 or exactly 1 first.
 */
final class StrokeRecordingCanvas extends ComponentTestBase.FakeCanvas {

    /** One recorded stroke: the call that made it and the width it used. */
    record Stroke(String kind, float width) {
    }

    private final List<Stroke> strokes = new ArrayList<>();

    StrokeRecordingCanvas(float width, float height) {
        super(width, height);
    }

    List<Stroke> strokes() {
        return strokes;
    }

    /** @return every recorded width, sorted: the order-insensitive form for comparing steps */
    List<Float> widths() {
        return strokes.stream().map(Stroke::width).sorted().toList();
    }

    void reset() {
        strokes.clear();
    }

    @Override
    public void drawRect(float x, float y, float w, float h, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("rect", strokeWidth));
    }

    @Override
    public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("roundRect", strokeWidth));
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("circle", strokeWidth));
    }

    @Override
    public void drawEllipse(float cx, float cy, float rx, float ry, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("ellipse", strokeWidth));
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("line", strokeWidth));
    }

    @Override
    public void drawPath(Path2D path, float strokeWidth, Paint paint) {
        strokes.add(new Stroke("path", strokeWidth));
    }
}
