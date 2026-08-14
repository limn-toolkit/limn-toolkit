package limn.graphics;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide font configuration (mirrors the {@link TextRulers} / {@code Ui}
 * facade lifecycle). Holds the {@linkplain #installCatalog installed}
 * {@link FontCatalog} (the list of usable families) and the current
 * {@linkplain #defaultFamily() default family} that {@link Font#DEFAULT_FAMILY}
 * resolves to, so an application can switch its UI font at runtime.
 *
 * <p>Both the catalog becoming available (system-font enumeration finishes) and
 * a default-family change notify {@linkplain #addChangeListener listeners} on
 * the calling thread; scenes subscribe to re-layout, and the backend's font
 * store subscribes to drop its resolution cache. Mutators are meant to be called
 * on the UI thread.
 */
public final class Fonts {

    private static volatile FontCatalog catalog = FontCatalog.EMPTY;
    private static volatile FontLoader loader = FontLoader.UNAVAILABLE;
    private static volatile String defaultFamily = Font.DEFAULT_FAMILY;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Fonts() {
    }

    /** Installs the backend's catalog (e.g. once system-font enumeration completes). */
    public static void installCatalog(FontCatalog newCatalog) {
        catalog = newCatalog == null ? FontCatalog.EMPTY : newCatalog;
        notifyListeners();
    }

    /** Resets to {@link FontCatalog#EMPTY} (backend shutdown). */
    public static void uninstallCatalog(FontCatalog current) {
        if (catalog == current) {
            catalog = FontCatalog.EMPTY;
        }
    }

    /** Installs the backend's file loader (backend startup). */
    public static void installLoader(FontLoader newLoader) {
        loader = newLoader == null ? FontLoader.UNAVAILABLE : newLoader;
    }

    /** Resets to {@link FontLoader#UNAVAILABLE} (backend shutdown). */
    public static void uninstallLoader(FontLoader current) {
        if (loader == current) {
            loader = FontLoader.UNAVAILABLE;
        }
    }

    /**
     * Registers a font file the application ships and returns the family name it declares, so
     * the family can then be named in a {@link Font} or in {@link #setDefaultFamily}.
     *
     * <p>UI thread. <b>Synchronous, and deliberately so; there is no asynchronous form.</b> What
     * this reads is the file's name table, which is kilobytes: the glyph outlines are parsed on
     * first use, exactly as they are for an operating-system face, so the large read this
     * facade could offload is not the read it performs. Offloading it would also defeat the
     * point of the call, because the family has to be registered before the caller builds the
     * first widget that names it; an asynchronous form would hand back a name that resolves to
     * the default face until some later frame, which is the silent-wrong-font failure this API
     * exists to remove.
     *
     * <p>A relative path resolves against the process working directory. That makes the call
     * dependent on where the process was started, so an application that ships a typeface
     * should resolve its own path from something it controls rather than assuming a directory.
     *
     * @throws UnsupportedOperationException if no backend is installed (nothing here can parse a
     *                                       font file; see {@link FontLoader})
     * @throws IllegalArgumentException      if the file carries no usable face
     */
    public static String load(java.nio.file.Path file) {
        String family = loader.load(Objects.requireNonNull(file, "file"));
        notifyListeners();
        return family;
    }

    /**
     * @return the available families (bundled + system), or empty when headless. Reads no file and
     *         does not block. It is the installed catalog's current answer, which may be a partial
     *         one: a backend that enumerates the operating system does so in the background and
     *         installs a fuller catalog when it finishes. Rebuild a family list from
     *         {@linkplain #addChangeListener a change listener} rather than reading this once at
     *         startup and trusting it.
     */
    public static List<String> available() {
        return catalog.families();
    }

    /** @return the family {@link Font#DEFAULT_FAMILY} currently resolves to */
    public static String defaultFamily() {
        return defaultFamily;
    }

    /**
     * Sets the family the default UI font resolves to; {@code null}/blank restores
     * {@link Font#DEFAULT_FAMILY}. No-op if unchanged. Notifies listeners so the
     * UI re-lays-out in the new font. UI thread.
     *
     * <p>This call itself reads nothing: it stores a name and runs the listeners, and there is no
     * asynchronous form for that reason. The cost it schedules is the part worth knowing. A backend
     * resolves families to font files lazily and drops its resolution cache when this fires, so the
     * <em>next</em> measure or paint resolves the new family, and if its face is not already
     * resident, that resolution reads and parses a font file (tens of megabytes for a CJK face) on
     * the UI thread, inside that frame. Switching the UI font is the one public act in the toolkit
     * that guarantees a large read inside the next frame, and it cannot be moved out of that frame
     * from here: nothing in this package can read a font file, so nothing here can make the face
     * resident first.
     *
     * <p>What follows from that is a placement rule, not a workaround. Call this from a settings
     * dialog or a menu, where one long frame reads as the switch happening; never from an
     * animation, a drag, or anything that must stay at frame rate. It costs once per face; the
     * second switch back to a family already read is cheap.
     */
    public static void setDefaultFamily(String family) {
        String value = family == null || family.isBlank() ? Font.DEFAULT_FAMILY : family;
        if (value.equals(defaultFamily)) {
            return;
        }
        defaultFamily = value;
        notifyListeners();
    }

    /** Subscribes to catalog/default-family changes (idempotent per instance). */
    public static void addChangeListener(Runnable listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Unsubscribes; no-op when it was never registered. */
    public static void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
