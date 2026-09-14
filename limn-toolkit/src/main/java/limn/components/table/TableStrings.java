package limn.components.table;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The table's own vocabulary, which today is what a header cell says about the order it holds.
 *
 * <p>Its own file family, registered when this class is first touched, exactly as
 * {@code TreeStrings} and {@code DateStrings} do: a domain nobody uses costs nothing, and touching
 * one of its strings is what makes its translations available.
 */
final class TableStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/table"));
    }

    private TableStrings() {
    }

    // The sort direction a header cell carries, as its accessible description, so a reader on the
    // header hears which way the rows run (decision 36 of 2026-09-14). A description and not a
    // facet, until the three platforms' carriers of a sort direction have been read (phase 3);
    // the words are what the chevron draws.
    static final I18nString SORTED_ASCENDING =
            new I18nString("limn.table.sortedAscending", "Sorted ascending");
    static final I18nString SORTED_DESCENDING =
            new I18nString("limn.table.sortedDescending", "Sorted descending");
}
