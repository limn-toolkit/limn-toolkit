package limn.backend.lwjgl.a11y.linux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayRow;
import limn.backend.spi.TrayState;
import limn.graphics.Color;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

/**
 * What a tray's two objects say on the bus, as values: the status item's properties and
 * introspection, and the menu's layout and rows. Nothing here touches a connection.
 */
final class TrayWire {

    static final String ITEM = "org.kde.StatusNotifierItem";
    static final String ITEM_PATH = "/StatusNotifierItem";
    static final String MENU = "com.canonical.dbusmenu";
    static final String MENU_PATH = "/MenuBar";
    static final String WATCHER = "org.kde.StatusNotifierWatcher";
    static final String WATCHER_PATH = "/StatusNotifierWatcher";

    /** The sizes a picture is sent at: what panels draw at from 100 % to 300 %. */
    static final int[] SIZES = {16, 22, 24, 32, 48, 64};

    /** The grey a monochrome icon is drawn in where the panel does not say its colour. */
    static final Color PANEL_GREY = Color.rgb(0x8C8C8C);

    /** The number of the row that stands for a menu the application did not set. */
    static final int PLACEHOLDER_ROW = 1;

    private static final String NODE = "(ia{sv}av)";

    private TrayWire() {
    }

    // ---- the item ---------------------------------------------------------------------------

    /**
     * @param state what the tray shows
     * @param id    the application's identifier for the icon
     * @return every property of the status item
     */
    static Map<String, DBus.Variant> itemProperties(TrayState state, String id) {
        List<Object> pixmaps = pixmaps(state);
        Map<String, DBus.Variant> p = new LinkedHashMap<>();
        p.put("Category", DBus.v("s", "ApplicationStatus"));
        p.put("Id", DBus.v("s", id));
        p.put("Title", DBus.v("s", state.title()));
        p.put("Status", DBus.v("s", status(state)));
        p.put("WindowId", DBus.v("i", 0));
        p.put("IconName", DBus.v("s", ""));
        p.put("IconPixmap", DBus.v("a(iiay)", pixmaps));
        p.put("OverlayIconName", DBus.v("s", ""));
        p.put("OverlayIconPixmap", DBus.v("a(iiay)", List.of()));
        p.put("AttentionIconName", DBus.v("s", ""));
        // The same picture, which already carries what shows attention: a panel that swaps to
        // this one while the status asks and one that does not show the same thing.
        p.put("AttentionIconPixmap", DBus.v("a(iiay)", state.attention() ? pixmaps : List.of()));
        p.put("AttentionMovieName", DBus.v("s", ""));
        p.put("ToolTip", DBus.v("(sa(iiay)ss)", new Object[] {"", List.of(), tooltipTitle(state), ""}));
        p.put("IconThemePath", DBus.v("s", ""));
        p.put("ItemIsMenu", DBus.v("b", !state.activates()));
        p.put("Menu", DBus.v("o", MENU_PATH));
        return p;
    }

    static String status(TrayState state) {
        return state.attention() ? "NeedsAttention" : "Active";
    }

    /** A panel shows the tooltip's title; with none set, the icon's name is what there is to say. */
    static String tooltipTitle(TrayState state) {
        return state.tooltip().isEmpty() ? state.title() : state.tooltip();
    }

    /** The picture at every size, each as width, height and ARGB in network byte order. */
    static List<Object> pixmaps(TrayState state) {
        TrayPicture picture = state.picture();
        Color tint = null;
        if (picture == null) {
            picture = state.monochrome();
            tint = PANEL_GREY;
        }
        List<Object> out = new ArrayList<>();
        if (picture == null) {
            return out;
        }
        for (int size : SIZES) {
            out.add(new Object[] {size, size, argb(picture.render(size, tint, false))});
        }
        return out;
    }

    static byte[] argb(Image image) {
        byte[] rgba = image.pixels();
        byte[] out = new byte[rgba.length];
        for (int i = 0; i < rgba.length; i += 4) {
            out[i] = rgba[i + 3];
            out[i + 1] = rgba[i];
            out[i + 2] = rgba[i + 1];
            out[i + 3] = rgba[i + 2];
        }
        return out;
    }

    /**
     * The item's own description. A panel reads from it whether the item can be activated: one
     * that lists no activation has its menu opened by any click, at once.
     */
    static String itemIntrospection(boolean activates) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append("<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n")
                .append("\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n")
                .append("<node><interface name=\"").append(ITEM).append("\">");
        for (String[] property : new String[][] {{"Category", "s"}, {"Id", "s"}, {"Title", "s"}, {"Status", "s"},
                {"WindowId", "i"}, {"IconName", "s"}, {"IconPixmap", "a(iiay)"}, {"OverlayIconName", "s"},
                {"OverlayIconPixmap", "a(iiay)"}, {"AttentionIconName", "s"}, {"AttentionIconPixmap", "a(iiay)"},
                {"AttentionMovieName", "s"}, {"ToolTip", "(sa(iiay)ss)"}, {"IconThemePath", "s"},
                {"ItemIsMenu", "b"}, {"Menu", "o"}}) {
            xml.append("<property name=\"").append(property[0]).append("\" type=\"").append(property[1])
                    .append("\" access=\"read\"/>");
        }
        if (activates) {
            xml.append("<method name=\"Activate\"><arg name=\"x\" type=\"i\" direction=\"in\"/>")
                    .append("<arg name=\"y\" type=\"i\" direction=\"in\"/></method>");
        }
        xml.append("<method name=\"ContextMenu\"><arg name=\"x\" type=\"i\" direction=\"in\"/>")
                .append("<arg name=\"y\" type=\"i\" direction=\"in\"/></method>")
                .append("<method name=\"ProvideXdgActivationToken\"><arg name=\"token\" type=\"s\" direction=\"in\"/></method>");
        for (String signal : new String[] {"NewTitle", "NewIcon", "NewAttentionIcon", "NewOverlayIcon", "NewToolTip"}) {
            xml.append("<signal name=\"").append(signal).append("\"/>");
        }
        xml.append("<signal name=\"NewStatus\"><arg name=\"status\" type=\"s\"/></signal>")
                .append("</interface>")
                .append("<interface name=\"org.freedesktop.DBus.Properties\">")
                .append("<method name=\"Get\"><arg type=\"s\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/>")
                .append("<arg type=\"v\" direction=\"out\"/></method>")
                .append("<method name=\"GetAll\"><arg type=\"s\" direction=\"in\"/>")
                .append("<arg type=\"a{sv}\" direction=\"out\"/></method></interface>")
                .append("<interface name=\"org.freedesktop.DBus.Introspectable\">")
                .append("<method name=\"Introspect\"><arg type=\"s\" direction=\"out\"/></method></interface>")
                .append("</node>");
        return xml.toString();
    }

    // ---- the menu ---------------------------------------------------------------------------

    /**
     * The rows a panel is shown: the application's, or one disabled row of the title when it set
     * none, since a panel may show no item that has no menu.
     */
    static List<TrayRow> shown(List<TrayRow> rows, String title) {
        return rows.isEmpty()
                ? List.of(new TrayRow(PLACEHOLDER_ROW, TrayRow.Kind.COMMAND, title, false, false, (char) 0, List.of()))
                : rows;
    }

    /** The menu's own properties. */
    static Map<String, DBus.Variant> menuProperties(boolean rightToLeft) {
        Map<String, DBus.Variant> p = new LinkedHashMap<>();
        p.put("Version", DBus.v("u", 3));
        p.put("TextDirection", DBus.v("s", rightToLeft ? "rtl" : "ltr"));
        p.put("Status", DBus.v("s", "normal"));
        p.put("IconThemePath", DBus.v("as", List.of()));
        return p;
    }

    /**
     * @param parent the row whose layout is asked, 0 for the menu itself
     * @param depth  how many levels below it, negative for all
     * @param rows   the top-level rows
     * @return the layout node, or {@code null} when no row has that number
     */
    static Object @Nullable [] layout(int parent, int depth, List<TrayRow> rows) {
        if (parent == 0) {
            Map<String, DBus.Variant> root = new LinkedHashMap<>();
            root.put("children-display", DBus.v("s", "submenu"));
            return new Object[] {0, root, children(rows, depth)};
        }
        TrayRow row = find(rows, parent);
        return row == null ? null : node(row, depth);
    }

    private static Object[] node(TrayRow row, int depth) {
        return new Object[] {row.id(), rowProperties(row), children(row.children(), depth)};
    }

    private static List<Object> children(List<TrayRow> rows, int depth) {
        List<Object> out = new ArrayList<>();
        if (depth != 0) {
            for (TrayRow row : rows) {
                out.add(DBus.v(NODE, node(row, depth - 1)));
            }
        }
        return out;
    }

    static @Nullable TrayRow find(List<TrayRow> rows, int id) {
        for (TrayRow row : rows) {
            if (row.id() == id) {
                return row;
            }
            TrayRow below = find(row.children(), id);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    /** A row's properties; what is at its default is left out, as the protocol asks. */
    static Map<String, DBus.Variant> rowProperties(TrayRow row) {
        Map<String, DBus.Variant> p = new LinkedHashMap<>();
        if (row.kind() == TrayRow.Kind.SEPARATOR) {
            p.put("type", DBus.v("s", "separator"));
            return p;
        }
        p.put("label", DBus.v("s", label(row)));
        if (!row.enabled()) {
            p.put("enabled", DBus.v("b", false));
        }
        if (row.kind() == TrayRow.Kind.CHECK) {
            p.put("toggle-type", DBus.v("s", "checkmark"));
            p.put("toggle-state", DBus.v("i", row.checked() ? 1 : 0));
        }
        if (row.kind() == TrayRow.Kind.SUBMENU) {
            p.put("children-display", DBus.v("s", "submenu"));
        }
        return p;
    }

    /**
     * A label as the protocol reads one: an underscore marks the access key, so each of the
     * text's own is doubled, and one goes before the row's access letter where the text has it.
     */
    static String label(TrayRow row) {
        String text = row.label();
        int at = -1;
        if (row.mnemonic() != 0) {
            at = text.toUpperCase(java.util.Locale.ROOT).indexOf(Character.toUpperCase(row.mnemonic()));
        }
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i == at) {
                out.append('_');
            }
            out.append(c);
            if (c == '_') {
                out.append('_');
            }
        }
        return out.toString();
    }
}
