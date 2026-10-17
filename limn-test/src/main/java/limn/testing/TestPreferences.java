package limn.testing;

import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;

import java.util.Objects;

/**
 * The system's preferences as a test puts them in place: a dark appearance, less motion, more
 * contrast, access keys underlined at all times. What {@link SystemPreferences#current()} answers,
 * and what its listeners hear, is what a backend would have reported on a desktop set that way,
 * so an application's own test can check that it follows the system.
 *
 * <p>Open it in {@code @BeforeEach} or a {@code try} block, after the {@link HeadlessUi}, and close
 * it before that: closing puts back what {@link SystemPreferences#current()} answered when it was
 * opened, which in a headless test is {@link SystemPreferences#NONE}. Each setter reports at once,
 * so a listener hears a change as it would from the platform, and a value that does not change is
 * not heard.
 *
 * <pre>{@code
 * try (TestPreferences system = new TestPreferences()) {
 *     system.colorScheme(ColorScheme.DARK);
 *     assertTrue(myTheme.isDark());
 * }
 * }</pre>
 *
 * <p>Everything here runs on the UI thread, as {@link SystemPreferences#report} does.
 */
public final class TestPreferences implements AutoCloseable {

    private final SystemPreferences before;
    private ColorScheme colorScheme;
    private boolean reduceMotion;
    private boolean increaseContrast;
    private boolean underlineAccessKeys;

    /** Starts from what {@link SystemPreferences#current()} answers now, and changes nothing yet. */
    public TestPreferences() {
        before = SystemPreferences.current();
        colorScheme = before.colorScheme();
        reduceMotion = before.reduceMotion();
        increaseContrast = before.increaseContrast();
        underlineAccessKeys = before.underlineAccessKeys();
    }

    /**
     * @param scheme the appearance the system reports now; never {@code null}
     * @return this
     */
    public TestPreferences colorScheme(ColorScheme scheme) {
        colorScheme = Objects.requireNonNull(scheme, "scheme");
        return report();
    }

    /**
     * @param reduce whether the system reports that the user asked for less motion
     * @return this
     */
    public TestPreferences reduceMotion(boolean reduce) {
        reduceMotion = reduce;
        return report();
    }

    /**
     * @param increase whether the system reports that the user asked for more contrast
     * @return this
     */
    public TestPreferences increaseContrast(boolean increase) {
        increaseContrast = increase;
        return report();
    }

    /**
     * @param underline whether the system reports that access keys are underlined at all times
     * @return this
     */
    public TestPreferences underlineAccessKeys(boolean underline) {
        underlineAccessKeys = underline;
        return report();
    }

    private TestPreferences report() {
        SystemPreferences.report(SystemPreferencesAccess.of(colorScheme, reduceMotion, increaseContrast,
                underlineAccessKeys));
        return this;
    }

    /** Puts back what the system reported when this was opened. */
    @Override
    public void close() {
        SystemPreferences.report(before);
    }
}
