package limn.backend.lwjgl.a11y.linux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.MenuRow;
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

    /**
     * The sizes a picture is sent at, each drawn for that size and not scaled from another: what
     * panels draw an item at, 16 to 32, and the same at 200 % and 300 %, where a panel takes the
     * size nearest what it needs.
     */
    static final int[] SIZES = {16, 22, 24, 32, 44, 48, 64, 96};

    /** The grey a monochrome icon is drawn in where nothing says the panel's colour. */
    static final Color PANEL_GREY = Color.rgb(0x8C8C8C);

    /**
     * The colour a monochrome icon is drawn in. The protocol does not say the panel's colour, and
     * the desktop's light or dark setting is not it: read on 2026-10-11, KDE's panel and GNOME's
     * top bar were both dark with the system set to light. What is known is GNOME's bar, dark in
     * both settings, so there the icon is the white of the bar's own; anywhere else it is a grey
     * that reads on a light and on a dark panel.
     *
     * @param desktop {@code XDG_CURRENT_DESKTOP}, or null
     * @return the tint
     */
    static Color panelTint(@Nullable String desktop) {
        return desktop != null && desktop.toUpperCase(java.util.Locale.ROOT).contains("GNOME")
                ? Color.rgb(0xFFFFFF) : PANEL_GREY;
    }

    /** The number of the row that stands for a menu the application did not set. */
    static final int PLACEHOLDER_ROW = 1;

    private static final String NODE = "(ia{sv}av)";

    private TrayWire() {
    }

    // ---- the item ---------------------------------------------------------------------------

    /**
     * @param state what the tray shows
     * @param id    the application's identifier for the icon
     * @param monochromeTint the colour of a monochrome icon shown for want of another
     * @return every property of the status item
     */
    static Map<String, DBus.Variant> itemProperties(TrayState state, String id, Color monochromeTint) {
        return itemProperties(state, id, monochromeTint, null);
    }

    /**
     * A picture the panel is given by name, from a folder of ours: the way a panel colours a
     * shape itself, as it does its own icons.
     *
     * @param name   the icon's name in the folder
     * @param folder the folder
     */
    record Named(String name, String folder) {
    }

    /**
     * @param named the monochrome icon as a file the panel colours, or {@code null} to send
     *              pixels only
     */
    static Map<String, DBus.Variant> itemProperties(TrayState state, String id, Color monochromeTint,
            @Nullable Named named) {
        List<Object> pixmaps = pixmaps(state, monochromeTint);
        Map<String, DBus.Variant> p = new LinkedHashMap<>();
        p.put("Category", DBus.v("s", "ApplicationStatus"));
        p.put("Id", DBus.v("s", id));
        p.put("Title", DBus.v("s", state.title()));
        p.put("Status", DBus.v("s", status(state)));
        p.put("WindowId", DBus.v("i", 0));
        p.put("IconName", DBus.v("s", named == null ? "" : named.name()));
        p.put("IconPixmap", DBus.v("a(iiay)", pixmaps));
        p.put("OverlayIconName", DBus.v("s", ""));
        p.put("OverlayIconPixmap", DBus.v("a(iiay)", List.of()));
        p.put("AttentionIconName", DBus.v("s", named == null || !state.attention() ? "" : named.name()));
        // The same picture, which already carries what shows attention: a panel that swaps to
        // this one while the status asks and one that does not show the same thing.
        p.put("AttentionIconPixmap", DBus.v("a(iiay)", state.attention() ? pixmaps : List.of()));
        p.put("AttentionMovieName", DBus.v("s", ""));
        p.put("ToolTip", DBus.v("(sa(iiay)ss)", new Object[] {"", List.of(), tooltipTitle(state), ""}));
        p.put("IconThemePath", DBus.v("s", named == null ? "" : named.folder()));
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
    static List<Object> pixmaps(TrayState state, Color monochromeTint) {
        TrayPicture picture = state.picture();
        Color tint = null;
        if (picture == null) {
            picture = state.monochrome();
            tint = monochromeTint;
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

    /** Whether KDE's panel is the one that will show the item, which colours a named shape itself. */
    static boolean panelColoursNamedShapes(@Nullable String desktop) {
        return desktop != null && desktop.toUpperCase(java.util.Locale.ROOT).contains("KDE");
    }

    /**
     * A shape as a file a panel colours: a rectangle in the panel's text colour, which the panel
     * substitutes by the class it knows, seen through the shape as a mask. Read on a KDE guest on
     * 2026-10-11: the panel drew such a file, with the mask a picture inside it, in the white of
     * its own icons. The attention dot is a circle of its own colour over it, which the panel
     * leaves alone.
     *
     * @param maskPng     the shape as a PNG, white where it is
     * @param badge       the dot's colour, or {@code null}
     * @param badgeAtLeft whether the dot is at the top left
     * @return the file's text
     */
    static String symbolicSvg(byte[] maskPng, @Nullable Color badge, boolean badgeAtLeft) {
        StringBuilder svg = new StringBuilder(maskPng.length * 2 + 1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
                .append(" width=\"22\" height=\"22\" viewBox=\"0 0 22 22\"><defs>")
                .append("<style id=\"current-color-scheme\" type=\"text/css\">")
                .append(".ColorScheme-Text { color:#232629; }</style>")
                .append("<mask id=\"m\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"22\" height=\"22\">")
                .append("<image width=\"22\" height=\"22\" xlink:href=\"data:image/png;base64,")
                .append(java.util.Base64.getEncoder().encodeToString(maskPng))
                .append("\"/></mask></defs>")
                .append("<rect class=\"ColorScheme-Text\" fill=\"currentColor\" width=\"22\" height=\"22\"")
                .append(" mask=\"url(#m)\"/>");
        if (badge != null) {
            double radius = 22 * 0.22;
            svg.append(String.format(java.util.Locale.ROOT,
                    "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"#%02x%02x%02x\"/>",
                    badgeAtLeft ? radius : 22 - radius, radius, radius,
                    Color.toByte(badge.r()), Color.toByte(badge.g()), Color.toByte(badge.b())));
        }
        return svg.append("</svg>").toString();
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
    static List<MenuRow> shown(List<MenuRow> rows, String title) {
        return rows.isEmpty()
                ? List.of(new MenuRow(PLACEHOLDER_ROW, MenuRow.Kind.COMMAND, title, false, false, (char) 0, List.of()))
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
    static Object @Nullable [] layout(int parent, int depth, List<MenuRow> rows) {
        if (parent == 0) {
            Map<String, DBus.Variant> root = new LinkedHashMap<>();
            root.put("children-display", DBus.v("s", "submenu"));
            return new Object[] {0, root, children(rows, depth)};
        }
        MenuRow row = find(rows, parent);
        return row == null ? null : node(row, depth);
    }

    private static Object[] node(MenuRow row, int depth) {
        return new Object[] {row.id(), rowProperties(row), children(row.children(), depth)};
    }

    private static List<Object> children(List<MenuRow> rows, int depth) {
        List<Object> out = new ArrayList<>();
        if (depth != 0) {
            for (MenuRow row : rows) {
                out.add(DBus.v(NODE, node(row, depth - 1)));
            }
        }
        return out;
    }

    static @Nullable MenuRow find(List<MenuRow> rows, int id) {
        for (MenuRow row : rows) {
            if (row.id() == id) {
                return row;
            }
            MenuRow below = find(row.children(), id);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    /** A row's properties; what is at its default is left out, as the protocol asks. */
    static Map<String, DBus.Variant> rowProperties(MenuRow row) {
        Map<String, DBus.Variant> p = new LinkedHashMap<>();
        if (row.kind() == MenuRow.Kind.SEPARATOR) {
            p.put("type", DBus.v("s", "separator"));
            return p;
        }
        p.put("label", DBus.v("s", label(row)));
        if (!row.enabled()) {
            p.put("enabled", DBus.v("b", false));
        }
        if (row.kind() == MenuRow.Kind.CHECK) {
            p.put("toggle-type", DBus.v("s", "checkmark"));
            p.put("toggle-state", DBus.v("i", row.checked() ? 1 : 0));
        }
        if (row.kind() == MenuRow.Kind.SUBMENU) {
            p.put("children-display", DBus.v("s", "submenu"));
        }
        byte[] icon = iconPng(row);
        if (icon != null) {
            p.put("icon-data", DBus.v("ay", icon));
        }
        if (!row.shortcutKey().isEmpty()) {
            p.put("shortcut", DBus.v("aas", List.of(shortcut(row))));
        }
        return p;
    }

    /** The side a row's picture is sent at: a menu's line at up to twice the scale. */
    static final int ROW_ICON = 32;

    /**
     * A row's picture as a PNG, a tintable one in the grey that reads on a light and on a dark
     * menu, since the protocol says the colour of neither; {@code null} with no picture or no
     * encoder.
     */
    static byte @Nullable [] iconPng(MenuRow row) {
        if (row.icon() == null) {
            return null;
        }
        try {
            Image image = new TrayPicture(row.icon(), null, false).render(ROW_ICON, PANEL_GREY, false);
            return limn.graphics.Images.encode(image, limn.graphics.ImageFormat.PNG);
        } catch (RuntimeException noEncoder) {
            return null;
        }
    }

    /** An accelerator as the protocol writes one: the modifiers by name, then the key. */
    static List<String> shortcut(MenuRow row) {
        List<String> keys = new ArrayList<>();
        int modifiers = row.shortcutModifiers();
        if ((modifiers & limn.input.Keys.MOD_CONTROL) != 0) {
            keys.add("Control");
        }
        if ((modifiers & limn.input.Keys.MOD_ALT) != 0) {
            keys.add("Alt");
        }
        if ((modifiers & limn.input.Keys.MOD_SHIFT) != 0) {
            keys.add("Shift");
        }
        if ((modifiers & limn.input.Keys.MOD_SUPER) != 0) {
            keys.add("Super");
        }
        keys.add(row.shortcutKey());
        return keys;
    }

    /**
     * A label as the protocol reads one: an underscore marks the access key, so each of the
     * text's own is doubled, and one goes before the row's access letter where the text has it.
     */
    static String label(MenuRow row) {
        String text = row.label();
        int at = row.mnemonicAt();
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
