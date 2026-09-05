package limn.a11y.linux;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.io.IOException;

/**
 * Reads a Limn window to a screen reader on Linux, over AT-SPI2.
 *
 * <p>Hand one to a window and its scene publishes into the desktop's accessibility tree; hand it
 * nothing and there is no cost at all, which is the arrangement the seam already has for a backend
 * with no accessibility. An application installs it by returning it from its window's
 * {@code accessibility()}.
 *
 * <p><b>Nothing here is native.</b> The platform accessibility API on this system is not a C API:
 * it is a D-Bus protocol, and this module speaks it over {@code java.nio.channels.SocketChannel}
 * and {@code java.net.UnixDomainSocketAddress}. No JNI, no libffi, no LWJGL, no third-party jar.
 *
 * <p><b>The gate is the desktop's own switch, and it is read before anything is opened.</b>
 * {@code org.a11y.Status.IsEnabled} on the session bus says whether assistive technology is running
 * at all. While it is false this bridge opens no connection to the accessibility bus and starts no
 * thread, so a machine with no screen reader pays one property read for the life of the window. It
 * is never "a client asked us something recently": Orca registers for a focus change and then calls
 * nothing until one fires, so a gate of that shape goes silent exactly when the interface is being
 * used.
 *
 * <p><b>Three threads, and which one may do what is the whole of the concurrency design.</b> The
 * user-interface thread publishes snapshots and enqueues events and blocks on nothing. The reader
 * thread answers every inbound call from every client, computing each answer from the published
 * snapshot, so it never touches a widget and never blocks. The writer thread performs every write.
 * A reply written from the reader thread would park the one thread serving every client the moment
 * a peer stopped draining, which a well-behaved client cannot even detect it is causing.
 */
public final class AtspiBridge implements AccessibilityBridge {

    /** The session-bus object that says whether assistive technology is running. */
    private static final String STATUS_NAME = "org.a11y.Bus";
    private static final String STATUS_PATH = "/org/a11y/bus";
    private static final String STATUS_IFACE = "org.a11y.Status";

    private volatile AccessibleTree published = AccessibleTree.EMPTY;
    private volatile Host host;
    private final boolean enabled;
    private volatile boolean embedded;
    private volatile DBus.Conn connection;
    private final AtspiTree objects;

    private AtspiBridge(boolean enabled, String applicationName) {
        this.enabled = enabled;
        this.objects = new AtspiTree(() -> published, () -> host, applicationName);
    }

    /**
     * Opens a bridge if the desktop says assistive technology is running, and otherwise nothing.
     *
     * <p>The gate is read here, once, before a socket to the accessibility bus is opened or a
     * thread is started: a window on a machine with no screen reader is meant to cost a property
     * read and never a connection. A session bus that cannot be reached at all — a headless
     * process, a container with no D-Bus — is not an error and answers no.
     *
     * @return a bridge, or {@link AccessibilityBridge#NONE} when accessibility is switched off or
     *         the session bus cannot be asked
     */
    public static AccessibilityBridge openIfEnabled(String applicationName) {
        Boolean on = readStatusFlag("IsEnabled");
        if (on == null || !on) {
            return AccessibilityBridge.NONE;
        }
        AtspiBridge bridge = new AtspiBridge(true, applicationName);
        return bridge.connect() ? bridge : AccessibilityBridge.NONE;
    }

    /**
     * Joins the accessibility bus and hands the registry this window's plug.
     *
     * <p>The sequence the spike proved on the guest, and its order is not free: the address of the
     * accessibility bus comes from the session bus, our own name on it comes from {@code Hello},
     * and the handler has to be exported <em>before</em> {@code Embed}, because the registry may
     * call back the moment it has the plug. A failure at any step leaves this window with no
     * accessibility rather than a half-joined connection, which is why it answers a boolean and
     * the caller falls back to {@link AccessibilityBridge#NONE}.
     *
     * @return whether this process is now an AT-SPI2 application
     */
    private boolean connect() {
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (session == null) {
            return false;
        }
        try (DBus.Conn bus = DBus.Conn.open(session)) {
            bus.hello();
            Object[] address = bus.callArgs("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus",
                    "GetAddress", null);
            if (address.length == 0 || !(address[0] instanceof String where)) {
                return false;
            }
            DBus.Conn a11y = DBus.Conn.open(where);
            objects.busName(a11y.hello());
            // Before Embed, and on every path rather than one: the registry and the reader walk
            // from the root by introspection, and a path with no handler answers UnknownMethod,
            // which a client reads as a broken application rather than as an absent node.
            a11y.exportFallback(objects::handle);
            Object[] socket = a11y.callArgs(Atspi.REGISTRY, Atspi.PATH_ROOT, Atspi.I_SOCKET,
                    "Embed", "(so)", (Object) objects.rootRef().toStruct());
            if (socket.length > 0) {
                objects.desktop(DBus.Ref.of(socket[0]));
            }
            this.connection = a11y;
            this.embedded = true;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Reads one boolean property of {@code org.a11y.Status} off the session bus.
     *
     * @param name the property
     * @return its value, or {@code null} when the bus or the property cannot be reached
     */
    private static Boolean readStatusFlag(String name) {
        String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (address == null) {
            return null;
        }
        try (DBus.Conn session = DBus.Conn.open(address)) {
            // Hello first, always: the bus routes nothing for a connection that has not asked for
            // its name, so every later call would sit unanswered until the timeout.
            session.hello();
            Object[] out = session.callArgs(STATUS_NAME, STATUS_PATH, DBus.I_PROPS_NAME, "Get",
                    "ss", STATUS_IFACE, name);
            Object value = out.length == 0 ? null : out[0];
            if (value instanceof DBus.Variant variant) {
                value = variant.value;
            }
            return value instanceof Boolean b ? b : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean isListening() {
        return enabled && embedded;
    }

    @Override
    public boolean needsPrimingPublish() {
        // False, and the reason is the gate above: this platform can be asked whether anything is
        // reading, so no window pays a walk to find out. The priming publish is for a platform
        // whose only honest gate is "someone has asked", which cannot open before that someone has
        // been handed something to ask about.
        return false;
    }

    @Override
    public void attach(Host newHost) {
        this.host = newHost;
    }

    @Override
    public void detach() {
        this.host = null;
        this.published = AccessibleTree.EMPTY;
        DBus.Conn open = connection;
        connection = null;
        embedded = false;
        if (open != null) {
            // close() on this connection throws nothing: the window is going away, a socket that
            // will not shut politely is not its problem, and both threads on it are daemons.
            open.close();
        }
    }

    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        // One volatile write, and it is the whole of what the reader thread reads. Reentrancy
        // costs nothing here because nothing is released, re-pushed or drained on this path:
        // the tree that was published a moment ago is answered from until this one replaces it.
        this.published = tree;
    }

    @Override
    public void emit(AccessibleEvent event) {
        // Signals are the one message this bridge may refuse; see Outbound. Wiring them to the
        // connection is the next step and needs the role and state table below to be complete.
    }

    /** @return the tree this bridge is currently answering from; never {@code null} */
    AccessibleTree tree() {
        return published;
    }

    /** @return the scene this bridge may ask to republish or to perform an action, or null */
    Host host() {
        return host;
    }
}
