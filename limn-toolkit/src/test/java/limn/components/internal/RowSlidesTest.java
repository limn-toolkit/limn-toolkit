package limn.components.internal;

import limn.scene.Change;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where rows belong while a drag has some of them lifted out and room open for the drop, against
 * rows of plain numbers: the lifted ones are not drawn, the ones below close up by their height,
 * and the ones from the room down give way by half of it.
 */
class RowSlidesTest {

    private final BitSet lifted = new BitSet();
    private int roomFrom = -1;
    private int invalidated;

    private final Rows<Integer> rows = new Rows<>(new Rows.Host<Integer>() {
        @Override
        public int count() {
            return 100;
        }

        @Override
        public Integer mount(int index, int position) {
            return index;
        }

        @Override
        public void unmount(Integer row, boolean heldFocus) {
        }

        @Override
        public float measure(Integer row, int index) {
            return 20;
        }

        @Override
        public void place(Integer row, int index, float y, float height) {
        }

        @Override
        public void shift(Integer row, float dy) {
        }

        @Override
        public boolean holdsFocus(Integer row) {
            return false;
        }

        @Override
        public boolean holdsPopup(Integer row) {
            return false;
        }

        @Override
        public limn.scene.Widget<?> widget() {
            throw new AssertionError("no row is kept for a popup here");
        }

        @Override
        public void rowsFreed() {
        }

        @Override
        public int cursorIndex() {
            return -1;
        }

        @Override
        public float viewportHeight() {
            return 100;
        }

        @Override
        public float seedHeight() {
            return 20;
        }

        @Override
        public void scrolled(Change.Origin origin) {
        }
    });

    private final RowSlides slides = new RowSlides(rows, new RowSlides.Host() {
        @Override
        public void prepare() {
        }

        @Override
        public boolean isLifted(int row) {
            return lifted.get(row);
        }

        @Override
        public int roomFrom() {
            return roomFrom;
        }

        @Override
        public Object keyOf(int row) {
            return row;
        }

        @Override
        public float yAt(int position) {
            return position * 20f;
        }

        @Override
        public Scene scene() {
            return null; // in no scene: nothing slides, everything is where it belongs at once
        }

        @Override
        public void invalidate() {
            invalidated++;
        }
    }, false);

    private float[] first(int count) {
        return java.util.Arrays.copyOf(slides.belongs(), count);
    }

    @Test
    void withNothingLiftedAndNoRoomEveryRowIsWhereTheLayoutPutIt() {
        rows.layout();
        assertArrayEquals(new float[] {0, 0, 0, 0, 0}, first(5));
        assertEquals(0f, slides.room());
    }

    @Test
    void theRowsBelowALiftedOneCloseUpAndTheLiftedOneIsNotDrawn() {
        rows.layout();
        lifted.set(1);
        float[] at = first(5);
        assertEquals(0f, at[0]);
        assertTrue(Float.isNaN(at[1]), "under the pointer, and not in the list");
        assertArrayEquals(new float[] {-20, -20, -20}, new float[] {at[2], at[3], at[4]});
    }

    @Test
    void roomOpensWhereTheDropWouldLandHalfTheHeightOfWhatIsDragged() {
        rows.layout();
        lifted.set(0);
        lifted.set(1);
        roomFrom = 4;
        assertEquals(20f, slides.room(), "half of the two lifted rows' forty");
        float[] at = first(5);
        assertArrayEquals(new float[] {-40, -40, -20}, new float[] {at[2], at[3], at[4]},
                "closed up by forty, and from the room down given back twenty");

        lifted.clear();
        assertEquals(10f, slides.room(), "with nothing of its own realized, half a row of the mean height");
    }

    @Test
    void outsideASceneAChangeTakesItsPlaceAtOnceAndAsksForARepaint() {
        rows.layout();
        slides.rowsAreHere();
        lifted.set(1);
        slides.rowsBelongElsewhere();
        assertFalse(slides.isSliding());
        assertEquals(1, invalidated);
        assertArrayEquals(first(5), java.util.Arrays.copyOf(slides.drawnOffsets(), 5));

        BitSet back = new BitSet();
        back.set(1);
        slides.setReturning(back);
        assertTrue(slides.isReturning(1));
        slides.forget();
        assertFalse(slides.isReturning(1));
        slides.slideFromHere();
        assertFalse(slides.isRemembering(), "in no scene there is nowhere to slide from");
    }
}
