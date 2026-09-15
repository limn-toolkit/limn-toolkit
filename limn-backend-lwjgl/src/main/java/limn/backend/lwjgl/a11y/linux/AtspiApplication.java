package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.concurrent.Threads;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 * <p><b>The join never runs on the user-interface thread.</b> It is up to four round trips, each of
 * which may wait for a peer — the session bus's {@code Hello} and {@code GetAddress}, the
 * accessibility bus's {@code Hello}, the registry's {@code Embed} — and a frame that waited on them
 * would freeze the window it is trying to make readable. So the first publish that has a tree starts
 * one short-lived daemon thread that joins and ends; a publish while it runs does nothing; a join
 * that fails closes everything it opened, and its thread waits out a back-off and then asks every
 * window for a publish, which tries again — so a failed join is retried on a growing interval and
 * never on every frame, and an idle window whose tree nothing will dirty is not left off the
 * desktop. A joined connection that is lost soon after its join counts as a failure too. Events
 * emitted before the join completes are dropped, as they always were.
 *
 * <p><b>Who touches what.</b> The table of windows is copy-on-write: the user-interface thread adds a
 * window on its first publish and removes it on its detach, and the reader thread iterates it on
 * every root query. The joined state is one atomic reference, set by the joiner thread and cleared
 * by whichever thread lets the connection go; the rest of what the joiner writes is volatile. The
 * per-window bookkeeping kept on each facade ({@code member}, {@code shownAsFrame},
 * {@code frameId}) is the user-interface thread's alone.
 *
 * <p><b>Joined only once some window has a tree</b> (docs/design/accessibility.md): at-spi2-core
 * 2.60 reads an application as it registers and never lists one that answers "no children". A
 * window that arrives after the join, or leaves before the process does, is announced from the
 * application object as an ordinary {@code ChildrenChanged} carrying the frame's reference, which is
 * the one shape libatspi 2.60.6 updates a cached child list from
 * ({@code cache_process_children_changed}, readings/upstream-at-spi2-core-2.60.6-libatspi.txt); it
 * removes the child before inserting it, so an arrival told twice is harmless and one never told is
 * not. When the last window leaves, the connection goes with it, so the next window registers with a
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
         * Runs on the joiner thread, never on the user-interface thread.
         *
         * @param objects what every inbound call is answered by; the connector names its bus name
         *                and desktop and exports its handler before the embed
         * @param lost    to run, once, if the joined connection later stops working on its own
         * @return the joined link
         * @throws IOException when any step fails, having closed everything it opened; the
         *                     application then stays unjoined until the back-off has passed
         */
        Link join(AtspiTree objects, Runnable lost) throws IOException;
    }

    /** How the joiner thread is started: a daemon thread, or the caller's own thread in a test. */
    interface Starter {
        /** Runs the body on the calling thread; only for tests that want the join synchronous. */
        Starter ON_THE_CALLER = (name, body) -> body.run();

        /** A daemon thread per call, which ends when the body does. */
        Starter DAEMON = Threads::daemon;

        void start(String name, Runnable body);
    }

    /** One accessibility connection, as the steps of a join see it. */
    interface Bus {
        String hello() throws IOException;

        void exportFallback(DBus.Handler handler);

        /** {@code Socket.Embed} of the application root; answers the registry's socket reference. */
        Object[] embed(Object[] root) throws IOException;

        Link link();

        /** Runs {@code lost} once if this connection stops working without being closed. */
        void onLost(Runnable lost);

        void close();
    }

    /** Where a join finds its accessibility bus, and how it opens a connection to it. */
    interface Buses {
        /** @return the accessibility bus's address, asked of the session bus */
        String a11yAddress() throws IOException;

        Bus open(String address) throws IOException;
    }

    /** The first wait after a failed join; each further failure doubles it. */
    static final long FIRST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The longest wait between two joins, however many have failed. */
    static final long LONGEST_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60);

    /**
     * How long a joined connection must have lasted for its loss to be news rather than a failure:
     * one lost sooner is counted against the back-off, so a connection that joins and dies at once
     * (a bus still restarting, a reader killed by an error) is not rejoined as fast as frames come.
     * Policy, like the two above, not a platform constant.
     */
    static final long STEADY_NANOS = TimeUnit.SECONDS.toNanos(60);

    /**
     * The joined state: the link, which join it was, the frames the registry read at it, and when
     * it was joined, on the application's clock.
     */
    private record Joined(Link link, int generation, Map<AtspiBridge, Long> framesAtJoin,
                          long joinedAt) {
    }

    private static final Object PROCESS_LOCK = new Object();
    private static AtspiApplication process;

    /**
     * @return an application of its own that joins this machine's bus the real way, on a thread of
     *         its own, for a test that must not share the process's
     */
    static AtspiApplication forThisMachine() {
        return new AtspiApplication(AtspiApplication::joinTheBus, Starter.DAEMON, System::nanoTime,
                AtspiStatusWatch.Sleeper.REAL);
    }

    /** @return the process's application, made on the first ask; it opens nothing until a join */
    static AtspiApplication process() {
        synchronized (PROCESS_LOCK) {
            if (process == null) {
                process = forThisMachine();
            }
            return process;
        }
    }

    private final Connector connector;
    private final Starter starter;
    private final LongSupplier clock;
    private final AtspiStatusWatch.Sleeper sleeper;
    private final CopyOnWriteArrayList<AtspiBridge> windows = new CopyOnWriteArrayList<>();
    private final AtspiTree objects;
    private final AtomicReference<Joined> joined = new AtomicReference<>();
    /**
     * Held from the moment a join is started until its thread ends: through the join, and through
     * the back-off wait that follows a failed one. Nothing starts a join while it is held.
     */
    private final AtomicBoolean joining = new AtomicBoolean();
    private volatile String name = "";
    /** The desktop's accessibility switch, as the watch last read it. Watch thread writes. */
    private volatile boolean enabled;
    private final AtomicBoolean watching = new AtomicBoolean();
    /**
     * Joins that failed, or connections lost before {@link #STEADY_NANOS}, since the last one that
     * held. Written only by a thread holding {@code joining}.
     */
    private volatile int failures;
    /** The thread waiting out a back-off, so the switch turning off can end the wait. */
    private volatile Thread waiting;
    private volatile int generations;
    /** The join whose frames the windows' bookkeeping describes. User-interface thread. */
    private int caughtUpGeneration;
    private int joinAttempts;

    AtspiApplication(Connector connector, Starter starter, LongSupplier clock) {
        this(connector, starter, clock, AtspiStatusWatch.Sleeper.REAL);
    }

    /**
     * @param sleeper how a thread holding the join waits out a back-off; a test's advances its clock
     */
    AtspiApplication(Connector connector, Starter starter, LongSupplier clock,
                     AtspiStatusWatch.Sleeper sleeper) {
        this.connector = connector;
        this.starter = starter;
        this.clock = clock;
        this.sleeper = sleeper;
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

    /** @return whether the desktop says assistive technology is running, as last read */
    boolean isEnabled() {
        return enabled;
    }

    /**
     * Starts the process's one watch of the desktop's switch, once; later calls do nothing.
     *
     * @param sessionAddress the session bus to watch it on
     */
    void watchStatus(String sessionAddress) {
        if (watching.compareAndSet(false, true)) {
            AtspiStatusWatch watch = new AtspiStatusWatch(sessionAddress, this::enabled,
                    AtspiStatusWatch.Sleeper.REAL);
            Threads.daemon(AtspiStatusWatch.THREAD_NAME, watch);
        }
    }

    /**
     * The desktop's switch moved, or was read. Any thread; the watch thread in the process.
     *
     * <p>On: every attached window is asked for a publish, which buys the frame an idle window would
     * otherwise never spend, and that publish joins. Off: the join is let go of, so the registry
     * sees the application leave and no window keeps walking for a reader that has gone (decision
     * 29).
     *
     * @param on the switch's value
     */
    void enabled(boolean on) {
        boolean was = enabled;
        enabled = on;
        if (on == was) {
            return;
        }
        if (on) {
            askEveryWindowToPublish();
            return;
        }
        // A back-off being waited out is for a reader that has gone: end it now rather than keep a
        // thread for up to a minute. Written before this read, as the waiter reads the switch after
        // it names itself, so neither misses the other.
        Thread waiter = waiting;
        if (waiter != null) {
            waiter.interrupt();
        }
        Joined now = joined.get();
        if (now != null) {
            leave(now);
        }
    }

    /** Asks every attached window for a publish, which is what starts a join. Any thread. */
    private void askEveryWindowToPublish() {
        for (AtspiBridge window : windows) {
            limn.backend.AccessibilityBridge.Host host = window.host();
            if (host != null) {
                host.requestRepublish();
            }
        }
    }

    /**
     * A window's scene attached. User-interface thread. The window joins the table now rather than
     * on its first publish, because a scene bound while the switch is off never publishes, and it is
     * exactly that window the switch turning on must be able to wake.
     *
     * @param window the facade
     */
    void attached(AtspiBridge window) {
        if (!window.member) {
            windows.add(window);
            window.member = true;
        }
    }

    /** @return whether the application has joined the accessibility bus */
    boolean isJoined() {
        return joined.get() != null;
    }

    /** @return how many joins a publish has started */
    int joinAttempts() {
        return joinAttempts;
    }

    /**
     * A window published. User-interface thread; returns without waiting on anything.
     *
     * @param window the facade that published
     * @param tree   what it published, already stored on the facade
     */
    void published(AtspiBridge window, AccessibleTree tree) {
        if (!window.member) {
            windows.add(window);
            window.member = true;
        }
        Joined now = joined.get();
        if (now == null) {
            if (tree.nodeCount() > 0) {
                requestJoin();
            }
            return;
        }
        catchUp(now);
        boolean shows = tree.nodeCount() > 0;
        if (shows == window.shownAsFrame) {
            return;
        }
        if (shows) {
            window.frameId = tree.node(0).id();
            window.shownAsFrame = true;
            announceFrame(now.link(), "add", frameIndexOf(window), window.frameId);
        } else {
            int index = frameIndexOf(window);
            window.shownAsFrame = false;
            announceFrame(now.link(), "remove", index, window.frameId);
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
        Joined now = joined.get();
        if (now != null) {
            catchUp(now);
            if (window.shownAsFrame) {
                announceFrame(now.link(), "remove", frameIndexOf(window), window.frameId);
            }
        }
        window.shownAsFrame = false;
        windows.remove(window);
        window.member = false;
        if (windows.isEmpty()) {
            Thread waiter = waiting;
            if (waiter != null) {
                waiter.interrupt();  // a back-off for no window at all
            }
            if (now != null) {
                leave(now);
            }
        }
    }

    /**
     * One of the toolkit's events, sent from the node it names. User-interface thread. Dropped while
     * the application has not joined, as every event before the join always was.
     *
     * @param event what the difference between two published trees found
     */
    void emit(AccessibleEvent event) {
        Joined now = joined.get();
        if (now == null) {
            return;
        }
        AtspiEvents.Signal signal = AtspiEvents.of(event);
        if (signal == null) {
            return;  // nothing on this platform carries it; better silent than approximate
        }
        // From the node the event is about, so a client that subscribed by path hears it, and as a
        // signal rather than a reply, so it is the one kind the connection may refuse when a peer
        // has stopped draining.
        now.link().signal(DBus.Msg.signal(pathOf(event.nodeId()), signal.iface(), signal.member(),
                AtspiEvents.SIGNATURE, AtspiEvents.body(signal, objects.rootRef())));
    }

    /**
     * Brings every window's bookkeeping up to a join the user-interface thread has not seen yet: a
     * frame the registry read at the join is already known to clients, anything else is not. Once
     * per join, so an ordinary publish walks no window table.
     */
    private void catchUp(Joined now) {
        if (caughtUpGeneration == now.generation()) {
            return;
        }
        caughtUpGeneration = now.generation();
        for (AtspiBridge window : windows) {
            Long frame = now.framesAtJoin().get(window);
            window.shownAsFrame = frame != null;
            if (frame != null) {
                window.frameId = frame;
            }
        }
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
    private void announceFrame(Link link, String detail, int index, long frameId) {
        AtspiEvents.Signal signal = new AtspiEvents.Signal(AtspiEvents.I_EVENT_OBJECT,
                "ChildrenChanged", detail, index, 0,
                new DBus.Variant("(so)", objects.refOf(frameId).toStruct()));
        link.signal(DBus.Msg.signal(Atspi.PATH_ROOT, signal.iface(), signal.member(),
                AtspiEvents.SIGNATURE, AtspiEvents.body(signal, objects.rootRef())));
    }

    /**
     * Starts a join unless one is running or a failed one's back-off is still being waited out —
     * the same flag holds both. User-interface thread: a compare-and-set and, at most once per join,
     * a thread start.
     */
    private void requestJoin() {
        if (!enabled) {
            return;
        }
        if (!joining.compareAndSet(false, true)) {
            return;
        }
        joinAttempts++;
        starter.start("limn-a11y-atspi-join", this::joinNow);
    }

    /** The joiner thread's whole body. */
    private void joinNow() {
        // The frames the registry will read: every window with a tree before the embed. A window
        // that gains one during the join is announced when it next publishes, which at worst tells
        // a client of a frame it already read, and libatspi removes before it inserts.
        Map<AtspiBridge, Long> frames = new IdentityHashMap<>();
        for (AtspiBridge window : windows) {
            AccessibleTree tree = window.tree();
            if (tree.nodeCount() > 0) {
                frames.put(window, tree.node(0).id());
            }
        }
        // The connection may be lost before the joined state exists to be let go of; the flag keeps
        // that loss for the moment it does.
        AtomicBoolean lostEarly = new AtomicBoolean();
        AtomicReference<Joined> self = new AtomicReference<>();
        Runnable lost = () -> {
            lostEarly.set(true);
            Joined mine = self.get();
            if (mine != null) {
                connectionLost(mine);
            }
        };
        Link link;
        try {
            link = connector.join(objects, lost);
        } catch (IOException | RuntimeException e) {
            failures++;
            waitOutTheBackOffAndAskAgain();
            return;
        }
        Joined now = new Joined(link, ++generations, Map.copyOf(frames), clock.getAsLong());
        joined.set(now);
        // The join is let go of before the loss handler can see this state, so a loss it handles
        // finds the flag free to start its back-off; a loss that saw no state yet is seen below.
        joining.set(false);
        self.set(now);
        if (lostEarly.get()) {
            connectionLost(now);
        } else if (!enabled || windows.isEmpty()) {
            // The switch went off, or every window left, while the join ran: nothing is reading,
            // and an application with no frame is what the next window must not register into.
            //
            // Read AFTER the joined state is published, never before: enabled(false) writes the
            // switch and then reads the joined state, so each of the two threads reads what the
            // other wrote first and one of them always lets the join go. The check used to come
            // before the publication, and a switch turned off between the two found nothing to
            // leave while this thread found nothing turned off (the linux-A review).
            leave(now);
        }
    }

    /**
     * The joined connection stopped on its own: its reader reached the end of the stream or could
     * not go on, or its writer could not write. An application still believing itself embedded
     * would go on sending signals into a connection nobody answers on — the "embedded but deaf"
     * state LINUX-NEW-13 found — so the join is let go of and every window is asked for a publish,
     * which joins again.
     *
     * <p>At once only when the connection had held for {@link #STEADY_NANOS}. One lost sooner is a
     * failure: until 2026-09-15 a successful join reset the count, so a connection that joined and
     * died at once — a bus still restarting, a handler error that ends the reader — was rejoined as
     * fast as the scene published, a socket and two threads each time. Such a loss waits out the
     * back-off on a thread of its own and then asks.
     */
    private void connectionLost(Joined now) {
        if (!leave(now)) {
            return;  // already let go of, by this handler or on purpose
        }
        boolean steady = clock.getAsLong() - now.joinedAt() >= STEADY_NANOS;
        if (!joining.compareAndSet(false, true)) {
            return;  // a join already started will ask, or wait, for itself
        }
        if (steady) {
            failures = 0;
            joining.set(false);
            askEveryWindowToPublish();
            return;
        }
        failures++;
        starter.start("limn-a11y-atspi-join", this::waitOutTheBackOffAndAskAgain);
    }

    /**
     * On a thread holding {@code joining}, after a failure: waits the back-off for the failures
     * counted so far, lets the join go, and asks every window for the publish that tries again —
     * unless the switch went off or the application joined meanwhile.
     *
     * <p>It used to only record when the next join might start and leave the asking to whatever
     * published next (LINUX-NEW-12, the linux-A review). A scene publishes only when its tree is
     * dirty, so an idle window — the window decision 29 is about, opened before the reader and then
     * left alone — stayed off the desktop after one failed join until something else changed on
     * screen, and a publish that fell inside the back-off was dropped with nothing to repeat it.
     */
    private void waitOutTheBackOffAndAskAgain() {
        long wait = Math.min(LONGEST_RETRY_NANOS, FIRST_RETRY_NANOS << Math.min(failures - 1, 16));
        waiting = Thread.currentThread();
        boolean waited = false;
        try {
            // Read after naming this thread, as enabled(false) writes the switch before it reads
            // the name: one of the two always sees the other.
            if (enabled && !windows.isEmpty()) {
                sleeper.sleep(wait);
                waited = true;
            }
        } catch (InterruptedException e) {
            // The switch went off, or the last window left: nobody to ask.
        } finally {
            waiting = null;
            Thread.interrupted();  // an interrupt that came after the wait was for this wait alone
            joining.set(false);
        }
        if (waited && enabled && joined.get() == null) {
            askEveryWindowToPublish();
        }
    }

    /**
     * Lets a join go, once, whichever thread gets here first.
     *
     * @return whether this call was the one that let it go
     */
    private boolean leave(Joined now) {
        if (joined.compareAndSet(now, null)) {
            now.link().close();
            return true;
        }
        return false;
    }

    /**
     * The real join: the sequence the spike proved on the guest, and its order is not free. The
     * accessibility bus's address comes from the session bus, our own name on it comes from
     * {@code Hello}, and the handler is exported <em>before</em> {@code Embed}, because the registry
     * may call back the moment it has the plug and a path with no handler answers UnknownMethod.
     */
    private static Link joinTheBus(AtspiTree objects, Runnable lost) throws IOException {
        return join(REAL_BUSES, objects, lost);
    }

    /**
     * The join's steps over any pair of buses, closing the accessibility connection on every path
     * that does not end joined.
     *
     * <p>It used not to: the connection was opened outside any {@code try}, so a {@code Hello} or an
     * {@code Embed} that timed out or answered an error left a socket and its reader and writer
     * threads behind, once per attempt, and the attempt was repeated on every frame (LINUX-NEW-12).
     *
     * @param lost run once if the connection later stops working on its own
     * @throws IOException when a step fails
     */
    static Link join(Buses buses, AtspiTree objects, Runnable lost) throws IOException {
        Bus a11y = buses.open(buses.a11yAddress());
        boolean done = false;
        try {
            objects.busName(a11y.hello());
            a11y.exportFallback(objects::handle);
            Object[] socket = a11y.embed(objects.rootRef().toStruct());
            if (socket.length > 0) {
                objects.desktop(DBus.Ref.of(socket[0]));
            }
            Link link = a11y.link();
            a11y.onLost(lost);
            done = true;
            return link;
        } catch (RuntimeException e) {
            throw new IOException("the join failed: " + e, e);
        } finally {
            if (!done) {
                a11y.close();
            }
        }
    }

    /** The session bus from the environment, and the accessibility bus it names. */
    private static final Buses REAL_BUSES = new Buses() {
        @Override
        public String a11yAddress() throws IOException {
            String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
            if (session == null) {
                throw new IOException("no session bus address in this process's environment");
            }
            try (DBus.Conn bus = DBus.Conn.open(session)) {
                bus.hello();
                Object[] address = bus.callArgs("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus",
                        "GetAddress", null);
                if (address.length == 0 || !(address[0] instanceof String found)) {
                    throw new IOException("org.a11y.Bus.GetAddress answered no address");
                }
                return found;
            } catch (RuntimeException e) {
                throw new IOException("the session bus refused GetAddress: " + e, e);
            }
        }

        @Override
        public Bus open(String address) throws IOException {
            DBus.Conn connection = DBus.Conn.open(address);
            return new Bus() {
                @Override public String hello() throws IOException {
                    return connection.hello();
                }

                @Override public void exportFallback(DBus.Handler handler) {
                    connection.exportFallback(handler);
                }

                @Override public Object[] embed(Object[] root) throws IOException {
                    return connection.callArgs(Atspi.REGISTRY, Atspi.PATH_ROOT, Atspi.I_SOCKET,
                            "Embed", "(so)", (Object) root);
                }

                @Override public Link link() {
                    return linkOver(connection);
                }

                @Override public void onLost(Runnable lost) {
                    connection.onLost(lost);
                }

                @Override public void close() {
                    connection.close();
                }
            };
        }
    };

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
