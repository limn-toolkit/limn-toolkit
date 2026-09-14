package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ComponentTestBase.FakeCanvas} that records where a table drew its text and its
 * lines, so a test can assert what a column's placement, a header's chevron or a divider look
 * like by arithmetic rather than by reading pixels: which texts were painted at all (column
 * virtualization), and at what x (alignment and mirroring).
 */
final class TablePaintCanvas extends ComponentTestBase.FakeCanvas {

    /** One recorded {@code drawText}: the text and where its line starts. */
    record Text(String text, float x, float y) {
    }

    /** One recorded {@code drawLine}. */
    record Line(float x1, float y1, float x2, float y2, float width) {
    }

    private final List<Text> texts = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();

    TablePaintCanvas(float width, float height) {
        super(width, height);
    }

    List<Text> texts() {
        return texts;
    }

    List<Line> lines() {
        return lines;
    }

    void reset() {
        texts.clear();
        lines.clear();
    }

    /** @return the one recorded text equal to {@code text}; fails the test otherwise */
    Text text(String text) {
        Text found = null;
        for (Text t : texts) {
            if (t.text().equals(text)) {
                if (found != null) {
                    throw new AssertionError("\"" + text + "\" was drawn twice: " + texts);
                }
                found = t;
            }
        }
        if (found == null) {
            throw new AssertionError("\"" + text + "\" was not drawn; drawn: " + texts);
        }
        return found;
    }

    /** @return whether any recorded text equals {@code text} */
    boolean drew(String text) {
        for (Text t : texts) {
            if (t.text().equals(text)) {
                return true;
            }
        }
        return false;
    }

    /** @return the recorded vertical lines ({@code x1 == x2}), the dividers among them */
    List<Line> verticalLines() {
        List<Line> vertical = new ArrayList<>();
        for (Line line : lines) {
            if (line.x1() == line.x2()) {
                vertical.add(line);
            }
        }
        return vertical;
    }

    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
        texts.add(new Text(text, x, y));
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        lines.add(new Line(x1, y1, x2, y2, strokeWidth));
    }
}
