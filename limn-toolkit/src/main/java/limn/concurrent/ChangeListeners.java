package limn.concurrent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who wants to hear that a process-wide setting changed: the locale, the fonts, the default
 * control size, the default layout direction.
 *
 * <p>Four classes kept the same copy-on-write list with the same three operations on it and
 * the same rules: a listener is registered once however often it is added, removing one that was
 * never there is nothing, and every listener runs on a change, in registration order, on the
 * thread that made the change. This is the list once.
 */
public final class ChangeListeners {

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Subscribes, once per instance however often it is called.
     *
     * @param listener what to run on a change
     */
    public void add(Runnable listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unsubscribes; nothing happens when it was never registered.
     *
     * @param listener what was subscribed
     */
    public void remove(Runnable listener) {
        listeners.remove(listener);
    }

    /** Runs every listener, in registration order, on the calling thread. */
    public void fire() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
