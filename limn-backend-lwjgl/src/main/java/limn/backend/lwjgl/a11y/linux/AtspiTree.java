package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Answers AT-SPI2's interfaces for one window, out of the snapshot the scene last published.
 *
 * <p><b>Every answer is computed from an immutable tree, on the reader thread, with no hop.</b>
 * That is what makes the two rules of that thread satisfiable at all: a handler must not block and
 * must never make a blocking call on the same connection, and reading a snapshot does neither. No
 * widget is touched here and no lock is taken; the only mutable thing in sight is the reference to
 * the current tree, which the user-interface thread replaces with one volatile write.
 *
 * <p><b>The application object is ours and the window is the tree's.</b> AT-SPI expects an
 * application at the root of what an application exports, with its windows beneath it; the toolkit
 * publishes a window node and knows nothing of applications. So the root path answers for a
 * synthetic application whose one child is the tree's node zero, and every other path is a node id
 * — which is stable for the life of the widget, so the path a client is holding stays the path of
 * the thing it was holding.
 */
final class AtspiTree {

    /** {@code -Dlimn.a11y.linux.trace=true}: log every inbound call. Off by default and free. */
    private static final boolean TRACE = Boolean.getBoolean("limn.a11y.linux.trace");

    /** Where a node's object path begins; the id follows. */
    private static final String NODE_PREFIX = "/org/a11y/atspi/accessible/";

    private final Supplier<AccessibleTree> current;
    private final Supplier<AccessibilityBridge.Host> host;
    private final String applicationName;
    private volatile String busName = "";
    private volatile DBus.Ref desktop;

    AtspiTree(Supplier<AccessibleTree> current, Supplier<AccessibilityBridge.Host> host,
              String applicationName) {
        this.current = current;
        this.host = host;
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

    private DBus.Ref refOf(long id) {
        return new DBus.Ref(busName, NODE_PREFIX + id);
    }

    private DBus.Ref nullRef() {
        return new DBus.Ref(busName, Atspi.PATH_NULL);
    }

    /** The node a path names, or {@code null} for the application object and for anything else. */
    private AccessibleNode nodeOf(String path) {
        if (path == null || !path.startsWith(NODE_PREFIX)) {
            return null;
        }
        String tail = path.substring(NODE_PREFIX.length());
        try {
            return current.get().find(Long.parseLong(tail));
        } catch (NumberFormatException e) {
            return null;
        }
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
        AccessibleNode node = root ? null : nodeOf(m.path);
        if (!root && node == null) {
            return null;
        }
        if (Atspi.I_PROPS.equals(iface)) {
            return properties(m, root, node);
        }
        if (Atspi.I_ACCESSIBLE.equals(iface)) {
            return accessible(m, root, node);
        }
        if (Atspi.I_COMPONENT.equals(iface)) {
            // The application object answers Component too. A client asks the root for its extents
            // before it walks anything, and an error there stops the walk at the first step rather
            // than degrading: libatspi reports the failure and abandons the subtree.
            return node == null ? applicationComponent(m) : component(m, node);
        }
        if (Atspi.I_ACTION.equals(iface) && node != null) {
            return action(m, node);
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
        AccessibleTree tree = current.get();
        List<Object> items = new ArrayList<>();
        items.add(new Object[] {
                rootRef().toStruct(), rootRef().toStruct(), desktopOrNull().toStruct(),
                -1, tree.nodeCount() == 0 ? 0 : 1,
                new ArrayList<Object>(List.of(Atspi.I_ACCESSIBLE, Atspi.I_APPLICATION,
                        Atspi.I_COMPONENT)),
                applicationName, Atspi.ROLE_APPLICATION, "",
                Atspi.stateWords(AtspiStates.setOf(s -> s == Accessible.State.ENABLED)),
        });
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            items.add(new Object[] {
                    refOf(node.id()).toStruct(), rootRef().toStruct(),
                    parentRef(tree, node).toStruct(),
                    indexInParent(tree, node), childrenOf(tree, node).size(),
                    new ArrayList<>(interfacesOf(false, node)),
                    node.name(), roleOf(node), node.description(),
                    Atspi.stateWords(statesOf(node)),
            });
        }
        return DBus.Msg.ret(m, Atspi.CACHE_ITEMS, items);
    }

    /** The application's own rectangle: the window it holds, or nothing before the first frame. */
    private DBus.Msg applicationComponent(DBus.Msg m) {
        AccessibleTree tree = current.get();
        int[] box = tree.nodeCount() == 0
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

    /** Where {@code node} sits among its siblings, which the cache item carries. */
    private int indexInParent(AccessibleTree tree, AccessibleNode node) {
        int parent = node.parent();
        if (parent < 0) {
            return 0;
        }
        List<AccessibleNode> siblings = childrenOf(tree, tree.node(parent));
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id() == node.id()) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ org.freedesktop.DBus.Properties

    private DBus.Msg properties(DBus.Msg m, boolean root, AccessibleNode node) {
        String which = m.body.length > 0 ? String.valueOf(m.body[0]) : "";
        Map<Object, Object> all = propertiesOf(which, root, node);
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

    private Map<Object, Object> propertiesOf(String which, boolean root, AccessibleNode node) {
        Map<Object, Object> out = new LinkedHashMap<>();
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
        if (!Atspi.I_ACCESSIBLE.equals(which)) {
            return out;
        }
        AccessibleTree tree = current.get();
        if (root) {
            out.put("Name", new DBus.Variant("s", applicationName));
            out.put("Description", new DBus.Variant("s", ""));
            out.put("Parent", new DBus.Variant("(so)", desktopOrNull().toStruct()));
            out.put("ChildCount", new DBus.Variant("i", tree.nodeCount() == 0 ? 0 : 1));
            out.put("Locale", new DBus.Variant("s", ""));
            out.put("AccessibleId", new DBus.Variant("s", ""));
            out.put("HelpText", new DBus.Variant("s", ""));
            return out;
        }
        out.put("Name", new DBus.Variant("s", node.name()));
        out.put("Description", new DBus.Variant("s", node.description()));
        out.put("Parent", new DBus.Variant("(so)", parentRef(tree, node).toStruct()));
        out.put("ChildCount", new DBus.Variant("i", childrenOf(tree, node).size()));
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

    private List<AccessibleNode> childrenOf(AccessibleTree tree, AccessibleNode node) {
        List<AccessibleNode> out = new ArrayList<>();
        int index = tree.indexOf(node.id());
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).parent() == index) {
                out.add(tree.node(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Accessible

    private DBus.Msg accessible(DBus.Msg m, boolean root, AccessibleNode node) {
        AccessibleTree tree = current.get();
        switch (m.member == null ? "" : m.member) {
            case "GetChildren": {
                List<Object> kids = new ArrayList<>();
                if (root) {
                    if (tree.nodeCount() > 0) {
                        kids.add(refOf(tree.node(0).id()).toStruct());
                    }
                } else {
                    for (AccessibleNode k : childrenOf(tree, node)) {
                        kids.add(refOf(k.id()).toStruct());
                    }
                }
                return DBus.Msg.ret(m, "a(so)", kids);
            }
            case "GetChildAtIndex": {
                int i = ((Number) m.body[0]).intValue();
                List<AccessibleNode> kids = root
                        ? (tree.nodeCount() > 0 ? List.of(tree.node(0)) : List.<AccessibleNode>of())
                        : childrenOf(tree, node);
                DBus.Ref ref = i >= 0 && i < kids.size() ? refOf(kids.get(i).id()) : nullRef();
                return DBus.Msg.ret(m, "(so)", (Object) ref.toStruct());
            }
            case "GetIndexInParent": {
                if (root) {
                    return DBus.Msg.ret(m, "i", -1);
                }
                int parent = node.parent();
                if (parent < 0) {
                    return DBus.Msg.ret(m, "i", 0);
                }
                List<AccessibleNode> siblings = childrenOf(tree, tree.node(parent));
                int at = -1;
                for (int i = 0; i < siblings.size(); i++) {
                    if (siblings.get(i).id() == node.id()) {
                        at = i;
                    }
                }
                return DBus.Msg.ret(m, "i", at);
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
                return DBus.Msg.ret(m, "a(ua(so))", new ArrayList<>());
            default:
                return null;
        }
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
        return out;
    }

    // ------------------------------------------------------------------ org.a11y.atspi.Component

    private DBus.Msg component(DBus.Msg m, AccessibleNode node) {
        AccessibleTree tree = current.get();
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

    private DBus.Msg action(DBus.Msg m, AccessibleNode node) {
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
                AccessibilityBridge.Host h = host.get();
                boolean done = h != null
                        && h.perform(node.id(), verbs.get(i), Accessible.Argument.NONE);
                return DBus.Msg.ret(m, "b", done);
            }
            default:
                return null;
        }
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
