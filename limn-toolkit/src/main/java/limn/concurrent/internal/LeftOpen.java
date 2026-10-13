package limn.concurrent.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an application opened and has to close, closed for it when the backend ends if it did not.
 *
 * <p>Not API. For the few things that keep a thread or a native resource for as long as they are
 * open, and that nothing else would ever stop: a media player's decoding thread outlives every
 * reference to the player. Each is named in a warning as it is closed here, since an application
 * that leaves one open has left it open all along.
 */
public final class LeftOpen {

    private static final System.Logger LOG = System.getLogger(LeftOpen.class.getName());

    private record Entry(String name, Runnable close, boolean expected) {
    }

    private static final Map<Object, Entry> OPEN = new LinkedHashMap<>();

    private LeftOpen() {
    }

    /**
     * Notes that {@code owner} is open.
     *
     * @param owner what is open; noted once however often it is said
     * @param name  what a warning calls it
     * @param close closes it, on the UI thread
     */
    public static synchronized void opened(Object owner, String name, Runnable close) {
        OPEN.putIfAbsent(owner, new Entry(name, close, false));
    }

    /**
     * Notes that {@code owner} is open and that being open to the end is its ordinary life, a tray
     * icon's: closed with the backend, and with no warning.
     *
     * @param owner what is open
     * @param close closes it, on the UI thread
     */
    public static synchronized void openedToTheEnd(Object owner, Runnable close) {
        OPEN.putIfAbsent(owner, new Entry("", close, true));
    }

    /** Notes that {@code owner} was closed by whoever opened it. */
    public static synchronized void closed(Object owner) {
        OPEN.remove(owner);
    }

    /** @return how many are open now, for a test */
    public static synchronized int count() {
        return OPEN.size();
    }

    /** Closes whatever is still open, on the UI thread of the backend that is ending. */
    public static void closeAll() {
        List<Entry> left;
        synchronized (LeftOpen.class) {
            left = new ArrayList<>(OPEN.values());
            OPEN.clear();
        }
        for (Entry entry : left) {
            if (!entry.expected()) {
                LOG.log(System.Logger.Level.WARNING, entry.name()
                        + " was left open and is closed as the backend ends; close it when it is done with");
            }
            try {
                entry.close().run();
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.WARNING, entry.name() + " could not be closed", failed);
            }
        }
    }
}
