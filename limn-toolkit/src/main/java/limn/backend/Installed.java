package limn.backend;

import java.util.Objects;

/**
 * The one slot a backend fills for a service the toolkit offers statically: the image decoder,
 * the audio engine, the text ruler, the 3D provider, the crash handler, the UI runtime.
 *
 * <p>Nine classes kept this slot by hand and wrote the same four operations on it: install
 * (never null), uninstall <em>if it is still mine</em> so that a late teardown cannot clear a
 * newer one, is-installed, and require with a message saying which backend was not started.
 * This is the slot once. What differs per service is what "nothing installed" is &mdash; null
 * for most, a no-op ruler or a default crash handler for two &mdash; and what the message says.
 *
 * <p>Reads are volatile: a service is installed by the backend's thread and asked for from
 * workers, and the slot is the one thing both touch.
 *
 * @param <T> the service
 */
public final class Installed<T> {

    private final T absent;
    private final String missing;
    private volatile T current;

    /**
     * A slot whose empty state is {@code null}.
     *
     * @param missing what {@link #require()} says when nothing is installed
     */
    public Installed(String missing) {
        this(null, missing);
    }

    /**
     * A slot whose empty state is a value of its own: a no-op ruler, a default handler.
     *
     * @param absent  what the slot holds when nothing is installed; may be null
     * @param missing what {@link #require()} says when nothing is installed
     */
    public Installed(T absent, String missing) {
        this.absent = absent;
        this.missing = Objects.requireNonNull(missing, "missing");
        this.current = absent;
    }

    /**
     * Fills the slot, replacing whatever was there.
     *
     * @param value the service; never null
     */
    public void install(T value) {
        current = Objects.requireNonNull(value, "value");
    }

    /**
     * Empties the slot if it still holds {@code expected}, so that a late teardown cannot clear
     * a newer install.
     *
     * @param expected what the caller installed
     */
    public void uninstall(T expected) {
        if (current == expected) {
            current = absent;
        }
    }

    /** @return whether something other than the empty state is installed */
    public boolean isInstalled() {
        return current != absent;
    }

    /** @return what is installed, or the empty state */
    public T current() {
        return current;
    }

    /**
     * @return what is installed
     * @throws IllegalStateException with the message this slot was given, when nothing is
     */
    public T require() {
        T value = current;
        if (value == absent) {
            throw new IllegalStateException(missing);
        }
        return value;
    }
}
