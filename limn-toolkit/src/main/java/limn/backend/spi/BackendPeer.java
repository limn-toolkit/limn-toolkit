package limn.backend.spi;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import org.jspecify.annotations.Nullable;

/**
 * What the toolkit asks of a backend below the application's view of it: the modal stack, and
 * windows it can reach as peers. Every {@link Backend} is one, so the toolkit reaches this with
 * {@link #of(Backend)}; a backend implements it.
 *
 * <p><b>Incubating</b>, as the rest of this package: it may change in a minor release.
 */
public non-sealed interface BackendPeer extends Backend {

    /**
     * @param backend any backend
     * @return the same backend as a peer; every backend is one, since {@link Backend} permits no
     *         other implementation
     */
    static BackendPeer of(Backend backend) {
        return (BackendPeer) backend;
    }

    /** Creates (but does not necessarily show) a native window, as its peer. UI thread only. */
    @Override
    WindowPeer createWindow(WindowConfig config);

    /**
     * Registers {@code modal} as an active modal window and pushes it on the
     * modal stack. Only the topmost modal receives input; every window it
     * locks ignores input until it closes.
     *
     * <p>Modality scope: a non-null {@code parent} makes it <em>window-modal</em>,
     * so it locks only {@code parent} (and {@code parent}'s owned popups),
     * leaving unrelated windows interactive. A {@code null} parent makes it
     * <em>toolkit-modal</em>, so it locks every other window. Modals stack: with
     * several open, only the top one is usable and the non-modal windows are
     * released only after the whole modal stack closes. UI thread only.
     */
    void pushModal(NativeWindow modal, @Nullable NativeWindow parent);

    /** Removes {@code modal} from the modal stack, releasing what it locked. UI thread only. */
    void popModal(NativeWindow modal);

    /**
     * Registers an <em>in-scene</em> (overlay) modal hosted by {@code owner}.
     * Unlike {@link #pushModal}, there is no separate modal window: the overlay
     * blocks {@code owner}'s own content, so {@code owner} stays interactive,
     * while this call blocks the same <em>sibling</em> windows a native modal
     * would. {@code toolkitScope} {@code true} locks every other window;
     * {@code false} locks only {@code owner}'s owned popups. Release the
     * returned handle when the overlay closes. UI thread only.
     */
    SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope);

    /** Handle to an in-scene modal (see {@link #pushSceneModal}); {@link #release} it on close. */
    interface SceneModalHandle {
        /** Releases the in-scene modal, unblocking what it locked. UI thread only. */
        void release();
    }

    /**
     * Gives the modal-blocked feedback (the alert sound plus a re-raise of the
     * top modal) for an interaction the active modal ignores. The backend
     * already does this for clicks on blocked native windows; call it for an
     * in-scene modal's scrim so internal and native modals feel identical.
     * UI thread only.
     */
    void signalModalBlocked();

    /**
     * Makes the peer of a tray icon. Nothing is shown until its first {@link TrayPeer#update}.
     * UI thread only.
     *
     * <p>The default is the answer of a backend with no desktop under it: a peer that shows
     * nothing and is never available.
     *
     * @param id   the application's identifier for this icon: ASCII letters, digits, a dot, a
     *             hyphen and an underscore, already checked, and no other tray of this backend has it
     * @param host what the peer asks the menu of and tells of a click
     * @return the peer
     */
    default TrayPeer createTray(String id, TrayHost host) {
        return TrayPeer.NONE;
    }
}
