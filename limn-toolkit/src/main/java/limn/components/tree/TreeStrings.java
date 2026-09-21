package limn.components.tree;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The tree's own vocabulary: the line drawn under a row whose children are still on their way,
 * and the line drawn under an open row that turned out to hold nothing.
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

    // The muted line under an open row with nothing in it: a lazy row whose load found no
    // children, or an eager branch the model calls a non-leaf over an empty list. The row stays an
    // open branch, the way Finder shows an empty folder, and the line says why nothing follows it
    // — without it an open row over nothing reads as a row that never loaded (decision 45 of
    // 2026-09-14).
    static final I18nString EMPTY = new I18nString("limn.tree.empty", "Empty");

    // What a reader is TOLD when a lazy load begins and when it lands on nothing (decision 73 of
    // 2026-09-16). The two lines above are drawn and stay exactly as they are, unfocusable; these
    // are spoken, and they carry the branch's name because an announcement arrives with no
    // context of its own.
    //
    // An announcement rather than a state, and the reason is measured rather than preferred. BUSY
    // reaches Windows as ItemStatus and NVDA 2024.4.2 has no handler for it — 6 raises, 5
    // received, 0 spoken — and the placeholder line is skipped by the cursor on every platform
    // because it is not focusable, so a blind user opening a branch that takes a second was told
    // nothing at all. The announcement path is the one route all three readers were measured
    // speaking on the same day: NVDA, Orca and VoiceOver each said 'Salvo' and 'Interrompido,
    // nada foi salvo' through it.
    static final I18nString LOADING_ANNOUNCEMENT =
            new I18nString("limn.tree.loadingAnnouncement", "Loading {0}");
    static final I18nString EMPTY_ANNOUNCEMENT =
            new I18nString("limn.tree.emptyAnnouncement", "{0} empty");

    // The other end of the same load: what a reader is told when the children arrive (decision 83
    // of 2026-09-17, which closed the first of the two cases decision 73 left open). Without it a
    // branch that takes a second says "Loading Documents" and then nothing, so a reader has no way
    // to tell an arrival from a load still running.
    //
    // No count in it, and that is a decision rather than an omission: the number of children is
    // what a reader would most want here, and this toolkit has no plural mechanism — MessageFormat
    // is all I18nString has, no catalog holds a second argument or a choice format, and the shipped
    // languages include six whose plural rules MessageFormat cannot express. A count added by
    // guessing those forms would be wrong in exactly the languages that notice. It is owed the day
    // a plural mechanism exists (ADR 044 §2).
    static final I18nString LOADED_ANNOUNCEMENT =
            new I18nString("limn.tree.loadedAnnouncement", "{0} loaded");
}
