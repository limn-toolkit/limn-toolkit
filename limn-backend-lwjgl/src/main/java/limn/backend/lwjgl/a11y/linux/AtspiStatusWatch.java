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
 * application started before the screen reader stayed unreadable for the life of its windows
 * (LINUX-NEW-7). The owner chose the watch over a re-read on activation (decision 29): a thread that
 * waits costs nothing a frame can measure, and a re-read answers only when a window happens to be
 * activated.
 *
 * <p><b>Only the rising edge is acted on</b> (decision 67). Orca only ever sets the flag true (ADR
 * 039 §6's record of 2026-09-15), so a false never means "the reader left"; the application it
 * feeds ignores every false once it has seen a true and stays embedded. This watch still reports
 * both edges, because what it reports is the switch and not a policy, and the one reader of it
 * decides.
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
 * <p><b>The order of the calls is the correctness argument.</b> Both matches are added before the
 * flag is read, so a change between the two arrives as a signal after the read rather than falling
 * between them; a signal that arrives while a reply is awaited is applied before that reply, which is
 * newer.
 *
 * <p><b>The launcher is followed by its name, not by reconnecting.</b> The session connection does
 * not end when at-spi-bus-launcher does, so the watch also matches the bus's own
 * {@code NameOwnerChanged} for {@code org.a11y.Bus} (arg0): a new owner is a new launcher, whose
 * switch is read again; no owner is no accessibility bus, so the switch is off until one appears.
 * A session with no launcher at all answers the read with an error, and the watch then parks on
 * the same connection until the name gets an owner, instead of reconnecting on a timer. Only the
 * session connection itself ending — the session going away — is retried after a back-off. The
 * signal's shape and its routing by arg0 were read on both guests
 * (readings/fedora-dbus-bus-facts.txt, readings/ubuntu-dbus-bus-facts.txt, 2026-09-15,
 * {@code scripts/a11y/linux/read-dbus-bus-facts.py}): {@code NameOwnerChanged} is sent by
 * {@code org.freedesktop.DBus} from {@code /org/freedesktop/DBus} with signature {@code sss} (name,
 * old owner, new owner, an empty string for none), and a match with {@code arg0} receives that
 * name's changes and not the unique names' on dbus-broker 37 and dbus-daemon 1.14.10.
 * Until the linux-A review this class said a restarted launcher was followed because the watch
 * reconnects; it did not, and a session with no launcher reconnected every minute for ever.
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

    /** The bus driver's name, object and interface, which send {@code NameOwnerChanged} (the readings above). */
    static final String DRIVER = "org.freedesktop.DBus";
    static final String DRIVER_PATH = "/org/freedesktop/DBus";

    /** What the bus is asked to route about the switch's owner: its arrivals and departures, only. */
    static final String OWNER_RULE = "type='signal',sender='" + DRIVER + "',path='" + DRIVER_PATH
            + "',interface='" + DRIVER + "',member='NameOwnerChanged',arg0='" + STATUS_NAME + "'";

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
    /** Whether the switch must be read again: a new owner appeared. Watch thread only. */
    private boolean readAgain;

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

    /**
     * The owner a message announces for the switch's name, or null when it is not the bus's
     * announcement of that name's owner.
     *
     * @param m any message this connection received
     * @return the new owner's unique name, the empty string when the name has no owner, or null
     */
    static String newOwnerIn(DBus.Msg m) {
        if (m.type != DBus.SIGNAL || !"NameOwnerChanged".equals(m.member) || !DRIVER.equals(m.iface)
                || !DRIVER_PATH.equals(m.path) || !DRIVER.equals(m.sender) || m.body.length < 3
                || !STATUS_NAME.equals(m.body[0]) || !(m.body[2] instanceof String owner)) {
            return null;
        }
        return owner;
    }

    /** The watch thread's body: connect, read, follow; reconnect after a back-off; until stopped. */
    @Override
    public void run() {
        int failures = 0;
        while (!stopped) {
            boolean worked;
            try {
                worked = watchOnce();
            } catch (IOException | RuntimeException e) {
                worked = false;
                if (!stopped && failures == 0) {
                    System.err.println("[atspi] the accessibility switch cannot be watched: " + e);
                }
            }
            if (stopped) {
                return;
            }
            failures = worked ? 1 : failures + 1;
            long wait = Math.min(LONGEST_RETRY_NANOS, FIRST_RETRY_NANOS << Math.min(failures - 1, 16));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * One session connection's life: subscribe, read, then follow the switch and its owner until the
     * connection ends.
     *
     * @return whether the connection subscribed before it ended, which makes its end a loss to
     *         follow again rather than a failure to back off from
     */
    private boolean watchOnce() throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(address);
        current = connection;
        boolean subscribed = false;
        try {
            if (stopped) {
                return false;
            }
            java.util.function.Consumer<DBus.Msg> meanwhile = m -> consider(connection, m);
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), meanwhile);
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "AddMatch", "s",
                    OWNER_RULE), meanwhile);
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "AddMatch", "s",
                    MATCH_RULE), meanwhile);
            subscribed = true;
            readAgain = true;
            while (!stopped) {
                if (readAgain) {
                    readAgain = false;
                    readTheSwitch(connection, meanwhile);
                } else {
                    consider(connection, connection.readHere());
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (subscribed) {
                return true;  // a connection that did its work and then ended: follow it again
            }
            throw e;
        } finally {
            connection.close();
        }
    }

    /**
     * Reads {@code IsEnabled} from whoever owns {@code org.a11y.Bus} now. An error answer — no
     * launcher on this session, or one that refused — is a switch nobody can have turned on: off,
     * until the name gets an owner, which this connection hears.
     */
    private void readTheSwitch(DBus.Conn connection, java.util.function.Consumer<DBus.Msg> meanwhile)
            throws IOException {
        DBus.Msg got;
        try {
            got = connection.callHere(DBus.Msg.call(STATUS_NAME, STATUS_PATH, DBus.I_PROPS_NAME, "Get",
                    "ss", STATUS_IFACE, IS_ENABLED), meanwhile);
        } catch (DBus.DBusError e) {
            sink.enabled(false);
            return;
        }
        Object value = got.body.length == 0 ? null : got.body[0];
        if (value instanceof DBus.Variant variant) {
            value = variant.value;
        }
        if (value instanceof Boolean on) {
            sink.enabled(on);
        }
    }

    /** A message that is not a reply this thread waits for: the switch's news, or a stray call. */
    private void consider(DBus.Conn connection, DBus.Msg m) {
        Boolean on = enabledIn(m);
        if (on != null) {
            sink.enabled(on);
            return;
        }
        String owner = newOwnerIn(m);
        if (owner != null) {
            if (owner.isEmpty()) {
                // The launcher left, and its accessibility bus with it. Reported because this watch
                // reports the switch and not a policy; recorded nowhere, because decision 67's
                // reader of it acts on the rising edge alone. So nothing here tears an embedded
                // application down: a connection that dies with the bus is noticed by that
                // connection (AtspiApplication#connectionLost), which is the only path that can
                // tell a dead bus from a desktop setting somebody turned off.
                sink.enabled(false);
            } else {
                readAgain = true;  // a launcher arrived: its switch is read, not assumed
            }
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
