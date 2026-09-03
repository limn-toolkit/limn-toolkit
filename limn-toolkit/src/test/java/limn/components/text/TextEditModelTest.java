package limn.components.text;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.ShapedText.Affinity;
import limn.graphics.ShapedText.Direction;
import limn.graphics.ShapedText.Position;
import limn.graphics.ShapedText.Span;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEditModelTest {

    @Test
    void insertTypeAndDelete() {
        TextEditModel m = new TextEditModel(true);
        m.insert("abc");
        assertEquals("abc", m.text());
        assertEquals(3, m.cursor());
        m.backspace();
        assertEquals("ab", m.text());
        m.setCursor(0, false);
        m.deleteForward();
        assertEquals("b", m.text());
    }

    @Test
    void singleLineStripsNewlines() {
        TextEditModel m = new TextEditModel(true);
        m.insert("a\nb\r\nc");
        assertEquals("a b c", m.text());
    }

    @Test
    void horizontalMovesStepByCodePoints() {
        TextEditModel m = new TextEditModel(true);
        m.insert("a𝔸b"); // 𝔸 = surrogate pair (2 chars)
        assertEquals(4, m.cursor());
        m.moveLeft(false);
        assertEquals(3, m.cursor());
        m.moveLeft(false);
        assertEquals(1, m.cursor(), "must jump the whole pair");
        m.moveRight(false);
        assertEquals(3, m.cursor());
        m.backspace();
        assertEquals("ab", m.text(), "backspace removes the whole pair");
    }

    @Test
    void selectionWithShiftMovesAndCollapse() {
        TextEditModel m = new TextEditModel(true);
        m.insert("hello");
        m.setCursor(1, false);
        m.moveRight(true);
        m.moveRight(true);
        assertTrue(m.hasSelection());
        assertEquals("el", m.selectedText());
        // Plain move collapses to the selection edge.
        m.moveLeft(false);
        assertFalse(m.hasSelection());
        assertEquals(1, m.cursor());
    }

    @Test
    void insertReplacesSelection() {
        TextEditModel m = new TextEditModel(true);
        m.insert("hello");
        m.setCursor(1, false);
        m.setCursor(4, true);
        m.insert("EY");
        assertEquals("hEYo", m.text());
        assertFalse(m.hasSelection());
    }

    @Test
    void selectAllAndDeleteSelection() {
        TextEditModel m = new TextEditModel(true);
        m.insert("abc");
        m.selectAll();
        assertEquals("abc", m.selectedText());
        m.backspace();
        assertEquals("", m.text());
    }

    @Test
    void homeEndAreLineAwareOnMultiline() {
        TextEditModel m = new TextEditModel(false);
        m.insert("first\nsecond");
        m.setCursor(9, false); // inside "second"
        m.moveHome(false);
        assertEquals(6, m.cursor(), "start of the second line");
        m.moveEnd(false);
        assertEquals(12, m.cursor());
    }

    @Test
    void upDownKeepStickyGoalColumn() {
        TextEditModel m = new TextEditModel(false);
        m.insert("longest line\nab\nmedium!");
        m.setCursor(8, false); // column 8 on line 0
        m.moveDown(false);
        assertEquals(m.lineStart(m.cursor()) + 2, m.cursor(), "clamped to 'ab' end");
        m.moveDown(false);
        int line2Start = m.lineStart(m.cursor());
        assertEquals(line2Start + 7, m.cursor(), "sticky column 8 clamps to 'medium!' length 7");
        m.moveUp(false);
        m.moveUp(false);
        assertEquals(8, m.cursor(), "back to column 8 on line 0");
    }

    @Test
    void upFromFirstLineGoesToStartDownFromLastToEnd() {
        TextEditModel m = new TextEditModel(false);
        m.insert("ab\ncd");
        m.setCursor(1, false);
        m.moveUp(false);
        assertEquals(0, m.cursor());
        m.setCursor(4, false);
        m.moveDown(false);
        assertEquals(5, m.cursor());
    }

    @Test
    void lineHelpers() {
        TextEditModel m = new TextEditModel(false);
        m.insert("aa\nbbb\nc");
        assertEquals(3, m.lineCount());
        assertEquals("bbb", m.lineText(1));
        assertEquals(1, m.lineOf(4));
        assertEquals(3, m.lineStart(5));
        assertEquals(6, m.lineEnd(4));
    }

    @Test
    void enterInsertsNewlineOnMultiline() {
        TextEditModel m = new TextEditModel(false);
        m.insert("ab");
        m.setCursor(1, false);
        m.insert("\n");
        assertEquals("a\nb", m.text());
        assertEquals(2, m.cursor());
    }

    // -------------------------------------------------------------- graphemes

    @Test
    void combiningMarksMoveAndDeleteAsOneCluster() {
        TextEditModel m = new TextEditModel(true);
        m.insert("xéy"); // e + combining acute = one cluster (2 chars)
        m.setCursor(4, false);
        m.moveLeft(false);
        assertEquals(3, m.cursor());
        m.moveLeft(false);
        assertEquals(1, m.cursor(), "the caret never lands between base and mark");
        m.moveRight(false);
        assertEquals(3, m.cursor());
        m.backspace();
        assertEquals("xy", m.text(), "backspace removes base + mark together");
    }

    @Test
    void zwjEmojiFamilyIsOneCluster() {
        String family = "👨‍👩‍👧"; // 👨‍👩‍👧 (8 chars)
        TextEditModel m = new TextEditModel(true);
        m.insert("a" + family + "b");
        m.setCursor(m.length(), false);
        m.moveLeft(false); // before b
        m.moveLeft(false);
        assertEquals(1, m.cursor(), "the whole ZWJ sequence is one caret step");
        m.deleteForward();
        assertEquals("ab", m.text(), "delete-forward removes the whole family");

        m.setText("a" + family + "b");
        m.setCursor(1 + family.length(), false);
        m.backspace();
        assertEquals("ab", m.text(), "backspace removes the whole family");
    }

    @Test
    void alignToGraphemeSnapsIntoClusterInteriors() {
        String family = "👨‍👩‍👧";
        TextEditModel m = new TextEditModel(true);
        m.insert(family);
        assertEquals(0, m.alignToGrapheme(3), "mid-cluster positions snap to the start");
        assertEquals(0, m.alignToGrapheme(0));
        assertEquals(family.length(), m.alignToGrapheme(family.length()));
    }

    @Test
    void verticalMovesNeverLandInsideACluster() {
        TextEditModel m = new TextEditModel(false);
        m.insert("aaaa\nx😀yz"); // line 1: x + emoji (2 chars) + yz
        m.setCursor(2, false); // column 2 on line 0
        m.moveDown(false);
        int col = m.cursor() - m.lineStart(m.cursor());
        assertTrue(col == 1 || col == 3, "column 2 would split the emoji; landed at " + col);
    }

    // -------------------------------------------------------------- line index

    @Test
    void lineIndexStaysCorrectAcrossEdits() {
        TextEditModel m = new TextEditModel(false);
        m.insert("aa\nbbb\nc");
        assertEquals(3, m.lineCount());
        m.setCursor(2, false);
        m.insert("\nZZ"); // "aa\nZZ\nbbb\nc"
        assertEquals(4, m.lineCount());
        assertEquals("ZZ", m.lineText(1));
        assertEquals(3, m.lineStartOfLine(1));
        assertEquals(2, m.lineOf(7));
        m.selectAll();
        m.deleteSelection();
        assertEquals(1, m.lineCount());
        assertEquals("", m.lineText(0));
    }

    @Test
    void textRangeMatchesSubstring() {
        TextEditModel m = new TextEditModel(false);
        m.insert("hello\nworld");
        assertEquals("lo\nwo", m.textRange(3, 8));
    }

    // --------------------------------------------------------------- undo/redo

    @Test
    void undoRedoRestoresTextCursorAndSelection() {
        TextEditModel m = new TextEditModel(true);
        m.insert("hello");
        m.selectAll();
        m.insertCodePoint('x'); // destroy everything with one keystroke…
        assertEquals("x", m.text());
        assertTrue(m.undo(), "…and get it back");
        assertEquals("hello", m.text());
        assertEquals("hello", m.selectedText(), "the selection is restored too");
        assertTrue(m.redo());
        assertEquals("x", m.text());
        assertFalse(m.redo(), "nothing further to redo");
    }

    @Test
    void typingRunsCoalesceIntoOneUndoStep() {
        TextEditModel m = new TextEditModel(true);
        m.insert("base ");
        m.insertCodePoint('a');
        m.insertCodePoint('b');
        m.insertCodePoint('c');
        assertEquals("base abc", m.text());
        assertTrue(m.undo());
        assertEquals("base ", m.text(), "one undo reverts the whole typing run");
    }

    @Test
    void caretJumpSplitsTypingRuns() {
        TextEditModel m = new TextEditModel(true);
        m.insertCodePoint('a');
        m.insertCodePoint('b');
        m.setCursor(0, false); // click elsewhere ends the run
        m.setCursor(2, false);
        m.insertCodePoint('c');
        assertEquals("abc", m.text());
        m.undo();
        assertEquals("ab", m.text(), "the second run undoes separately");
        m.undo();
        assertEquals("", m.text());
    }

    @Test
    void newEditClearsRedoAndSetTextClearsHistory() {
        TextEditModel m = new TextEditModel(true);
        m.insertCodePoint('a');
        m.undo();
        assertTrue(m.canRedo());
        m.insertCodePoint('b');
        assertFalse(m.canRedo(), "a fresh edit invalidates the redo branch");
        m.setText("programmatic");
        assertFalse(m.canUndo(), "setText is a reset, not an edit");
        assertFalse(m.canRedo());
    }

    @Test
    void deleteRunsCoalesceAndEmptyEditsRecordNothing() {
        TextEditModel m = new TextEditModel(true);
        m.setText("abcd");
        m.setCursor(4, false);
        m.backspace();
        m.backspace();
        m.backspace();
        assertEquals("a", m.text());
        assertTrue(m.undo());
        assertEquals("abcd", m.text(), "one undo reverts the whole backspace run");
        assertFalse(m.undo(), "nothing else was recorded");

        m.setCursor(4, false);
        m.deleteForward(); // at the end: no-op
        m.insert("");      // empty paste: no-op
        assertFalse(m.canUndo(), "no-ops must not pollute the history");
    }

    @Test
    void wordMovementJumpsBetweenWords() {
        TextEditModel m = new TextEditModel(false);
        m.insert("foo bar  baz"); // note the double space
        m.setCursor(0, false);
        m.moveWordRight(false);
        assertEquals(3, m.cursor(), "end of foo");
        m.moveWordRight(false);
        assertEquals(7, m.cursor(), "end of bar");
        m.moveWordRight(false);
        assertEquals(12, m.cursor(), "end of baz");
        m.moveWordLeft(false);
        assertEquals(9, m.cursor(), "start of baz (past both spaces)");
        m.moveWordLeft(false);
        assertEquals(4, m.cursor(), "start of bar");
        m.moveWordLeft(false);
        assertEquals(0, m.cursor());
    }

    @Test
    void wordMovementStopsAtPunctuation() {
        TextEditModel m = new TextEditModel(true);
        m.insert("foo, bar");
        m.setCursor(0, false);
        m.moveWordRight(false);
        assertEquals(3, m.cursor(), "before the comma");
        m.moveWordRight(false);
        assertEquals(4, m.cursor(), "after the comma");
        m.moveWordRight(false);
        assertEquals(8, m.cursor(), "end of bar");
    }

    @Test
    void wordMovementCrossesGraphemeInternalClassBoundaries() {
        // NFD "café test": the combining acute (not a letter to classOf) puts a
        // char-class boundary INSIDE the e+◌́ cluster; forward motion must snap
        // to the cluster END and keep progressing, never stall.
        TextEditModel m = new TextEditModel(false);
        m.insert("café test");
        m.setCursor(0, false);
        m.moveWordRight(false);
        assertEquals(5, m.cursor(), "past the full cluster, never mid-accent");
        m.moveWordRight(false);
        assertEquals(10, m.cursor(), "end of 'test'");

        // Keycap emoji 1️⃣ = '1' + VS16 + U+20E3: class boundary right after '1'.
        TextEditModel k = new TextEditModel(false);
        k.insert("1️⃣ abc"); // keycap: '1' + VS16 + combining keycap
        k.setCursor(0, false);
        k.moveWordRight(false);
        assertEquals(3, k.cursor(), "whole keycap cluster crossed");
        k.moveWordRight(false);
        assertEquals(7, k.cursor(), "end of 'abc'");
    }

    @Test
    void deleteWordForwardCrossesClustersAndKeepsHistoryHonest() {
        TextEditModel m = new TextEditModel(false);
        m.insert("café x");
        m.setCursor(0, false);
        m.deleteWordForward();
        assertEquals(" x", m.text(), "the whole accented word goes, cluster intact");
        assertTrue(m.undo());
        assertEquals("café x", m.text());
        assertTrue(m.canRedo());
        m.setCursor(m.text().length(), false);
        m.deleteWordForward(); // at the end: no-op
        assertTrue(m.canRedo(), "a no-op must not push a snapshot or wipe redo");
    }

    @Test
    void wordClassificationWorksByCodePoint() {
        // Supplementary-plane letters (𝕏 = U+1D54F, two chars each) are ONE
        // word, and combining marks belong to their base's run in both
        // directions: neither may split a word at a surrogate.
        TextEditModel m = new TextEditModel(false);
        m.insert("𝕏𝕐 cafe\u0301 x"); // NFD
        m.setCursor(0, false);
        m.moveWordRight(false);
        assertEquals(4, m.cursor(), "both supplementary letters are one word");
        m.moveWordRight(false);
        assertEquals(10, m.cursor(), "the accented word ends after its mark");
        m.moveWordLeft(false);
        assertEquals(5, m.cursor(), "backward: mark rejoins its base word");
        m.moveWordLeft(false);
        assertEquals(0, m.cursor());
    }

    @Test
    void boundedGraphemeScanStaysCorrectAcrossTheWindow() {
        // Cluster far past the scan window on one long line: boundaries must
        // match the unbounded answer.
        TextEditModel m = new TextEditModel(false);
        m.insert("a".repeat(500) + "é"); // NFD cluster at 500..502
        assertEquals(500, m.previousGrapheme(502), "steps over the whole trailing cluster");
        assertEquals(500, m.alignToGrapheme(501), "mid-cluster aligns to the cluster start");

        // A regional-indicator run longer than the window: pairing depends on
        // the run's true start, so the window must escape the whole run.
        TextEditModel flags = new TextEditModel(false);
        flags.insert("🇧🇷".repeat(40)); // 40 flags = 80 RIs = 160 chars, clusters of 4
        int inside = 39 * 4 + 2; // middle of the last flag
        assertEquals(39 * 4, flags.alignToGrapheme(inside), "flag pairing survives the window");
        assertEquals(39 * 4, flags.previousGrapheme(40 * 4), "previous cluster is one whole flag");
    }

    @Test
    void wordMovementExtendsSelectionWithShift() {
        TextEditModel m = new TextEditModel(true);
        m.insert("hello world");
        m.setCursor(0, false);
        m.moveWordRight(true);
        assertTrue(m.hasSelection());
        assertEquals("hello", m.selectedText());
    }

    @Test
    void deleteWordBackwardAndForward() {
        TextEditModel m = new TextEditModel(true);
        m.insert("hello world");
        m.deleteWordBackward();
        assertEquals("hello ", m.text());
        m.deleteWordBackward();
        assertEquals("", m.text());

        m.insert("hello world");
        m.setCursor(0, false);
        m.deleteWordForward();
        assertEquals(" world", m.text());
    }

    @Test
    void documentStartAndEnd() {
        TextEditModel m = new TextEditModel(false);
        m.insert("line1\nline2\nline3");
        m.setCursor(7, false); // somewhere in the middle
        m.moveDocumentStart(false);
        assertEquals(0, m.cursor());
        m.moveDocumentEnd(false);
        assertEquals(17, m.cursor(), "end of the whole text");
    }

    /**
     * The stamp views cache against. It has to move on every edit and stay put on everything
     * else, and both halves are load-bearing: a stamp that missed an edit would leave TextArea
     * painting the text as it was before it, and one that moved on a cursor step would rebuild a
     * screenful of Strings on every caret blink: the cost it exists to remove.
     */
    @Test
    void theTextVersionMovesOnEditsAndOnlyOnEdits() {
        TextEditModel model = new TextEditModel(false);
        model.setText("hello\nthere");

        long afterSetText = model.textVersion();
        model.setCursor(2, false);
        model.moveRight(false);
        model.moveDown(true);
        model.selectAll();
        assertEquals(afterSetText, model.textVersion(),
                "moving the caret or the selection changes no text");

        model.setCursor(0, false);
        model.insert("x");
        long afterInsert = model.textVersion();
        assertNotEquals(afterSetText, afterInsert, "an insert is an edit");

        model.setCursor(0, false);
        model.deleteForward();
        assertNotEquals(afterInsert, model.textVersion(), "a delete is an edit");
    }

    // ------------------------------------------------------------ bidi fixtures

    private static final float EPS = 1e-4f;

    /** One advance for every cluster in every fixture below: expected geometry stays exact. */
    private static final float ADV = 10f;

    private static final Font FONT = Font.of(16);

    /** Face ids are opaque to {@link ShapedText}; one is all these fixtures need. */
    private static final int FACE = 7;

    /**
     * alef, bet, gimel: three strong right-to-left characters, one {@code char} apiece. Written as
     * escapes and named once, so no source line here mixes directions and reorders in an editor.
     */
    private static final String HEB = "\u05D0\u05D1\u05D2";

    /**
     * The frozen spec's own fixture. Base direction LTR, one face, every cluster 10pt:
     *
     * <pre>
     * text      = "abc" + alef bet gimel                       length 6
     * visual:     a[0,10) b[10,20) c[20,30) | gimel[30,40) bet[40,50) alef[50,60)
     * charIndex:    0        1        2     |     5            4          3
     * </pre>
     *
     * <p>Index 3 is the direction boundary: UPSTREAM it draws at 30, DOWNSTREAM at 60.
     */
    private static ShapedText latinThenHebrew() {
        return ShapedText.builder("abc" + HEB, FONT, Direction.LTR, 6)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 3, 0)
                .glyph(101, 0, ADV, 0, 0)
                .glyph(102, 1, ADV, 0, 0)
                .glyph(103, 2, ADV, 0, 0)
                // A right-to-left run's glyphs go in the order a shaper emits them, which is that
                // run's own left-to-right visual order: gimel, bet, alef.
                .run(FACE, 3, 6, 1)
                .glyph(203, 5, ADV, 0, 0)
                .glyph(202, 4, ADV, 0, 0)
                .glyph(201, 3, ADV, 0, 0)
                .build();
    }

    /**
     * A pure right-to-left paragraph, alef bet gimel, width 30:
     *
     * <pre>
     * visual:     gimel[0,10) bet[10,20) alef[20,30)
     * charIndex:      2           1          0
     * </pre>
     */
    private static ShapedText hebrew() {
        ShapedText.Builder b = ShapedText.builder(HEB, FONT, Direction.RTL, HEB.length())
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, HEB.length(), 1);
        for (int i = HEB.length() - 1; i >= 0; i--) {
            b.glyph(200 + i, i, ADV, 0, 0);
        }
        return b.build();
    }

    /**
     * A right-to-left paragraph whose <em>first</em> characters are left-to-right: "cd" at level 2
     * inside alef bet at level 1, width 40.
     *
     * <pre>
     * visual:     bet[0,10) alef[10,20) c[20,30) d[30,40)
     * charIndex:      3         2          0        1
     * </pre>
     *
     * <p>This is the discriminating fixture for the edge jumps: at index 0 the paragraph's start
     * edge is 40 and the first cluster's leading edge is 20, so Home has one right answer and one
     * wrong one. On a line with no embedded run they coincide and the assertion is vacuous.
     */
    private static ShapedText ltrFirstInRtlParagraph() {
        return ShapedText.builder("cd" + HEB.substring(0, 2), FONT, Direction.RTL, 4)
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, 2, 2)
                .glyph(103, 0, ADV, 0, 0)
                .glyph(104, 1, ADV, 0, 0)
                .run(FACE, 2, 4, 1)
                .glyph(201, 3, ADV, 0, 0)
                .glyph(200, 2, ADV, 0, 0)
                .build();
    }

    /** One left-to-right run, one cluster per char: the fast path, for the multi-line cases. */
    private static ShapedText latin(String text) {
        ShapedText.Builder b = ShapedText.builder(text, FONT, Direction.LTR, text.length())
                .lineMetrics(8, 2, 12)
                .run(FACE, 0, text.length(), 0);
        for (int i = 0; i < text.length(); i++) {
            b.glyph(100 + i, i, ADV, 0, 0);
        }
        return b.build();
    }

    private static TextEditModel modelOf(String text) {
        TextEditModel m = new TextEditModel(true);
        m.setText(text);
        return m;
    }

    // ------------------------------------------------------------- caret side

    /**
     * The whole of §6.2 in one walk. It is the cheap test that stops two dozen assignments
     * drifting apart: the side is written by every mutator, and a mutator that forgets leaves a
     * correct index carrying the previous caret's side, which draws a run away from the truth only
     * on the lines this phase exists for.
     */
    @Test
    void everyMutatorLeavesTheDocumentedSide() {
        record Case(String name, Affinity expected, Consumer<TextEditModel> action) {
        }
        List<Case> cases = List.of(
                new Case("insert", Affinity.UPSTREAM, m -> m.insert("z")),
                new Case("insertCodePoint", Affinity.UPSTREAM, m -> m.insertCodePoint('z')),
                new Case("backspace", Affinity.UPSTREAM, TextEditModel::backspace),
                new Case("deleteForward", Affinity.UPSTREAM, TextEditModel::deleteForward),
                new Case("deleteWordBackward", Affinity.UPSTREAM, TextEditModel::deleteWordBackward),
                new Case("deleteWordForward", Affinity.UPSTREAM, TextEditModel::deleteWordForward),
                new Case("deleteSelection", Affinity.UPSTREAM, m -> {
                    m.selectAll();
                    m.deleteSelection();
                }),
                new Case("setText", Affinity.DOWNSTREAM, m -> m.setText("q")),
                new Case("setCursor", Affinity.DOWNSTREAM, m -> m.setCursor(1, false)),
                new Case("setCaret(UPSTREAM)", Affinity.UPSTREAM,
                        m -> m.setCaret(new Position(1, Affinity.UPSTREAM), false)),
                new Case("setCaret(DOWNSTREAM)", Affinity.DOWNSTREAM,
                        m -> m.setCaret(new Position(1, Affinity.DOWNSTREAM), false)),
                new Case("moveLeft", Affinity.DOWNSTREAM, m -> m.moveLeft(false)),
                new Case("moveRight", Affinity.UPSTREAM, m -> m.moveRight(false)),
                new Case("moveWordLeft", Affinity.DOWNSTREAM, m -> m.moveWordLeft(false)),
                new Case("moveWordRight", Affinity.UPSTREAM, m -> m.moveWordRight(false)),
                new Case("moveHome", Affinity.UPSTREAM, m -> m.moveHome(false)),
                new Case("moveEnd", Affinity.DOWNSTREAM, m -> m.moveEnd(false)),
                new Case("moveDocumentStart", Affinity.UPSTREAM, m -> m.moveDocumentStart(false)),
                new Case("moveDocumentEnd", Affinity.DOWNSTREAM, m -> m.moveDocumentEnd(false)),
                new Case("moveUp", Affinity.DOWNSTREAM, m -> m.moveUp(false)),
                new Case("moveDown", Affinity.DOWNSTREAM, m -> m.moveDown(false)),
                // The two early returns out of the vertical mover, which are the paths a side
                // written after the branch would miss.
                new Case("moveUp at the first line", Affinity.DOWNSTREAM, m -> {
                    m.setCaret(new Position(1, Affinity.UPSTREAM), false);
                    m.moveUp(false);
                }),
                new Case("moveDown at the last line", Affinity.DOWNSTREAM, m -> {
                    m.setCaret(new Position(m.length(), Affinity.UPSTREAM), false);
                    m.moveDown(false);
                }),
                new Case("selectAll", Affinity.DOWNSTREAM, TextEditModel::selectAll));

        // Every case runs from BOTH starting sides, so no row can pass by leaving the side alone.
        for (Case c : cases) {
            for (Affinity start : Affinity.values()) {
                TextEditModel m = new TextEditModel(false);
                m.setText("abc def\nghi jkl");
                m.setCaret(new Position(5, start), false);
                c.action().accept(m);
                String where = c.name() + " from " + start;
                assertEquals(c.expected(), m.caret().affinity(), where);
                assertEquals(m.cursor(), m.caret().charIndex(), where + ": index");
            }
        }
    }

    /**
     * The side is restored with the index or it is not restored at all. Index 3 of the mixed line
     * draws at 30 on one side and 60 on the other, so an undo that dropped the side would put the
     * caret at the far end of the right-to-left run and the next arrow press would jump.
     */
    @Test
    void undoRestoresTheSideAndNotOnlyTheIndex() {
        ShapedText line = latinThenHebrew();
        assertEquals(60f, line.caretX(new Position(3, Affinity.DOWNSTREAM)), EPS,
                "the wrong side of index 3 is a whole run away, which is what makes this a test");

        TextEditModel m = modelOf("abc" + HEB);
        m.setCaret(new Position(3, Affinity.UPSTREAM), false);
        assertEquals(30f, line.caretX(m.caret()), EPS);

        m.insertCodePoint('x');
        assertTrue(m.undo());
        assertEquals(new Position(3, Affinity.UPSTREAM), m.caret());
        assertEquals(30f, line.caretX(m.caret()), EPS,
                "60 is the same index on the other side: a whole run away");

        assertTrue(m.redo());
        assertEquals(Affinity.UPSTREAM, m.caret().affinity(), "redo carries a side too");
    }

    /**
     * Two clicks 27 points apart name the same index with different sides. The anchor takes only
     * the index, so which of the two started the drag cannot change what is selected — which is
     * why the anchor is a bare {@code int} and gains nothing.
     */
    @Test
    void anAnchorReachedFromEitherSideOfABoundarySelectsTheSameBoxes() {
        ShapedText line = latinThenHebrew();
        Position upstream = line.hitTest(29);   // trailing half of 'c'
        Position downstream = line.hitTest(56); // leading (right) half of the RTL alef
        assertEquals(new Position(3, Affinity.UPSTREAM), upstream);
        assertEquals(new Position(3, Affinity.DOWNSTREAM), downstream);
        assertEquals(30f, line.caretX(upstream), EPS);
        assertEquals(60f, line.caretX(downstream), EPS);

        Position drag = line.hitTest(12); // leading half of 'b'
        int[] bounds = new int[2];
        List<List<Span>> boxes = new ArrayList<>();
        for (Position press : List.of(upstream, downstream)) {
            TextEditModel m = modelOf("abc" + HEB);
            m.setCaret(press, false);
            m.setCaret(drag, true);
            if (press == upstream) {
                bounds[0] = m.selectionStart();
                bounds[1] = m.selectionEnd();
            } else {
                assertEquals(bounds[0], m.selectionStart());
                assertEquals(bounds[1], m.selectionEnd());
            }
            boxes.add(line.selection(m.selectionStart(), m.selectionEnd()));
        }
        assertEquals(1, bounds[0]);
        assertEquals(3, bounds[1]);
        assertEquals(boxes.get(0), boxes.get(1), "the anchor contributed only its index");
    }

    // ------------------------------------------------- logical edges, visual arrows

    /**
     * Home is <b>logical</b>, so in a right-to-left paragraph it goes to the visual RIGHT. That is
     * what Windows edit controls, GTK's {@code DISPLAY_LINE_ENDS} movement and Cocoa's
     * {@code moveToBeginningOfLine:} all do; no platform makes Home visual, and Shift+Home would
     * otherwise have to select a range that is not contiguous in the string.
     */
    @Test
    void homeGoesToTheVisualRightOfARightToLeftLine() {
        ShapedText line = hebrew();
        TextEditModel m = modelOf(HEB);
        m.moveHome(false);
        assertEquals(0, m.cursor());
        assertEquals(30f, line.caretX(m.caret()), EPS, "the logical start is the right edge");
        m.moveEnd(false);
        assertEquals(HEB.length(), m.cursor());
        assertEquals(0f, line.caretX(m.caret()), EPS, "the logical end is the left edge");
    }

    /**
     * The side of an edge jump names the PARAGRAPH's edge, not the leading edge of whichever
     * cluster happens to sit there. On this line those are 20 points apart, and applying the
     * ordinary by-one-unit rule to Home would take the wrong one.
     */
    @Test
    void anEdgeJumpLandsOnTheParagraphEdgeAndNotOnTheFirstClustersEdge() {
        ShapedText line = ltrFirstInRtlParagraph();
        assertEquals(20f, line.caretX(new Position(0, Affinity.DOWNSTREAM)), EPS,
                "the two sides of index 0 are 20 points apart here, or this test proves nothing");

        TextEditModel m = modelOf("cd" + HEB.substring(0, 2));
        m.moveHome(false);
        assertEquals(40f, line.caretX(m.caret()), EPS, "20 is where the 'c' cluster begins");
        m.moveDocumentStart(false);
        assertEquals(40f, line.caretX(m.caret()), EPS, "Ctrl+Home is the same jump");
        m.moveEnd(false);
        assertEquals(0f, line.caretX(m.caret()), EPS);
    }

    /**
     * The whole shape of the arrows/word split: on the same three right-to-left letters, from the
     * same caret, Left and Ctrl+Left move it in opposite directions. That is deliberate — a word
     * has to be a contiguous range of the string so that Ctrl+Backspace deletes what was
     * highlighted — and it is what Windows and GTK do.
     */
    @Test
    void leftArrowIsVisualAndWordLeftIsLogicalAndTheyDisagreeInRtl() {
        ShapedText line = hebrew();
        TextEditModel visual = modelOf(HEB);
        visual.setCursor(1, false);
        assertEquals(20f, line.caretX(visual.caret()), EPS);
        assertTrue(visual.moveVisualLeft(line, 0, false));
        assertEquals(new Position(2, Affinity.UPSTREAM), visual.caret());
        assertEquals(10f, line.caretX(visual.caret()), EPS, "Left went LEFT");

        TextEditModel word = modelOf(HEB);
        word.setCursor(1, false);
        word.moveWordLeft(false);
        assertEquals(0, word.cursor());
        assertEquals(30f, line.caretX(word.caret()), EPS, "Ctrl+Left went RIGHT, to the word start");
    }

    /**
     * An index alone does not say which of its two points the caret is at, so it cannot say where
     * the next press steps from. Here the same key, from the same index, lands 30 points apart.
     */
    @Test
    void theSameIndexOnTwoSidesStepsToTwoDifferentPlaces() {
        ShapedText line = latinThenHebrew();

        TextEditModel up = modelOf("abc" + HEB);
        up.setCaret(new Position(3, Affinity.UPSTREAM), false);
        assertTrue(up.moveVisualLeft(line, 0, false));
        assertEquals(new Position(2, Affinity.DOWNSTREAM), up.caret(), "left out of the Latin run");
        assertEquals(20f, line.caretX(up.caret()), EPS);

        TextEditModel down = modelOf("abc" + HEB);
        down.setCaret(new Position(3, Affinity.DOWNSTREAM), false);
        assertTrue(down.moveVisualLeft(line, 0, false));
        assertEquals(new Position(4, Affinity.UPSTREAM), down.caret(), "left out of the Hebrew run");
        assertEquals(50f, line.caretX(down.caret()), EPS);
    }

    /**
     * {@code false} is how a multi-line caller learns to change line, and the edge it reports is
     * the <em>visual</em> one: on this fixture the right edge of the line is index 3, not the end
     * of the string.
     */
    @Test
    void arrowAtTheLineEdgeReportsFalseSoTheCallerChangesLine() {
        ShapedText line = latinThenHebrew();
        TextEditModel m = modelOf("abc" + HEB);

        m.setCaret(new Position(0, Affinity.UPSTREAM), false);
        assertFalse(m.moveVisualLeft(line, 0, false), "already at the visual left edge");
        assertEquals(0, m.cursor(), "and the caret did not move");

        m.setCaret(line.hitTest(line.metrics().width()), false);
        assertEquals(new Position(3, Affinity.DOWNSTREAM), m.caret(),
                "the visual right edge of this line is the boundary index, not the string's end");
        assertFalse(m.moveVisualRight(line, 0, false));
        assertEquals(3, m.cursor());

        assertTrue(m.moveVisualLeft(line, 0, false), "a step that moves reports true");
    }

    /**
     * Collapsing stays logical even under a visual arrow: a selection can span lines and its two
     * ends can sit in different runs, so "the visually left end" of one has no answer. On a
     * right-to-left line the collapse therefore lands on the visual right, and it still reports
     * {@code true} — the caret moved, and a caller that read {@code false} would collapse the
     * selection and change line in the same keystroke.
     */
    @Test
    void collapsingASelectionIsLogicalEvenWhenTheArrowIsVisual() {
        ShapedText line = hebrew();
        TextEditModel m = modelOf(HEB);
        m.setCursor(0, false);
        m.setCursor(2, true);
        assertTrue(m.hasSelection());
        assertTrue(m.moveVisualLeft(line, 0, false));
        assertFalse(m.hasSelection());
        assertEquals(0, m.cursor(), "the LOGICAL start of the selection");
        assertEquals(30f, line.caretX(m.caret()), EPS, "which on this line is the visual right");

        m.setCursor(0, false);
        m.setCursor(2, true);
        assertTrue(m.moveVisualRight(line, 0, false));
        assertEquals(2, m.cursor(), "the logical end");
        assertEquals(10f, line.caretX(m.caret()), EPS);
    }

    /** With shift held, the visual arrows drag a selection like every other mover. */
    @Test
    void visualArrowsExtendTheSelection() {
        ShapedText line = latinThenHebrew();
        TextEditModel m = modelOf("abc" + HEB);
        m.setCursor(0, false);
        m.moveVisualRight(line, 0, true);
        m.moveVisualRight(line, 0, true);
        assertTrue(m.hasSelection());
        assertEquals("ab", m.selectedText());
    }

    /**
     * {@code lineStart} is what makes one line's shaping speak the buffer's index space, and it is
     * the only translation a multi-line caller does.
     */
    @Test
    void aVisualStepIsRelativeToTheLineStart() {
        ShapedText line = latin("cd");
        TextEditModel m = new TextEditModel(false);
        m.setText("ab\ncd");
        m.moveDocumentEnd(false);
        assertTrue(m.moveVisualLeft(line, 3, false));
        assertEquals(4, m.cursor());
        assertTrue(m.moveVisualLeft(line, 3, false));
        assertEquals(3, m.cursor(), "the start of line 1, not of the buffer");
        assertFalse(m.moveVisualLeft(line, 3, false), "now the caller changes line");
        assertEquals(3, m.cursor());
    }

    /** A caret restored from a stale view has to produce a position, not an exception. */
    @Test
    void aCursorOutsideTheLineIsClampedIntoIt() {
        ShapedText line = latin("cd");
        TextEditModel m = new TextEditModel(false);
        m.setText("ab\ncd");
        m.setCursor(0, false); // line 0, while the caller hands line 1's shaping
        m.moveVisualRight(line, 3, false);
        assertEquals(4, m.cursor(), "clamped to the line's start, then stepped right");

        assertThrows(NullPointerException.class, () -> m.moveVisualLeft(null, 0, false));
        assertThrows(NullPointerException.class, () -> m.setCaret(null, false));
    }

    /** {@link TextEditModel#setCaret} clamps its index exactly as {@code setCursor} does. */
    @Test
    void setCaretClampsIntoTheBuffer() {
        TextEditModel m = modelOf("abc");
        m.setCaret(new Position(99, Affinity.UPSTREAM), false);
        assertEquals(3, m.cursor());
        m.setCaret(new Position(-4, Affinity.DOWNSTREAM), false);
        assertEquals(0, m.cursor());
    }

    // ---------------------------------------------------------- line damage

    /** A model with damage already acknowledged, so each test sees only its own edit. */
    private static TextEditModel settled(String text) {
        TextEditModel m = new TextEditModel(false);
        m.setText(text);
        m.clearLineDamage();
        return m;
    }

    @Test
    void lineDamageIsNullUntilAnEditAndAfterTheClear() {
        TextEditModel m = settled("aaa\nbbb");
        assertNull(m.lineDamage());
        m.moveRight(false);
        m.selectAll();
        assertNull(m.lineDamage(), "movement and selection are not edits");
        m.insert("x");
        assertNotNull(m.lineDamage());
        m.clearLineDamage();
        assertNull(m.lineDamage());
    }

    @Test
    void typingDamagesExactlyTheEditedLine() {
        TextEditModel m = settled("aaa\nbbb\nccc");
        m.setCursor(5, false);
        m.clearLineDamage();
        m.insertCodePoint('x');
        assertEquals(new TextEditModel.LineDamage(1, 1, 1), m.lineDamage());
    }

    @Test
    void enterSplitsOneOldLineIntoTwoNewOnes() {
        TextEditModel m = settled("aaa\nbbb");
        m.setCursor(1, false);
        m.clearLineDamage();
        m.insert("\n");
        assertEquals(new TextEditModel.LineDamage(0, 0, 1), m.lineDamage());
    }

    @Test
    void backspaceAcrossTheNewlineJoinsTwoOldLinesIntoOne() {
        TextEditModel m = settled("aaa\nbbb");
        m.setCursor(4, false);
        m.clearLineDamage();
        m.backspace();
        assertEquals("aaabbb", m.text());
        assertEquals(new TextEditModel.LineDamage(0, 1, 0), m.lineDamage());
    }

    @Test
    void replacingASelectionSpanningLinesIsOneSplice() {
        TextEditModel m = settled("aaa\nbbb\nccc");
        m.setCursor(2, false);
        m.setCursor(9, true);
        m.clearLineDamage();
        m.insert("X");
        assertEquals("aaXcc", m.text());
        // ONE splice, not a delete composed with an insert: composing widens to the whole
        // document, and typing over a selection is far too common to pay that.
        assertEquals(new TextEditModel.LineDamage(0, 2, 0), m.lineDamage());
    }

    @Test
    void aSecondEditBeforeTheClearWidensToTheWholeDocument() {
        TextEditModel m = settled("aaa\nbbb");
        m.setCursor(0, false);
        m.clearLineDamage();
        m.insertCodePoint('x');
        m.insertCodePoint('y');
        // (0, oldCount-1, newCount-1): an empty kept prefix and suffix, so the consumer
        // re-derives everything — conservative, and correct whatever the two edits were.
        assertEquals(new TextEditModel.LineDamage(0, 1, 1), m.lineDamage());
    }

    @Test
    void setTextAndUndoDamageTheWholeDocument() {
        TextEditModel m = settled("aaa\nbbb");
        m.setText("c");
        assertEquals(new TextEditModel.LineDamage(0, 1, 0), m.lineDamage());
        m = settled("aaa\nbbb");
        m.setCursor(0, false);
        m.clearLineDamage();
        m.insertCodePoint('x');
        m.clearLineDamage();
        m.undo();
        assertEquals(new TextEditModel.LineDamage(0, 1, 1), m.lineDamage());
    }

    @Test
    void aRefusedEditRecordsNoDamage() {
        TextEditModel m = settled("abc");
        m.setCursor(3, false);
        m.clearLineDamage();
        m.deleteForward(); // nothing after the cursor
        m.deleteWordForward();
        assertNull(m.lineDamage());
    }

    // ------------------------------------------------- undo as differences

    @Test
    void aDeletingRunCoalescesFromBothEndsIntoOneStep() {
        TextEditModel m = new TextEditModel(true);
        m.setText("abcde");
        m.setCursor(3, false);
        m.backspace();      // c
        m.deleteForward();  // d
        m.backspace();      // b
        assertEquals("ae", m.text());
        // Three clusters, taken from either side of one caret, are one run of deleting: the
        // step grows at whichever end the key removed from, and one undo puts all three back.
        assertTrue(m.undo());
        assertEquals("abcde", m.text());
        assertEquals(3, m.cursor(), "the caret is where the run began");
        assertFalse(m.canUndo());
        assertTrue(m.redo());
        assertEquals("ae", m.text());
    }

    @Test
    void theOldestStepsGoWhenTheRetainedTextOutgrowsTheBudget() {
        // The budget is four mebibytes of removed-plus-inserted text at two bytes a char, so a
        // paste of a million characters is two mebibytes against it.
        String million = "x".repeat(1 << 20);
        TextEditModel m = new TextEditModel(true);
        m.setText("");
        m.insert(million);
        m.insert(million);
        m.insert(million); // six mebibytes retained: the first paste has to go
        assertTrue(m.undo());
        assertTrue(m.undo());
        assertFalse(m.undo(), "the oldest paste was dropped to stay inside the budget");
        assertEquals(million, m.text(), "and what remains undoes to the text it left behind");
    }

    @Test
    void theNewestStepIsKeptEvenWhenItAloneExceedsTheBudget() {
        TextEditModel m = new TextEditModel(true);
        m.setText("start");
        m.insert("x".repeat(3 << 20)); // six mebibytes in one step: over the budget by itself
        assertTrue(m.canUndo(), "an edit nobody can take back is worse than one that costs its size");
        m.insertCodePoint('y');
        // The small step behind it is the newest now, so the big one is the oldest and goes.
        assertTrue(m.undo());
        assertFalse(m.undo());
        assertEquals("start" + "x".repeat(3 << 20), m.text());
    }

    /**
     * What a step restores, as the caller can observe it. The anchor itself is private; a
     * collapsed selection reads the same whether it is stored as {@code -1} or as the cursor.
     */
    private record Observed(String text, int cursor, Affinity affinity, boolean selected,
                            int selectionStart, int selectionEnd) {

        static Observed of(TextEditModel m) {
            return new Observed(m.text(), m.cursor(), m.caret().affinity(), m.hasSelection(),
                    m.selectionStart(), m.selectionEnd());
        }
    }

    /**
     * The implementation the difference-based history replaced, kept as the oracle: a copy of the
     * whole text and caret pushed before every step, runs of typing and of deleting coalescing
     * into the step that began them, two hundred deep. It is the naive model on purpose — it
     * cannot be wrong about what an undo restores, only expensive — and the model under test has
     * to agree with it at every undo and redo of every sequence below.
     */
    private static final class SnapshotHistory {
        private final ArrayDeque<Observed> undo = new ArrayDeque<>();
        private final ArrayDeque<Observed> redo = new ArrayDeque<>();
        private String lastKind = "OTHER";

        void remember(String kind, Observed before) {
            boolean coalesce = !kind.equals("OTHER") && kind.equals(lastKind) && !undo.isEmpty();
            if (!coalesce) {
                undo.push(before);
                while (undo.size() > 200) {
                    undo.removeLast();
                }
            }
            redo.clear();
            lastKind = kind;
        }

        void motion() {
            lastKind = "OTHER";
        }

        void reset() {
            undo.clear();
            redo.clear();
            lastKind = "OTHER";
        }

        Observed undo(Observed now) {
            if (undo.isEmpty()) {
                return null;
            }
            redo.push(now);
            lastKind = "OTHER";
            return undo.pop();
        }

        Observed redo(Observed now) {
            if (redo.isEmpty()) {
                return null;
            }
            undo.push(now);
            lastKind = "OTHER";
            return redo.pop();
        }
    }

    /** Pieces an edit can be made of: clusters of one and two chars, a mark, a newline, a space. */
    private static final String[] PIECES = {
            "a", "b", "c", " ", "\n", "é", "😀", "ع", "ب",
    };

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int pieces = random.nextInt(7);
        for (int i = 0; i < pieces; i++) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    /**
     * One random operation against both the model and the oracle, with the line index checked
     * after it. The recording rule for each edit — whether it makes a step, and of which kind —
     * is the one the model documents, restated here rather than read back from it.
     */
    private static void randomOperation(Random random, TextEditModel m, SnapshotHistory oracle,
                                        boolean multiline, int step) {
        Observed before = Observed.of(m);
        boolean selected = m.hasSelection();
        int op = random.nextInt(20);
        switch (op) {
            case 0, 1, 2 -> {
                String text = randomText(random);
                m.insert(text);
                if (!text.isEmpty() || selected) {
                    oracle.remember("OTHER", before);
                }
            }
            case 3, 4, 5, 6 -> {
                int[] points = {'x', 'y', '\n', 0x1F600, 0x0301, 0x0639};
                m.insertCodePoint(points[random.nextInt(points.length)]);
                oracle.remember(selected ? "OTHER" : "TYPING", before);
            }
            case 7, 8 -> {
                m.backspace();
                if (selected) {
                    oracle.remember("OTHER", before);
                } else if (before.cursor() > 0) {
                    oracle.remember("DELETING", before);
                }
            }
            case 9 -> {
                m.deleteForward();
                if (selected) {
                    oracle.remember("OTHER", before);
                } else if (before.cursor() < before.text().length()) {
                    oracle.remember("DELETING", before);
                }
            }
            case 10 -> {
                m.deleteWordBackward();
                if (selected || before.cursor() > 0) {
                    oracle.remember("OTHER", before);
                }
            }
            case 11 -> {
                m.deleteWordForward();
                if (selected || before.cursor() < before.text().length()) {
                    oracle.remember("OTHER", before);
                }
            }
            case 12 -> {
                m.deleteSelection();
                if (selected) {
                    oracle.remember("OTHER", before);
                }
            }
            case 13, 14 -> {
                m.setCursor(random.nextInt(before.text().length() + 3) - 1, random.nextBoolean());
                oracle.motion();
            }
            case 15 -> {
                boolean select = random.nextBoolean();
                int which = random.nextInt(8);
                switch (which) {
                    case 0 -> m.moveLeft(select);
                    case 1 -> m.moveRight(select);
                    case 2 -> m.moveHome(select);
                    case 3 -> m.moveEnd(select);
                    case 4 -> m.moveWordLeft(select);
                    case 5 -> m.moveWordRight(select);
                    case 6 -> m.moveUp(select);
                    default -> m.moveDown(select);
                }
                // Up and Down on a single-line model are documented no-ops, and a no-op ends
                // no typing run.
                if (which < 6 || multiline) {
                    oracle.motion();
                }
            }
            case 16 -> {
                m.selectAll();
                oracle.motion();
            }
            case 17 -> {
                Observed expected = oracle.undo(before);
                assertEquals(expected != null, m.undo(), "undo availability at step " + step);
                if (expected != null) {
                    assertEquals(expected, Observed.of(m), "undo restored the wrong state at step "
                            + step);
                }
            }
            case 18 -> {
                Observed expected = oracle.redo(before);
                assertEquals(expected != null, m.redo(), "redo availability at step " + step);
                if (expected != null) {
                    assertEquals(expected, Observed.of(m), "redo restored the wrong state at step "
                            + step);
                }
            }
            default -> {
                if (random.nextInt(10) == 0) {
                    m.setText(randomText(random) + randomText(random));
                    oracle.reset();
                } else {
                    m.clearSelection(); // neither an edit nor, by the model's rule, a motion
                }
            }
        }
        assertLineIndexMatchesARecount(m, random, step);
    }

    /** The line index against a from-scratch recount of the text: every start, every probe. */
    private static void assertLineIndexMatchesARecount(TextEditModel m, Random random, int step) {
        String text = m.text();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        assertEquals(starts.size(), m.lineCount(), "line count at step " + step);
        for (int line = 0; line < starts.size(); line++) {
            assertEquals(starts.get(line), m.lineStartOfLine(line),
                    "start of line " + line + " at step " + step);
            int end = line + 1 < starts.size() ? starts.get(line + 1) - 1 : text.length();
            assertEquals(text.substring(starts.get(line), end), m.lineText(line),
                    "text of line " + line + " at step " + step);
        }
        for (int probe = 0; probe < 4; probe++) {
            int index = random.nextInt(text.length() + 1);
            int expected = 0;
            for (int i = 0; i < index; i++) {
                if (text.charAt(i) == '\n') {
                    expected++;
                }
            }
            assertEquals(expected, m.lineOf(index), "lineOf(" + index + ") at step " + step);
        }
    }

    @Test
    void undoAndRedoAgreeWithTheSnapshotModelOverRandomEdits() {
        for (int seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            boolean multiline = seed % 2 == 0;
            TextEditModel m = new TextEditModel(!multiline);
            SnapshotHistory oracle = new SnapshotHistory();
            m.setText(randomText(random));
            for (int step = 0; step < 300; step++) {
                randomOperation(random, m, oracle, multiline, step);
            }
            // Then all the way back and all the way forward, past every step still retained.
            Observed here = Observed.of(m);
            int undone = 0;
            for (Observed expected = oracle.undo(Observed.of(m)); expected != null;
                    expected = oracle.undo(Observed.of(m))) {
                assertTrue(m.undo(), "seed " + seed + ": undo " + undone);
                assertEquals(expected, Observed.of(m), "seed " + seed + ": undo " + undone);
                undone++;
            }
            assertFalse(m.undo(), "seed " + seed + ": nothing left to undo");
            for (int i = 0; i < undone; i++) {
                Observed expected = oracle.redo(Observed.of(m));
                assertNotNull(expected);
                assertTrue(m.redo(), "seed " + seed + ": redo " + i);
                assertEquals(expected, Observed.of(m), "seed " + seed + ": redo " + i);
            }
            assertEquals(here, Observed.of(m), "seed " + seed + ": the round trip lands home");
            // Whatever the random phase had itself undone is still ahead, behind home.
            for (Observed expected = oracle.redo(Observed.of(m)); expected != null;
                    expected = oracle.redo(Observed.of(m))) {
                assertTrue(m.redo(), "seed " + seed);
                assertEquals(expected, Observed.of(m), "seed " + seed);
            }
            assertFalse(m.redo(), "seed " + seed + ": nothing left to redo");
        }
    }

    @Test
    void theLineIndexMatchesARecountAfterEveryRandomEdit() {
        // Multiline only, and newline-heavy: the single-line half of the test above never has a
        // second line, and what this pins is the splice of the index, not the text.
        for (int seed = 1; seed <= 20; seed++) {
            Random random = new Random(1000 + seed);
            TextEditModel m = new TextEditModel(false);
            m.setText("one\ntwo\nthree\n\nfive");
            for (int step = 0; step < 400; step++) {
                switch (random.nextInt(6)) {
                    case 0 -> m.insert("\n".repeat(random.nextInt(4)) + randomText(random));
                    case 1 -> m.insertCodePoint(random.nextBoolean() ? '\n' : 'q');
                    case 2 -> m.backspace();
                    case 3 -> m.deleteForward();
                    case 4 -> m.setCursor(random.nextInt(m.length() + 1), random.nextBoolean());
                    default -> {
                        if (!m.undo()) {
                            m.redo();
                        }
                    }
                }
                assertLineIndexMatchesARecount(m, random, step);
            }
        }
    }
}
