package limn.backend;

import limn.backend.spi.LauncherPeer;
import limn.concurrent.Ui;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands an address or a file to the system the window is on: a web page to the browser, a
 * {@code mailto:} to the mail program, a file to the program registered for its type, or a file
 * to the file manager, shown selected in its folder. Reached from a window,
 * {@link NativeWindow#launcher()}, because the system is told which window asked and brings what
 * it opens in front of it.
 *
 * <pre>{@code
 * window.launcher().openAddress(URI.create("https://example.org/docs"));
 * window.launcher().openFile(report);
 * window.launcher().showInFolder(report);
 * }</pre>
 *
 * <p><b>Each call returns at once</b> and its stage completes on the UI thread. {@code true}
 * means the system took what it was handed, which is all a system tells a caller: not that a
 * program opened, nor that the user saw it. {@code false} means it was not handed over (an
 * address this class refuses, a file that is not there, no program registered, a window with no
 * desktop under it), and the reason is logged as a warning. UI thread only.
 *
 * <p><b>Which addresses.</b> An address often comes from data, and handed to a system as it is,
 * text can start a program. So {@link #openAddress} takes an absolute address whose scheme is
 * {@code http}, {@code https} or {@code mailto}, and refuses the rest until the application says,
 * once and for the process, that its addresses may be of another kind: {@link #allowScheme}. A
 * {@code file:} address is never taken: a file is opened by {@link #openFile}, whose argument is a
 * path the application built.
 */
public final class Launcher {

    /** The launcher of a window with no desktop under it (headless, embedded): always {@code false}. */
    public static final Launcher NONE = new Launcher(LauncherPeer.NONE);

    private static final System.Logger LOG = System.getLogger(Launcher.class.getName());

    private static final Set<String> SCHEMES = ConcurrentHashMap.newKeySet();

    static {
        SCHEMES.addAll(Set.of("http", "https", "mailto"));
    }

    private final LauncherPeer peer;

    /**
     * For a backend: the launcher of one of its windows.
     *
     * @param peer what hands things to the system
     */
    public Launcher(LauncherPeer peer) {
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    /**
     * Lets {@link #openAddress} take addresses of one more scheme, for the whole process: a
     * {@code tel:} number, the application's own protocol. Said once where the application
     * starts, since it is a fact about the application and not about one call. Any thread.
     *
     * @param scheme the scheme, without its colon; case does not matter
     * @throws IllegalArgumentException for {@code file}, which {@link #openFile} opens, and for a
     *                                  text that is not a scheme
     */
    public static void allowScheme(String scheme) {
        String name = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        if (name.equals("file")) {
            throw new IllegalArgumentException("a file is opened by openFile, which takes a path");
        }
        if (!name.matches("[a-z][a-z0-9+.-]*")) {
            throw new IllegalArgumentException("not a scheme: " + scheme);
        }
        SCHEMES.add(name);
    }

    /**
     * Whether {@link #openAddress} would hand this address to the system: it is absolute, its
     * scheme is allowed, and a web address names a host. Says nothing of whether a program is
     * registered for it. Any thread.
     *
     * @param address the address
     * @return whether it is one this class takes
     */
    public static boolean isAllowed(URI address) {
        return refusal(Objects.requireNonNull(address, "address")) == null;
    }

    private static String refusal(URI address) {
        String scheme = address.getScheme();
        if (scheme == null) {
            return "it is not absolute";
        }
        String name = scheme.toLowerCase(Locale.ROOT);
        if (name.equals("file")) {
            return "a file is opened by openFile";
        }
        if (!SCHEMES.contains(name)) {
            return "the scheme " + name + " is not allowed; see Launcher.allowScheme";
        }
        if ((name.equals("http") || name.equals("https")) && (address.getHost() == null
                || address.getHost().isEmpty())) {
            return "it names no host";
        }
        return null;
    }

    /**
     * Opens an address in the program the system has for it.
     *
     * @param address an absolute address of an allowed scheme
     * @return whether the system took it; {@code false} at once for an address this class refuses
     */
    public CompletionStage<Boolean> openAddress(URI address) {
        Objects.requireNonNull(address, "address");
        Ui.checkUiThread();
        String refusal = refusal(address);
        if (refusal != null) {
            LOG.log(System.Logger.Level.WARNING, "not opened, {0}: {1}", refusal, address);
            return CompletableFuture.completedFuture(false);
        }
        return peer.openAddress(address);
    }

    /**
     * Opens a file in the program registered for its type, as a double click in the file manager
     * does. For a program file that means running it.
     *
     * @param file the file, or a folder, which opens in the file manager
     * @return whether the system took it; {@code false} at once for a path that does not exist
     */
    public CompletionStage<Boolean> openFile(Path file) {
        Ui.checkUiThread();
        Path there = existing(file);
        return there == null ? CompletableFuture.completedFuture(false) : peer.openFile(there);
    }

    /**
     * Shows a file in the file manager, selected in the folder it is in. Where the system can
     * open the folder but not select in it, the folder opens and the answer is still
     * {@code true}.
     *
     * @param file the file or folder to show
     * @return whether the file manager was asked; {@code false} at once for a path that does not
     *         exist
     */
    public CompletionStage<Boolean> showInFolder(Path file) {
        Ui.checkUiThread();
        Path there = existing(file);
        return there == null ? CompletableFuture.completedFuture(false) : peer.showInFolder(there);
    }

    private static Path existing(Path file) {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            LOG.log(System.Logger.Level.WARNING, "not opened, there is no such file: {0}", absolute);
            return null;
        }
        return absolute;
    }
}
