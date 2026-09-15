package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.a11y.PlatformBridge;

import java.io.IOException;

/**
 * Reads a Limn window to a screen reader on Linux, over AT-SPI2: one window's facade onto the
 * process's one AT-SPI application ({@link AtspiApplication}, ADR 039 §2.3).
 *
 * <p>Hand one to a window and its scene publishes into the desktop's accessibility tree as a frame
 * of the application; hand it nothing and there is no cost at all, which is the arrangement the
 * seam already has for a backend with no accessibility. The backend installs it from the window's
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
 * thread answers every inbound call from every client, computing each answer from the tree()
 * snapshot, so it never touches a widget and never blocks. The writer thread performs every write.
 * A reply written from the reader thread would park the one thread serving every client the moment
 * a peer stopped draining, which a well-behaved client cannot even detect it is causing.
 */
public final class AtspiBridge extends PlatformBridge implements AtspiTree.Window {

    /** The session-bus object that says whether assistive technology is running. */
    private static final String STATUS_NAME = "org.a11y.Bus";
    private static final String STATUS_PATH = "/org/a11y/bus";
    private static final String STATUS_IFACE = "org.a11y.Status";

    private final AtspiApplication application;

    /** Whether the application's window table holds this facade. User-interface thread. */
    boolean member;
    /** Whether clients have been told this window is a frame of the application. UI thread. */
    boolean shownAsFrame;
    /** The node id this window was announced as, so its departure names the same object. UI thread. */
    long frameId;

    AtspiBridge(AtspiApplication application) {
        this.application = application;
    }

    /**
     * Opens a bridge if the desktop says assistive technology is running, and otherwise nothing.
     *
     * <p>The gate is read here, once, before a socket to the accessibility bus is opened or a
     * thread is started: a window on a machine with no screen reader is meant to cost a property
     * read and never a connection. A session bus that cannot be reached at all — a headless
     * process, a container with no D-Bus — is not an error and answers no.
     *
     * @param applicationName what the desktop calls this process (decision 56: the backend's
     *                        application name, by default its first window's title)
     * @return a bridge, or {@link AccessibilityBridge#NONE} when accessibility is switched off or
     *         the session bus cannot be asked
     */
    public static AccessibilityBridge openIfEnabled(String applicationName) {
        Boolean on = readStatusFlag("IsEnabled");
        if (on == null || !on) {
            return AccessibilityBridge.NONE;
        }
        // The bus is NOT joined here. See publish(): an application that registers before it has a
        // tree is an application some desktops refuse to list.
        AtspiApplication application = AtspiApplication.process();
        application.name(applicationName);
        return application.window();
    }

    /**
     * Renames the process's application object, for a name the backend was given after its windows
     * opened.
     *
     * @param applicationName the new name
     */
    public static void nameApplication(String applicationName) {
        AtspiApplication.process().name(applicationName);
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

    /**
     * The same bridge without asking the desktop whether accessibility is on, so that the rules
     * above can be exercised on a machine that has no accessibility bus — which is most of them.
     *
     * <p>Package-private and not a way to install a bridge anywhere: it is a window of an
     * application of its own, not the process's, and joins no bus until it is published to; on a
     * machine with none that attempt fails and leaves it unconnected.
     *
     * @return a bridge that believes the desktop said yes
     */
    static AtspiBridge withoutTheGate() {
        AtspiApplication application = AtspiApplication.forThisMachine();
        application.name("a test");
        return application.window();
    }

    /** @return the object-path handler a client's calls are answered by. For tests. */
    AtspiTree objects() {
        return application.objects();
    }

    /** @return whether this bridge's application has joined the accessibility bus. For tests. */
    boolean isOnTheBus() {
        return application.isJoined();
    }

    /**
     * @return how many times a publish has decided there was something worth registering. Counted
     *         rather than inferred from the connection, because whether the attempt SUCCEEDS
     *         depends on the machine and whether it is MADE does not — and the defect was never
     *         making it at a moment when there was a tree
     */
    int joinAttempts() {
        return application.joinAttempts();
    }

    @Override
    public boolean isListening() {
        // The desktop's own flag, and not "are we on the bus yet". This platform is the one that
        // can be asked whether anything is reading, which is what §6 wants a gate to be — and
        // making it depend on being embedded would be a cycle with no way in: the bus is joined on
        // the first publish, and a scene publishes only when something is listening.
        return true;
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
    protected void releasePlatformHalf() {
        // The window leaves the application; the application lets the connection go when it was
        // the last one (AtspiApplication#detached).
        application.detached(this);
    }

    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        // One volatile write, and it is the whole of what the reader thread reads. Reentrancy
        // costs nothing here because nothing is released, re-pushed or drained on this path: the
        // tree published a moment ago is answered from until this one replaces it.
        super.publish(tree, reentrant);
        // Joined here rather than at construction, and only once there is something to show.
        //
        // Fedora 44 is what found this. Its at-spi2-core 2.60 registry reads an application AS IT
        // REGISTERS -- role, name, a whole Cache.GetItems -- and an application that answers "no
        // children" is one it never adds to the desktop: over a hundred inbound calls, a perfect
        // conversation, and no libatspi client would list us, Orca included. Ubuntu's 2.52 adds
        // first and reads later, so registering with an empty tree looked correct there for every
        // run this bridge has ever had. Registering before there is a tree was always wrong; only
        // one of the two desktops minded.
        application.published(this, tree);
    }

    @Override
    public void emit(AccessibleEvent event) {
        application.emit(event);
    }
}
