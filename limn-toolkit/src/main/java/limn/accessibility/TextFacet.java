package limn.accessibility;

import limn.graphics.Rect;
import limn.graphics.ShapedText;

/**
 * A node that holds text a user can read, and usually edit: a text field, a text area, a search
 * field, a password field.
 *
 * <p><b>Every offset here is a UTF-16 code unit.</b> The unit is stated once, in this sentence,
 * because the three platforms disagree &mdash; one counts UTF-16, one counts characters &mdash;
 * and a model that left it implicit produces silently wrong carets in emoji and in the CJK
 * extensions. UTF-16 is chosen because it is what the toolkit's own editing model counts in and
 * what one of the three platforms wants unchanged; the bridge that needs characters converts at
 * that one boundary and nowhere else.
 *
 * <p><b>A caret is not an index.</b> An offset on a direction boundary is two points on the line,
 * so the caret carries its {@linkplain ShapedText.Affinity affinity} as well as its offset. A
 * bridge drops the affinity only where its platform has nowhere to put it.
 *
 * <p><b>A masked field publishes the mask.</b> A password field that is not revealed puts one mask
 * character per caret stop here and never its own text, and its caret and selection offsets are
 * offsets into <em>that</em> string: the secret's own offsets would run past the end of a mask
 * that is shorter by an astral character.
 *
 * @param text           the whole text, already masked where it is masked; never {@code null}
 * @param caretOffset    where the caret sits
 * @param caretAffinity  which side of {@code caretOffset} the caret is on
 * @param selectionStart where the selection begins, equal to {@code selectionEnd} when there is
 *                       none
 * @param selectionEnd   where it ends
 * @param lineCount      how many lines the text has, counting a trailing empty one
 * @param caretRect      the caret's rectangle in the node's own coordinates, or {@code null} when
 *                       the node has no caret to draw
 */
public record TextFacet(String text, int caretOffset, ShapedText.Affinity caretAffinity,
                        int selectionStart, int selectionEnd, int lineCount, Rect caretRect) {

    /**
     * @throws NullPointerException if {@code text} or {@code caretAffinity} is {@code null}
     */
    public TextFacet {
        java.util.Objects.requireNonNull(text, "text");
        java.util.Objects.requireNonNull(caretAffinity, "caretAffinity");
    }

    /**
     * @return whether any text is selected
     */
    public boolean hasSelection() {
        return selectionStart != selectionEnd;
    }
}
