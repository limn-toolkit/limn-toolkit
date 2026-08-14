package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ComboBox} dropdown's row contract, which had no test of its own.
 *
 * <p>The panel is a private nested {@code Widget} that only materializes when the scene has a
 * window, so a headless test cannot click its rows. What it <em>can</em> pin is the pair the
 * size conversion actually threatens: the highlight-to-selection mapping, and the row geometry
 * identity {@code rowAt(rowTop(i)) == i}. Those were four independent copies of
 * {@code POPUP_PADDING + i * ITEM_HEIGHT - scroll} (paint, damage, reveal and the hit-test
 * inverse), and a conversion that re-derives one of them from tokens while leaving another on
 * a literal would route clicks to the wrong row with nothing failing.
 *
 * <p>The identity is asserted against a local mirror of the formula rather than by reaching
 * into the private class. That is weaker than calling the real method, and it is stated here
 * so nobody mistakes it for stronger: it pins the arithmetic, and the production code now has
 * exactly one copy of it for the arithmetic to be about.
 */
class PopupPanelTest extends ComponentTestBase {

    /** Mirrors {@code ComboBox.POPUP_PADDING} and {@code ITEM_HEIGHT}. */
    private static final float PADDING = 6;
    private static final float ITEM_HEIGHT = 30;

    private static float rowTop(int index, float scroll) {
        return PADDING + index * ITEM_HEIGHT - scroll;
    }

    private static int rowAt(float localY, float scroll) {
        return (int) ((localY + scroll - PADDING) / ITEM_HEIGHT);
    }

    private ComboBox combo;
    private Scene scene;

    private void build(int itemCount) {
        String[] items = new String[itemCount];
        for (int i = 0; i < itemCount; i++) {
            items[i] = "Item " + i;
        }
        combo = new ComboBox(List.of(items));
        Column root = new Column();
        root.add(combo);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 400);
        scene.requestFocus(combo);
    }

    // --------------------------------------------------------- row geometry

    @Test
    void everyRowRoundTripsThroughTheOneFormulaAtEveryScrollOffset() {
        // The identity that four copies used to have to agree on by hand.
        for (float scroll : new float[] {0, 7, 30, 45, 120, 133.5f}) {
            for (int i = 0; i < 12; i++) {
                assertEquals(i, rowAt(rowTop(i, scroll), scroll),
                        "row " + i + " at scroll " + scroll);
            }
        }
    }

    @Test
    void aPointJustInsideARowStillBelongsToIt() {
        // The boundary the hit test gets wrong first: a row owns [top, top + ITEM_HEIGHT).
        for (int i = 0; i < 6; i++) {
            float top = rowTop(i, 0);
            assertEquals(i, rowAt(top, 0), "the exact top edge belongs to row " + i);
            assertEquals(i, rowAt(top + ITEM_HEIGHT - 0.01f, 0),
                    "a hair above the next top still belongs to row " + i);
            assertEquals(i + 1, rowAt(top + ITEM_HEIGHT, 0),
                    "the next top belongs to the next row");
        }
    }

    @Test
    void theBottomPaddingIsInertAndTheTopPaddingExtendsTheFirstRow() {
        // Asymmetric, and pinned here because it is pre-existing behaviour rather than intent:
        // truncation makes the top padding hit row 0, while the bottom yields an out-of-range
        // index that the caller rejects.
        assertEquals(0, rowAt(PADDING / 2, 0), "top padding truncates into row 0");
        int items = 4;
        float belowLast = rowTop(items - 1, 0) + ITEM_HEIGHT + 1;
        assertTrue(rowAt(belowLast, 0) >= items, "past the last row the index is out of range");
    }

    // ------------------------------------------------ highlight and commit

    @Test
    void theHighlightStartsAtTheSelectionAndCommitsBackToIt() {
        build(5);
        combo.setSelectedIndex(3);
        combo.open();
        assertEquals(3, combo.highlightedIndex(), "the open row is the selected one");

        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(4, combo.highlightedIndex());

        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(4, combo.selectedIndex(), "Enter commits the highlighted row, exactly");
        assertFalse(combo.isOpen());
    }

    @Test
    void theHighlightClampsAtBothEnds() {
        build(3);
        combo.setSelectedIndex(0);
        combo.open();
        scene.keyEvent(Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, combo.highlightedIndex(), "no wrap past the first row");

        for (int i = 0; i < 6; i++) {
            scene.keyEvent(Keys.DOWN, true, false, 0);
        }
        scene.inputBatchEnded();
        assertEquals(2, combo.highlightedIndex(), "no wrap past the last row");
    }

    @Test
    void escapeLeavesTheSelectionWhereItWas() {
        build(5);
        combo.setSelectedIndex(1);
        combo.open();
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, combo.selectedIndex(), "a cancelled navigation commits nothing");
        assertFalse(combo.isOpen());
    }

    @Test
    void selectionFiresOnceWithTheCommittedIndex() {
        build(4);
        AtomicReference<Integer> fired = new AtomicReference<>();
        AtomicReference<Integer> count = new AtomicReference<>(0);
        combo.onSelect(index -> {
            fired.set(index);
            count.updateAndGet(n -> n + 1);
        });
        combo.setSelectedIndex(0);
        combo.open();
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, fired.get(), "the committed index, not the navigation path");
        assertEquals(1, count.get(), "one commit, one notification");
    }
}
