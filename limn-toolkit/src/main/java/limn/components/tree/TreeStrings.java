package limn.components.tree;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The tree's own vocabulary, which today is the one line it draws under a row whose children are
 * still on their way.
 *
 * <p>Its own file family, registered when this class is first touched, exactly as
 * {@code DateStrings} and {@code ColorPickerStrings} do: a domain nobody uses costs nothing, and
 * touching one of its strings is what makes its translations available.
 */
final class TreeStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/tree"));
    }

    private TreeStrings() {
    }

    /** Nothing: initialising this class is the whole of what a caller wants from it. */
    static void ensureRegistered() {
    }

    // The muted line under an open row whose load has not landed (ADR 044 §2). Words and not only
    // the spinner beside the row, because a spinner says "something is happening" and this says
    // what: the row is open, and what it holds has not arrived yet, which an open row with nothing
    // under it would otherwise contradict.
    static final I18nString LOADING = new I18nString("limn.tree.loading", "Loading…");
}
