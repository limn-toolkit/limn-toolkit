package limn.backend.spi;

import java.util.List;

/**
 * What a tray's backend asks of the toolkit and tells it. Every method is called <b>on the UI
 * thread</b>; a backend that hears of a click on another thread comes to the UI thread first.
 */
public interface TrayHost {

    /**
     * Reads the application's menu now. A backend calls it each time the system is about to open
     * the menu, and hands the system these rows.
     *
     * @return the top-level rows; empty when the application set no menu
     */
    List<MenuRow> menu();

    /**
     * The user chose a row.
     *
     * @param id the row's number in the reading {@link #menu} last returned; a number that reading
     *           does not have is ignored
     */
    void choose(int id);

    /** The user activated the icon. Called only while the state says the icon activates. */
    void activate();

    /** {@link TrayPeer#isAvailable} changed. */
    void availabilityChanged();
}
