package limn.scene;

import limn.concurrent.Ui;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The direction a subtree lays out in: an inherited design axis, <b>not</b> a transform and
 * <b>not</b> a property of the locale.
 *
 * <p>Mirroring in this toolkit is a <em>placement</em> decision, taken during layout and paint
 * by the widget that owns the coordinate. There is no mirror transform at the canvas root and
 * there must never be one: a global flip would turn correctly shaped text into a mirror image
 * needing a per-run un-flip, would flip every image and every video frame, and would put an
 * inverse transform on the hot path of every hit test.
 *
 * <p>This enum is the <em>axis</em> only. It carries no numbers and no locale knowledge, so the
 * toolkit can own it without knowing anything about text, which is what lets a raw
 * {@link limn.scene.layout.Row} act as a direction scope for the components inside it. It is the
 * companion of {@link ControlSize}, resolved by the same chain, invalidated by its own epoch, and
 * read under the same two rules.
 *
 * <h2>The coexistence contract</h2>
 * A widget's direction is <b>inherited down the tree</b>: its own
 * {@linkplain Widget#declaredLayoutDirection() declared} value, else the nearest declaring
 * ancestor's, else its {@linkplain Scene#layoutDirection() scene's} default, else its
 * {@linkplain Widget#setInheritanceHost host}'s, else {@link #processDefault()}.
 *
 * <p>{@link #LTR} is the process default and mirroring is opt-in per subtree, which is what makes
 * a Hebrew interface holding a left-to-right code editor, log pane, URL bar or JSON viewer
 * expressible: each of those is a subtree that reads one way inside an interface that reads the
 * other, and a direction derived from a process-wide locale could not say so. An application in
 * Arabic writes one line at its scene root; a subtree that disagrees writes one more.
 *
 * <h2>This is never read from the locale</h2>
 * Nothing in the toolkit consults {@code I18n.locale()} to decide a direction. Language and
 * direction are different axes: a Hebrew subtree inside an English interface still shows English
 * strings, it just lays them out right to left. {@link #forLocale} exists so an <em>application</em>
 * can bridge the two at its own call site, typically once at startup; it is not called by the
 * toolkit and must not be called from inside a widget, where it would smuggle the process locale
 * back into the axis this enum exists to keep out of it.
 *
 * <h2>Never read this in a constructor</h2>
 * {@link Widget#add} assigns the child's parent <em>after</em> the child is fully constructed, so
 * {@code new Button("OK")} runs with no parent and resolves to the process default whatever its
 * eventual parent declares. A direction captured at construction is permanently wrong with no path
 * to recovery, exactly as a captured {@link ControlSize} is.
 *
 * <p>Read it in {@code onMeasure}, {@code onPaint}, {@code onLayout} or an event handler, where the
 * tree is complete, and resolve it <b>once per pass</b> into a local: two resolutions that disagree
 * inside one {@code onPaint} put the caret on one side and the selection band on the other.
 *
 * <h2>What a direction change does to a held value</h2>
 * A paragraph's base direction decides which bidi level a boundary neutral takes, which decides
 * which run it extends, which decides which face measures it. A line of mixed content therefore
 * measures a fraction of a point differently in the two directions. The amount is one face's
 * disagreement about a space per neutral at the paragraph's <em>edge</em> &mdash; an interior
 * neutral does not move at all &mdash; so it is small on any real line, and it is nonetheless
 * enough to make a held
 * {@link limn.graphics.ShapedText} and a cached measurement both stale across a change &mdash;
 * which is why {@link limn.graphics.ShapedText#matches} takes a direction and why
 * {@link Widget#measure} keys its cache on the resolved one.
 *
 * @see Widget#layoutDirection()
 * @see Scene#setLayoutDirection(LayoutDirection)
 */
public enum LayoutDirection {
    /** Left to right: the default, and what every existing Limn UI renders as. */
    LTR,
    /** Right to left: Arabic, Hebrew, Persian, Urdu and the rest of the right-to-left scripts. */
    RTL;

    /** @return the opposite direction; {@code LTR.opposite() == RTL} */
    public LayoutDirection opposite() {
        return this == LTR ? RTL : LTR;
    }

    /** @return whether this is {@link #RTL}, for the {@code dir == RTL} tests that fill widgets */
    public boolean isRightToLeft() {
        return this == RTL;
    }

    private static volatile LayoutDirection processDefault = LTR;

    /**
     * Listeners notified when the process default changes. Every live {@link Scene} subscribes in
     * its constructor, so unbound (headless) scenes hear it too. Mirrors
     * {@link ControlSize#addChangeListener}.
     */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** @return the direction used where nothing in the tree and no scene declares one */
    public static LayoutDirection processDefault() {
        return processDefault;
    }

    /**
     * Sets the process-wide fallback direction: the root of the inheritance chain, and the one
     * line an application that is entirely right to left writes at startup. Every live scene
     * re-measures, overlays included and <em>unbound scenes included</em>; widgets and scenes that
     * declare their own direction are unaffected. No-op when unchanged. UI thread only.
     */
    public static void setProcessDefault(LayoutDirection direction) {
        Ui.checkUiThread();
        Objects.requireNonNull(direction, "direction");
        if (processDefault == direction) {
            return;
        }
        processDefault = direction;
        Widget.bumpLayoutDirectionEpoch();
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    /** Subscribes to process-default changes (idempotent per instance). */
    public static void addChangeListener(Runnable listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Unsubscribes; no-op when it was never registered. */
    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * The four-letter script subtags written right to left. Checked before the language, because a
     * language can be written in either: {@code az-Arab} reads right to left and {@code az-Latn}
     * does not, and only the script says which.
     */
    private static final Set<String> RTL_SCRIPTS = Set.of(
            "Adlm", "Arab", "Aran", "Armi", "Avst", "Cprt", "Hatr", "Hebr", "Hung", "Khar",
            "Lydi", "Mand", "Mani", "Mend", "Merc", "Mero", "Nbat", "Nkoo", "Orkh", "Palm",
            "Phli", "Phlp", "Phnx", "Prti", "Rohg", "Samr", "Sarb", "Sogd", "Sogo", "Syrc",
            "Thaa", "Yezi");

    /**
     * The language subtags whose default script is written right to left, for the common case of
     * a locale that names no script at all. Both of Hebrew's codes are here: {@code he} is the
     * current tag and {@code iw} is what {@link Locale} still normalises it to.
     */
    private static final Set<String> RTL_LANGUAGES = Set.of(
            "ar", "arc", "ckb", "dv", "fa", "he", "iw", "ji", "nqo", "ps", "sd", "syr", "ug",
            "ur", "yi");

    /**
     * Maps a locale to the direction its script is normally written in: the bridge between
     * {@link limn.i18n.I18n} and this axis, offered so that an application can write
     * {@code LayoutDirection.setProcessDefault(LayoutDirection.forLocale(locale))} at startup
     * instead of writing the same table itself.
     *
     * <p><b>Nothing in the toolkit calls this</b>, and a widget that calls it has reintroduced the
     * process-wide locale as a source of direction, which is the one thing this axis exists to
     * prevent. It is a function from a locale to a direction, at an application's call site, and a
     * starting point rather than a policy: an application whose user has chosen a direction should
     * honour that choice instead.
     *
     * <p>The script is consulted first and the language only when the locale names no script,
     * because a language can be written in either. Anything unrecognised is {@link #LTR}, which is
     * the same fallback the first-strong rule makes for text with no strong character.
     *
     * @param locale the locale to classify
     * @return {@link #RTL} when that locale's script is written right to left, else {@link #LTR}
     * @throws NullPointerException if {@code locale} is null
     */
    public static LayoutDirection forLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        String script = locale.getScript();
        if (!script.isEmpty()) {
            return RTL_SCRIPTS.contains(script) ? RTL : LTR;
        }
        return RTL_LANGUAGES.contains(locale.getLanguage()) ? RTL : LTR;
    }
}
