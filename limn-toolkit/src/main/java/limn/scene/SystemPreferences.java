package limn.scene;

import limn.graphics.Color;

import limn.concurrent.Ui;
import limn.concurrent.internal.ChangeListeners;
import limn.scene.internal.SystemPreferencesAccess;
import org.jspecify.annotations.Nullable;

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
 * SystemPreferences.observeChanges(() -> pickTheme(SystemPreferences.current()).apply());
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
 * <p><b>A class and not a record</b>, with no public constructor: only a backend builds one. A
 * record's constructor and its deconstruction pattern would have made the four preferences of
 * today a contract, so that a fifth, which a system may offer in a later 1.x release, could not be
 * added without breaking every caller that builds or deconstructs one. An application reads the
 * accessors, compares two values with {@link #equals}, and starts from {@link #NONE}.
 */
public final class SystemPreferences {

    /**
     * A light or dark appearance, or none chosen.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
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

    /**
     * No preference, no reduced motion, no increased contrast, access keys underlined only while
     * Alt is held: what a platform that says nothing reports.
     */
    public static final SystemPreferences NONE =
            new SystemPreferences(ColorScheme.NO_PREFERENCE, false, false, false, null);

    /**
     * The palette a user chose when the platform's request for more contrast is a palette of its
     * own: a Windows contrast theme, whose eight system colours every native application draws
     * with. Each pair a control draws from them is the user's to read; nothing here adjusts them.
     *
     * @param window        the canvas of a window and of every surface on it
     * @param windowText    text, and the edge of every control
     * @param link          a link, and a hot item
     * @param disabledText  anything disabled
     * @param highlight     a selection's fill
     * @param highlightText text on a selection
     * @param buttonFace    a button's face
     * @param buttonText    a button's text
     */
    public record ContrastColors(Color window, Color windowText, Color link, Color disabledText,
                                 Color highlight, Color highlightText, Color buttonFace, Color buttonText) {

        /**
         * @throws NullPointerException if any colour is {@code null}
         */
        public ContrastColors {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(windowText, "windowText");
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(disabledText, "disabledText");
            Objects.requireNonNull(highlight, "highlight");
            Objects.requireNonNull(highlightText, "highlightText");
            Objects.requireNonNull(buttonFace, "buttonFace");
            Objects.requireNonNull(buttonText, "buttonText");
        }
    }

    private static volatile SystemPreferences current = NONE;

    private static final ChangeListeners LISTENERS = new ChangeListeners();

    static {
        // The one way to build a value from outside this package: the backends' readers and the
        // tests reach it through an internal class that an application does not name.
        SystemPreferencesAccess.install(SystemPreferences::new);
    }

    private final ColorScheme colorScheme;
    private final boolean reduceMotion;
    private final boolean increaseContrast;
    private final boolean underlineAccessKeys;
    private final @Nullable ContrastColors contrastColors;

    /** The preferences with no palette chosen for more contrast. */
    SystemPreferences(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
            boolean underlineAccessKeys) {
        this(colorScheme, reduceMotion, increaseContrast, underlineAccessKeys, null);
    }

    /**
     * @param colorScheme         the appearance; never {@code null}
     * @param reduceMotion        whether less motion is asked for
     * @param increaseContrast    whether more contrast is asked for
     * @param underlineAccessKeys whether access keys are underlined at all times
     * @param contrastColors      the palette the user chose for more contrast, or {@code null}
     * @throws NullPointerException if {@code colorScheme} is {@code null}
     */
    SystemPreferences(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
            boolean underlineAccessKeys, @Nullable ContrastColors contrastColors) {
        this.colorScheme = Objects.requireNonNull(colorScheme, "colorScheme");
        this.reduceMotion = reduceMotion;
        this.increaseContrast = increaseContrast;
        this.underlineAccessKeys = underlineAccessKeys;
        this.contrastColors = contrastColors;
    }

    /** @return the appearance the user chose, or {@link ColorScheme#NO_PREFERENCE} */
    public ColorScheme colorScheme() {
        return colorScheme;
    }

    /** @return whether the user asked for less motion */
    public boolean reduceMotion() {
        return reduceMotion;
    }

    /** @return whether the user asked for more contrast */
    public boolean increaseContrast() {
        return increaseContrast;
    }

    /**
     * @return the palette the user chose for more contrast where the platform has one (a Windows
     *         contrast theme, while {@link #increaseContrast()} is true); {@code null} elsewhere,
     *         where more contrast keeps the application's palette and strengthens it
     */
    public @Nullable ContrastColors contrastColors() {
        return contrastColors;
    }

    /**
     * @return whether the user asked for access keys to be underlined at all times; {@code false}
     *         on every system but Windows
     */
    public boolean underlineAccessKeys() {
        return underlineAccessKeys;
    }

    /** Equal when every preference is: what {@link #report} compares to tell a change. */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SystemPreferences other
                && colorScheme == other.colorScheme
                && reduceMotion == other.reduceMotion
                && increaseContrast == other.increaseContrast
                && underlineAccessKeys == other.underlineAccessKeys
                && Objects.equals(contrastColors, other.contrastColors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorScheme, reduceMotion, increaseContrast, underlineAccessKeys, contrastColors);
    }

    /** Every preference by name, for a log line. */
    @Override
    public String toString() {
        return "SystemPreferences[colorScheme=" + colorScheme + ", reduceMotion=" + reduceMotion
                + ", increaseContrast=" + increaseContrast + ", underlineAccessKeys=" + underlineAccessKeys
                + (contrastColors == null ? "" : ", contrastColors=" + contrastColors) + "]";
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
     * signals a change, and {@link #NONE} when it closes; a test calls it to put back what it found
     * in {@link #current()}. Listeners run only when the value differs from the current one. UI
     * thread only.
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
        SystemPreferences before = current;
        current = preferences;
        if (before.increaseContrast != preferences.increaseContrast
                || !Objects.equals(before.contrastColors, preferences.contrastColors)) {
            // The palette every widget paints with follows contrast unless the application
            // refused it: every scene repaints, as on a theme switch.
            limn.scene.internal.SceneBackground.changed();
        }
        LISTENERS.fire();
    }
}
