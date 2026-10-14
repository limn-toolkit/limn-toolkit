package limn.graphics;

import limn.concurrent.Ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The frames of an animation read as it plays: a few are in memory, the one asked for last and
 * the ones after it, and the rest are decoded when their turn comes, on a worker.
 *
 * <p>Not API. A file's frames are built each on the one before, so they are decoded in order: a
 * frame after the reader's place costs the frames between, and one before it costs a start from
 * the first. Playing forward, that is one frame's work a frame, and one start a turn.
 *
 * <p>Frames may also be kept somewhere that outlives this window, the shared loads, where each is
 * an entry of its own: asked there first, so that a turn already decoded is not decoded again for
 * as long as the budget keeps it.
 */
final class StreamedFrames implements Image.Frames {

    /** A file's frames in order. Used by one thread at a time, under this object's lock. */
    interface Source {
        /** @return the next frame, whole, or null if the file has no more */
        Image next();

        /** Goes back to before the first frame. */
        void rewind();

        /** @return the index of the frame {@link #next} hands out next */
        int position();
    }

    /** Somewhere frames are kept beyond this window; may forget any of them at any time. */
    interface Store {
        /** @return the frame if it is still kept, else null */
        Image peek(int index);

        void put(int index, Image frame);
    }

    private final Source source;
    private final Store store;
    private final Image first;
    private final int[] millis;
    private final int loopCount;
    /** How many frames after the one asked for are kept ready. */
    private final int ahead;

    /** The frames in memory here, by index; the first is always held and is not among them. */
    private final Map<Integer, Image> window = new HashMap<>();
    /** The frame asked for last: where the window starts. */
    private int cursor;
    /** Whether a worker is decoding now; at most one does. */
    private boolean working;
    /** Set when the file could not be read past some frame: nothing after it will ever be ready. */
    private boolean broken;

    /**
     * @param source    the file's frames, at its start or anywhere
     * @param store     where frames are kept beyond the window, or null
     * @param first     the first frame, held for as long as the image is
     * @param millis    how long each frame is shown; its length is the frame count
     * @param loopCount how many turns, 0 for no end
     * @param ahead     how many frames after the one asked for are kept ready, at least 1
     */
    StreamedFrames(Source source, Store store, Image first, int[] millis, int loopCount, int ahead) {
        this.source = source;
        this.store = store;
        this.first = first;
        this.millis = millis;
        this.loopCount = loopCount;
        this.ahead = Math.max(1, Math.min(ahead, millis.length - 1));
    }

    @Override
    public int count() {
        return millis.length;
    }

    @Override
    public int millis(int index) {
        return millis[index];
    }

    @Override
    public int loopCount() {
        return loopCount;
    }

    /** The frame, decoded here and now if it is not in memory. */
    @Override
    public Image frame(int index) {
        synchronized (this) {
            Image known = lookup(index);
            if (known != null) {
                return known;
            }
        }
        Image decoded = decode(index);
        if (decoded == null) {
            throw new limn.io.MalformedContentException("animation", "frame " + index + " could not be read");
        }
        return decoded;
    }

    @Override
    public @org.jspecify.annotations.Nullable Image ready(int index) {
        Image known;
        boolean start = false;
        synchronized (this) {
            cursor = index;
            known = lookup(index);
            if (!working && !broken && missing() >= 0) {
                working = true;
                start = true;
            }
        }
        if (start) {
            if (Ui.isInstalled()) {
                Ui.async(() -> {
                    work();
                    return null;
                });
            } else {
                work(); // no worker to hand it to: a test, or a tool with no window
            }
        }
        return known;
    }

    /** Decodes what the window wants and does not have, until it has it all. Off the UI thread. */
    private void work() {
        try {
            while (true) {
                int target;
                synchronized (this) {
                    target = missing();
                    if (target < 0 || broken) {
                        return;
                    }
                }
                // Not under this object's lock: the UI thread asks it which frame is ready at
                // every frame of the display, and must not wait out a decode to be answered.
                if (decode(target) == null) {
                    synchronized (this) {
                        broken = true;
                    }
                    return;
                }
                synchronized (this) {
                    trim();
                }
            }
        } catch (RuntimeException unreadable) {
            synchronized (this) {
                broken = true; // the file is damaged from here on: the frames before it are what there is
            }
        } finally {
            synchronized (this) {
                working = false;
            }
        }
    }

    /** @return the first frame the window wants and has not, in the order it will be shown, or -1 */
    private int missing() {
        for (int step = 0; step <= ahead; step++) {
            int index = (cursor + step) % millis.length;
            if (lookup(index) == null) {
                return index;
            }
        }
        return -1;
    }

    private Image lookup(int index) {
        if (index == 0) {
            return first;
        }
        Image here = window.get(index);
        if (here != null) {
            return here;
        }
        Image kept = store == null ? null : store.peek(index);
        if (kept != null && inWindow(index)) {
            window.put(index, kept);
        }
        return kept;
    }

    /**
     * Reads forward to a frame, from the start if the reader is past it. One thread at a time
     * reads, under the reader's own lock, which is not the lock the window is asked under.
     */
    private Image decode(int index) {
        Image frame = null;
        synchronized (source) {
            if (source.position() > index) {
                source.rewind();
            }
            while (source.position() <= index) {
                int at = source.position();
                frame = source.next();
                if (frame == null) {
                    return null;
                }
                if (store != null && at != 0) {
                    store.put(at, frame); // decoded on the way: kept if there is room, at no further cost
                }
            }
        }
        if (frame == null) {
            // Another reader got there first: what it decoded is in the window or the store.
            synchronized (this) {
                Image known = lookup(index);
                if (known != null) {
                    return known;
                }
            }
            synchronized (source) {
                source.rewind();
            }
            return decode(index);
        }
        if (index != 0) {
            synchronized (this) {
                window.put(index, frame);
            }
        }
        return index == 0 ? first : frame;
    }

    private boolean inWindow(int index) {
        int distance = Math.floorMod(index - cursor, millis.length);
        return distance <= ahead;
    }

    /** Lets go of what is behind the frame asked for and past what is kept ahead of it. */
    private void trim() {
        window.keySet().removeIf(index -> !inWindow(index));
    }
}
