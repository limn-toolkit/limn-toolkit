package limn.backend.spi;

/**
 * What a backend does for {@link limn.desktop.Tray}: keep one icon in the system's place for one.
 *
 * <p>A backend owes four things. Every method here is called on the UI thread and <b>returns at
 * once</b>: what the system needs time for is done elsewhere. Every call it makes on its
 * {@link TrayHost} is <b>on the UI thread</b>. The menu is <b>asked of the host each time</b> the
 * system is about to open it, never kept from one opening to the next. And {@link #dispose} takes
 * the icon away, as does closing the backend.
 */
public interface TrayPeer {

    /**
     * Shows this state. The first call is what puts the icon in the tray.
     *
     * @param state everything the icon shows
     */
    void update(TrayState state);

    /**
     * Takes the icon out of the tray until the next {@link #update}, which puts it back as the
     * first one did. The peer is {@linkplain #isAvailable() not available} meanwhile. Nothing
     * happens when no icon is shown.
     */
    void hide();

    /**
     * @return whether the system took the icon. Not whether somebody sees it: no system says that
     */
    boolean isAvailable();

    /** Takes the icon away. Idempotent; nothing is called on the host afterwards. */
    void dispose();

    /** The peer of a backend with no desktop under it: no icon, and never available. */
    TrayPeer NONE = new TrayPeer() {
        @Override
        public void update(TrayState state) {
        }

        @Override
        public void hide() {
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void dispose() {
        }
    };
}
