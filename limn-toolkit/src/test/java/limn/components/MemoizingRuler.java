package limn.components;

import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;

/**
 * {@link ComponentTestBase#RULER} with the memo the shipped ruler has.
 *
 * <p>Only an allocation case installs it, and the reason is worth stating rather than hiding. The
 * backend's ruler answers {@code measure} out of the memo it keeps for {@code shape}, so the
 * vertical band the caret rectangle is built from costs nothing there; the fake is a lambda that
 * builds a {@link TextMetrics} per call, so under it <em>any</em> widget that measures inside its
 * describe hook allocates, whatever the widget does. Measuring the walk against a ruler that
 * allocates by construction would be measuring the fake. The answers are
 * {@link ComponentTestBase#RULER}'s own, so nothing else about a fixture moves.
 *
 * <p>Its own file because two text widgets need it: a plain field, whose caret rectangle is
 * measured on every blink, and a masked one, which measures again to size the band its dots sit
 * on. A copy in each would be one memo to keep in step with {@code RULER} in two places.
 */
final class MemoizingRuler implements TextRuler {

    private String lastText;
    private Font lastFont;
    private TextMetrics last;

    @Override
    public TextMetrics measure(String text, Font font) {
        if (last == null || !text.equals(lastText) || !font.equals(lastFont)) {
            last = ComponentTestBase.RULER.measure(text, font);
            lastText = text;
            lastFont = font;
        }
        return last;
    }
}
