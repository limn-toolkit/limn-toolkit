package limn.backend.lwjgl;

import limn.i18n.I18n;
import limn.i18n.PropertyBundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Shows {@link WindowsDriverDialog} on a Windows desktop without needing a machine that lacks a
 * driver, in the language given as the first argument, and logs the route it took to the file
 * given as the second: a {@code javaw.exe} or jpackage launcher has no stderr to read it from. A
 * third argument names a directory of {@code drvoverride_<tag>.properties} files whose keys win
 * over the shipped ones, for trying a wording on the real dialog. {@code -Dprobe.route=messagebox}
 * shows the plain message box fallback instead.
 *
 * <p>A live check, not part of what an application gets; see ADR 003 §5 for the run.
 */
public final class DriverDialogProbe {

    private DriverDialogProbe() {
    }

    public static void main(String[] args) throws IOException {
        String tag = args.length > 0 ? args[0] : "en";
        if (args.length > 1) {
            FileHandler file = new FileHandler(args[1], true);
            file.setFormatter(new SimpleFormatter());
            file.setLevel(Level.ALL);
            Logger root = Logger.getLogger("");
            root.addHandler(file);
            root.setLevel(Level.ALL);
            Logger.getLogger(WindowsDriverDialog.class.getName()).setLevel(Level.ALL);
        }
        if (args.length > 2) {
            // A directory of drvoverride_<tag>.properties files registered after the shipped
            // catalog, so it wins: one run per candidate wording, without a rebuild.
            // The shipped catalog registers when its class loads; load it first, or it would
            // register after these and win.
            DriverHelpStrings.HEADING.key();
            Path overrides = Path.of(args[2]);
            I18n.addBundle(PropertyBundle.family("/drvoverride", name -> {
                Path file = overrides.resolve(name.substring(1));
                try {
                    return Files.exists(file) ? Files.newInputStream(file) : null;
                } catch (IOException e) {
                    return null;
                }
            }));
        }
        I18n.setLocale(Locale.forLanguageTag(tag));
        if ("messagebox".equals(System.getProperty("probe.route"))) {
            // The last fallback, which no Windows 10 or 11 launcher reaches: every one tried gets
            // TaskDialogIndirect one way or the other. Called directly so it is seen at least once.
            try {
                Class<?> win32 = Class.forName(WindowsDriverDialog.class.getName() + "$Win32");
                var open = win32.getDeclaredMethod("open");
                open.setAccessible(true);
                var box = win32.getDeclaredMethod("showMessageBox", WindowsDriverDialog.Content.class);
                box.setAccessible(true);
                Object choice = box.invoke(open.invoke(null),
                        WindowsDriverDialog.content("Limn UI: Kitchen Sink", "probe", true));
                Logger.getLogger(DriverDialogProbe.class.getName()).info("message box: " + choice);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return;
        }
        WindowsDriverDialog.offer("Limn UI: Kitchen Sink", "glfwCreateWindow failed on the Win32 "
                + "platform: 65542 WGL: The driver does not appear to support OpenGL; Limn needs "
                + "an OpenGL 3.3 core context (probe: " + tag + ")");
        Logger.getLogger(DriverDialogProbe.class.getName()).info("probe done: " + tag);
    }
}
