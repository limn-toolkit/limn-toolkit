package limn.backend.lwjgl.a11y.linux;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hands an address or a file to a Linux desktop: the XDG desktop portal's
 * {@code org.freedesktop.portal.OpenURI} for an address, {@code xdg-open} for a file and wherever
 * no portal answers, and {@code org.freedesktop.FileManager1} to show a file selected in its
 * folder. Beside the accessibility bridge because it speaks the same D-Bus client, which is this
 * package's. Every method blocks, and is called on a thread of its own.
 */
public final class DesktopOpener {

    private static final System.Logger LOG = System.getLogger(DesktopOpener.class.getName());

    private static final String OPEN_URI = "org.freedesktop.portal.OpenURI";
    private static final String FILE_MANAGER = "org.freedesktop.FileManager1";
    private static final String FILE_MANAGER_PATH = "/org/freedesktop/FileManager1";
    private static final String DRIVER = "org.freedesktop.DBus";
    private static final String DRIVER_PATH = "/org/freedesktop/DBus";

    /** How long {@code xdg-open} is waited for before it is taken to be showing what it opened. */
    private static final long XDG_OPEN_SECONDS = 10;

    private DesktopOpener() {
    }

    /**
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @param parent         the portal's name for the window that asks, or {@code ""}
     * @param address        the address
     * @return whether the portal, or {@code xdg-open} where none answers, took it
     */
    public static boolean openAddress(@Nullable String sessionAddress, String parent, URI address) {
        if (PortalFileChooser.canAsk(sessionAddress)) {
            try {
                Map<String, DBus.Variant> options = new LinkedHashMap<>();
                call(sessionAddress, PortalFileChooser.PORTAL, PortalFileChooser.PORTAL_PATH, OPEN_URI,
                        "OpenURI", "ssa{sv}", parent, address.toString(), options);
                return true;
            } catch (IOException | RuntimeException noPortal) {
                LOG.log(System.Logger.Level.INFO, "the desktop portal did not open the address; trying xdg-open",
                        noPortal);
            }
        }
        return xdgOpen(address.toString());
    }

    /**
     * @param file an absolute path
     * @return whether {@code xdg-open} took it
     */
    public static boolean openFile(Path file) {
        return xdgOpen(file.toString());
    }

    /**
     * @param sessionAddress {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @param file           an absolute path
     * @return whether a file manager was asked to show it, or its folder was opened where none
     *         answers to the name
     */
    public static boolean showInFolder(@Nullable String sessionAddress, Path file) {
        if (PortalFileChooser.canAsk(sessionAddress)) {
            try {
                List<Object> uris = new ArrayList<>();
                uris.add(file.toUri().toString());
                call(sessionAddress, FILE_MANAGER, FILE_MANAGER_PATH, FILE_MANAGER, "ShowItems", "ass", uris, "");
                return true;
            } catch (IOException | RuntimeException noFileManager) {
                LOG.log(System.Logger.Level.INFO,
                        "no file manager answers to FileManager1; opening the folder instead", noFileManager);
            }
        }
        Path folder = file.getParent();
        return xdgOpen((folder == null ? file : folder).toString());
    }

    /** One call on a connection of its own, on this thread; an error reply is thrown. */
    private static void call(String sessionAddress, String destination, String path, String iface,
                             String member, String signature, Object... arguments) throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(sessionAddress);
        try {
            java.util.function.Consumer<DBus.Msg> meanwhile = message -> { };
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), meanwhile);
            connection.callHere(DBus.Msg.call(destination, path, iface, member, signature, arguments), meanwhile);
        } catch (DBus.DBusError refused) {
            throw new IOException(destination + " refused " + member + ": " + refused.getMessage(), refused);
        } finally {
            connection.close();
        }
    }

    /**
     * {@code xdg-open}, which exits once it has handed its argument to a program: zero is taken,
     * anything else is not, and one still running after ten seconds is a desktop whose opener
     * stays for as long as what it opened, which is taken too.
     */
    static boolean xdgOpen(String target) {
        try {
            Process process = new ProcessBuilder(xdgOpenCommand(target))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(XDG_OPEN_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            if (process.exitValue() != 0) {
                LOG.log(System.Logger.Level.WARNING, "xdg-open answered {0} for {1}", process.exitValue(), target);
                return false;
            }
            return true;
        } catch (IOException notInstalled) {
            LOG.log(System.Logger.Level.WARNING, "not opened, xdg-open is not on this system: " + target,
                    notInstalled);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The command and its one argument, never a line for a shell to read. */
    static List<String> xdgOpenCommand(String target) {
        return List.of("xdg-open", target);
    }
}
