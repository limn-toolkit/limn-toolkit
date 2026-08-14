package limn.backend.lwjgl;

/**
 * Trivial shelf packer for the glyph atlas: rectangles are placed left to
 * right on the current shelf; when a rectangle doesn't fit horizontally a new
 * shelf opens below. No GL: pure geometry, unit-testable.
 */
final class ShelfPacker {

    private final int width;
    private final int height;
    private int cursorX;
    private int cursorY;
    private int shelfHeight;

    ShelfPacker(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Reserves a {@code w x h} region. Returns the packed position encoded as
     * {@code (y << 16) | x}, or {@code -1} if the atlas page is full, in which
     * case no state is mutated (a failed pack must not waste shelf space that
     * a smaller glyph could still use).
     */
    long pack(int w, int h) {
        if (w > width || h > height) {
            return -1;
        }
        int x = cursorX;
        int y = cursorY;
        int shelf = shelfHeight;
        if (x + w > width) {
            // candidate placement on a new shelf
            y += shelf;
            x = 0;
            shelf = 0;
        }
        if (y + h > height) {
            return -1;
        }
        cursorX = x + w;
        cursorY = y;
        shelfHeight = Math.max(shelf, h);
        return ((long) y << 16) | x;
    }

    static int x(long position) {
        return (int) (position & 0xFFFF);
    }

    static int y(long position) {
        return (int) (position >>> 16);
    }
}
