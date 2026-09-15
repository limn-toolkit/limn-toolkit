package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Answers AT-SPI2's interfaces for every window of the process, out of the snapshots their scenes
 * last published.
 *
 * <p><b>Every answer is computed from immutable trees, on the reader thread, with no hop.</b>
 * That is what makes the two rules of that thread satisfiable at all: a handler must not block and
 * must never make a blocking call on the same connection, and reading a snapshot does neither. No
 * widget is touched here and no lock is taken; the only mutable things in sight are the table of
 * windows, which is copy-on-write, and each window's reference to its current tree, which the
 * user-interface thread replaces with one volatile write.
 *
 * <p><b>The application object is ours and the windows are the trees'.</b> AT-SPI expects one
 * application at the root of what a connection exports, with its windows beneath it as frames
 * (ADR 039 §2.3); the toolkit publishes a window node per scene and knows nothing of applications.
 * So the root path answers for a synthetic application whose children are the node zero of every
 * window that has published one, in the order the windows joined, and every other path is a node
 * id. Ids are process-wide (§1.3), so a path names one node in one window, a relation may name a
 * node in another — a native popup's root names the field that opened it — and the path a client
 * is holding stays the path of the thing it was holding.
 */
final class AtspiTree {

    /** {@code -Dlimn.a11y.linux.trace=true}: log every inbound call. Off by default and free. */
    private static final boolean TRACE = Boolean.getBoolean("limn.a11y.linux.trace");

    /** Where a node's object path begins; the id follows. */
    private static final String NODE_PREFIX = "/org/a11y/atspi/accessible/";

    /** One window the application holds: the snapshot it last published and what acts on it. */
    interface Window {
        /** @return the snapshot every answer about this window is read from; never null */
        AccessibleTree tree();

        /** @return what performs a verb on this window's nodes, or null while detached */
        AccessibilityBridge.Host host();
    }

    /** A node together with the snapshot that holds it and the window that published that. */
    private record Located(AccessibleTree tree, Window window, AccessibleNode node) {
    }

    private final Supplier<? extends List<? extends Window>> windows;
    private final Supplier<String> applicationName;
    private volatile String busName = "";
    private volatile DBus.Ref desktop;

    /**
     * @param windows         every window the application holds, in the order they joined; read
     *                        on the reader thread, so the list must be safe to iterate there
     * @param applicationName what the application object is called, read on every ask
     */
    AtspiTree(Supplier<? extends List<? extends Window>> windows, Supplier<String> applicationName) {
        this.windows = windows;
        this.applicationName = applicationName;
    }

    /** Our own name on the accessibility bus, which every object reference carries. */
    void busName(String name) {
        this.busName = name;
    }

    /** The registry's socket, learnt from {@code Socket.Embed}; the application's parent. */
    void desktop(DBus.Ref ref) {
        this.desktop = ref;
    }

    DBus.Ref rootRef() {
        return new DBus.Ref(busName, Atspi.PATH_ROOT);
    }

    /** The reference a node's object has on this bus, whichever window holds it. */
    DBus.Ref refOf(long id) {
        return new DBus.Ref(busName, NODE_PREFIX + id);
    }

    DBus.Ref nullRef() {
        return new DBus.Ref(busName, Atspi.PATH_NULL);
    }

    /** The node a path names, or {@code null} for the application object and for anything else. */
    private Located nodeOf(String path) {
        if (path == null || !path.startsWith(NODE_PREFIX)) {
            return null;
        }
        String tail = path.substring(NODE_PREFIX.length());
        try {
            return locate(Long.parseLong(tail));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The window holding a node, and the node.
     *
     * <p>The identifier's scene tag says which window minted it ({@link AccessibleTree#holds}), so
     * the ordinary lookup is one tag comparison per window and one find. A tree built by hand, whose
     * identifiers carry no tag, is still found by asking each window in turn.
     *
     * @return the node and where it lives, or {@code null} when no window's snapshot has it
     */
    private Located locate(long id) {
        List<? extends Window> all = windows.get();
        for (Window window : all) {
            AccessibleTree tree = window.tree();
            if (tree.holds(id)) {
                AccessibleNode node = tree.find(id);
                return node == null ? null : new Located(tree, window, node);
            }
        }
        for (Window window : all) {
            AccessibleTree tree = window.tree();
            AccessibleNode node = tree.find(id);
            if (node != null) {
                return new Located(tree, window, node);
            }
        }
        return null;
    }

    /**
     * The application object's children: every window's snapshot that has a node zero, in the order
     * the windows joined. A window that has not published yet, or that published nothing, is not a
     * frame a client can enter, and listing it would hand the client a path that answers nothing.
     */
    private List<Frame> frames() {
        List<Frame> out = new ArrayList<>();
        for (Window window : windows.get()) {
            AccessibleTree tree = window.tree();
            if (tree.nodeCount() > 0) {
                out.add(new Frame(window, tree));
            }
        }
        return out;
    }

    /** One of the application's children: a window, and the snapshot it was read at. */
    private record Frame(Window window, AccessibleTree tree) {
    }

    /**
     * Where a window sits among the application's frames, or -1 when it is none. By the window and
     * not by its snapshot, because the user-interface thread may have published a newer one between
     * the two reads.
     */
    private static int frameIndexOf(List<Frame> frames, Window window) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).window() == window) {
                return i;
            }
        }
        return -1;
    }

    /** Where a window's own node zero sits among the application's children, or 0 when unknown. */
    private int frameIndexOf(Window window) {
        return Math.max(0, frameIndexOf(frames(), window));
    }

    private static boolean isRoot(String path) {
        return Atspi.PATH_ROOT.equals(path);
    }

    /**
     * The one handler: every inbound call on every accessible path arrives here.
     *
     * @return the reply, or {@code null} when the path names nothing this window exports
     */
    DBus.Msg handle(DBus.Conn conn, DBus.Msg m) {
        String iface = m.iface == null ? "" : m.iface;
        if (TRACE) {
            // Every inbound call, so a desktop that refuses this application can be asked what it
            // wanted rather than guessed at. Two of the three platforms have now produced a defect
            // whose only symptom was silence, and a trace is what turns that into a question.
            StringBuilder args = new StringBuilder();
            for (Object arg : m.body) {
                if (args.length() > 0) args.append(", ");
                args.append(arg);
            }
            System.out.println("[atspi] " + m.path + "  " + iface + "." + m.member
                    + "(" + args + ")");
            System.out.flush();
        }
        // Before any path is resolved, because a ping is about the CONNECTION and not about an
        // object: the registry sends it to "/", which is neither the application root nor a node,
        // so the lookup below would refuse it.
        //
        // <b>That refusal is what made this application invisible on Fedora.</b> at-spi2-core 2.60
        // added "detect unresponsive applications, and do not expose them as children of the
        // desktop", and an unanswered ping is exactly what it detects. The symptom is peculiar
        // enough to be worth writing down: the registry goes on talking to the application
        // perfectly -- hundreds of calls, a full Cache.GetItems, no error anywhere -- while no
        // client can see it at all, because being hidden from the desktop's children is not a
        // refusal, it is an omission. Ubuntu's 2.52 does not ping, so this was invisible there.
        if (Atspi.I_PEER.equals(iface)) {
            if ("Ping".equals(m.member)) {
                return DBus.Msg.ret(m, null);
            }
            return null;
        }
        if (Atspi.PATH_CACHE.equals(m.path)) {
            return cache(m, iface);
        }
        boolean root = isRoot(m.path);
        Located at = root ? null : nodeOf(m.path);
        if (!root && at == null) {
            return departed(m, iface);
        }
        AccessibleNode node = at == null ? null : at.node();
        if (Atspi.I_PROPS.equals(iface)) {
            return properties(m, root, at);
        }
        if (Atspi.I_ACCESSIBLE.equals(iface)) {
            return accessible(m, root, at);
        }
        if (Atspi.I_COMPONENT.equals(iface)) {
            // The application object answers Component too. A client asks the root for its extents
            // before it walks anything, and an error there stops the walk at the first step rather
            // than degrading: libatspi reports the failure and abandons the subtree.
            return at == null ? applicationComponent(m) : component(m, at);
        }
        if (Atspi.I_ACTION.equals(iface) && at != null) {
            return action(m, at);
        }
        if (Atspi.I_TABLE.equals(iface) && at != null && node.table() != null) {
            return table(m, at);
        }
        if (Atspi.I_TABLE_CELL.equals(iface) && at != null && node.cell() != null) {
            return tableCell(m, at);
        }
        if (Atspi.I_SELECTION.equals(iface) && at != null && node.selection() != null) {
            return selection(m, at);
        }
        if (Atspi.I_EDITABLE_TEXT.equals(iface) && at != null && isEditableText(node)) {
            return editableText(m, at);
        }
        if (Atspi.I_TEXT.equals(iface) && at != null) {
            AtspiText.Source source = AtspiText.of(node);
            return source == null ? null : text(m, at, source);
        }
        if (Atspi.I_APPLICATION.equals(iface) && root) {
            return application(m);
        }
        return null;
    }

    /**
     * A call on a node path no window's snapshot holds any more: {@code GetState} answers
     * {@code DEFUNCT} alone, and everything else is declined as before.
     *
     * <p>A node leaves with a {@code ChildrenChanged remove} from its parent, a
     * {@code Cache.RemoveAccessible} and a {@code StateChanged defunct} from its own path
     * (LINUX-NEW-1), but a client may still hold the reference and ask. GTK 4.22.4 marks a context
     * defunct before it unregisters it; Orca 50.2 ignores an event whose source has
     * {@code DEFUNCT} or whose name cannot be read ({@code _handle_early_event_processing},
     * {@code is_dead}: readings/fedora-orca-dead-object.txt). Declining the name keeps the second
     * test true; answering the state makes the first true for a client that asks it. Only a path
     * that parses as a node id: anything else was never ours.
     */
    private static DBus.Msg departed(DBus.Msg m, String iface) {
        if (m.path == null || !m.path.startsWith(NODE_PREFIX) || !Atspi.I_ACCESSIBLE.equals(iface)
                || !"GetState".equals(m.member)) {
            return null;
        }
        try {
            Long.parseLong(m.path.substring(NODE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        return DBus.Msg.ret(m, "au", Atspi.stateWords(Atspi.state(AtspiStates.DEFUNCT)));
    }

    /**
     * The whole tree in one message, which is how a client on this platform starts.
     *
     * <p>{@code libatspi} asks for this before it walks anything, and an application that cannot
     * answer is walked one call per node per property instead -- if it is walked at all. The items
     * are built from the same snapshot every other answer comes from, so the cache a client holds
     * and the answers it would get node by node cannot disagree.
     */
    /**
     * The application object's own methods, of which there is one that matters and it is newer than
     * this bridge.
     *
     * <p><b>{@code GetApplicationBusAddress} is what at-spi2-core 2.56 and later ask before they
     * will list an application, and answering nothing is not the same as answering "none".</b> On
     * Fedora 44 with at-spi2-core 2.60 the registry read this bridge perfectly — over a hundred
     * calls, a full {@code Cache.GetItems}, roles and states — and no {@code libatspi} client would
     * list the application at all, Orca included. The clients ask this first; an unknown method is
     * an error, and an application that errors here is one they drop. Ubuntu's 2.52 never asks, so
     * the whole platform looked correct for a year of runs.
     *
     * <p>The empty string is the answer that means "I have no bus of my own; talk to me on the
     * accessibility bus" — which is true, and is the whole of what this bridge wants. The feature it
     * declines is an application vending a private bus for its own subtree.
     *
     * @param m the call
     * @return its reply, or {@code null} for a member this object does not have
     */
    private DBus.Msg application(DBus.Msg m) {
        if ("GetApplicationBusAddress".equals(m.member)) {
            return DBus.Msg.ret(m, "s", "");
        }
        return null;
    }

    private DBus.Msg cache(DBus.Msg m, String iface) {
        if (Atspi.I_PEER.equals(iface) && "Ping".equals(m.member)) {
            return DBus.Msg.ret(m, null);
        }
        if (!Atspi.I_CACHE.equals(iface) || !"GetItems".equals(m.member)) {
            return null;
        }
        List<Frame> frames = frames();
        List<Object> items = new ArrayList<>();
        items.add(new Object[] {
                rootRef().toStruct(), rootRef().toStruct(), desktopOrNull().toStruct(),
                -1, frames.size(),
                new ArrayList<Object>(List.of(Atspi.I_ACCESSIBLE, Atspi.I_APPLICATION,
                        Atspi.I_COMPONENT)),
                applicationName.get(), Atspi.ROLE_APPLICATION, "",
                Atspi.stateWords(AtspiStates.setOf(s -> s == Accessible.State.ENABLED)),
        });
        for (int f = 0; f < frames.size(); f++) {
            AccessibleTree tree = frames.get(f).tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                items.add(cacheItem(tree, tree.node(i), f));
            }
        }
        return DBus.Msg.ret(m, Atspi.CACHE_ITEMS, items);
    }

    /**
     * One node's {@code ((so)(so)(so)iiassusau)} item: what {@code Cache.GetItems} lists for it and
     * what {@code Cache.AddAccessible} announces, built by one method so the two cannot differ.
     *
     * @param frame the window's place among the application's frames, for a node zero
     */
    private Object[] cacheItem(AccessibleTree tree, AccessibleNode node, int frame) {
        return new Object[] {
                refOf(node.id()).toStruct(), rootRef().toStruct(),
                parentRef(tree, node).toStruct(),
                node.parent() < 0 ? frame : tree.indexInParent(node),
                tree.children(node).size(),
                new ArrayList<>(interfacesOf(false, node)),
                node.name(), roleOf(node), node.description(),
                Atspi.stateWords(statesOf(node)),
        };
    }

    /**
     * The cache item a node has now, in whichever window holds it, for {@code Cache.AddAccessible}.
     *
     * @param id the node
     * @return its item, or {@code null} when no window's snapshot holds it
     */
    Object[] cacheItemOf(long id) {
        Located at = locate(id);
        return at == null ? null : cacheItem(at.tree(), at.node(), frameIndexOf(at.window()));
    }

    /**
     * The application's own rectangle: its first frame's, or nothing before any window has
     * published. The application object has no geometry of its own; a client asks for it only to
     * learn that the walk may go on, and the first window is what it walks into.
     */
    private DBus.Msg applicationComponent(DBus.Msg m) {
        List<Frame> frames = frames();
        AccessibleTree tree = frames.isEmpty() ? null : frames.get(0).tree();
        int[] box = tree == null
                ? new int[] {0, 0, 0, 0}
                : extentsOf(tree, tree.node(0),
                        m.body.length > 0 ? coordOf(m.body[0]) : Atspi.COORD_SCREEN);
        return switch (m.member == null ? "" : m.member) {
            case "GetExtents" -> DBus.Msg.ret(m, "(iiii)",
                    (Object) new Object[] {box[0], box[1], box[2], box[3]});
            case "GetPosition" -> DBus.Msg.ret(m, "(ii)", (Object) new Object[] {box[0], box[1]});
            case "GetSize" -> DBus.Msg.ret(m, "(ii)", (Object) new Object[] {box[2], box[3]});
            case "GetLayer" -> DBus.Msg.ret(m, "u", Atspi.LAYER_WINDOW);
            case "GetMDIZOrder" -> DBus.Msg.ret(m, "n", (short) 0);
            case "GetAlpha" -> DBus.Msg.ret(m, "d", 1.0d);
            default -> null;
        };
    }

    /**
     * Where a node sits among its siblings, in whichever window holds it: what
     * {@code GetIndexInParent} on its path answers, so an event and a call can never disagree.
     *
     * @param id the node
     * @return its index, or -1 when no window's snapshot holds it
     */
    int indexInParentOf(long id) {
        Located at = locate(id);
        return at == null ? -1 : indexInParent(at);
    }

    /**
     * Where a located node sits among its siblings. A window's own node zero answers its place
     * among the application object's frames, which is where it is on this platform.
     */
    private int indexInParent(Located at) {
        return at.node().parent() < 0 ? frameIndexOf(at.window())
                                      : at.tree().indexInParent(at.node());
    }

    // ------------------------------------------------------------------ org.freedesktop.DBus.Properties

    private DBus.Msg properties(DBus.Msg m, boolean root, Located at) {
        String which = m.body.length > 0 ? String.valueOf(m.body[0]) : "";
        Map<Object, Object> all = propertiesOf(which, root, at);
        if ("GetAll".equals(m.member)) {
            return DBus.Msg.ret(m, "a{sv}", all);
        }
        if ("Get".equals(m.member)) {
            Object value = all.get(String.valueOf(m.body[1]));
            if (value == null) {
                if (TRACE) {
                    System.out.println("[atspi]   REFUSED " + which + "." + m.body[1]);
                    System.out.flush();
                }
                return DBus.Msg.err(m, DBus.Conn.INVALID_ARGS,
                        "no property " + which + "." + m.body[1]);
            }
            return DBus.Msg.ret(m, "v", value);
        }
        if ("Set".equals(m.member)) {
            if (Atspi.I_VALUE.equals(which) && at != null && at.node().value() != null) {
                return setValue(m, at);
            }
            // The registry assigns the application its id straight after Embed. Accepting and
            // discarding it is honest: nothing here reads it back, and refusing would leave the
            // registry believing the application never took the number it handed out.
            return DBus.Msg.ret(m, null);
        }
        return null;
    }

    /**
     * {@code Properties.Set(Value, CurrentValue, v)}: the one writable property the bridge serves,
     * and how libatspi 2.60.6's {@code atspi_value_set_current_value} writes a value (a variant
     * {@code d}, whose error reply reaches the caller's {@code GError};
     * readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt).
     *
     * <p>Posted as {@code SET_VALUE} with the number only where the node accepts it now — a
     * writable facet on an {@code ENABLED} node (semantics 5 as amended 2026-09-15) — and refused
     * with {@code org.freedesktop.DBus.Error.Failed} otherwise, or for any other property of
     * Value, which are all read-only in the installed XML. A refusal is an error and not a silent
     * success: the caller would otherwise believe a read-only progress bar took the number.
     */
    private DBus.Msg setValue(DBus.Msg m, Located at) {
        String property = m.body.length > 1 ? String.valueOf(m.body[1]) : "";
        Object given = m.body.length > 2 && m.body[2] instanceof DBus.Variant v ? v.value : null;
        if (!"CurrentValue".equals(property) || !(given instanceof Number number)) {
            return DBus.Msg.err(m, DBus.Conn.FAILED, "Value." + property + " cannot be set");
        }
        if (!performFirst(at, at.node(), new Accessible.Argument.OfValue(number.doubleValue()),
                Accessible.Action.SET_VALUE)) {
            return DBus.Msg.err(m, DBus.Conn.FAILED, "the value of this object is not settable now");
        }
        return DBus.Msg.ret(m, null);
    }

    private Map<Object, Object> propertiesOf(String which, boolean root, Located at) {
        Map<Object, Object> out = new LinkedHashMap<>();
        AccessibleNode node = at == null ? null : at.node();
        if (Atspi.I_ACTION.equals(which)) {
            // A property and not only the GetNActions method: libatspi reads the count through
            // org.freedesktop.DBus.Properties, so a bridge that answers the method alone reports
            // no verbs to every client while happily performing the ones it says it has not got.
            //
            // Answered for the application object too, where it is zero. Fedora's newer registry
            // asks the root for it, and refusing a property is an error reply on a conversation
            // that had none -- "this object performs nothing" is both true and quieter.
            out.put("NActions", new DBus.Variant("i", node == null ? 0 : verbsOf(node).size()));
            return out;
        }
        if (Atspi.I_APPLICATION.equals(which)) {
            out.put("ToolkitName", new DBus.Variant("s", "Limn"));
            out.put("Version", new DBus.Variant("s", "1"));
            out.put("AtspiVersion", new DBus.Variant("s", "2.1"));
            out.put("Id", new DBus.Variant("i", 0));
            return out;
        }
        if (Atspi.I_TABLE.equals(which) && node != null && node.table() != null) {
            AccessibleTree tree = at.tree();
            out.put("NRows", new DBus.Variant("i", node.table().rowCount()));
            out.put("NColumns", new DBus.Variant("i", node.table().columnCount()));
            out.put("Caption", new DBus.Variant("(so)", nullRef().toStruct()));
            out.put("Summary", new DBus.Variant("(so)", nullRef().toStruct()));
            out.put("NSelectedRows", new DBus.Variant("i", selectedRowsOf(tree, node).size()));
            out.put("NSelectedColumns", new DBus.Variant("i", 0));
            return out;
        }
        if (Atspi.I_VALUE.equals(which) && node != null && node.value() != null) {
            // Every one a double, as libatspi 2.60.6 reads them, and Text a string
            // (atspi-value.c; readings/fedora-dbus-Value.xml). CurrentValue is mandatory: an empty
            // value answers its minimum, which the facet already carries (decision 16), and says
            // empty through Text and the events.
            limn.accessibility.ValueFacet value = node.value();
            out.put("MinimumValue", new DBus.Variant("d", value.min()));
            out.put("MaximumValue", new DBus.Variant("d", value.max()));
            out.put("MinimumIncrement", new DBus.Variant("d", value.step()));
            out.put("CurrentValue", new DBus.Variant("d", value.value()));
            out.put("Text", new DBus.Variant("s", value.text() == null ? "" : value.text()));
            return out;
        }
        if (Atspi.I_TEXT.equals(which) && node != null && AtspiText.of(node) != null) {
            // Both properties, "i" each, in characters (atspi-text.c reads them through Get).
            AtspiText.Source source = AtspiText.of(node);
            out.put("CharacterCount", new DBus.Variant("i", source.length()));
            out.put("CaretOffset", new DBus.Variant("i",
                    AtspiText.charsOf(source.text(), source.caret())));
            return out;
        }
        if (Atspi.I_SELECTION.equals(which) && node != null && node.selection() != null) {
            // A property, not a method: libatspi 2.60.6 reads it through Properties.Get, "i"
            // (atspi-selection.c, readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt).
            out.put("NSelectedChildren",
                    new DBus.Variant("i", selectedMembersOf(at.tree(), node).size()));
            return out;
        }
        if (Atspi.I_TABLE_CELL.equals(which) && node != null && node.cell() != null) {
            AccessibleTree tree = at.tree();
            AccessibleNode table = tableOf(tree, node);
            out.put("ColumnSpan", new DBus.Variant("i", 1));
            out.put("RowSpan", new DBus.Variant("i", 1));
            out.put("Position", new DBus.Variant("(ii)",
                    (Object) new Object[] {node.cell().row(), node.cell().column()}));
            out.put("Table", new DBus.Variant("(so)",
                    (table == null ? nullRef() : refOf(table.id())).toStruct()));
            return out;
        }
        if (!Atspi.I_ACCESSIBLE.equals(which)) {
            return out;
        }
        if (root) {
            out.put("Name", new DBus.Variant("s", applicationName.get()));
            out.put("Description", new DBus.Variant("s", ""));
            out.put("Parent", new DBus.Variant("(so)", desktopOrNull().toStruct()));
            out.put("ChildCount", new DBus.Variant("i", frames().size()));
            out.put("Locale", new DBus.Variant("s", ""));
            out.put("AccessibleId", new DBus.Variant("s", ""));
            out.put("HelpText", new DBus.Variant("s", ""));
            return out;
        }
        AccessibleTree tree = at.tree();
        out.put("Name", new DBus.Variant("s", node.name()));
        out.put("Description", new DBus.Variant("s", node.description()));
        out.put("Parent", new DBus.Variant("(so)", parentRef(tree, node).toStruct()));
        out.put("ChildCount", new DBus.Variant("i", tree.children(node).size()));
        out.put("Locale", new DBus.Variant("s",
                node.locale() == null ? "" : node.locale().toLanguageTag()));
        out.put("AccessibleId", new DBus.Variant("s", Long.toString(node.id())));
        // Empty, and deliberately not the description. AT-SPI2 2.52 added HelpText beside
        // Description as a SECOND string, and a bridge that answered the same text in both would
        // have a reader say it twice. This toolkit publishes one description per node; when a
        // widget grows something that is genuinely help rather than description, it goes here.
        out.put("HelpText", new DBus.Variant("s", ""));
        return out;
    }

    private DBus.Ref desktopOrNull() {
        DBus.Ref d = desktop;
        return d == null ? nullRef() : d;
    }

    private DBus.Ref parentRef(AccessibleTree tree, AccessibleNode node) {
        int parent = node.parent();
        if (parent < 0 || parent >= tree.nodeCount()) {
            return rootRef();  // node zero's parent is the application object
        }
        return refOf(tree.node(parent).id());
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Accessible

    private DBus.Msg accessible(DBus.Msg m, boolean root, Located at) {
        AccessibleNode node = at == null ? null : at.node();
        switch (m.member == null ? "" : m.member) {
            case "GetChildren": {
                List<Object> kids = new ArrayList<>();
                for (AccessibleNode k : childrenOf(root, at)) {
                    kids.add(refOf(k.id()).toStruct());
                }
                return DBus.Msg.ret(m, "a(so)", kids);
            }
            case "GetChildAtIndex": {
                int i = ((Number) m.body[0]).intValue();
                List<AccessibleNode> kids = childrenOf(root, at);
                DBus.Ref ref = i >= 0 && i < kids.size() ? refOf(kids.get(i).id()) : nullRef();
                return DBus.Msg.ret(m, "(so)", (Object) ref.toStruct());
            }
            case "GetIndexInParent": {
                if (root) {
                    return DBus.Msg.ret(m, "i", -1);
                }
                return DBus.Msg.ret(m, "i", indexInParent(at));
            }
            case "GetRole":
                return DBus.Msg.ret(m, "u", root ? Atspi.ROLE_APPLICATION : roleOf(node));
            case "GetRoleName":
            case "GetLocalizedRoleName":
                return DBus.Msg.ret(m, "s", root ? "application" : roleNameOf(node));
            case "GetState":
                return DBus.Msg.ret(m, "au",
                        Atspi.stateWords(root ? Atspi.state(Atspi.STATE_ENABLED) : statesOf(node)));
            case "GetAttributes":
                return DBus.Msg.ret(m, "a{ss}", Atspi.attrs("toolkit", "limn"));
            case "GetApplication":
                return DBus.Msg.ret(m, "(so)", (Object) rootRef().toStruct());
            case "GetInterfaces":
                return DBus.Msg.ret(m, "as", interfacesOf(root, node));
            case "GetRelationSet":
                return DBus.Msg.ret(m, "a(ua(so))",
                        root ? new ArrayList<>() : relationSetOf(node));
            default:
                return null;
        }
    }

    /**
     * The children a path has: the application object's are every frame's node zero, a node's are
     * its own snapshot's.
     */
    private List<AccessibleNode> childrenOf(boolean root, Located at) {
        if (!root) {
            return at.tree().children(at.node());
        }
        List<AccessibleNode> out = new ArrayList<>();
        for (Frame frame : frames()) {
            out.add(frame.tree().node(0));
        }
        return out;
    }

    /**
     * The node's relations as {@code a(ua(so))}: one entry per relation type this platform has a
     * number for, holding every target of that type. A target is a node a publish resolved, so it
     * is on the bus under its own id; one that no window holds any more is skipped rather than
     * named, because a path that answers {@code UnknownObject} is worse than no relation.
     *
     * <p>A target may live in another window of this process: a native popup's root is
     * {@code POPUP_FOR} the field that opened it, which its owner's scene published. Every window
     * is a frame of the one application object here, so that target is an ordinary reference on
     * this connection and is named as one (ADR 039 §1.11, §2.3).
     */
    private List<Object> relationSetOf(AccessibleNode node) {
        List<Object> out = new ArrayList<>();
        if (node.relations().isEmpty()) {
            return out;
        }
        Map<Integer, List<Object>> byType = new LinkedHashMap<>();
        for (AccessibleRelation relation : node.relations()) {
            Integer type = AtspiRelations.of(relation.kind());
            if (type == null || locate(relation.target()) == null) {
                continue;
            }
            byType.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(refOf(relation.target()).toStruct());
        }
        for (Map.Entry<Integer, List<Object>> entry : byType.entrySet()) {
            out.add(new Object[] {entry.getKey(), entry.getValue()});
        }
        return out;
    }

    private static int roleOf(AccessibleNode node) {
        Integer mapped = AtspiRoles.of(node.role());
        return mapped == null ? Atspi.ROLE_INVALID : mapped;
    }

    private static String roleNameOf(AccessibleNode node) {
        return AtspiRoles.nameOf(node.role());
    }

    private static long statesOf(AccessibleNode node) {
        return AtspiStates.setOf(node::has);
    }

    private static List<Object> interfacesOf(boolean root, AccessibleNode node) {
        List<Object> out = new ArrayList<>();
        out.add(Atspi.I_ACCESSIBLE);
        if (root) {
            out.add(Atspi.I_APPLICATION);
            return out;
        }
        out.add(Atspi.I_COMPONENT);
        if (node.actions() != null && !node.actions().actions().isEmpty()) {
            out.add(Atspi.I_ACTION);
        }
        // Named here and in the cache item alike, from the same facets: a client that reads the
        // cache and never calls GetInterfaces sees only what the cache said (ADR 041 §7).
        if (node.table() != null) {
            out.add(Atspi.I_TABLE);
        }
        if (node.cell() != null) {
            out.add(Atspi.I_TABLE_CELL);
        }
        if (node.selection() != null) {
            out.add(Atspi.I_SELECTION);
        }
        if (node.value() != null) {
            out.add(Atspi.I_VALUE);
        }
        if (AtspiText.of(node) != null) {
            out.add(Atspi.I_TEXT);
        }
        if (isEditableText(node)) {
            out.add(Atspi.I_EDITABLE_TEXT);
        }
        // An interface is named from the facet alone, never from whether its setter is accepted
        // now: a disabled field is still a field with a value. Every setter these serve posts
        // only what AccessibleNode#accepts allows (performFirst), a node without ENABLED
        // refused, as fix round 2e settled (semantics 5, amended 2026-09-15).
        return out;
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Component

    private DBus.Msg component(DBus.Msg m, Located at) {
        AccessibleTree tree = at.tree();
        AccessibleNode node = at.node();
        int[] box = extentsOf(tree, node, m.body.length > 0 ? coordOf(m.body[0]) : Atspi.COORD_SCREEN);
        switch (m.member == null ? "" : m.member) {
            case "GetExtents":
                return DBus.Msg.ret(m, "(iiii)",
                        (Object) new Object[] {box[0], box[1], box[2], box[3]});
            case "GetPosition":
                return DBus.Msg.ret(m, "(ii)", (Object) new Object[] {box[0], box[1]});
            case "GetSize":
                return DBus.Msg.ret(m, "(ii)", (Object) new Object[] {box[2], box[3]});
            case "GetLayer":
                return DBus.Msg.ret(m, "u", Atspi.LAYER_WIDGET);
            case "GetMDIZOrder":
                return DBus.Msg.ret(m, "n", (short) 0);
            case "GetAlpha":
                return DBus.Msg.ret(m, "d", 1.0d);
            case "Contains": {
                int x = ((Number) m.body[0]).intValue();
                int y = ((Number) m.body[1]).intValue();
                int[] b = extentsOf(tree, node, coordOf(m.body[2]));
                boolean in = x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3];
                return DBus.Msg.ret(m, "b", in);
            }
            default:
                return null;
        }
    }

    private static int coordOf(Object v) {
        return v instanceof Number n ? n.intValue() : Atspi.COORD_SCREEN;
    }

    /**
     * A node's rectangle, in whichever coordinates the client asked for.
     *
     * <p>The tree carries boxes in the scene's own logical points and a window stamp beside them,
     * because a screen origin is user-interface-thread-confined and a client's thread cannot ask
     * for it. This is the one place that conversion happens.
     */
    static int[] extentsOf(AccessibleTree tree, AccessibleNode node, int coords) {
        float factor = tree.logicalToScreenFactor();
        float x = node.x() * factor;
        float y = node.y() * factor;
        if (coords == Atspi.COORD_SCREEN && tree.supportsAbsolutePositioning()) {
            x += tree.screenX();
            y += tree.screenY();
        }
        return new int[] {Math.round(x), Math.round(y),
                Math.round(node.width() * factor), Math.round(node.height() * factor)};
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Action

    private DBus.Msg action(DBus.Msg m, Located at) {
        AccessibleNode node = at.node();
        List<Accessible.Action> verbs = verbsOf(node);
        switch (m.member == null ? "" : m.member) {
            case "GetNActions":
                return DBus.Msg.ret(m, "i", verbs.size());
            case "GetName":
            case "GetLocalizedName": {
                int i = ((Number) m.body[0]).intValue();
                return DBus.Msg.ret(m, "s", i >= 0 && i < verbs.size() ? verbName(verbs.get(i)) : "");
            }
            case "GetDescription":
                return DBus.Msg.ret(m, "s", "");
            case "GetKeyBinding": {
                String binding = node.actions() == null ? null : node.actions().keyBinding();
                return DBus.Msg.ret(m, "s", binding == null ? "" : binding);
            }
            case "GetActions": {
                List<Object> out = new ArrayList<>();
                for (Accessible.Action verb : verbs) {
                    out.add(new Object[] {verbName(verb), "", ""});
                }
                return DBus.Msg.ret(m, "a(sss)", out);
            }
            case "DoAction": {
                int i = ((Number) m.body[0]).intValue();
                if (i < 0 || i >= verbs.size()) {
                    return DBus.Msg.ret(m, "b", false);
                }
                // The host of the window that published this node: every window's scene performs
                // only on its own nodes, and a verb sent to another would find nothing to act on.
                AccessibilityBridge.Host h = at.window().host();
                boolean done = h != null
                        && h.perform(node.id(), verbs.get(i), Accessible.Argument.NONE);
                return DBus.Msg.ret(m, "b", done);
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Text

    /**
     * The text interface over a node's text or its value's display form (LINUX-NEW-4; settled
     * linux-value-text), in characters; {@link AtspiText} holds the arithmetic.
     *
     * <p>Every method Orca 50.2 calls is answered except the geometry ones
     * (readings/fedora-orca-interface-calls.txt): {@code GetCharacterExtents},
     * {@code GetRangeExtents}, {@code GetOffsetAtPoint} and {@code GetBoundedRanges} are declined,
     * because no facet carries a range's rectangle (ADR 039 §11), and {@code ScrollSubstringTo}
     * answers false. Attributes are none, over the whole text. The writes post, through
     * {@link AccessibleNode#accepts}, {@code SET_CARET} for {@code SetCaretOffset} and
     * {@code SET_SELECTION} for the selection methods, each with its offsets back in UTF-16 units;
     * the model holds one selection, so {@code AddSelection} is refused while one stands and a
     * selection number other than 0 names nothing. A value's display form has neither verb and
     * refuses them all.
     */
    private DBus.Msg text(DBus.Msg m, Located at, AtspiText.Source source) {
        String text = source.text();
        switch (m.member == null ? "" : m.member) {
            case "GetText": {
                int start = AtspiText.unitsOf(text, arg(m, 0));
                int endChars = arg(m, 1);
                int end = endChars < 0 ? text.length() : AtspiText.unitsOf(text, endChars);
                return DBus.Msg.ret(m, "s", end <= start ? "" : text.substring(start, end));
            }
            case "GetCharacterAtOffset": {
                int chars = arg(m, 0);
                int units = AtspiText.unitsOf(text, chars);
                return DBus.Msg.ret(m, "i",
                        chars < 0 || units >= text.length() ? 0 : text.codePointAt(units));
            }
            case "GetStringAtOffset": {
                int[] range = AtspiText.segment(source, arg(m, 0),
                        AtspiText.boundaryOfGranularity(arg(m, 1)), AtspiText.Where.AT);
                return range == null
                        ? DBus.Msg.err(m, DBus.Conn.INVALID_ARGS, "no such granularity")
                        : textRange(m, text, range);
            }
            case "GetTextAtOffset":
            case "GetTextBeforeOffset":
            case "GetTextAfterOffset": {
                AtspiText.Where where = switch (m.member) {
                    case "GetTextBeforeOffset" -> AtspiText.Where.BEFORE;
                    case "GetTextAfterOffset" -> AtspiText.Where.AFTER;
                    default -> AtspiText.Where.AT;
                };
                int[] range = AtspiText.segment(source, arg(m, 0), arg(m, 1), where);
                return range == null
                        ? DBus.Msg.err(m, DBus.Conn.INVALID_ARGS, "no such boundary type")
                        : textRange(m, text, range);
            }
            case "GetNSelections":
                return DBus.Msg.ret(m, "i", source.hasSelection() ? 1 : 0);
            case "GetSelection": {
                boolean one = arg(m, 0) == 0 && source.hasSelection();
                int start = Math.min(source.selectionStart(), source.selectionEnd());
                int end = Math.max(source.selectionStart(), source.selectionEnd());
                return DBus.Msg.ret(m, "ii", one ? AtspiText.charsOf(text, start) : 0,
                        one ? AtspiText.charsOf(text, end) : 0);
            }
            case "SetCaretOffset": {
                int units = AtspiText.unitsOf(text, arg(m, 0));
                return DBus.Msg.ret(m, "b", performFirst(at, at.node(),
                        new Accessible.Argument.OfRange(units, units), Accessible.Action.SET_CARET));
            }
            case "AddSelection":
                return DBus.Msg.ret(m, "b", !source.hasSelection()
                        && select(at, text, arg(m, 0), arg(m, 1)));
            case "SetSelection":
                return DBus.Msg.ret(m, "b", arg(m, 0) == 0 && select(at, text, arg(m, 1), arg(m, 2)));
            case "RemoveSelection":
                return DBus.Msg.ret(m, "b", arg(m, 0) == 0 && source.hasSelection()
                        && performFirst(at, at.node(),
                                new Accessible.Argument.OfRange(source.caret(), source.caret()),
                                Accessible.Action.SET_SELECTION));
            case "GetAttributes":
            case "GetAttributeRun":
                return DBus.Msg.ret(m, "a{ss}ii", new LinkedHashMap<>(), 0, source.length());
            case "GetDefaultAttributes":
            case "GetDefaultAttributeSet":
                return DBus.Msg.ret(m, "a{ss}", new LinkedHashMap<>());
            case "GetAttributeValue":
                return DBus.Msg.ret(m, "s", "");
            case "ScrollSubstringTo":
            case "ScrollSubstringToPoint":
                return DBus.Msg.ret(m, "b", false);
            default:
                return null;
        }
    }

    /** A {@code sii} reply: the text between two character offsets, and the offsets. */
    private static DBus.Msg textRange(DBus.Msg m, String text, int[] range) {
        int start = AtspiText.unitsOf(text, range[0]);
        int end = AtspiText.unitsOf(text, range[1]);
        return DBus.Msg.ret(m, "sii", text.substring(start, Math.max(start, end)), range[0],
                range[1]);
    }

    /** Posts {@code SET_SELECTION} over two character offsets, in either order. */
    private static boolean select(Located at, String text, int from, int to) {
        int start = AtspiText.unitsOf(text, Math.min(from, to));
        int end = AtspiText.unitsOf(text, Math.max(from, to));
        return performFirst(at, at.node(), new Accessible.Argument.OfRange(start, end),
                Accessible.Action.SET_SELECTION);
    }

    // ------------------------------------------------------------------ org.a11y.atspi.EditableText

    /**
     * Whether a node serves {@code EditableText}: a text the widget publishes {@code EDITABLE}
     * (settled linux-value-text). A field that is only disabled keeps {@code EDITABLE} and so keeps
     * the interface, as it keeps its role; it is the writes that {@link AccessibleNode#accepts}
     * refuses there (ADR 039 §1.2: enabled and read-only are never conflated).
     */
    private static boolean isEditableText(AccessibleNode node) {
        return node.text() != null && node.has(Accessible.State.EDITABLE);
    }

    /**
     * The editable-text interface: every write is one {@code SET_TEXT} carrying the whole new
     * string, built here from the published text, which is the only text verb the model has
     * (LINUX-NEW-4).
     *
     * <p>{@code SetTextContents} replaces it; {@code InsertText} inserts at a character offset the
     * first {@code length} characters of what it is given, or all of it when {@code length} is
     * negative or at least its length — so a client that counts the length in UTF-8 bytes, which is
     * never fewer than the characters, still inserts the whole string; {@code DeleteText} removes a
     * character range. A masked field publishes its mask and not its text ({@code TextFacet}), so an
     * insertion or deletion built on it would write the mask back: both are refused on a
     * {@code PASSWORD} node, where only a whole replacement means what the client asked. The
     * clipboard is not the bridge's: {@code CutText} and {@code PasteText} answer false and
     * {@code CopyText}, which has no reply value, does nothing. Every write goes through
     * {@link AccessibleNode#accepts}, so a read-only or disabled field answers false.
     */
    private DBus.Msg editableText(DBus.Msg m, Located at) {
        AccessibleNode node = at.node();
        String text = node.text().text();
        boolean masked = node.has(Accessible.State.PASSWORD);
        switch (m.member == null ? "" : m.member) {
            case "SetTextContents":
                return DBus.Msg.ret(m, "b", performFirst(at, node,
                        new Accessible.Argument.OfText(String.valueOf(m.body[0])),
                        Accessible.Action.SET_TEXT));
            case "InsertText": {
                if (masked) {
                    return DBus.Msg.ret(m, "b", false);
                }
                int at0 = AtspiText.unitsOf(text, arg(m, 0));
                String given = String.valueOf(m.body[1]);
                int length = arg(m, 2);
                int count = given.codePointCount(0, given.length());
                String inserted = length < 0 || length >= count ? given
                        : given.substring(0, given.offsetByCodePoints(0, length));
                return DBus.Msg.ret(m, "b", performFirst(at, node, new Accessible.Argument.OfText(
                        text.substring(0, at0) + inserted + text.substring(at0)),
                        Accessible.Action.SET_TEXT));
            }
            case "DeleteText": {
                if (masked) {
                    return DBus.Msg.ret(m, "b", false);
                }
                int start = AtspiText.unitsOf(text, Math.min(arg(m, 0), arg(m, 1)));
                int end = AtspiText.unitsOf(text, Math.max(arg(m, 0), arg(m, 1)));
                return DBus.Msg.ret(m, "b", end > start && performFirst(at, node,
                        new Accessible.Argument.OfText(text.substring(0, start) + text.substring(end)),
                        Accessible.Action.SET_TEXT));
            }
            case "CopyText":
                return DBus.Msg.ret(m, null);
            case "CutText":
            case "PasteText":
                return DBus.Msg.ret(m, "b", false);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Selection

    /**
     * The selection interface over a node with a {@code SelectionFacet} (L2; semantics 1 of the
     * 2026-09-13 pass, and the settled atspi-selection-membership).
     *
     * <p><b>Two indices, as the installed XML names them.</b> A method whose argument is
     * {@code selectedChildIndex} ({@code GetSelectedChild}, {@code DeselectSelectedChild}) counts
     * the container's selected <em>members</em> — the realized nodes whose selection container is
     * this node, wherever they hang (a calendar's days under its week rows), in reading order — and
     * {@code NSelectedChildren} counts the same set. A method whose argument is {@code childIndex}
     * ({@code SelectChild}, {@code IsChildSelected}, {@code DeselectChild}) names the container's
     * literal child at that index, whatever it is: a tree's scroll bar, a grid's week row, which
     * are no members and are selected by nothing (readings/fedora-dbus-Selection.xml). Orca 50.2
     * calls only the first two (readings/fedora-orca-interface-calls.txt). A member scrolled away
     * has no node and is not counted: the same degradation {@code GetAccessibleAt} accepts.
     *
     * <p>Writes post the first verb of their candidate list the node accepts (semantics 5):
     * {@code SelectChild} [ADD_TO_SELECTION, SELECT], {@code DeselectChild} and
     * {@code DeselectSelectedChild} [DESELECT]. {@code SelectAll} and {@code ClearSelection} answer
     * false: the model has no verb for either.
     */
    private DBus.Msg selection(DBus.Msg m, Located at) {
        AccessibleTree tree = at.tree();
        AccessibleNode node = at.node();
        switch (m.member == null ? "" : m.member) {
            case "GetSelectedChild": {
                List<AccessibleNode> selected = selectedMembersOf(tree, node);
                int i = arg(m, 0);
                DBus.Ref ref = i >= 0 && i < selected.size() ? refOf(selected.get(i).id()) : nullRef();
                return DBus.Msg.ret(m, "(so)", (Object) ref.toStruct());
            }
            case "IsChildSelected": {
                AccessibleNode child = childAt(tree, node, arg(m, 0));
                return DBus.Msg.ret(m, "b", child != null && isSelectedMemberOf(tree, child, node));
            }
            case "SelectChild":
                return DBus.Msg.ret(m, "b", performFirst(at, childAt(tree, node, arg(m, 0)),
                        Accessible.Action.ADD_TO_SELECTION, Accessible.Action.SELECT));
            case "DeselectChild":
                return DBus.Msg.ret(m, "b", performFirst(at, childAt(tree, node, arg(m, 0)),
                        Accessible.Action.DESELECT));
            case "DeselectSelectedChild": {
                List<AccessibleNode> selected = selectedMembersOf(tree, node);
                int i = arg(m, 0);
                return DBus.Msg.ret(m, "b", i >= 0 && i < selected.size()
                        && performFirst(at, selected.get(i), Accessible.Action.DESELECT));
            }
            case "SelectAll":
            case "ClearSelection":
                return DBus.Msg.ret(m, "b", false);
            default:
                return null;
        }
    }

    /** The container's literal child at {@code index}, or {@code null} when there is none. */
    private static AccessibleNode childAt(AccessibleTree tree, AccessibleNode node, int index) {
        List<AccessibleNode> kids = tree.children(node);
        return index >= 0 && index < kids.size() ? kids.get(index) : null;
    }

    /** Whether {@code member} is a selected member of {@code container} (semantics 1). */
    private static boolean isSelectedMemberOf(AccessibleTree tree, AccessibleNode member,
                                              AccessibleNode container) {
        int index = member.selectionContainer();
        return member.selectionItem() != null && member.selectionItem().selected()
                && index >= 0 && index < tree.nodeCount() && tree.node(index) == container;
    }

    /**
     * The realized selected members of a container, in reading order: every node the publish
     * resolved to this container ({@link AccessibleNode#selectionContainer}) whose selection item
     * is selected. Members follow their container in a snapshot, so the scan starts there.
     */
    private static List<AccessibleNode> selectedMembersOf(AccessibleTree tree,
                                                          AccessibleNode container) {
        List<AccessibleNode> out = new ArrayList<>();
        int from = tree.indexOf(container.id());
        for (int i = Math.max(0, from + 1); i < tree.nodeCount(); i++) {
            AccessibleNode candidate = tree.node(i);
            if (candidate.selectionContainer() == from && candidate.selectionItem() != null
                    && candidate.selectionItem().selected()) {
                out.add(candidate);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Table

    /**
     * The table interface over a node with a {@code TableFacet}; ADR 041 §7, semantics 2 and 3 of
     * the 2026-09-13 pass.
     *
     * <p>Rows and cells are answered from what the walk published: a row the table has not
     * realized has no node, so {@code GetAccessibleAt} on it answers the null object, which is the
     * degradation ADR 039 §4.1 already accepts for a client that walks a long list. A cell is found
     * by its {@code CellFacet} and a row by its cells' — never by a row's position in a selection,
     * which a calendar's week rows do not carry (LINUX-NEW-10) — column headers by
     * {@code CellFacet(-1, c)} among the table's direct group children, which a footer's row −2
     * never matches (LINUX-NEW-11), and row headers are none.
     */
    private DBus.Msg table(DBus.Msg m, Located at) {
        AccessibleTree tree = at.tree();
        AccessibleNode node = at.node();
        int columns = node.table().columnCount();
        switch (m.member == null ? "" : m.member) {
            case "GetAccessibleAt": {
                AccessibleNode cell = cellAt(tree, node, arg(m, 0), arg(m, 1));
                return DBus.Msg.ret(m, "(so)", (Object) (cell == null ? nullRef() : refOf(cell.id()))
                        .toStruct());
            }
            case "GetIndexAt":
                return DBus.Msg.ret(m, "i", columns == 0 ? -1 : arg(m, 0) * columns + arg(m, 1));
            case "GetRowAtIndex":
                return DBus.Msg.ret(m, "i", columns == 0 ? -1 : arg(m, 0) / columns);
            case "GetColumnAtIndex":
                return DBus.Msg.ret(m, "i", columns == 0 ? -1 : arg(m, 0) % columns);
            case "GetRowDescription":
                return DBus.Msg.ret(m, "s", "");
            case "GetColumnDescription": {
                AccessibleNode header = columnHeaderOf(tree, node, arg(m, 0));
                return DBus.Msg.ret(m, "s", header == null ? "" : header.name());
            }
            case "GetRowExtentAt":
            case "GetColumnExtentAt":
                return DBus.Msg.ret(m, "i", 1);
            case "GetRowHeader":
                return DBus.Msg.ret(m, "(so)", (Object) nullRef().toStruct());
            case "GetColumnHeader": {
                AccessibleNode header = columnHeaderOf(tree, node, arg(m, 0));
                return DBus.Msg.ret(m, "(so)",
                        (Object) (header == null ? nullRef() : refOf(header.id())).toStruct());
            }
            case "GetSelectedRows": {
                List<Object> rows = new ArrayList<>();
                for (AccessibleNode row : selectedRowsOf(tree, node)) {
                    rows.add(rowIndexOf(tree, node, row));
                }
                return DBus.Msg.ret(m, "ai", rows);
            }
            case "GetSelectedColumns":
                return DBus.Msg.ret(m, "ai", new ArrayList<>());
            case "IsRowSelected": {
                AccessibleNode row = rowAt(tree, node, arg(m, 0));
                return DBus.Msg.ret(m, "b", row != null && row.has(Accessible.State.SELECTED));
            }
            case "IsSelected": {
                AccessibleNode row = rowAt(tree, node, arg(m, 0));
                AccessibleNode cell = cellAt(tree, node, arg(m, 0), arg(m, 1));
                return DBus.Msg.ret(m, "b", row != null && row.has(Accessible.State.SELECTED)
                        || cell != null && cell.has(Accessible.State.SELECTED));
            }
            case "IsColumnSelected":
            case "AddColumnSelection":
            case "RemoveColumnSelection":
                return DBus.Msg.ret(m, "b", false);
            case "AddRowSelection":
                // Semantics 5: "add" is [ADD_TO_SELECTION, SELECT], the first the row accepts.
                return DBus.Msg.ret(m, "b", performFirst(at, rowAt(tree, node, arg(m, 0)),
                        Accessible.Action.ADD_TO_SELECTION, Accessible.Action.SELECT));
            case "RemoveRowSelection":
                return DBus.Msg.ret(m, "b", performFirst(at, rowAt(tree, node, arg(m, 0)),
                        Accessible.Action.DESELECT));
            case "GetRowColumnExtentsAtIndex": {
                int index = arg(m, 0);
                boolean valid = columns > 0 && index >= 0 && index < node.table().rowCount() * columns;
                int row = valid ? index / columns : 0;
                int column = valid ? index % columns : 0;
                AccessibleNode rowNode = valid ? rowAt(tree, node, row) : null;
                AccessibleNode cell = valid ? cellAt(tree, node, row, column) : null;
                // Six out arguments, not one struct: libatspi reads "biiiib" and refuses a reply
                // whose signature is "(biiiib)", as it refused GetRowColumnSpan on the Fedora guest.
                return DBus.Msg.ret(m, "biiiib", valid, row, column, 1, 1,
                        rowNode != null && rowNode.has(Accessible.State.SELECTED)
                                || cell != null && cell.has(Accessible.State.SELECTED));
            }
            default:
                return null;
        }
    }

    /** The table-cell interface over a node with a {@code CellFacet}; ADR 041 §7. */
    private DBus.Msg tableCell(DBus.Msg m, Located at) {
        AccessibleTree tree = at.tree();
        AccessibleNode node = at.node();
        switch (m.member == null ? "" : m.member) {
            case "GetRowColumnSpan":
                // Four out arguments and not a struct: measured on the Fedora guest, where a
                // "(iiii)" reply was refused by libatspi with "expected iiii". libatspi 2.60.6
                // reads "=>iiii" (atspi-table-cell.c), although the ATK bridge's own XML declares
                // "biiii" (readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt,
                // readings/fedora-dbus-TableCell.xml): the client's demand is what a reply meets.
                return DBus.Msg.ret(m, "iiii", node.cell().row(), node.cell().column(), 1, 1);
            case "GetRowHeaderCells":
                return DBus.Msg.ret(m, "a(so)", new ArrayList<>());
            case "GetColumnHeaderCells": {
                List<Object> out = new ArrayList<>();
                AccessibleNode table = tableOf(tree, node);
                AccessibleNode header = table == null ? null
                        : columnHeaderOf(tree, table, node.cell().column());
                if (header != null && header != node) {
                    out.add(refOf(header.id()).toStruct());
                }
                return DBus.Msg.ret(m, "a(so)", out);
            }
            default:
                return null;
        }
    }

    private static int arg(DBus.Msg m, int index) {
        return ((Number) m.body[index]).intValue();
    }

    /**
     * Posts the first of {@code candidates} the node accepts now, on the host of the window that
     * published it (semantics 5: each entry point is an ordered candidate list, and the published
     * snapshot is the only synchronous authority, read through {@link AccessibleNode#accepts}).
     *
     * @return whether a verb was posted and the host took it; {@code false} for a missing node, a
     *         node that accepts none of them, or a window with no host
     */
    private static boolean performFirst(Located at, AccessibleNode node,
                                        Accessible.Action... candidates) {
        return performFirst(at, node, Accessible.Argument.NONE, candidates);
    }

    private static boolean performFirst(Located at, AccessibleNode node, Accessible.Argument arg,
                                        Accessible.Action... candidates) {
        if (node == null) {
            return false;
        }
        AccessibilityBridge.Host h = at.window().host();
        if (h == null) {
            return false;
        }
        for (Accessible.Action verb : candidates) {
            if (node.accepts(verb)) {
                return h.perform(node.id(), verb, arg);
            }
        }
        return false;
    }

    /** The nearest ancestor of {@code node} that is a table, itself included; null when none. */
    private static AccessibleNode tableOf(AccessibleTree tree, AccessibleNode node) {
        for (AccessibleNode at = node; at != null; ) {
            if (at.table() != null) {
                return at;
            }
            at = parentOf(tree, at);
        }
        return null;
    }

    private static AccessibleNode parentOf(AccessibleTree tree, AccessibleNode node) {
        int parent = node.parent();
        return parent < 0 || parent >= tree.nodeCount() ? null : tree.node(parent);
    }

    /** Whether {@code table} is the nearest ancestor of {@code cell} carrying a table facet. */
    private static boolean belongsTo(AccessibleTree tree, AccessibleNode cell, AccessibleNode table) {
        AccessibleNode parent = parentOf(tree, cell);
        return parent != null && tableOf(tree, parent) == table;
    }

    /**
     * The header of column {@code column} (semantics 3): the child with {@code CellFacet(-1,
     * column)} of one of the table's direct group children, matched by column and never by
     * position. A footer cell (row −2) is never a header, and no such node means no header.
     */
    private static AccessibleNode columnHeaderOf(AccessibleTree tree, AccessibleNode table,
                                                 int column) {
        for (AccessibleNode group : tree.children(table)) {
            if (group.role() != Accessible.Role.GROUP) {
                continue;
            }
            for (AccessibleNode cell : tree.children(group)) {
                if (cell.cell() != null && cell.cell().row() == -1
                        && cell.cell().column() == column) {
                    return cell;
                }
            }
        }
        return null;
    }

    /**
     * The row index a realized row stands at (semantics 2): its cells' {@code CellFacet} row, or -1
     * when it holds no data cell of this table.
     */
    private static int rowIndexOf(AccessibleTree tree, AccessibleNode table, AccessibleNode row) {
        for (AccessibleNode cell : tree.children(row)) {
            if (cell.cell() != null && cell.cell().row() >= 0 && belongsTo(tree, cell, table)) {
                return cell.cell().row();
            }
        }
        return -1;
    }

    /** The realized row shown at {@code row}, found by its cells; null when unrealized. */
    private static AccessibleNode rowAt(AccessibleTree tree, AccessibleNode table, int row) {
        if (row < 0) {
            return null;
        }
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.ROW && rowIndexOf(tree, table, child) == row) {
                return child;
            }
        }
        return null;
    }

    /**
     * Cell ({@code row}, {@code column}) of {@code table} (semantics 2): the node whose
     * {@code CellFacet} is that pair and whose nearest table is this one, searched under the
     * table's row children, where a widget cell hangs under its synthetic row (decision 3).
     */
    private static AccessibleNode cellAt(AccessibleTree tree, AccessibleNode table, int row,
                                         int column) {
        if (row < 0) {
            return null;
        }
        for (AccessibleNode rowNode : tree.children(table)) {
            if (rowNode.role() != Accessible.Role.ROW) {
                continue;
            }
            for (AccessibleNode cell : tree.children(rowNode)) {
                if (cell.cell() != null && cell.cell().row() == row
                        && cell.cell().column() == column && belongsTo(tree, cell, table)) {
                    return cell;
                }
            }
        }
        return null;
    }

    private static List<AccessibleNode> selectedRowsOf(AccessibleTree tree, AccessibleNode table) {
        List<AccessibleNode> out = new ArrayList<>();
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.ROW && child.has(Accessible.State.SELECTED)) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * The verbs a client may invoke, in a fixed order so that an index means the same thing twice.
     *
     * <p>Only the ones that take no argument: AT-SPI's {@code DoAction} carries an index and
     * nothing else, so a verb that needs a value has no way to arrive through it.
     */
    private static List<Accessible.Action> verbsOf(AccessibleNode node) {
        List<Accessible.Action> out = new ArrayList<>();
        if (node.actions() == null) {
            return out;
        }
        for (Accessible.Action verb : Accessible.Action.values()) {
            if (node.actions().has(verb) && verb.isParameterless()) {
                out.add(verb);
            }
        }
        return out;
    }

    private static String verbName(Accessible.Action verb) {
        return verb.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
