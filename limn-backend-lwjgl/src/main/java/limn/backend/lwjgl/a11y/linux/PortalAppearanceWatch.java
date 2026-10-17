package limn.backend.lwjgl.a11y.linux;

import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reads the desktop's appearance, reduced motion and contrast from the XDG desktop portal's
 * {@code org.freedesktop.portal.Settings}, and follows its {@code SettingChanged} signal, for the
 * life of the process: one session-bus connection, read by one parked thread, as
 * {@link AtspiStatusWatch} watches the accessibility switch. It lives beside that watch because it
 * speaks the same D-Bus client, which is this package's.
 *
 * <p><b>What the portal answers was read before this was written</b>, on both desktops the lab
 * has, on 2026-09-29. On Ubuntu 24.04 GNOME (portal 1.18.4), {@code org.freedesktop.appearance}
 * carries {@code color-scheme} (1 dark, 2 light, 0 no preference, which is Ubuntu's default) and
 * {@code contrast}, and no {@code reduced-motion}; GNOME's {@code enable-animations} arrives through
 * the same portal under {@code org.gnome.desktop.interface}, and every change is signalled twice. On
 * Fedora 44 KDE (portal 1.22.1) the appearance namespace also carries {@code reduced-motion}, 1 when
 * Plasma's animation speed is instant. So reduced motion is the portal's key where it exists, and
 * GNOME's switch otherwise.
 *
 * <p>Each answer goes to the sink whole, on this thread; the sink hands it to the UI thread, where
 * a repeat of the same value is ignored.
 */
public final class PortalAppearanceWatch implements Runnable {

    static final String PORTAL = "org.freedesktop.portal.Desktop";
    static final String PORTAL_PATH = "/org/freedesktop/portal/desktop";
    static final String SETTINGS = "org.freedesktop.portal.Settings";
    static final String APPEARANCE = "org.freedesktop.appearance";
    static final String GNOME_INTERFACE = "org.gnome.desktop.interface";

    static final String MATCH_RULE = "type='signal',sender='" + PORTAL + "',path='" + PORTAL_PATH
            + "',interface='" + SETTINGS + "',member='SettingChanged'";

    private static final String DRIVER = "org.freedesktop.DBus";
    private static final String DRIVER_PATH = "/org/freedesktop/DBus";

    /** The name of this process's one parked thread. */
    public static final String THREAD_NAME = "limn-portal-appearance";

    private static final long FIRST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long LONGEST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final String address;
    private final Consumer<SystemPreferences> sink;
    /** What the portal said so far, by namespace and key. Watch thread only. */
    private final Map<String, Map<String, Object>> settings = new HashMap<>();
    private volatile DBus.Conn current;
    private volatile boolean stopped;
    /** The first reading, or the first failure to read, which the backend's constructor waits for. */
    private final java.util.concurrent.CountDownLatch firstReading = new java.util.concurrent.CountDownLatch(1);
    private volatile SystemPreferences first;

    /**
     * @param address the session bus address
     * @param sink    where each reading goes, called on the watch thread; must not block
     */
    public PortalAppearanceWatch(String address, Consumer<SystemPreferences> sink) {
        this.address = address;
        this.sink = sink;
    }

    /**
     * Waits for the portal's first answer, so an application that reads the preferences right after
     * the backend starts reads the desktop's and not {@code NONE}.
     *
     * @param millis how long to wait at most
     * @return the first reading, or {@code null} when none came in time, or the portal could not be
     *         reached
     */
    public SystemPreferences awaitFirst(long millis) {
        try {
            firstReading.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return first;
    }

    private void deliver(SystemPreferences reading) {
        if (first == null) {
            first = reading;
        }
        firstReading.countDown();
        sink.accept(reading);
    }

    /**
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @return whether this process can reach the portal at all
     */
    public static boolean canWatch(String sessionAddress) {
        return AtspiStatusWatch.canWatch(sessionAddress);
    }

    /**
     * The preferences a set of portal settings says, as the readings above found them.
     *
     * @param settings what the portal answered, by namespace and key; values unwrapped from variants
     * @return the preferences; {@link SystemPreferences#NONE} for an empty answer
     */
    static SystemPreferences preferencesOf(Map<String, Map<String, Object>> settings) {
        Map<String, Object> appearance = settings.getOrDefault(APPEARANCE, Map.of());
        Map<String, Object> gnome = settings.getOrDefault(GNOME_INTERFACE, Map.of());
        ColorScheme scheme = switch (number(appearance.get("color-scheme"), 0)) {
            case 1 -> ColorScheme.DARK;
            case 2 -> ColorScheme.LIGHT;
            default -> ColorScheme.NO_PREFERENCE;
        };
        boolean reduceMotion;
        if (appearance.containsKey("reduced-motion")) {
            reduceMotion = number(appearance.get("reduced-motion"), 0) == 1;
        } else {
            reduceMotion = Boolean.FALSE.equals(gnome.get("enable-animations"));
        }
        boolean increaseContrast = number(appearance.get("contrast"), 0) == 1;
        return SystemPreferencesAccess.of(scheme, reduceMotion, increaseContrast, false);
    }

    private static int number(Object value, int otherwise) {
        return value instanceof Number n ? n.intValue() : otherwise;
    }

    private static Object unwrap(Object value) {
        return value instanceof DBus.Variant variant ? variant.value : value;
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
                    System.err.println("[portal] the desktop's appearance cannot be read: " + e);
                }
            }
            firstReading.countDown(); // nobody waits past a failure
            if (stopped) {
                return;
            }
            failures = worked ? 1 : failures + 1;
            long wait = Math.min(LONGEST_RETRY_NANOS, FIRST_RETRY_NANOS << Math.min(failures - 1, 16));
            try {
                TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean watchOnce() throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(address);
        current = connection;
        boolean subscribed = false;
        try {
            if (stopped) {
                return false;
            }
            Consumer<DBus.Msg> meanwhile = m -> consider(connection, m);
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), meanwhile);
            // The match before the read, so a change between the two arrives after the read.
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "AddMatch", "s",
                    MATCH_RULE), meanwhile);
            subscribed = true;
            readAll(connection, meanwhile);
            while (!stopped) {
                consider(connection, connection.readHere());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (subscribed) {
                return true;
            }
            throw e;
        } finally {
            connection.close();
        }
    }

    private void readAll(DBus.Conn connection, Consumer<DBus.Msg> meanwhile) throws IOException {
        DBus.Msg got;
        try {
            got = connection.callHere(DBus.Msg.call(PORTAL, PORTAL_PATH, SETTINGS, "ReadAll", "as",
                    List.of(APPEARANCE, GNOME_INTERFACE)), meanwhile);
        } catch (DBus.DBusError e) {
            settings.clear();       // no portal on this desktop: it says nothing
            deliver(SystemPreferences.NONE);
            return;
        }
        settings.clear();
        if (got.body.length > 0 && got.body[0] instanceof Map<?, ?> namespaces) {
            for (Map.Entry<?, ?> namespace : namespaces.entrySet()) {
                if (namespace.getValue() instanceof Map<?, ?> keys) {
                    Map<String, Object> into = settings.computeIfAbsent(
                            String.valueOf(namespace.getKey()), k -> new HashMap<>());
                    for (Map.Entry<?, ?> key : keys.entrySet()) {
                        into.put(String.valueOf(key.getKey()), unwrap(key.getValue()));
                    }
                }
            }
        }
        deliver(preferencesOf(settings));
    }

    private void consider(DBus.Conn connection, DBus.Msg m) {
        if (m.type == DBus.SIGNAL && "SettingChanged".equals(m.member) && SETTINGS.equals(m.iface)
                && PORTAL_PATH.equals(m.path) && m.body.length >= 3) {
            String namespace = String.valueOf(m.body[0]);
            if (APPEARANCE.equals(namespace) || GNOME_INTERFACE.equals(namespace)) {
                settings.computeIfAbsent(namespace, k -> new HashMap<>())
                        .put(String.valueOf(m.body[1]), unwrap(m.body[2]));
                deliver(preferencesOf(settings));
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
    public void stop() {
        stopped = true;
        DBus.Conn connection = current;
        if (connection != null) {
            connection.close();
        }
    }
}
