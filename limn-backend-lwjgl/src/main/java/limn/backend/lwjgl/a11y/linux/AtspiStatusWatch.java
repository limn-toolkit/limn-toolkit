package limn.backend.lwjgl.a11y.linux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Watches the desktop's accessibility switch, {@code org.a11y.Status.IsEnabled}, for the life of the
 * process: one session-bus connection, read by one parked thread.
 *
 * <p><b>Read once was not enough, because the switch moves while applications run.</b> The bridge
 * used to read the flag when a window first asked for accessibility and keep the answer, so an
 * application started before the screen reader stayed unreadable for the life of its windows, and
 * one whose reader quit kept its connection and its walks (LINUX-NEW-7). The owner chose the watch
 * over a re-read on activation (decision 29): a thread that waits costs nothing a frame can measure,
 * and a re-read answers only when a window happens to be activated.
 *
 * <p><b>What the switch sends was read before this was written</b>, on both desktops the lab has
 * (readings/fedora-a11y-status-signal.txt, Fedora KDE 44, at-spi2-core 2.60.6, dbus-broker 37;
 * readings/ubuntu-a11y-status-signal.txt, Ubuntu 24.04, at-spi2-core 2.52.0, dbus-daemon 1.14.10;
 * both 2026-09-15, {@code scripts/a11y/linux/read-a11y-status-signal.sh --flip}): at-spi-bus-launcher,
 * the owner of {@code org.a11y.Bus}, broadcasts {@code org.freedesktop.DBus.Properties.PropertiesChanged}
 * on {@code /org/a11y/bus} with the body {@code ("org.a11y.Status", {"IsEnabled": <b>}, [])} once
 * per change of the value and nothing when it is set to what it already is; a subscriber whose match
 * names the sender {@code org.a11y.Bus} by that well-known name receives it on both buses. The
 * upstream function that sends it is byte-identical in 2.52.0 and 2.60.6
 * (readings/upstream-at-spi-bus-launcher-2.52-2.60.txt).
 *
 * <p><b>The order of the three calls is the correctness argument.</b> The match is added before the
 * flag is read, so a change between the two arrives as a signal after the read rather than falling
 * between them; a signal that arrives while a reply is awaited is applied before that reply, which is
 * newer. A connection that ends is opened again after a back-off and the flag read again, so a
 * restarted bus launcher is followed too.
 */
final class AtspiStatusWatch implements Runnable {

    /** The owner of the switch, its object and its interface (the readings above). */
    static final String STATUS_NAME = "org.a11y.Bus";
    static final String STATUS_PATH = "/org/a11y/bus";
    static final String STATUS_IFACE = "org.a11y.Status";
    static final String IS_ENABLED = "IsEnabled";

    /** What the bus is asked to route to this connection: the switch's own announcement, only. */
    static final String MATCH_RULE = "type='signal',sender='" + STATUS_NAME + "',path='" + STATUS_PATH
            + "',interface='" + DBus.I_PROPS_NAME + "',member='PropertiesChanged',arg0='"
            + STATUS_IFACE + "'";

    /** The name of this process's one parked thread. */
    static final String THREAD_NAME = "limn-a11y-atspi-status";

    /** The first wait before a lost session connection is opened again; doubled per failure. */
    static final long FIRST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long LONGEST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** Where the flag goes; called on the watch thread, must not block. */
    interface Sink {
        void enabled(boolean on);
    }

    /** How the watch waits between two connections; a test replaces it. */
    interface Sleeper {
        Sleeper REAL = TimeUnit.NANOSECONDS::sleep;

        void sleep(long nanos) throws InterruptedException;
    }

    private final String address;
    private final Sink sink;
    private final Sleeper sleeper;
    private volatile DBus.Conn current;
    private volatile boolean stopped;

    AtspiStatusWatch(String address, Sink sink, Sleeper sleeper) {
        this.address = address;
        this.sink = sink;
        this.sleeper = sleeper;
    }

    /**
     * Whether this process can watch the switch at all: a session bus address with a transport this
     * client can open. A process with none — headless, a container, a CI runner — gets no bridge and
     * no thread.
     *
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     */
    static boolean canWatch(String sessionAddress) {
        if (sessionAddress == null) {
            return false;
        }
        try {
            DBus.Conn.unixPathOf(sessionAddress);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The flag a message announces, or null when it is not the switch's announcement of it.
     *
     * @param m any message this connection received
     * @return the new value of {@code IsEnabled}, or {@code null}
     */
    static Boolean enabledIn(DBus.Msg m) {
        if (m.type != DBus.SIGNAL || !"PropertiesChanged".equals(m.member)
                || !DBus.I_PROPS_NAME.equals(m.iface) || !STATUS_PATH.equals(m.path)
                || m.body.length < 2 || !STATUS_IFACE.equals(m.body[0])
                || !(m.body[1] instanceof Map<?, ?> changed)) {
            return null;
        }
        Object value = changed.get(IS_ENABLED);
        if (value instanceof DBus.Variant variant) {
            value = variant.value;
        }
        return value instanceof Boolean on ? on : null;
    }

    /** The watch thread's body: connect, read, follow; reconnect after a back-off; until stopped. */
    @Override
    public void run() {
        int failures = 0;
        while (!stopped) {
            boolean read;
            try {
                read = watchOnce();
            } catch (IOException | RuntimeException e) {
                read = false;
                if (!stopped && failures == 0) {
                    System.err.println("[atspi] the accessibility switch cannot be watched: " + e);
                }
            }
            if (stopped) {
                return;
            }
            failures = read ? 1 : failures + 1;
            long wait = Math.min(LONGEST_RETRY_NANOS, FIRST_RETRY_NANOS << Math.min(failures - 1, 16));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * One session connection's life.
     *
     * @return whether the flag was read on it before it ended
     */
    private boolean watchOnce() throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(address);
        current = connection;
        boolean read = false;
        try {
            if (stopped) {
                return false;
            }
            java.util.function.Consumer<DBus.Msg> meanwhile = m -> consider(connection, m);
            connection.callHere(DBus.Msg.call("org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "Hello", null), meanwhile);
            connection.callHere(DBus.Msg.call("org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "AddMatch", "s", MATCH_RULE), meanwhile);
            DBus.Msg got = connection.callHere(DBus.Msg.call(STATUS_NAME, STATUS_PATH,
                    DBus.I_PROPS_NAME, "Get", "ss", STATUS_IFACE, IS_ENABLED), meanwhile);
            Object value = got.body.length == 0 ? null : got.body[0];
            if (value instanceof DBus.Variant variant) {
                value = variant.value;
            }
            if (value instanceof Boolean on) {
                sink.enabled(on);
                read = true;
            }
            while (!stopped) {
                consider(connection, connection.readHere());
            }
            return read;
        } catch (IOException | RuntimeException e) {
            if (read) {
                return true;  // a connection that did its work and then ended: follow it again
            }
            throw e;
        } finally {
            connection.close();
        }
    }

    /** A message that is not a reply this thread waits for: the switch's news, or a stray call. */
    private void consider(DBus.Conn connection, DBus.Msg m) {
        Boolean on = enabledIn(m);
        if (on != null) {
            sink.enabled(on);
            return;
        }
        if (m.type == DBus.METHOD_CALL && (m.flags & DBus.NO_REPLY_EXPECTED) == 0) {
            try {
                connection.writeHere(DBus.Conn.replyFor(null, connection, m));
            } catch (IOException ignored) {
                // The read that follows reports the dead connection.
            }
        }
    }

    /** Stops the watch and closes its connection, which ends the read it is parked in. */
    void stop() {
        stopped = true;
        DBus.Conn connection = current;
        if (connection != null) {
            connection.close();
        }
    }
}
