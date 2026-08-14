package limn.components.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
