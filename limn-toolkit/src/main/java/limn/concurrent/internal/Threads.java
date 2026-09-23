package limn.concurrent.internal;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads this toolkit starts of its own, all of them daemons.
 *
 * <p>A decode loop, an audio stream, an accessibility drain, a D-Bus reader and writer: each is
 * a thread the process must not wait for at exit, since the window it served is gone, and each
 * used to be started by hand as {@code new Thread}, {@code setDaemon(true)}, {@code start()}.
 * This is that sequence once, with the name as the one thing a caller chooses, and the name is
 * not optional: a thread in a dump with no name is a thread nobody can attribute.
 */
public final class Threads {

    private Threads() {
    }

    /**
     * Starts a daemon thread.
     *
     * @param name what a thread dump shows; {@code limn-} prefixed by convention
     * @param body what it runs
     * @return the started thread
     */
    public static Thread daemon(String name, Runnable body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * A factory of daemon threads numbered under one prefix, for an executor.
     *
     * @param prefix what each thread's name begins with; {@code limn-worker} gives
     *               {@code limn-worker-1}, {@code limn-worker-2} and so on
     * @return the factory
     */
    public static ThreadFactory daemonFactory(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
