package limn.backend;

/**
 * A rectangle in native screen coordinates: a {@link Display}'s physical
 * {@link Display#bounds() bounds} or its usable {@link Display#workArea() work
 * area} (the monitor minus OS chrome like the taskbar/dock/menu bar). Popups and
 * fullscreen logic clamp against these so windows never open off-screen.
 *
 * @param x      left edge in screen coordinates
 * @param y      top edge in screen coordinates
 * @param width  width in screen coordinates
 * @param height height in screen coordinates
 */
public record ScreenRect(int x, int y, int width, int height) {

    /** @return the right edge ({@code x + width}). */
    public int right() {
        return x + width;
    }

    /** @return the bottom edge ({@code y + height}). */
    public int bottom() {
        return y + height;
    }

    /** @return whether {@code (px, py)} lies inside this rectangle. */
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    @Override
    public String toString() {
        return width + "×" + height + " @(" + x + "," + y + ")";
    }
}
