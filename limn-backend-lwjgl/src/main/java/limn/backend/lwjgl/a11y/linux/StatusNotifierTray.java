package limn.backend.lwjgl.a11y.linux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.MenuRow;
import limn.backend.spi.TrayState;
import limn.concurrent.internal.Threads;
import org.jspecify.annotations.Nullable;

/**
 * A tray icon on a Linux desktop: a status item and its menu, exported on the session bus and
 * registered with the desktop's watcher, which a panel then shows and draws the menu of.
 *
 * <p><b>A connection of its own.</b> The protocol has no call that takes an item away; a panel
 * drops an item when its bus name leaves. So each tray has a connection, closed when it is
 * disposed.
 *
 * <p><b>Threads.</b> The UI thread calls {@link #update} and {@link #dispose}, and never waits
 * on the connection: what it sends, it queues. The connection's reader thread answers the panel;
 * when the panel is about to open the menu, the reader asks the UI thread for the rows and waits
 * for them a bounded time, after which the panel is given the rows of the last reading. A worker
 * thread connects, registers and reconnects, which are the calls that wait for an answer.
 */
public final class StatusNotifierTray implements TrayPeer {

    private static final System.Logger LOG = System.getLogger(StatusNotifierTray.class.getName());
    private static final String PROPERTIES = DBus.I_PROPS_NAME;
    private static final String INTROSPECTABLE = "org.freedesktop.DBus.Introspectable";
    private static final String BUS = "org.freedesktop.DBus";
    private static final String BUS_PATH = "/org/freedesktop/DBus";
    private static final String INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs";
    private static final String WATCHER_RULE = "type='signal',sender='" + BUS + "',interface='" + BUS
            + "',member='NameOwnerChanged',arg0='" + TrayWire.WATCHER + "'";

    /** How long the reader waits for the UI thread to read the menu before it answers with the last rows. */
    static final long MENU_WAIT_MILLIS = 400;
    private static final long FIRST_RETRY_MILLIS = 2_000;
    private static final long LONGEST_RETRY_MILLIS = 60_000;

    private final String id;
    private final TrayHost host;
    private final Executor ui;
    private final String address;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final Thread worker;

    private volatile @Nullable TrayState state;
    private volatile List<MenuRow> rows = List.of();
    private volatile DBus.@Nullable Conn connection;
    private volatile boolean registered;
    private volatile boolean disposed;
    private volatile @Nullable String activationToken;
    private int revision = 1;
    private final limn.graphics.Color monochromeTint = TrayWire.panelTint(System.getenv("XDG_CURRENT_DESKTOP"));

    private final java.util.function.Consumer<@Nullable String> acted;

    private StatusNotifierTray(String id, TrayHost host, Executor ui, String address,
            java.util.function.Consumer<@Nullable String> acted) {
        this.id = id;
        this.host = host;
        this.ui = ui;
        this.acted = acted;
        this.address = address;
        this.worker = Threads.daemon("limn-tray-" + id, this::workLoop);
    }

    /**
     * @param id             the application's identifier for the icon
     * @param host           what is asked for the menu and told of a click
     * @param ui             runs a task on the UI thread
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @return the tray's peer, or {@link TrayPeer#NONE} when there is no session bus this client
     *         can reach
     */
    public static TrayPeer create(String id, TrayHost host, Executor ui, @Nullable String sessionAddress) {
        return create(id, host, ui, sessionAddress, token -> { });
    }

    /**
     * @param acted told on the UI thread, just before the host hears of a click on the icon or
     *              on an item of its menu, with the token the panel handed over for it, or null:
     *              the user's consent for a window to come in front
     * @return the tray's peer, or {@link TrayPeer#NONE} when there is no session bus this client
     *         can reach
     */
    public static TrayPeer create(String id, TrayHost host, Executor ui, @Nullable String sessionAddress,
            java.util.function.Consumer<@Nullable String> acted) {
        if (!AtspiStatusWatch.canWatch(sessionAddress)) {
            return TrayPeer.NONE;
        }
        return new StatusNotifierTray(id, host, ui, sessionAddress, acted);
    }

    // ---- the UI thread ------------------------------------------------------------------------

    @Override
    public void update(TrayState next) {
        TrayState before = state;
        if (!disposed && (before == null || !java.util.Objects.equals(before.picture(), next.picture())
                || !java.util.Objects.equals(before.monochrome(), next.monochrome()))) {
            named = writeNamed(next);   // before the state, so that a panel that asks finds the file
        }
        state = next;
        if (disposed) {
            return;
        }
        if (before == null) {
            work.add(this::connect);
            return;
        }
        if (before.activates() != next.activates()) {
            // A panel reads once, when the item registers, whether it can be activated.
            work.add(this::reconnect);
            return;
        }
        DBus.Conn c = connection;
        if (c == null) {
            return;
        }
        boolean picture = !java.util.Objects.equals(before.picture(), next.picture())
                || !java.util.Objects.equals(before.monochrome(), next.monochrome());
        if (picture) {
            signal(c, "NewIcon");
            signal(c, "NewAttentionIcon");
        }
        if (!before.title().equals(next.title())) {
            signal(c, "NewTitle");
        }
        if (!TrayWire.tooltipTitle(before).equals(TrayWire.tooltipTitle(next))) {
            signal(c, "NewToolTip");
        }
        if (before.attention() != next.attention()) {
            send(c, DBus.Msg.signal(TrayWire.ITEM_PATH, TrayWire.ITEM, "NewStatus", "s", TrayWire.status(next)));
        }
    }

    @Override
    public void hide() {
        if (disposed || state == null) {
            return;
        }
        // A panel shows an item for as long as its name is on the bus, whatever its status says,
        // so the connection goes; the next state connects and registers as the first did.
        state = null;
        work.add(this::disconnect);
    }

    @Override
    public boolean isAvailable() {
        return registered && !disposed;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        registered = false;
        worker.interrupt();
        named = null;
        deleteFolder();
        DBus.Conn c = connection;
        if (c != null) {
            c.close();
        }
    }

    /**
     * @return the token a panel last handed this item as the user's consent to bring a window in
     *         front, or {@code null}; for whoever shows a window from a click on the icon
     */
    public @Nullable String activationToken() {
        return activationToken;
    }

    // ---- a shape the panel colours -----------------------------------------------------------

    private volatile TrayWire.@Nullable Named named;
    private @Nullable TrayShapeFiles shapeFiles;

    /**
     * Writes the monochrome icon as a file for a panel that colours a named shape itself, when
     * that is what will be shown: no picture in colour, KDE's panel, a runtime folder the panel
     * can read, which inside a sandbox it cannot, and an encoder for the mask. Each frame of an
     * animated shape is its own file, written once ({@link TrayShapeFiles}).
     *
     * @return the name and the folder, or {@code null} to send pixels only
     */
    private TrayWire.@Nullable Named writeNamed(TrayState next) {
        limn.backend.spi.TrayPicture shape = next.monochrome();
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (next.picture() != null || shape == null || runtime == null
                || !TrayWire.panelColoursNamedShapes(System.getenv("XDG_CURRENT_DESKTOP"))
                || System.getenv("SNAP") != null || java.nio.file.Files.exists(java.nio.file.Path.of("/.flatpak-info"))) {
            deleteFolder();
            return null;
        }
        try {
            TrayShapeFiles files = shapeFiles;
            if (files == null) {
                files = new TrayShapeFiles(id, java.nio.file.Path.of(runtime, "limn-tray"));
                shapeFiles = files;
            }
            return files.write(shape);
        } catch (java.io.IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.DEBUG, "the tray's shape was not written as a file", failed);
            return null;
        }
    }

    private void deleteFolder() {
        TrayShapeFiles files = shapeFiles;
        if (files != null) {
            files.delete();
        }
    }

    /** The token a panel handed over for the click being answered, once: the next click brings its own. */
    private @Nullable String takeToken() {
        String token = activationToken;
        activationToken = null;
        return token;
    }

    private void signal(DBus.Conn c, String name) {
        send(c, DBus.Msg.signal(TrayWire.ITEM_PATH, TrayWire.ITEM, name, null));
    }

    private static void send(DBus.Conn c, DBus.Msg message) {
        try {
            c.send(message);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "a tray's signal was not sent", e);
        }
    }

    // ---- the worker thread ------------------------------------------------------------------

    private void workLoop() {
        try {
            while (!disposed) {
                work.take().run();
            }
        } catch (InterruptedException e) {
            // Disposed.
        }
    }

    private void reconnect() {
        disconnect();
        connect();
    }

    private void disconnect() {
        DBus.Conn c = connection;
        connection = null;
        if (c != null) {
            c.close();
        }
        setRegistered(false);
    }

    /** Connects and registers, trying again later for as long as there is no bus to connect to. */
    private void connect() {
        long wait = FIRST_RETRY_MILLIS;
        while (!disposed) {
            DBus.Conn c = null;
            try {
                c = DBus.Conn.open(address);
                DBus.Conn opened = c;
                c.onSignal(m -> heard(opened, m));
                c.onLost(() -> lost(opened));
                c.hello();
                c.export(TrayWire.ITEM_PATH, this::item);
                c.export(TrayWire.MENU_PATH, this::menu);
                c.callArgs(BUS, BUS_PATH, BUS, "AddMatch", "s", WATCHER_RULE);
                connection = c;
                if (disposed) {
                    c.close();
                    return;
                }
                register(c);
                return;
            } catch (IOException | RuntimeException e) {
                if (c != null) {
                    c.close();
                }
                connection = null;
                LOG.log(System.Logger.Level.DEBUG, "no session bus for the tray yet", e);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(LONGEST_RETRY_MILLIS, wait * 2);
        }
    }

    /** Asks the watcher to take the item; a desktop with none answers that the name has no owner. */
    private void register(DBus.Conn c) {
        boolean taken;
        try {
            c.callArgs(TrayWire.WATCHER, TrayWire.WATCHER_PATH, TrayWire.WATCHER,
                    "RegisterStatusNotifierItem", "s", c.uniqueName);
            taken = true;
        } catch (DBus.DBusError e) {
            taken = false;   // no watcher: this desktop has no tray now
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "the tray's registration got no answer", e);
            taken = false;
        }
        if (c == connection) {
            setRegistered(taken);
        }
    }

    private void setRegistered(boolean now) {
        if (registered != now && !disposed) {
            registered = now;
            ui.execute(host::availabilityChanged);
        }
    }

    private void lost(DBus.Conn c) {
        if (c == connection && !disposed) {
            work.add(this::reconnect);
        }
    }

    // ---- the reader thread --------------------------------------------------------------------

    /** The watcher's name changed hands: a tray appeared on this desktop, or went. */
    private void heard(DBus.Conn c, DBus.Msg m) {
        if (!"NameOwnerChanged".equals(m.member) || m.body.length < 3 || !TrayWire.WATCHER.equals(m.body[0])) {
            return;
        }
        boolean owned = !String.valueOf(m.body[2]).isEmpty();
        if (owned) {
            work.add(() -> register(c));   // a call that waits, so not on this thread
        } else if (c == connection) {
            setRegistered(false);
        }
    }

    /** {@code -Dlimn.tray.trace=true}: every call a panel makes, on standard error. */
    private static final boolean TRACE = Boolean.getBoolean("limn.tray.trace");

    private static void trace(DBus.Msg m) {
        if (TRACE) {
            System.err.println("[tray] " + System.currentTimeMillis() + " " + m.iface + "." + m.member + " "
                    + DBus.fmt(m.body));
        }
    }

    private DBus.@Nullable Msg item(DBus.Conn c, DBus.Msg m) {
        trace(m);
        TrayState now = state;
        if (now == null) {
            return null;
        }
        if (INTROSPECTABLE.equals(m.iface)) {
            return DBus.Msg.ret(m, "s", TrayWire.itemIntrospection(now.activates()));
        }
        if (PROPERTIES.equals(m.iface)) {
            Map<String, DBus.Variant> properties = TrayWire.itemProperties(now, id, monochromeTint, named);
            if ("GetAll".equals(m.member)) {
                return DBus.Msg.ret(m, "a{sv}", properties);
            }
            if ("Get".equals(m.member) && m.body.length >= 2) {
                DBus.Variant value = properties.get(String.valueOf(m.body[1]));
                return value == null ? DBus.Msg.err(m, INVALID_ARGS, "no such property") : DBus.Msg.ret(m, "v", value);
            }
            return null;
        }
        if (!TrayWire.ITEM.equals(m.iface)) {
            return null;
        }
        switch (m.member) {
            case "ProvideXdgActivationToken" -> {
                if (m.body.length > 0) {
                    activationToken = String.valueOf(m.body[0]);
                }
            }
            case "Activate" -> {
                if (!now.activates()) {
                    return null;   // an item that is a menu has no activation to call
                }
                String token = takeToken();
                ui.execute(() -> {
                    acted.accept(token);
                    host.activate();
                });
            }
            default -> {
                // A secondary activation, a scroll, a context menu from a panel that draws none:
                // answered, and nothing done.
            }
        }
        return DBus.Msg.ret(m, null);
    }

    private DBus.@Nullable Msg menu(DBus.Conn c, DBus.Msg m) {
        trace(m);
        TrayState now = state;
        if (now == null) {
            return null;
        }
        if (PROPERTIES.equals(m.iface)) {
            Map<String, DBus.Variant> properties = TrayWire.menuProperties(now.rightToLeft());
            if ("GetAll".equals(m.member)) {
                return DBus.Msg.ret(m, "a{sv}", properties);
            }
            if ("Get".equals(m.member) && m.body.length >= 2) {
                DBus.Variant value = properties.get(String.valueOf(m.body[1]));
                return value == null ? DBus.Msg.err(m, INVALID_ARGS, "no such property") : DBus.Msg.ret(m, "v", value);
            }
            return null;
        }
        if (!TrayWire.MENU.equals(m.iface)) {
            return null;
        }
        switch (m.member) {
            case "GetLayout": {
                if (rows.isEmpty()) {
                    refresh(c, now);   // the first asking: there is no reading yet
                }
                Object[] layout = TrayWire.layout((Integer) m.body[0], (Integer) m.body[1], rows);
                return layout == null ? DBus.Msg.err(m, INVALID_ARGS, "no such row")
                        : DBus.Msg.ret(m, "u(ia{sv}av)", revision, layout);
            }
            case "GetGroupProperties": {
                List<Object> out = new ArrayList<>();
                List<?> ids = (List<?>) m.body[0];
                if (ids.isEmpty()) {
                    collect(rows, out);
                }
                for (Object rowId : ids) {
                    MenuRow row = TrayWire.find(rows, (Integer) rowId);
                    if (row != null) {
                        out.add(new Object[] {row.id(), TrayWire.rowProperties(row)});
                    }
                }
                return DBus.Msg.ret(m, "a(ia{sv})", out);
            }
            case "GetProperty": {
                MenuRow row = TrayWire.find(rows, (Integer) m.body[0]);
                DBus.Variant value = row == null ? null : TrayWire.rowProperties(row).get(String.valueOf(m.body[1]));
                return value == null ? DBus.Msg.err(m, INVALID_ARGS, "no such property") : DBus.Msg.ret(m, "v", value);
            }
            case "Event":
                event((Integer) m.body[0], String.valueOf(m.body[1]));
                return DBus.Msg.ret(m, null);
            case "EventGroup": {
                for (Object each : (List<?>) m.body[0]) {
                    Object[] event = each instanceof Object[] array ? array : ((List<?>) each).toArray();
                    event((Integer) event[0], String.valueOf(event[1]));
                }
                return DBus.Msg.ret(m, "ai", List.of());
            }
            case "AboutToShow":
                return DBus.Msg.ret(m, "b", refresh(c, now));
            case "AboutToShowGroup": {
                boolean changed = refresh(c, now);
                return DBus.Msg.ret(m, "aiai", changed ? m.body[0] : List.of(), List.of());
            }
            default:
                return null;
        }
    }

    private static void collect(List<MenuRow> from, List<Object> out) {
        for (MenuRow row : from) {
            out.add(new Object[] {row.id(), TrayWire.rowProperties(row)});
            collect(row.children(), out);
        }
    }

    private void event(int rowId, String what) {
        // The row that stands for a menu nobody set is not one of the application's.
        if ("clicked".equals(what) && !placeholder) {
            String token = takeToken();
            ui.execute(() -> {
                acted.accept(token);
                host.choose(rowId);
            });
        }
    }

    private volatile boolean placeholder;

    /**
     * Reads the menu again on the UI thread and, when it is not what the panel was last given,
     * tells the panel its layout is another.
     *
     * @return whether the rows changed
     */
    private boolean refresh(DBus.Conn c, TrayState now) {
        CompletableFuture<List<MenuRow>> reading = new CompletableFuture<>();
        ui.execute(() -> {
            try {
                reading.complete(host.menu());
            } catch (RuntimeException | Error failed) {
                reading.completeExceptionally(failed);
            }
        });
        List<MenuRow> read;
        try {
            read = reading.get(MENU_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "the UI thread did not read the tray's menu in time", e);
            return false;
        }
        placeholder = read.isEmpty();
        List<MenuRow> next = TrayWire.shown(read, now.title());
        if (next.equals(rows)) {
            return false;
        }
        rows = next;
        revision++;
        send(c, DBus.Msg.signal(TrayWire.MENU_PATH, TrayWire.MENU, "LayoutUpdated", "ui", revision, 0));
        return true;
    }
}
