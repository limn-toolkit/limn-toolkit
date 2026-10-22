package limn.print;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The print preview's vocabulary: what a reader hears of it. Its own file family, registered when
 * this class is first touched, as {@code TableStrings} is.
 */
final class PrintStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/print"));
    }

    private PrintStrings() {
    }

    /** The preview's name. */
    static final I18nString PREVIEW = new I18nString("limn.print.preview", "Print preview");

    /** Where the reader is: the page in the middle of the view, and how many there are. */
    static final I18nString PAGE_OF = new I18nString("limn.print.pageOf", "Page {0} of {1}");
}
