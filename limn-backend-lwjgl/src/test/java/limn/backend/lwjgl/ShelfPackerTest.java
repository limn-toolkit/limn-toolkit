package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShelfPackerTest {

    @Test
    void packsLeftToRightOnOneShelf() {
        ShelfPacker packer = new ShelfPacker(100, 100);
        long first = packer.pack(30, 10);
        long second = packer.pack(30, 12);
        assertEquals(0, ShelfPacker.x(first));
        assertEquals(0, ShelfPacker.y(first));
        assertEquals(30, ShelfPacker.x(second));
        assertEquals(0, ShelfPacker.y(second));
    }

    @Test
    void opensNewShelfWhenRowOverflows() {
        ShelfPacker packer = new ShelfPacker(100, 100);
        packer.pack(60, 20);
        long wrapped = packer.pack(60, 10);
        assertEquals(0, ShelfPacker.x(wrapped));
        assertEquals(20, ShelfPacker.y(wrapped), "new shelf starts below the tallest item");
    }

    @Test
    void shelfHeightTracksTallestItem() {
        ShelfPacker packer = new ShelfPacker(100, 100);
        packer.pack(40, 8);
        packer.pack(40, 30); // taller item on the same shelf
        long next = packer.pack(40, 5); // wraps
        assertEquals(30, ShelfPacker.y(next));
    }

    @Test
    void returnsMinusOneWhenFullOrTooBig() {
        ShelfPacker packer = new ShelfPacker(50, 30);
        assertEquals(-1, packer.pack(51, 10), "wider than the page");
        assertEquals(-1, packer.pack(10, 31), "taller than the page");
        assertTrue(packer.pack(50, 30) >= 0);
        assertEquals(-1, packer.pack(1, 1), "page exhausted");
    }
}
