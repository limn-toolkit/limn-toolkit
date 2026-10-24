package limn.scene.internal;

import org.jspecify.annotations.Nullable;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;

/**
 * Not API. The way to build a {@link SystemPreferences} outside {@code limn.scene}: for a backend
 * that reads the platform's preferences, and for a test that puts one in place. The value has no
 * public constructor, so that a preference can be added in a later release without breaking an
 * application; what builds one is here, and this class changes with it.
 *
 * <p>The value installs the factory from its own static initializer, and only it can: an install
 * from any other class is refused, so the factory cannot be replaced by whoever loads first.
 */
public final class SystemPreferencesAccess {

    /** What {@link SystemPreferences} lends this class: its constructor. */
    @FunctionalInterface
    public interface Factory {
        /**
         * @param colorScheme         the appearance; never {@code null}
         * @param reduceMotion        whether less motion is asked for
         * @param increaseContrast    whether more contrast is asked for
         * @param underlineAccessKeys whether access keys are underlined at all times
         * @param contrastColors      the palette chosen for more contrast, or {@code null}
         * @return the value
         */
        SystemPreferences create(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
                boolean underlineAccessKeys, SystemPreferences.@Nullable ContrastColors contrastColors);
    }

    private static volatile Factory factory;

    private SystemPreferencesAccess() {
    }

    /**
     * Called once, by {@link SystemPreferences}' static initializer.
     *
     * @param constructor the value's constructor
     * @throws IllegalStateException when called by any other class, or twice
     */
    public static void install(Factory constructor) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (caller != SystemPreferences.class || factory != null) {
            throw new IllegalStateException("only SystemPreferences installs its factory, once");
        }
        factory = java.util.Objects.requireNonNull(constructor, "constructor");
    }

    /**
     * The preferences a platform reported.
     *
     * @param colorScheme         the appearance the user chose; never {@code null}
     * @param reduceMotion        whether the user asked for less motion
     * @param increaseContrast    whether the user asked for more contrast
     * @param underlineAccessKeys whether the user asked for access keys to be underlined at all
     *                            times; {@code false} on every system but Windows
     * @return the value
     * @throws NullPointerException if {@code colorScheme} is {@code null}
     */
    public static SystemPreferences of(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
            boolean underlineAccessKeys) {
        return of(colorScheme, reduceMotion, increaseContrast, underlineAccessKeys, null);
    }

    /**
     * The preferences a platform reported, with the palette its user chose for more contrast.
     *
     * @param colorScheme         the appearance the user chose; never {@code null}
     * @param reduceMotion        whether the user asked for less motion
     * @param increaseContrast    whether the user asked for more contrast
     * @param underlineAccessKeys whether the user asked for access keys to be underlined at all
     *                            times
     * @param contrastColors      the palette chosen for more contrast (a Windows contrast theme's
     *                            system colours), or {@code null}
     * @return the value
     * @throws NullPointerException if {@code colorScheme} is {@code null}
     */
    public static SystemPreferences of(ColorScheme colorScheme, boolean reduceMotion, boolean increaseContrast,
            boolean underlineAccessKeys, SystemPreferences.@Nullable ContrastColors contrastColors) {
        Factory installed = factory;
        if (installed == null) {
            // NONE is the first thing a caller would otherwise touch; reading it initializes the
            // class that installs the factory.
            java.util.Objects.requireNonNull(SystemPreferences.NONE);
            installed = factory;
        }
        return installed.create(colorScheme, reduceMotion, increaseContrast, underlineAccessKeys, contrastColors);
    }
}
