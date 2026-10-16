package limn.scene;

import limn.concurrent.Ui;
import limn.concurrent.internal.ChangeListeners;

import java.util.Objects;

/**
 * Three preferences the user sets for every application on their desktop: a light or dark
 * appearance, less motion, and more contrast. The backend reads them from the platform and reports
 * them here; an application reads {@link #current()} and follows changes with
 * {@link #observeChanges}.
 *
 * <p><b>The toolkit reports; the application decides.</b> A dark system does not switch an
 * application's theme, because which palette to use is the application's choice, and its theme may
 * be its brand. Following the system is one line:
 *
 * <pre>{@code
 * SystemPreferences.observeChanges(() -> pickTheme(SystemPreferences.current()).apply(scene));
 * }</pre>
 *
 * <p>The one preference the toolkit acts on by itself is {@link #reduceMotion()}: while it is set,
 * a {@link limn.animation.Transition} finishes at once and popups and dialogs open and close without
 * fading; see {@link limn.animation.Motion}.
 *
 * <p>What each platform answers: on macOS the application's effective appearance and the
 * accessibility display options; on Windows the apps' light-theme setting, client-area animation and
 * high contrast; on Linux the desktop portal's appearance settings, with GNOME's
 * {@code enable-animations} where the portal has no reduced-motion key. A platform that cannot say
 * reports {@link ColorScheme#NO_PREFERENCE} and {@code false}, as Ubuntu's default appearance does.
 *
 * <p>The toolkit also follows {@link #underlineAccessKeys()}: while it is set, the access letters
 * of menus, buttons and labels stay underlined, where otherwise they show only while Alt is held.
 * Windows answers it (the keyboard setting to underline access keys); the other systems report
 * {@code false}.
 *
 * @param colorScheme         the appearance the user chose, or {@link ColorScheme#NO_PREFERENCE}
 * @param reduceMotion        whether the user asked for less motion
 * @param increaseContrast    whether the user asked for more contrast
 * @param underlineAccessKeys whether the user asked for access keys to be underlined at all times
 */
public record SystemPreferences(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
        boolean underlineAccessKeys) {

    /** A light or dark appearance, or none chosen. */
    public enum ColorScheme {

        /** The user chose a light appearance. */
        LIGHT,

        /** The user chose a dark appearance. */
        DARK,

        /**
         * The platform says nothing: no preference was set, or none can be read. An application
         * uses its own default.
         */
        NO_PREFERENCE
    }

    /** No preference, no reduced motion, no increased contrast: what a platform that says nothing reports. */
    public static final SystemPreferences NONE =
            new SystemPreferences(ColorScheme.NO_PREFERENCE, false, false, false);

    private static volatile SystemPreferences current = NONE;

    private static final ChangeListeners LISTENERS = new ChangeListeners();

    /**
     * @param colorScheme      the appearance; never {@code null}
     * @param reduceMotion     whether less motion is asked for
     * @param increaseContrast whether more contrast is asked for
     * @throws NullPointerException if {@code colorScheme} is {@code null}
     */
    public SystemPreferences {
        Objects.requireNonNull(colorScheme, "colorScheme");
    }

    /**
     * Preferences with access keys underlined only while Alt is held: what every system but
     * Windows reports.
     *
     * @param colorScheme      the appearance; never {@code null}
     * @param reduceMotion     whether less motion is asked for
     * @param increaseContrast whether more contrast is asked for
     */
    public SystemPreferences(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast) {
        this(colorScheme, reduceMotion, increaseContrast, false);
    }

    /**
     * @return the preferences the backend last reported, or {@link #NONE} before any backend did.
     *         Any thread.
     */
    public static SystemPreferences current() {
        return current;
    }

    /**
     * Subscribes to changes. A listener runs on the UI thread once per change of the value, however
     * many signals the platform sent for it.
     *
     * @param listener what to run when the preferences change
     * @return a handle that unsubscribes; cancelling it twice is a no-op. UI thread
     */
    public static limn.concurrent.Subscription observeChanges(Runnable listener) {
        return LISTENERS.observe(listener);
    }

    /**
     * Reports what the platform says now. A backend calls it when it starts and whenever the platform
     * signals a change, and {@link #NONE} when it closes; a test calls it to put a preference in
     * place. Listeners run only when the value differs from the current one. UI thread only.
     *
     * @param preferences what the platform says; never {@code null}
     * @throws NullPointerException if {@code preferences} is {@code null}
     */
    public static void report(SystemPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        Ui.checkUiThread();
        if (preferences.equals(current)) {
            return;
        }
        current = preferences;
        LISTENERS.fire();
    }
}
