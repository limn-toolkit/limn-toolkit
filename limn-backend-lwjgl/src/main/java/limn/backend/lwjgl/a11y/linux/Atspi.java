package limn.backend.lwjgl.a11y.linux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AT-SPI2 constants and interface XML, every value taken from the machine under test
 * (Ubuntu 24.04, at-spi2-core 2.52.0-1build1, aarch64) rather than from memory:
 *
 *  - roles and states: the GObject-introspection typelib
 *      /usr/lib/aarch64-linux-gnu/girepository-1.0/Atspi-2.0.typelib
 *    read with  python3 -c 'from gi.repository import Atspi; print(int(Atspi.Role.PUSH_BUTTON))'
 *  - object paths: `strings /usr/lib/aarch64-linux-gnu/libatspi.so.0 | grep ^/org/a11y`
 *  - interface XML and every D-Bus signature: the introspection blobs compiled into
 *      /usr/lib/aarch64-linux-gnu/libatk-bridge-2.0.so.0
 *    plus a live `gdbus introspect` of at-spi2-registryd on the a11y bus.
 */
final class Atspi {

    private Atspi() {}

    // ---- object paths (libatspi.so.0 string table) ---------------------------------------
    static final String PATH_ROOT  = "/org/a11y/atspi/accessible/root";
    static final String PATH_CACHE = "/org/a11y/atspi/cache";
    static final String PATH_NULL  = "/org/a11y/atspi/null";
    static final String REGISTRY   = "org.a11y.atspi.Registry";

    // ---- interfaces -----------------------------------------------------------------------
    static final String I_ACCESSIBLE  = "org.a11y.atspi.Accessible";
    static final String I_APPLICATION = "org.a11y.atspi.Application";
    static final String I_COMPONENT   = "org.a11y.atspi.Component";
    static final String I_ACTION      = "org.a11y.atspi.Action";
    static final String I_TABLE       = "org.a11y.atspi.Table";
    static final String I_TABLE_CELL  = "org.a11y.atspi.TableCell";
    // Named as the interface XML the Fedora KDE 44 guest's ATK bridge compiles in declares it
    // (at-spi2-atk 2.60.6-1.fc44, readings/fedora-dbus-<Interface>.xml, extracted by
    // scripts/a11y/linux/extract-atspi-introspection.sh on 2026-09-13, the guest's clock then
    // reading 2026-09-10).
    static final String I_SELECTION   = "org.a11y.atspi.Selection";
    static final String I_VALUE       = "org.a11y.atspi.Value";
    static final String I_CACHE       = "org.a11y.atspi.Cache";
    static final String I_SOCKET      = "org.a11y.atspi.Socket";
    static final String I_PROPS       = "org.freedesktop.DBus.Properties";
    static final String I_INTROSPECT  = "org.freedesktop.DBus.Introspectable";
    static final String I_PEER        = "org.freedesktop.DBus.Peer";

    // ---- AtspiRole (typelib) ---------------------------------------------------------------
    static final int ROLE_INVALID     = 0;    // Atspi.Role.INVALID
    static final int ROLE_FRAME       = 23;   // Atspi.Role.FRAME        -> "frame"
    static final int ROLE_PUSH_BUTTON = 43;   // Atspi.Role.PUSH_BUTTON  -> "push button"
    static final int ROLE_APPLICATION = 75;   // Atspi.Role.APPLICATION  -> "application"

    // ---- AtspiStateType, as bit indices into the 64-bit set (typelib) -----------------------
    static final int STATE_ACTIVE    = 1;
    static final int STATE_ENABLED   = 8;
    static final int STATE_FOCUSABLE = 11;
    static final int STATE_SENSITIVE = 24;
    static final int STATE_SHOWING   = 25;
    static final int STATE_VISIBLE   = 30;

    // ---- AtspiRelationType ------------------------------------------------------------------
    // Read 2026-09-09 from the enum's own declaration in atspi/atspi-constants.h at the release
    // tag AT_SPI2_CORE_2_52_0 (gitlab.gnome.org/GNOME/at-spi2-core), and confirmed the same day
    // against a guest's typelib (Fedora KDE 44, at-spi2-core 2.60.6, Atspi.RelationType), which
    // is what dump-atspi-constants.py prints. The enum is unnumbered, so each value is its
    // position from ATSPI_RELATION_NULL at 0.
    static final int RELATION_LABEL_FOR      = 1;    // ATSPI_RELATION_LABEL_FOR
    static final int RELATION_LABELLED_BY    = 2;    // ATSPI_RELATION_LABELLED_BY
    static final int RELATION_CONTROLLER_FOR = 3;    // ATSPI_RELATION_CONTROLLER_FOR
    static final int RELATION_CONTROLLED_BY  = 4;    // ATSPI_RELATION_CONTROLLED_BY
    static final int RELATION_MEMBER_OF      = 5;    // ATSPI_RELATION_MEMBER_OF
    static final int RELATION_POPUP_FOR      = 15;   // ATSPI_RELATION_POPUP_FOR
    static final int RELATION_DESCRIBED_BY   = 18;   // ATSPI_RELATION_DESCRIBED_BY

    // ---- AtspiLive ------------------------------------------------------------------------------
    // Read 2026-09-13 off the Fedora KDE 44 guest's typelib (libatspi 2.60.6, Atspi.Live, every
    // enumerator) by scripts/a11y/linux/dump-atspi-constants.py --all
    // (readings/fedora-atspi-constants-all.txt). An Announcement's detail1 is one of these: GTK
    // 4.22.4 sends POLITE for a low or medium priority and ASSERTIVE for a high one, and Orca 50.2's
    // _get_priority compares detail1 with them (readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-
    // event-shapes.txt, readings/fedora-orca-event-consumers.txt).
    static final int LIVE_POLITE    = 1;   // Atspi.Live.POLITE
    static final int LIVE_ASSERTIVE = 2;   // Atspi.Live.ASSERTIVE

    // ---- AtspiCoordType / AtspiComponentLayer (typelib) -------------------------------------
    static final int COORD_SCREEN = 0, COORD_WINDOW = 1;
    static final int LAYER_WIDGET = 3, LAYER_WINDOW = 7;

    static long state(int... bits) {
        long s = 0;
        for (int b : bits) s |= 1L << b;
        return s;
    }

    /** GetState is "au": exactly two uint32, low word first (not one uint64). */
    static List<Object> stateWords(long states) {
        List<Object> out = new ArrayList<>(2);
        out.add((int) (states & 0xffffffffL));
        out.add((int) (states >>> 32));
        return out;
    }

    static Map<Object, Object> attrs(String... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // =========================================================================================
    // Introspection XML, verbatim from libatk-bridge-2.0.so.0's compiled-in blobs.
    // Serving the real XML means gdbus/busctl/d-feet see exactly what a GTK app shows.
    // =========================================================================================

    static final String XML_ACCESSIBLE =
        "<interface name=\"org.a11y.atspi.Accessible\">"
        + "<property name=\"Name\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Description\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Parent\" type=\"(so)\" access=\"read\"/>"
        + "<property name=\"ChildCount\" type=\"i\" access=\"read\"/>"
        + "<property name=\"Locale\" type=\"s\" access=\"read\"/>"
        + "<property name=\"AccessibleId\" type=\"s\" access=\"read\"/>"
        + "<property name=\"HelpText\" type=\"s\" access=\"read\"/>"
        + "<method name=\"GetChildAtIndex\"><arg direction=\"in\" name=\"index\" type=\"i\"/><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetChildren\"><arg direction=\"out\" type=\"a(so)\"/></method>"
        + "<method name=\"GetIndexInParent\"><arg direction=\"out\" type=\"i\"/></method>"
        + "<method name=\"GetRelationSet\"><arg direction=\"out\" type=\"a(ua(so))\"/></method>"
        + "<method name=\"GetRole\"><arg direction=\"out\" type=\"u\"/></method>"
        + "<method name=\"GetRoleName\"><arg direction=\"out\" type=\"s\"/></method>"
        + "<method name=\"GetLocalizedRoleName\"><arg direction=\"out\" type=\"s\"/></method>"
        + "<method name=\"GetState\"><arg direction=\"out\" type=\"au\"/></method>"
        + "<method name=\"GetAttributes\"><arg direction=\"out\" type=\"a{ss}\"/></method>"
        + "<method name=\"GetApplication\"><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetInterfaces\"><arg direction=\"out\" type=\"as\"/></method>"
        + "</interface>";

    static final String XML_APPLICATION =
        "<interface name=\"org.a11y.atspi.Application\">"
        + "<property name=\"ToolkitName\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Version\" type=\"s\" access=\"read\"/>"
        + "<property name=\"AtspiVersion\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Id\" type=\"i\" access=\"readwrite\"/>"
        + "<method name=\"GetLocale\"><arg direction=\"in\" name=\"lctype\" type=\"u\"/><arg direction=\"out\" type=\"s\"/></method>"
        + "</interface>";

    static final String XML_COMPONENT =
        "<interface name=\"org.a11y.atspi.Component\">"
        + "<method name=\"Contains\"><arg direction=\"in\" name=\"x\" type=\"i\"/><arg direction=\"in\" name=\"y\" type=\"i\"/><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"b\"/></method>"
        + "<method name=\"GetAccessibleAtPoint\"><arg direction=\"in\" name=\"x\" type=\"i\"/><arg direction=\"in\" name=\"y\" type=\"i\"/><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetExtents\"><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"(iiii)\"/></method>"
        + "<method name=\"GetPosition\"><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" name=\"x\" type=\"i\"/><arg direction=\"out\" name=\"y\" type=\"i\"/></method>"
        + "<method name=\"GetSize\"><arg direction=\"out\" name=\"width\" type=\"i\"/><arg direction=\"out\" name=\"height\" type=\"i\"/></method>"
        + "<method name=\"GetLayer\"><arg direction=\"out\" type=\"u\"/></method>"
        + "<method name=\"GetMDIZOrder\"><arg direction=\"out\" type=\"n\"/></method>"
        + "<method name=\"GrabFocus\"><arg direction=\"out\" type=\"b\"/></method>"
        + "<method name=\"GetAlpha\"><arg direction=\"out\" type=\"d\"/></method>"
        + "</interface>";

    static final String XML_ACTION =
        "<interface name=\"org.a11y.atspi.Action\">"
        + "<property name=\"NActions\" type=\"i\" access=\"read\"/>"
        + "<method name=\"GetDescription\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetName\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetLocalizedName\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetKeyBinding\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetActions\"><arg direction=\"out\" type=\"a(sss)\"/></method>"
        + "<method name=\"DoAction\"><arg direction=\"in\" name=\"index\" type=\"i\"/><arg direction=\"out\" type=\"b\"/></method>"
        + "</interface>";

    static final String XML_CACHE =
        "<interface name=\"org.a11y.atspi.Cache\">"
        + "<method name=\"GetItems\"><arg direction=\"out\" name=\"nodes\" type=\"a((so)(so)(so)iiassusau)\"/></method>"
        + "<signal name=\"AddAccessible\"><arg name=\"nodeAdded\" type=\"((so)(so)(so)iiassusau)\"/></signal>"
        + "<signal name=\"RemoveAccessible\"><arg name=\"nodeRemoved\" type=\"(so)\"/></signal>"
        + "</interface>";

    /** The cache item struct, from libatk-bridge-2.0.so.0 / libatspi.so.0. */
    static final String CACHE_ITEM = "((so)(so)(so)iiassusau)";
    static final String CACHE_ITEMS = "a" + CACHE_ITEM;

    static final String XML_STD =
        "<interface name=\"org.freedesktop.DBus.Introspectable\">"
        + "<method name=\"Introspect\"><arg name=\"data\" direction=\"out\" type=\"s\"/></method></interface>"
        + "<interface name=\"org.freedesktop.DBus.Properties\">"
        + "<method name=\"Get\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"propname\" direction=\"in\" type=\"s\"/><arg name=\"value\" direction=\"out\" type=\"v\"/></method>"
        + "<method name=\"Set\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"propname\" direction=\"in\" type=\"s\"/><arg name=\"value\" direction=\"in\" type=\"v\"/></method>"
        + "<method name=\"GetAll\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"props\" direction=\"out\" type=\"a{sv}\"/></method></interface>"
        + "<interface name=\"org.freedesktop.DBus.Peer\">"
        + "<method name=\"Ping\"/><method name=\"GetMachineId\"><arg name=\"machine_uuid\" direction=\"out\" type=\"s\"/></method></interface>";

    static String node(String... interfaces) {
        StringBuilder sb = new StringBuilder(
            "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n"
            + "\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>");
        for (String i : interfaces) sb.append(i);
        sb.append(XML_STD).append("</node>");
        return sb.toString();
    }
}
