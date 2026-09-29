package limn.backend.lwjgl.a11y.linux;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The desktop's own file chooser, through the XDG desktop portal's
 * {@code org.freedesktop.portal.FileChooser}: the chooser GNOME, KDE and every sandbox (Flatpak,
 * Snap) provide, parented to the window that asked where the platform can name it ({@code x11:}
 * and the window's id; a Wayland window has no name GLFW can export, so the chooser is unparented
 * there). It lives beside the accessibility bridge because it speaks the same D-Bus client, which is
 * this package's.
 *
 * <p>One request is one connection on the calling thread, which the caller makes a thread of its
 * own: the {@code Response} signal is subscribed to before the call, on the request path the
 * {@code handle_token} option fixes in advance, so an answer can never arrive before anybody
 * listens for it.
 */
public final class PortalFileChooser {

    static final String PORTAL = "org.freedesktop.portal.Desktop";
    static final String PORTAL_PATH = "/org/freedesktop/portal/desktop";
    static final String FILE_CHOOSER = "org.freedesktop.portal.FileChooser";
    static final String REQUEST = "org.freedesktop.portal.Request";
    private static final String DRIVER = "org.freedesktop.DBus";
    private static final String DRIVER_PATH = "/org/freedesktop/DBus";

    private static final AtomicInteger TOKENS = new AtomicInteger();

    private PortalFileChooser() {
    }

    /**
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @return whether a portal can be asked at all from this process
     */
    public static boolean canAsk(@Nullable String sessionAddress) {
        return AtspiStatusWatch.canWatch(sessionAddress);
    }

    /**
     * Asks the portal and waits for the answer, on the calling thread.
     *
     * @param address     the session bus address
     * @param parent      the portal's name for the owner window ({@code "x11:1c00007"}), or {@code ""}
     * @param save        a save chooser rather than an open one
     * @param multiple    whether several files may be chosen (open only)
     * @param directory   whether a folder is chosen rather than a file (open only)
     * @param title       the chooser's title
     * @param initial     where it opens, and for a save the name it suggests; {@code null} for none
     * @param filterName  the filter's description, or {@code null} for none
     * @param patterns    the filter's glob patterns
     * @return the chosen paths, empty when cancelled
     * @throws IOException when the portal cannot be reached or refuses the request
     */
    public static List<Path> ask(String address, String parent, boolean save, boolean multiple,
                                 boolean directory, String title, @Nullable Path initial,
                                 @Nullable String filterName, List<String> patterns) throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(address);
        try {
            List<DBus.Msg> early = new ArrayList<>();
            java.util.function.Consumer<DBus.Msg> meanwhile = early::add;
            DBus.Msg hello = connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), meanwhile);
            String unique = String.valueOf(hello.body[0]);
            String token = "limn" + ProcessHandle.current().pid() + "_" + TOKENS.incrementAndGet();
            String handle = PORTAL_PATH + "/request/" + unique.substring(1).replace('.', '_') + "/" + token;
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "AddMatch", "s",
                    "type='signal',interface='" + REQUEST + "',member='Response',path='" + handle + "'"), meanwhile);

            Map<String, DBus.Variant> options = new LinkedHashMap<>();
            options.put("handle_token", DBus.v("s", token));
            options.put("modal", DBus.v("b", true));
            if (!save) {
                options.put("multiple", DBus.v("b", multiple));
                options.put("directory", DBus.v("b", directory));
            }
            if (filterName != null && !patterns.isEmpty() && !directory) {
                List<Object> globs = new ArrayList<>();
                for (String pattern : patterns) {
                    globs.add(new Object[] {0, pattern}); // (us): 0 is a glob
                }
                List<Object> filters = new ArrayList<>(); // not List.of(array), which spreads the array
                filters.add(new Object[] {filterName, globs});
                options.put("filters", DBus.v("a(sa(us))", filters));
            }
            if (initial != null) {
                Path folder = Files.isDirectory(initial) ? initial : initial.getParent();
                if (folder != null && Files.isDirectory(folder)) {
                    options.put("current_folder", DBus.v("ay", bytes(folder.toAbsolutePath().toString())));
                }
                if (save && !Files.isDirectory(initial) && initial.getFileName() != null) {
                    options.put("current_name", DBus.v("s", initial.getFileName().toString()));
                }
            }
            connection.callHere(DBus.Msg.call(PORTAL, PORTAL_PATH, FILE_CHOOSER, save ? "SaveFile" : "OpenFile",
                    "ssa{sv}", parent, title, options), meanwhile);

            for (DBus.Msg m : early) {
                List<Path> answer = responseIn(m, handle);
                if (answer != null) {
                    return answer;
                }
            }
            while (true) {
                DBus.Msg m = connection.readHere();
                List<Path> answer = responseIn(m, handle);
                if (answer != null) {
                    return answer;
                }
                if (m.type == DBus.METHOD_CALL && (m.flags & DBus.NO_REPLY_EXPECTED) == 0) {
                    connection.writeHere(DBus.Conn.replyFor(null, connection, m));
                }
            }
        } catch (DBus.DBusError refused) {
            throw new IOException("the portal refused the chooser: " + refused.getMessage(), refused);
        } finally {
            connection.close();
        }
    }

    /**
     * The answer a message carries for {@code handle}, or {@code null} when it is not that request's
     * {@code Response}: code 0 is chosen, anything else (1 cancelled, 2 ended otherwise) is empty.
     */
    static @Nullable List<Path> responseIn(DBus.Msg m, String handle) {
        if (m.type != DBus.SIGNAL || !"Response".equals(m.member) || !REQUEST.equals(m.iface)
                || !handle.equals(m.path) || m.body.length < 2) {
            return null;
        }
        if (!(m.body[0] instanceof Number code) || code.intValue() != 0
                || !(m.body[1] instanceof Map<?, ?> results)) {
            return List.of();
        }
        Object uris = results.get("uris");
        if (uris instanceof DBus.Variant variant) {
            uris = variant.value;
        }
        List<Path> paths = new ArrayList<>();
        if (uris instanceof List<?> list) {
            for (Object uri : list) {
                try {
                    paths.add(Path.of(URI.create(String.valueOf(uri))));
                } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException notAFile) {
                    // A portal on a document store can answer a non-file URI; it is not a path here.
                }
            }
        }
        return paths;
    }

    /** A NUL-terminated byte string, the portal's {@code ay} for a path. */
    private static List<Object> bytes(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        List<Object> out = new ArrayList<>(raw.length + 1);
        for (byte b : raw) {
            out.add(b);
        }
        out.add((byte) 0);
        return out;
    }
}
