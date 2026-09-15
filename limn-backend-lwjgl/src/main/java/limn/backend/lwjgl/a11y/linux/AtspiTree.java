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

    private DBus.Ref nullRef() {
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
            return null;
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
        if (Atspi.I_APPLICATION.equals(iface) && root) {
            return application(m);
        }
        return null;
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
                AccessibleNode node = tree.node(i);
                items.add(new Object[] {
                        refOf(node.id()).toStruct(), rootRef().toStruct(),
                        parentRef(tree, node).toStruct(),
                        node.parent() < 0 ? f : tree.indexInParent(node),
                        tree.children(node).size(),
                        new ArrayList<>(interfacesOf(false, node)),
                        node.name(), roleOf(node), node.description(),
                        Atspi.stateWords(statesOf(node)),
                });
            }
        }
        return DBus.Msg.ret(m, Atspi.CACHE_ITEMS, items);
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
                return DBus.Msg.err(m, "org.freedesktop.DBus.Error.InvalidArgs",
                        "no property " + which + "." + m.body[1]);
            }
            return DBus.Msg.ret(m, "v", value);
        }
        if ("Set".equals(m.member)) {
            // The registry assigns the application its id straight after Embed. Accepting and
            // discarding it is honest: nothing here reads it back, and refusing would leave the
            // registry believing the application never took the number it handed out.
            return DBus.Msg.ret(m, null);
        }
        return null;
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
        // No setter is served yet: neither Value nor EditableText is named here, so nothing this
        // bridge answers can post SET_VALUE or SET_TEXT. Phase 3 serves them (linux-value-text)
        // and owes each setter the refusal fix round 2e settled: none posted to a node
        // AccessibleNode#accepts refuses, a node without ENABLED included, while the facet alone
        // answers whether the value or the text is settable (semantics 5, amended 2026-09-15).
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
    private static int[] extentsOf(AccessibleTree tree, AccessibleNode node, int coords) {
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

    // ------------------------------------------------------------------ org.a11y.atspi.Table

    /**
     * The table interface over a node with a {@code TableFacet}; ADR 041 §7.
     *
     * <p>Rows and cells are answered from what the walk published: a row the table has not
     * realized has no node, so {@code GetAccessibleAt} on it answers the null object, which is the
     * degradation ADR 039 §4.1 already accepts for a client that walks a long list. Column headers
     * are the header group's children, and row headers are none.
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
                    rows.add(row.selectionItem().positionInSet() - 1);
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
                return DBus.Msg.ret(m, "b", row != null && row.has(Accessible.State.SELECTED));
            }
            case "IsColumnSelected":
            case "AddColumnSelection":
            case "RemoveColumnSelection":
            case "RemoveRowSelection":
                return DBus.Msg.ret(m, "b", false);
            case "AddRowSelection": {
                AccessibleNode row = rowAt(tree, node, arg(m, 0));
                AccessibilityBridge.Host h = at.window().host();
                boolean done = row != null && h != null && row.actions() != null
                        && row.actions().has(Accessible.Action.SELECT)
                        && h.perform(row.id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
                return DBus.Msg.ret(m, "b", done);
            }
            case "GetRowColumnExtentsAtIndex": {
                int index = arg(m, 0);
                boolean valid = columns > 0 && index >= 0 && index < node.table().rowCount() * columns;
                int row = valid ? index / columns : 0;
                int column = valid ? index % columns : 0;
                AccessibleNode rowNode = valid ? rowAt(tree, node, row) : null;
                // Six out arguments, not one struct: libatspi reads "biiiib" and refuses a reply
                // whose signature is "(biiiib)", as it refused GetRowColumnSpan on the Fedora guest.
                return DBus.Msg.ret(m, "biiiib", valid, row, column, 1, 1,
                        rowNode != null && rowNode.has(Accessible.State.SELECTED));
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
                // "(iiii)" reply was refused by libatspi with "expected iiii".
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

    /** The nearest ancestor of {@code node} that is a table, itself included; null when none. */
    private static AccessibleNode tableOf(AccessibleTree tree, AccessibleNode node) {
        for (AccessibleNode at = node; at != null; ) {
            if (at.table() != null) {
                return at;
            }
            int parent = at.parent();
            at = parent < 0 || parent >= tree.nodeCount() ? null : tree.node(parent);
        }
        return null;
    }

    /** The header group's child at {@code column}: the table's first group child's children. */
    private static AccessibleNode columnHeaderOf(AccessibleTree tree, AccessibleNode table,
                                                 int column) {
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.GROUP) {
                List<AccessibleNode> headers = tree.children(child);
                return column >= 0 && column < headers.size() ? headers.get(column) : null;
            }
        }
        return null;
    }

    /** The realized row shown at {@code row}, by its position in set; null when unrealized. */
    private static AccessibleNode rowAt(AccessibleTree tree, AccessibleNode table, int row) {
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.ROW && child.selectionItem() != null
                    && child.selectionItem().positionInSet() == row + 1) {
                return child;
            }
        }
        return null;
    }

    private static AccessibleNode cellAt(AccessibleTree tree, AccessibleNode table, int row,
                                         int column) {
        AccessibleNode rowNode = rowAt(tree, table, row);
        if (rowNode == null) {
            return null;
        }
        for (AccessibleNode cell : tree.children(rowNode)) {
            if (cell.cell() != null && cell.cell().column() == column) {
                return cell;
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
