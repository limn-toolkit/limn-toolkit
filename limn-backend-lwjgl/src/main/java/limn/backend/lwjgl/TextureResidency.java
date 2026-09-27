package limn.backend.lwjgl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Which cached textures stay resident, and which go at a frame boundary: the bookkeeping of
 * {@link ImageTextureCache} without the GL calls that make and delete the textures, so that the
 * rules can be tested without a context. Keyed by identity, as that cache is, each entry carrying
 * the bytes its texture holds and the last frame that drew it. Confined to the thread that renders
 * the window, like everything else in its canvas.
 *
 * <p>Two rules run at every boundary, in this order.
 * <ol>
 *   <li><b>Age.</b> An entry no frame has drawn for {@link #EVICT_AFTER_FRAMES} frames goes,
 *       whatever the budget.</li>
 *   <li><b>Budget.</b> While the resident bytes exceed the budget, the entry drawn least recently
 *       goes, the one uploaded first among entries last drawn in the same frame, but never one the
 *       frame that just ended drew. That makes the budget soft: a frame whose pictures together
 *       exceed it keeps them all, and a single texture larger than the whole budget is kept for as
 *       long as every frame draws it and goes at the first boundary after a frame that did not.
 *       Dropping what the frame just drew would upload it again in the next one, every frame, for
 *       a screen that has not changed; being over the budget for as long as it shows is the
 *       cheaper failure.</li>
 * </ol>
 * Least recently drawn rather than least recently uploaded, because the pictures an application
 * keeps coming back to are the ones worth keeping, whenever they were first made. Nothing is
 * dropped between boundaries: the batch may still hold geometry sampling any texture drawn in the
 * frame, and the boundary is the one place where none does.
 *
 * @param <K> what an entry is cached for, compared by identity
 * @param <V> what the owner keeps per entry, handed back when the entry goes
 */
final class TextureResidency<K, V> {

    /**
     * Rendered frames of disuse after which an entry goes whatever the budget, and the key is
     * released with it (the cache's map is the only backend reference, so an image the application
     * dropped becomes collectable). Counted in frames, not time, on purpose: the loop is
     * event-driven, so an idle window evicts nothing, and drawing an evicted image again just
     * uploads it again.
     */
    static final int EVICT_AFTER_FRAMES = 300;

    private static final class Resident<K, V> {
        final K key;
        final V value;
        final long bytes;
        /** Upload order, which breaks a tie between entries last drawn in the same frame. */
        final long sequence;
        long lastDrawnFrame;

        Resident(K key, V value, long bytes, long sequence, long frame) {
            this.key = key;
            this.value = value;
            this.bytes = bytes;
            this.sequence = sequence;
            this.lastDrawnFrame = frame;
        }
    }

    private static final Comparator<Resident<?, ?>> LEAST_RECENTLY_DRAWN =
            Comparator.<Resident<?, ?>>comparingLong(resident -> resident.lastDrawnFrame)
                    .thenComparingLong(resident -> resident.sequence);

    private final Map<K, Resident<K, V>> residents = new IdentityHashMap<>();
    private long residentBytes;
    private long uploads;
    // A long: an int wraps after some two hundred days of frames at 120 Hz, and while a difference
    // of two counts survives the wrap, the ordering the budget rule sorts by does not.
    private long frame;

    /**
     * The bytes an RGBA8 texture of {@code width} × {@code height} holds with its whole mipmap
     * chain: every level from the full size down to 1 × 1, each half the one before in both
     * directions and never below one pixel, four bytes a pixel. About four thirds of the full size
     * for a picture of any real size, which is what a device allocates for it before its own
     * alignment, and why a count without the chain read a quarter low.
     *
     * @param width  the full size's width, in pixels
     * @param height the full size's height, in pixels
     * @return the bytes, every level included
     */
    static long mipmappedRgba8Bytes(int width, int height) {
        long bytes = 0;
        int w = width;
        int h = height;
        while (true) {
            bytes += (long) w * h * 4;
            if (w == 1 && h == 1) {
                return bytes;
            }
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
        }
    }

    /**
     * What is kept for {@code key}, recording that the current frame drew it.
     *
     * @return the value, or {@code null} when nothing is resident for {@code key}
     */
    V drawn(K key) {
        Resident<K, V> resident = residents.get(key);
        if (resident == null) {
            return null;
        }
        resident.lastDrawnFrame = frame;
        return resident.value;
    }

    /**
     * What is kept for {@code key}, without counting it as drawn.
     *
     * @return the value, or {@code null} when nothing is resident for {@code key}
     */
    V peek(K key) {
        Resident<K, V> resident = residents.get(key);
        return resident == null ? null : resident.value;
    }

    /**
     * Records a texture just uploaded for {@code key}, which is not resident (the cache asks
     * {@link #drawn} first), as drawn by the current frame. Its bytes count against the budget from
     * the next boundary on; nothing is dropped here, whatever the total.
     *
     * @param bytes what the texture holds, as {@link #mipmappedRgba8Bytes} counts it
     */
    void admit(K key, V value, long bytes) {
        residents.put(key, new Resident<>(key, value, bytes, uploads++, frame));
        residentBytes += bytes;
    }

    /**
     * Ends the current frame and starts the next: advances the frame count, then drops what the age
     * rule and then the budget rule say must go, as the class describes.
     *
     * @param budget the most bytes to keep, unless the frame that just ended drew more
     * @return the values of the entries dropped, whose textures the caller deletes; empty when
     *         nothing went
     */
    List<V> beginFrame(long budget) {
        frame++;
        if (residents.isEmpty()) {
            return List.of();
        }
        List<V> evicted = new ArrayList<>();
        List<Resident<K, V>> cold = null;
        for (Iterator<Resident<K, V>> it = residents.values().iterator(); it.hasNext(); ) {
            Resident<K, V> resident = it.next();
            if (frame - resident.lastDrawnFrame > EVICT_AFTER_FRAMES) {
                it.remove();
                residentBytes -= resident.bytes;
                evicted.add(resident.value);
            } else if (resident.lastDrawnFrame < frame - 1) {
                if (cold == null) {
                    cold = new ArrayList<>();
                }
                cold.add(resident);
            }
        }
        if (residentBytes > budget && cold != null) {
            cold.sort(LEAST_RECENTLY_DRAWN);
            for (Resident<K, V> resident : cold) {
                if (residentBytes <= budget) {
                    break;
                }
                residents.remove(resident.key);
                residentBytes -= resident.bytes;
                evicted.add(resident.value);
            }
        }
        return evicted;
    }

    /** @return how many entries are resident */
    int count() {
        return residents.size();
    }

    /** @return the bytes the resident entries hold together */
    long bytes() {
        return residentBytes;
    }

    /**
     * Forgets every entry.
     *
     * @return the values of all of them, whose textures the caller deletes
     */
    List<V> clear() {
        List<V> all = new ArrayList<>(residents.size());
        for (Resident<K, V> resident : residents.values()) {
            all.add(resident.value);
        }
        residents.clear();
        residentBytes = 0;
        return all;
    }
}
