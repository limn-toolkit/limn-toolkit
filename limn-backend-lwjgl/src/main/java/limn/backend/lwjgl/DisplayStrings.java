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
        // This module opens the catalog itself: on the module path it sits in a package of the
        // backend's that no class loader can read.
        I18n.addBundle(PropertyBundle.family("/limn/backend/lwjgl/i18n/display",
                name -> DisplayStrings.class.getResourceAsStream(name)));
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
