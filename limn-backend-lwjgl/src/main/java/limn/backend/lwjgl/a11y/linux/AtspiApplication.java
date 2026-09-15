package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one AT-SPI2 application a Limn process is, with every window it opens as a frame beneath it.
 *
 * <p><b>One connection per process, not per window, because the platform has one application
 * object per connection.</b> ADR 039 §2.3 says so and the code did the opposite until 2026-09-15:
 * each native window opened its own connection and did its own {@code Socket.Embed}, so a
 * DatePicker's calendar or a ComboBox's list — a native popup window — was a second application on
 * the desktop, literally named "popup", and its {@code POPUP_FOR} named a field no client could reach
 * from it. Here there is one connection, one application object at
 * {@code /org/a11y/atspi/accessible/root} and one frame child per window that has published a tree;
 * each window's {@link AtspiBridge} is a facade that registers its snapshot with this object.
 *
 * <p><b>Who touches what.</b> The table of windows is copy-on-write: the user-interface thread adds a
 * window on its first publish and removes it on its detach, and the reader thread iterates it on
 * every root query. The link is a {@code volatile} reference written by whichever thread joins or
 * leaves the bus and read by the user-interface thread when it emits. The per-window bookkeeping
 * kept on each facade ({@code member}, {@code shownAsFrame}, {@code frameId}) is the user-interface
 * thread's alone.
 *
 * <p><b>Joined only once some window has a tree</b> (docs/design/accessibility.md): at-spi2-core
 * 2.60 reads an application as it registers and never lists one that answers "no children". A
 * window that arrives after the join, or leaves before the process does, is announced from the
 * application object as an ordinary {@code ChildrenChanged} carrying the frame's reference, which is
 * the one shape libatspi 2.60.6 updates a cached child list from
 * ({@code cache_process_children_changed}, readings/upstream-at-spi2-core-2.60.6-libatspi.txt).
 * When the last window leaves, the connection goes with it, so the next window registers with a
 * tree again rather than into an application the registry already decided was empty.
 */
final class AtspiApplication {

    /** What the application sends through once it has joined: the connection, or a test's recorder. */
    interface Link {
        /**
         * Queues one signal. Never blocks.
         *
         * @param signal the message
         * @return whether it was accepted
         */
        boolean signal(DBus.Msg signal);

        /** Lets the connection go. Throws nothing. */
        void close();
    }

    /** Joins the accessibility bus as this application and hands the registry its root. */
    interface Connector {
        /**
         * @param objects what every inbound call is answered by; the connector names its bus name
         *                and desktop and exports its handler before the embed
         * @return the joined link
         * @throws IOException when any step fails; the application then stays unjoined
         */
        Link join(AtspiTree objects) throws IOException;
    }

    private static final Object PROCESS_LOCK = new Object();
    private static AtspiApplication process;

    /**
     * @return an application of its own that joins this machine's bus the real way, for a test that
     *         must not share the process's
     */
    static AtspiApplication forThisMachine() {
        return new AtspiApplication(AtspiApplication::joinTheBus);
    }

    /** @return the process's application, made on the first ask; it opens nothing until a join */
    static AtspiApplication process() {
        synchronized (PROCESS_LOCK) {
            if (process == null) {
                process = new AtspiApplication(AtspiApplication::joinTheBus);
            }
            return process;
        }
    }

    private final Connector connector;
    private final CopyOnWriteArrayList<AtspiBridge> windows = new CopyOnWriteArrayList<>();
    private final AtspiTree objects;
    private volatile String name = "";
    private volatile Link link;
    private int joinAttempts;

    AtspiApplication(Connector connector) {
        this.connector = connector;
        this.objects = new AtspiTree(() -> windows, () -> name);
    }

    /** @return a new window of this application; it becomes a frame on its first non-empty publish */
    AtspiBridge window() {
        return new AtspiBridge(this);
    }

    /**
     * Names the application object (decision 56: the backend's application name, by default the
     * title of its first window). Read on every ask, so a later name is what the next client reads.
     *
     * @param applicationName what the desktop should call this process; {@code null} leaves it
     */
    void name(String applicationName) {
        if (applicationName != null) {
            this.name = applicationName;
        }
    }

    /** @return what the application object is called now */
    String name() {
        return name;
    }

    /** @return the handler every inbound call is answered by */
    AtspiTree objects() {
        return objects;
    }

    /** @return whether the application has joined the accessibility bus */
    boolean isJoined() {
        return link != null;
    }

    /** @return how many times a publish decided there was something worth registering */
    int joinAttempts() {
        return joinAttempts;
    }

    /**
     * A window published. User-interface thread.
     *
     * @param window the facade that published
     * @param tree   what it published, already stored on the facade
     */
    void published(AtspiBridge window, AccessibleTree tree) {
        if (!window.member) {
            windows.add(window);
            window.member = true;
        }
        Link joined = link;
        if (joined == null) {
            if (tree.nodeCount() > 0) {
                join();
            }
            return;
        }
        boolean shows = tree.nodeCount() > 0;
        if (shows == window.shownAsFrame) {
            return;
        }
        if (shows) {
            window.frameId = tree.node(0).id();
            window.shownAsFrame = true;
            announceFrame(joined, "add", frameIndexOf(window), window.frameId);
        } else {
            int index = frameIndexOf(window);
            window.shownAsFrame = false;
            announceFrame(joined, "remove", index, window.frameId);
        }
    }

    /**
     * A window is going away. User-interface thread, after the facade dropped its tree.
     *
     * @param window the facade
     */
    void detached(AtspiBridge window) {
        if (!window.member) {
            return;
        }
        Link joined = link;
        if (joined != null && window.shownAsFrame) {
            announceFrame(joined, "remove", frameIndexOf(window), window.frameId);
        }
        window.shownAsFrame = false;
        windows.remove(window);
        window.member = false;
        if (windows.isEmpty() && joined != null) {
            link = null;
            joined.close();
        }
    }

    /**
     * One of the toolkit's events, sent from the node it names. User-interface thread. Dropped while
     * the application has not joined, as every event before the join always was.
     *
     * @param event what the difference between two published trees found
     */
    void emit(AccessibleEvent event) {
        Link joined = link;
        if (joined == null) {
            return;
        }
        AtspiEvents.Signal signal = AtspiEvents.of(event);
        if (signal == null) {
            return;  // nothing on this platform carries it; better silent than approximate
        }
        // From the node the event is about, so a client that subscribed by path hears it, and as a
        // signal rather than a reply, so it is the one kind the connection may refuse when a peer
        // has stopped draining.
        joined.signal(DBus.Msg.signal(pathOf(event.nodeId()), signal.iface(), signal.member(),
                AtspiEvents.SIGNATURE, AtspiEvents.body(signal, objects.rootRef())));
    }

    /** The object path an event's node is at; node zero's is the application's. */
    private static String pathOf(long nodeId) {
        return nodeId == 0 ? Atspi.PATH_ROOT : "/org/a11y/atspi/accessible/" + nodeId;
    }

    /** Where a window stands among the frames already announced, counting those before it. */
    private int frameIndexOf(AtspiBridge window) {
        int index = 0;
        for (AtspiBridge other : windows) {
            if (other == window) {
                return index;
            }
            if (other.shownAsFrame) {
                index++;
            }
        }
        return index;
    }

    /**
     * Tells clients the application object gained or lost a frame: {@code ChildrenChanged} from the
     * root with the index in {@code detail1} and the frame's own reference as the value, which is
     * the struct libatspi turns into an accessible and inserts at that index (or removes).
     */
    private void announceFrame(Link joined, String detail, int index, long frameId) {
        AtspiEvents.Signal signal = new AtspiEvents.Signal(AtspiEvents.I_EVENT_OBJECT,
                "ChildrenChanged", detail, index, 0,
                new DBus.Variant("(so)", objects.refOf(frameId).toStruct()));
        joined.signal(DBus.Msg.signal(Atspi.PATH_ROOT, signal.iface(), signal.member(),
                AtspiEvents.SIGNATURE, AtspiEvents.body(signal, objects.rootRef())));
    }

    /** Joins now, on the calling thread; a failure leaves the application unjoined. */
    private void join() {
        joinAttempts++;
        Link joined;
        try {
            joined = connector.join(objects);
        } catch (IOException | RuntimeException e) {
            return;
        }
        // Every window with a tree at this moment is a child the registry has just read; only a
        // change from here on is news.
        for (AtspiBridge window : windows) {
            AccessibleTree tree = window.tree();
            window.shownAsFrame = tree.nodeCount() > 0;
            if (window.shownAsFrame) {
                window.frameId = tree.node(0).id();
            }
        }
        link = joined;
    }

    /**
     * The real join: the sequence the spike proved on the guest, and its order is not free. The
     * accessibility bus's address comes from the session bus, our own name on it comes from
     * {@code Hello}, and the handler is exported <em>before</em> {@code Embed}, because the registry
     * may call back the moment it has the plug and a path with no handler answers UnknownMethod.
     */
    private static Link joinTheBus(AtspiTree objects) throws IOException {
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (session == null) {
            throw new IOException("no session bus address in this process's environment");
        }
        String where;
        try (DBus.Conn bus = DBus.Conn.open(session)) {
            bus.hello();
            Object[] address = bus.callArgs("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus",
                    "GetAddress", null);
            if (address.length == 0 || !(address[0] instanceof String found)) {
                throw new IOException("org.a11y.Bus.GetAddress answered no address");
            }
            where = found;
        }
        DBus.Conn a11y = DBus.Conn.open(where);
        objects.busName(a11y.hello());
        a11y.exportFallback(objects::handle);
        Object[] socket = a11y.callArgs(Atspi.REGISTRY, Atspi.PATH_ROOT, Atspi.I_SOCKET,
                "Embed", "(so)", (Object) objects.rootRef().toStruct());
        if (socket.length > 0) {
            objects.desktop(DBus.Ref.of(socket[0]));
        }
        return linkOver(a11y);
    }

    /** The link a real connection is. */
    static Link linkOver(DBus.Conn connection) {
        return new Link() {
            @Override
            public boolean signal(DBus.Msg signal) {
                try {
                    return connection.sendSignal(signal);
                } catch (IOException e) {
                    // The writer thread reports its own failures and the connection closes itself;
                    // an event lost to a dying socket is not worth a second report.
                    return false;
                }
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }
}
