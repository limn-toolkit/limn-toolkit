package limn.scene.event;

import java.nio.file.Path;
import java.util.List;

/**
 * Files dragged from the OS and dropped on the window. Dispatched bubbling from
 * the widget under the pointer (like a mouse event): a drop target handles it in
 * {@code Widget.onFileDrop} and {@link #consume()}s; an unconsumed drop bubbles
 * up to ancestors and is finally ignored. Coordinates are in scene space.
 */
public final class FileDropEvent extends InputEvent {

    private final List<Path> paths;
    private final float x;
    private final float y;

    /** Files dropped at a point, in the receiving widget's coordinates. */
    public FileDropEvent(List<Path> paths, float x, float y) {
        this.paths = List.copyOf(paths);
        this.x = x;
        this.y = y;
    }

    /** The dropped files, in platform order (never empty). */
    public List<Path> paths() {
        return paths;
    }

    /** Drop x, in the receiving widget's coordinates. */
    public float x() {
        return x;
    }

    /** Drop y, in the receiving widget's coordinates. */
    public float y() {
        return y;
    }

    @Override
    public String toString() {
        return "FileDropEvent[" + paths.size() + " file(s) @ " + x + "," + y + "]";
    }
}
