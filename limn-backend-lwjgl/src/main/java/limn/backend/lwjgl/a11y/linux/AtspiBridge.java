package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.a11y.PlatformBridge;

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
 * <p><b>The gate is the desktop's own switch, and it is watched, not read once.</b>
 * {@code org.a11y.Status.IsEnabled} on the session bus says whether assistive technology is running
 * at all, and it moves while applications run: a screen reader started after this window turns it
 * on. The process keeps one session connection and one parked thread following it
 * ({@link AtspiStatusWatch}, decision 29). While it has never been true no connection to the
 * accessibility bus is opened, no scene walks and no frame is spent; the first true asks every
 * window for a publish, and there it stays: <b>once embedded, embedded for the life of the
 * process</b> (decision 67), as a GTK application is once {@code atk-bridge} has loaded. A false
 * afterwards changes nothing, because it never means what a teardown would need it to mean:
 * neither Orca 50.2 nor 46.1 ever writes the switch false
 * (readings/fedora-orca-switch-writes.txt, readings/ubuntu-orca-switch-writes.txt), so a reader
 * that quits leaves it on, and a false that does arrive — the desktop's own accessibility setting
 * — can arrive while a reader is still reading us (ADR 039 §6). The gate is never "a client asked
 * us something recently": Orca registers for a focus change and then calls nothing until one
 * fires, so a gate of that shape goes silent exactly when the interface is being used.
 *
 * <p><b>Which thread may do what is the whole of the concurrency design.</b> The user-interface
 * thread publishes snapshots and enqueues events and blocks on nothing. The status thread follows
 * the switch. A short-lived joiner thread joins the accessibility bus. On that bus the reader thread
 * answers every inbound call from every client, computing each answer from the published snapshots,
 * so it never touches a widget and never blocks, and the writer thread performs every write. A reply
 * written from the reader thread would park the one thread serving every client the moment a peer
 * stopped draining, which a well-behaved client cannot even detect it is causing.
 */
public final class AtspiBridge extends PlatformBridge implements AtspiTree.Window {

    private final AtspiApplication application;

    /** Whether the application's window table holds this facade. User-interface thread. */
    boolean member;
    /** Whether clients have been told this window is a frame of the application. UI thread. */
    boolean shownAsFrame;
    /** The node id this window was announced as, so its departure names the same object. UI thread. */
    long frameId;
    /** The name this window was announced with, which its {@code Destroy} carries. UI thread. */
    String frameName = "";
    /**
     * The node a {@code focused} 1 was sent for in this publish since the reader's locus last
     * moved to this frame, or 0. UI thread. Cleared by every publish, and by the frame's
     * {@code active} 1 or its {@code Activate}, either of which moves Orca 50.2's locus to the
     * frame; so a survivor's gain is not sent twice in one publish, and a focus said before the
     * locus moved is said again after it.
     */
    long focusSaid;
    /** The descendant an {@code ActiveDescendantChanged} named on the same terms, or 0. UI thread. */
    long cursorSaid;
    /** Whether this publish sent the frame's own {@code StateChanged active} 1. UI thread. */
    boolean frameActiveSaid;
    /**
     * The join this window's {@link #reconcileOwed} belongs to, so an owe raised on a connection
     * clients no longer hold is not paid on the next one. UI thread. What was <em>announced</em> is
     * not remembered here: the last effective focus is one memory for the process and lives on
     * {@link AtspiApplication} (semantics 4, settled 2026-09-15), because the platform focus is one.
     */
    int reconcileGeneration;
    /**
     * Whether the model's {@code INVALIDATED} or a refused signal left the focus and cursor to be
     * reconciled at the tail's place: before the first tail event after the structure signals, or,
     * when the publish carried none, at {@link #frameEnded()} — and, for a refusal that came after
     * that, before this window's next publish replaces its tree. UI thread.
     */
    boolean reconcileOwed;
    /**
     * The tree this window published before its current one, which a bit this platform derives
     * (COLLAPSED) is diffed against when its events arrive. UI thread: written by the publish and
     * read by the emits that follow it; the reader thread never reads it.
     */
    AccessibleTree previousTree = AccessibleTree.EMPTY;

    AtspiBridge(AtspiApplication application) {
        this.application = application;
    }

    /**
     * Opens a window's bridge onto the process's application, or nothing on a machine where the
     * switch cannot be watched.
     *
     * <p>Nothing is read and nothing is opened on the calling thread, which is the scene's bind on
     * the user-interface thread: the process's status watch is started (once) and reads the switch
     * on its own thread, and until it says yes this bridge is not listening. A process with no
     * session bus it can reach — headless, a container, a CI runner — gets {@link
     * AccessibilityBridge#NONE} and no thread at all.
     *
     * @param applicationName what the desktop calls this process (decision 56: the backend's
     *                        application name, by default its first window's title)
     * @return a bridge, or {@link AccessibilityBridge#NONE} when there is no session bus to watch
     */
    public static AccessibilityBridge open(String applicationName) {
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (!AtspiStatusWatch.canWatch(session)) {
            return AccessibilityBridge.NONE;
        }
        // The bus is NOT joined here. See publish(): an application that registers before it has a
        // tree is an application some desktops refuse to list.
        AtspiApplication application = AtspiApplication.process();
        application.name(applicationName);
        application.watchStatus(session);
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
        application.enabled(true);
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
        // The desktop's own flag as the watch last read it, and not "are we on the bus yet". This
        // platform is the one that can be asked whether anything is reading, which is what §6 wants
        // a gate to be — and making it depend on being embedded would be a cycle with no way in:
        // the bus is joined on the first publish, and a scene publishes only when something is
        // listening. One volatile read per frame.
        return application.isEnabled();
    }

    @Override
    public void attach(Host host) {
        super.attach(host);
        application.attached(this);
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
        previousTree = AccessibleTree.EMPTY;
        reconcileOwed = false;
        // The window leaves the application; the application lets the connection go when it was
        // the last one (AtspiApplication#detached).
        application.detached(this);
    }

    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        // One volatile write, and it is the whole of what the reader thread reads. Reentrancy
        // costs nothing here because nothing is released, re-pushed or drained on this path: the
        // tree published a moment ago is answered from until this one replaces it.
        //
        // A reconcile the last publish owed and never reached (its tail had no event after the
        // structure signals) runs first, against the tree it was owed for.
        application.publishing(this);
        previousTree = tree();
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
        application.emit(this, event);
    }

    @Override
    public void frameEnded() {
        // Nothing is posted here — this bridge writes from its own writer thread, and every signal
        // of this frame has already been queued. What is owed is the re-announcement of the focus
        // and the cursor after a collapse whose tail held nothing after its structure signals
        // (semantics 4): it belongs to this frame, not to whenever the tree next changes.
        application.frameEnded(this);
    }
}
