package limn.components.internal.text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextRuler;

/**
 * Shapes a line a masked field shows, group by group: the stretches of value between the
 * pattern's literals, and the literals between them, are each shaped alone in a left-to-right
 * paragraph and set side by side, left to right, in the order they are written. Inside a group
 * each script keeps its own direction, so a group of Hebrew letters reads right to left within
 * it; what no group can do is reach across a literal, which the bidirectional algorithm does when
 * the line is shaped whole: a space or a dash between two runs of Arabic-Indic digits resolves
 * right to left (numbers count as right to left for the neutrals between them), and
 * {@code ٤١١١ ١١١١} would come out with its second group on the left.
 *
 * <p>The result is one line in the text's own index space, assembled from the groups' runs and
 * glyphs with their levels, so the caret, a selection, a hit test and the arrows ask it as they
 * ask any line. A group boundary that falls inside a grapheme cluster (a combining mark typed
 * into the position after a literal) moves to the end of that cluster, so the cluster is shaped
 * whole.
 *
 * <p><b>Not API</b>: see the package.
 */
public final class MaskedLine {

    private MaskedLine() {
    }

    /**
     * @param ruler   the ruler to shape with
     * @param text    the line
     * @param font    its font
     * @param literal for each char of {@code text}, whether it belongs to a literal of the pattern
     *                rather than to a group; both chars of a surrogate pair say the same
     * @return the line, left to right, its groups in written order
     */
    public static ShapedText shape(TextRuler ruler, String text, Font font, boolean[] literal) {
        int[] cuts = cuts(text, literal);
        if (cuts.length <= 2) {
            return ruler.shape(text, font, ShapedText.Direction.LTR);
        }
        List<ShapedText> parts = new ArrayList<>(cuts.length - 1);
        int glyphs = 0;
        for (int i = 0; i + 1 < cuts.length; i++) {
            ShapedText part = ruler.shape(text.substring(cuts[i], cuts[i + 1]), font,
                    ShapedText.Direction.LTR);
            parts.add(part);
            glyphs += part.glyphCount();
        }
        ShapedText first = parts.get(0);
        ShapedText.Builder line = ShapedText.builder(text, font, ShapedText.Direction.LTR, glyphs)
                .lineMetrics(first.metrics().ascent(), first.metrics().descent(),
                        first.metrics().lineHeight())
                .epoch(ruler.epoch());
        for (int i = 0; i < parts.size(); i++) {
            copy(parts.get(i), cuts[i], line);
        }
        return line.build();
    }

    /**
     * Where the line is cut: its start, every boundary between a literal and a group, moved to the
     * end of a grapheme cluster it falls inside, and its end.
     */
    private static int[] cuts(String text, boolean[] literal) {
        int[] out = new int[text.length() + 1];
        int n = 0;
        out[n++] = 0;
        if (text.isEmpty()) {
            return new int[]{0};
        }
        BreakIterator clusters = BreakIterator.getCharacterInstance();
        clusters.setText(text);
        for (int i = 1; i < text.length(); i++) {
            if (literal[i] != literal[i - 1] && !Character.isLowSurrogate(text.charAt(i))) {
                int at = clusters.isBoundary(i) ? i : clusters.following(i);
                if (at > out[n - 1] && at < text.length()) {
                    out[n++] = at;
                }
            }
        }
        out[n++] = text.length();
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Copies one group's runs, in logical order with their levels, and their glyphs, in the order
     * the shaper emitted them, into the line, its offsets moved by {@code offset}. A glyph's
     * placement inside its cluster is kept: the builder places a glyph at its cluster's pen plus
     * the advances of the cluster's earlier glyphs plus its offset, so the offset is read back
     * from the group's own line the same way.
     */
    private static void copy(ShapedText part, int offset, ShapedText.Builder line) {
        List<ShapedText.Run> runs = new ArrayList<>(part.runs());
        runs.sort(Comparator.comparingInt(ShapedText.Run::charStart));
        float[] clusterPen = new float[part.caretCount()];
        boolean[] started = new boolean[part.caretCount()];
        for (ShapedText.Run run : runs) {
            line.run(run.faceId(), run.charStart() + offset, run.charEnd() + offset, run.level());
            for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                int cluster = part.glyphCluster(g);
                int j = part.caretOrdinal(cluster);
                if (!started[j]) {
                    started[j] = true;
                    clusterPen[j] = clusterLeft(part, j);
                }
                float advance = part.glyphAdvance(g);
                line.glyph(part.glyphId(g), cluster + offset, advance, part.glyphX(g) - clusterPen[j],
                        part.glyphY(g));
                clusterPen[j] += advance;
            }
        }
    }

    /** The left edge of the {@code j}-th cluster of {@code part}, in logical order. */
    private static float clusterLeft(ShapedText part, int j) {
        List<ShapedText.Span> box = part.selection(part.caretIndex(j), part.caretIndex(j + 1));
        if (box.isEmpty()) {
            // A cluster with no width: both its edges are the same caret position.
            return part.caretX(part.caretIndex(j), ShapedText.Affinity.DOWNSTREAM);
        }
        return box.get(0).x0();
    }
}
