package limn.backend.lwjgl;

import limn.backend.ImageCursor;
import limn.graphics.Image;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The native cursors made of {@link ImageCursor}s, shared across windows like the standard shapes
 * but bounded: the distinct cursors an application cycles through are few, and past
 * {@link #MAX} the one used longest ago that no window shows is destroyed. Churning more than
 * that many distinct cursors is a caller's bug.
 *
 * <p>An animated cursor is <b>one</b> entry however many frames it has: it keeps a native cursor
 * of every frame shown so far, made as each is first shown, so an animation of more frames than
 * the cache holds is swapped through without one being destroyed and made again at every swap,
 * which is what a cache of one entry per frame did to it.
 *
 * <p>UI thread only, as GLFW's cursors are.
 */
final class ImageCursors {

    /** How many cursors are kept, an animated one counted once. */
    static final int MAX = 16;

    /** What makes and destroys a native cursor; GLFW's, or a test's. */
    interface Natives {
        /**
         * @return the native cursor of {@code image} with that hotspot, never 0
         */
        long create(Image image, int hotspotX, int hotspotY);

        void destroy(long handle);
    }

    private final Natives natives;
    /** Whether a window shows a cursor now, which is never destroyed. */
    private final Predicate<ImageCursor> inUse;
    /** Each cursor's native cursors, by frame; 0 for a frame not shown yet. */
    private final LinkedHashMap<ImageCursor, long[]> cursors =
            new LinkedHashMap<>(MAX, 0.75f, true /* access order */);

    ImageCursors(Natives natives, Predicate<ImageCursor> inUse) {
        this.natives = natives;
        this.inUse = inUse;
    }

    /**
     * @param cursor a cursor
     * @param frame  which of its image's frames, 0 for a still one
     * @return the native cursor of that frame, made if it was not
     */
    long handle(ImageCursor cursor, int frame) {
        Image image = cursor.image();
        java.util.Objects.checkIndex(frame, image.frameCount());
        long[] frames = cursors.get(cursor);
        if (frames == null) {
            evictOne();
            frames = new long[image.frameCount()];
            cursors.put(cursor, frames);
        }
        if (frames[frame] == 0) {
            frames[frame] = natives.create(image.frame(frame), cursor.hotspotX(), cursor.hotspotY());
        }
        return frames[frame];
    }

    /** @return how many cursors are kept, an animated one counted once */
    int size() {
        return cursors.size();
    }

    /**
     * Makes room for one more: destroys the cursor used longest ago that no window shows, since
     * destroying one in use would revert its window to the arrow and strand what it recorded.
     * With every one in use, which is pathological, the cache grows instead.
     */
    private void evictOne() {
        if (cursors.size() < MAX) {
            return;
        }
        Iterator<Map.Entry<ImageCursor, long[]>> entries = cursors.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<ImageCursor, long[]> entry = entries.next();
            if (!inUse.test(entry.getKey())) {
                destroy(entry.getValue());
                entries.remove();
                return;
            }
        }
    }

    /** Destroys every native cursor; the backend's end. */
    void destroyAll() {
        for (long[] frames : cursors.values()) {
            destroy(frames);
        }
        cursors.clear();
    }

    private void destroy(long[] frames) {
        for (long handle : frames) {
            if (handle != 0) {
                natives.destroy(handle);
            }
        }
    }
}
