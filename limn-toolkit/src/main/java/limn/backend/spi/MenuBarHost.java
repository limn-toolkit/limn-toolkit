package limn.backend.spi;

/**
 * What a backend asks and tells of a menu bar the system shows. Every call arrives on the UI
 * thread.
 */
public interface MenuBarHost {

    /**
     * Reads the bar as it is now. Asked whenever the system brings a menu up to date: when one
     * opens, and before an accelerator is matched.
     */
    MenuBarReading read();

    /**
     * What the scene this bar is in hears its window through, or {@code null} once the bar is in
     * no scene. A window shows the bar of the scene bound to it: a bar whose answer is not the
     * window's own input belongs to a scene the window has left, and gives way to another.
     */
    @org.jspecify.annotations.Nullable WindowInput input();

    /** The user chose a row of the last reading. A number of an older reading does nothing. */
    void choose(int id);

    /**
     * Whether the system's bar holds this bar changed: it went to the window under a fullscreen
     * that covers the system's bar, or came back.
     */
    void heldChanged();
}
