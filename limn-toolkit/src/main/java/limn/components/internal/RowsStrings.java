package limn.components.internal;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PluralString;
import limn.i18n.PropertyBundle;

/**
 * What a list, a table or a tree says while it loads: the line its default loading content draws,
 * and what a reader is told when a load begins and when it lands.
 *
 * <p>Its own file family, registered when this class is first touched, as {@code TreeStrings} and
 * {@code TableStrings} do. The words are the tree's for its own branches, translated once there and
 * copied here for the whole widget, so the three widgets say a load the same way in every language.
 */
final class RowsStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/rows"));
    }

    private RowsStrings() {
    }

    // The muted line of the default loading content: words and not only a moving bar, because a
    // bar says something is happening and this says what.
    static final I18nString LOADING = new I18nString("limn.rows.loading", "Loading…");

    // What a reader is told when a load begins, with the widget's name, because an announcement
    // arrives with no context of its own: "Loading" alone names nothing. A widget with no name is
    // told the line above.
    //
    // An announcement as well as the BUSY state, and the reason is measured: BUSY reaches Windows
    // as ItemStatus, which NVDA was heard saying nothing for (ADR 044's busy runs), while the
    // announcement path is the one all three readers were heard speaking through.
    static final I18nString LOADING_ANNOUNCEMENT =
            new I18nString("limn.rows.loadingAnnouncement", "Loading {0}");

    // What a reader is told when the load lands: the widget's name and how many rows it brought,
    // which is what tells an arrival from a load still running. A plural, because a number in a
    // sentence is not one string per language.
    static final PluralString LOADED_ANNOUNCEMENT = PluralString.of(
            "limn.rows.loadedAnnouncement", "{0}, {1} item", "{0}, {1} items");

    // The same, for a widget with no name to say.
    static final PluralString ITEMS = PluralString.of("limn.rows.items", "{0} item", "{0} items");
}
