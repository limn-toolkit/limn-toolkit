package limn.backend.lwjgl;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The backend's own user-visible text: one string, and it is the parameterized one.
 *
 * <p>A domain per module rather than per repo: this family ships in the backend jar,
 * so a build that swaps the backend takes its translations with it and the
 * components' files are untouched.
 */
final class DisplayStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/display"));
    }

    /**
     * The name a monitor gets when the platform reports none. The argument is passed
     * pre-formatted as text: {@code MessageFormat} localizes a number, which for this
     * string would mean grouping separators past 999 and, in some locales, digits the
     * text pipeline cannot draw.
     */
    static final I18nString FALLBACK_NAME =
            new I18nString("limn.display.fallbackName", "Display {0}");

    private DisplayStrings() {
    }
}
