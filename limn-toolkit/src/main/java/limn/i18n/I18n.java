package limn.i18n;

import limn.concurrent.Ui;

import java.text.Collator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The UI language: the {@linkplain #processLocale() process locale}, the
 * {@linkplain #locale() locale in effect here}, the registered
 * {@linkplain #addBundle bundles}, and the {@linkplain #epoch() epoch}
 * that every {@link I18nString} validates its cache against.
 *
 * <p>Shaped after {@code Fonts} and {@code ControlSize}, because a language change
 * is measurement-affecting in exactly the way a font change is: it notifies
 * {@linkplain #addChangeListener listeners}, every scene subscribes and re-lays-out,
 * and widgets re-read their text on the way through. An application switches
 * languages with one call and nothing else:
 *
 * <pre>{@code
 * I18n.addBundle(PropertyBundle.family("/i18n/settings"));
 * I18n.addBundle(PropertyBundle.family("/i18n/editor"));
 * I18n.setLocale(Locale.forLanguageTag("pt-BR"));
 * }</pre>
 *
 * <p><b>Nothing is required.</b> With no bundle registered every string resolves to
 * the English it carries, which is what the toolkit did before this class existed.
 *
 * <h2>A subtree can hold its own language</h2>
 * A widget that {@linkplain limn.scene.Widget#setLocale declares a locale} gives its
 * whole subtree one, resolved down the tree exactly as {@code ControlSize} is
 * (ADR 035). The mechanism this class contributes is the <b>scope</b>: while the
 * toolkit measures, lays out, paints or dispatches an event to a widget, that
 * widget's effective locale is {@linkplain #pushScope in scope}, and {@link #locale()}
 * answers it. Everything that already read {@code I18n.locale()} at the moment it
 * resolved, formatted or broke text &mdash; {@link I18nString#get()},
 * {@link I18nString#format}, {@link #localizeDigits}, a chart format, a line
 * breaker &mdash; follows the subtree it is working inside without knowing
 * subtrees exist. That is the point: after ADR 006's own argument, the default
 * spelling is the correct one, and a widget written naively cannot capture the
 * wrong language.
 *
 * <p>Mutators are meant to be called on the UI thread (checked when a {@code Ui}
 * runtime is installed, so headless tests can drive them directly).
 */
public final class I18n {

    /** Newest first: the last bundle registered gets the first chance at a key. */
    private static final CopyOnWriteArrayList<StringBundle> BUNDLES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Every key ever declared, in declaration order; see {@link #declaredKeys()}.
     * Bounded by the number of distinct keys, which is the catalog itself.
     */
    private static final Map<String, String> DECLARED =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private static volatile Locale locale = Locale.getDefault();
    /** Starts at 1 so an {@link I18nString}'s zero-initialised memo reads as stale. */
    private static volatile long epoch = 1;

    /**
     * The innermost open {@linkplain #pushScope scope} on this thread, or {@code null} for
     * none. Thread-local rather than a plain static so a scope opened by a widget pass on
     * the UI thread can never bleed into a worker formatting something concurrently.
     */
    private static final ThreadLocal<Locale> SCOPE = new ThreadLocal<>();

    /**
     * The subtree locales currently declared somewhere, each with the number of
     * declarations naming it: what {@link #retainLocale} counts and {@link #setLocale}
     * consults before letting a bundle drop a language. UI-thread confined.
     */
    private static final Map<Locale, Integer> RETAINED = new LinkedHashMap<>();

    private I18n() {
    }

    /**
     * The language in effect <em>here</em>: the innermost open {@linkplain #pushScope
     * scope}'s locale, else the {@linkplain #processLocale() process locale}.
     *
     * <p>Inside a widget's measure, layout, paint or event dispatch this is that widget's
     * {@linkplain limn.scene.Widget#locale() effective locale}, because the toolkit opens
     * the scope around each of those; everywhere else &mdash; application startup, a
     * posted task, a worker thread &mdash; it is the process locale, exactly as before
     * ADR 035. Reading it at the moment text is resolved or formatted is what makes code
     * follow the subtree it is working inside.
     */
    public static Locale locale() {
        Locale scoped = SCOPE.get();
        return scoped != null ? scoped : locale;
    }

    /**
     * The process-wide UI language, ignoring any open scope: what {@link #setLocale}
     * set, {@link Locale#getDefault()} until something says otherwise, and the root of
     * the {@linkplain limn.scene.Widget#locale() widget resolution chain}. Almost every
     * reader wants {@link #locale()} instead.
     */
    public static Locale processLocale() {
        return locale;
    }

    /**
     * Switches the UI language. No-op when unchanged. Every registered bundle is
     * {@linkplain StringBundle#prepare prepared} first (so a file-backed bundle
     * reads from disk here rather than inside the next measure pass), then the epoch
     * is bumped and listeners run, which is what re-lays-out every live scene.
     * The outgoing language's tables are {@linkplain StringBundle#release released},
     * unless a {@linkplain #retainLocale retained} subtree still reads them.
     */
    public static void setLocale(Locale next) {
        checkUiThread();
        Objects.requireNonNull(next, "locale");
        Locale previous = locale;
        if (previous.equals(next)) {
            return;
        }
        locale = next;
        prepareAll(next);
        if (!RETAINED.containsKey(previous)) {
            releaseAll(previous);
        }
        invalidate();
    }

    /**
     * Opens a resolution scope: until the matching {@link #popScope}, {@link #locale()}
     * on this thread answers {@code locale}. This is how a widget's effective locale
     * reaches everything that resolves or formats text while the toolkit is inside that
     * widget &mdash; {@code Widget} opens one around measure, layout, paint and event
     * dispatch, and {@code Widget.tooltip()} around its own resolution. An application
     * may open one too, to format something for a particular pane from outside a pass.
     *
     * <p>Scopes nest: the returned value is the scope this call replaced, and handing it
     * back to {@link #popScope} restores it, so the idiom is
     *
     * <pre>{@code
     * Locale enclosing = I18n.pushScope(locale);
     * try {
     *     ...
     * } finally {
     *     I18n.popScope(enclosing);
     * }
     * }</pre>
     *
     * @return the scope in effect before this call, possibly {@code null}: the value
     *         {@link #popScope} must be given back
     */
    public static Locale pushScope(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Locale enclosing = SCOPE.get();
        // Every widget opens a scope around its measure, layout and paint, and nearly every
        // one resolves to the language its parent resolved to: the scope changes at subtree
        // boundaries, not at every node. A push that would set what is already set costs a
        // thread-local write per widget per pass for nothing, and the matching pop another.
        if (enclosing != locale) {
            SCOPE.set(locale);
        }
        return enclosing;
    }

    /**
     * Closes the innermost scope by restoring what {@link #pushScope} returned;
     * {@code null} (the usual outermost case) restores "no scope", which is the
     * process locale. Always call it in a {@code finally}.
     */
    public static void popScope(Locale enclosing) {
        if (SCOPE.get() != enclosing) {
            SCOPE.set(enclosing);
        }
    }

    /**
     * Declares that {@code locale} is being read somewhere &mdash; a widget or scene
     * subtree resolves through it &mdash; so every bundle keeps (and any late-registered
     * bundle gains) a prepared table for it, and {@link #setLocale} moving the process
     * away from it does not drop what that subtree is still reading. Counted:
     * {@code Widget.setLocale} and {@code Scene.setLocale} call this for a new
     * declaration and {@link #releaseLocale} for the one it replaced, and an application
     * with its own reason to resolve a language outside the tree may do the same.
     *
     * <p>Preparation happens here, on the first retain, for the reason {@link #setLocale}
     * prepares: a file-backed bundle must read from disk now, not inside the measure pass
     * of the frame that first shows the subtree.
     */
    public static void retainLocale(Locale locale) {
        checkUiThread();
        Objects.requireNonNull(locale, "locale");
        Integer count = RETAINED.get(locale);
        RETAINED.put(locale, count == null ? 1 : count + 1);
        if (count == null) {
            prepareAll(locale);
        }
    }

    /**
     * Undoes one {@link #retainLocale}. When the last retain for {@code locale} is
     * released and it is not the process locale, every bundle is told to
     * {@linkplain StringBundle#release release} its table for it. Releasing a locale
     * that was never retained is a no-op, so a clear-before-declare cannot throw.
     */
    public static void releaseLocale(Locale locale) {
        checkUiThread();
        Objects.requireNonNull(locale, "locale");
        Integer count = RETAINED.get(locale);
        if (count == null) {
            return;
        }
        if (count > 1) {
            RETAINED.put(locale, count - 1);
            return;
        }
        RETAINED.remove(locale);
        if (!locale.equals(I18n.locale)) {
            releaseAll(locale);
        }
    }

    /**
     * The counter every {@link I18nString} memo is keyed on. Bumped by a locale change
     * and by any bundle registration; both change what a key resolves to.
     */
    public static long epoch() {
        return epoch;
    }

    // ------------------------------------------------------------------ digits

    /** The declared override, or null while the locale decides (the default). */
    private static volatile NumberingSystem declaredNumberingSystem;

    /**
     * The digits a formatted number is written in: the {@linkplain NumberingSystem#forLocale
     * own system} of the {@linkplain #locale() locale in effect here}, unless
     * {@link #setNumberingSystem} declared otherwise. There is no numbering-system axis and
     * ADR 033's Decision 1 still stands: substitution happens at format time inside the widgets
     * that render numbers they own, so application strings are never rewritten. What changed
     * with ADR 035 is only which locale the system is derived from &mdash; the effective one, so
     * an Arabic subtree's spinner writes Arabic-Indic digits inside a Latin interface with no
     * second mechanism. The declared override stays process-wide and wins everywhere, because it
     * is a statement about the process ("this deployment writes Latin digits"), not about a
     * subtree.
     */
    public static NumberingSystem numberingSystem() {
        NumberingSystem declared = declaredNumberingSystem;
        return declared != null ? declared : NumberingSystem.forLocale(locale());
    }

    /**
     * Overrides the locale's numbering system; {@code null} returns to following the locale.
     * Treated as a text change: the epoch bumps and every live scene re-lays-out, exactly as a
     * locale switch does, because every formatted number on screen just changed.
     */
    public static void setNumberingSystem(NumberingSystem system) {
        checkUiThread();
        if (declaredNumberingSystem == system) {
            return;
        }
        declaredNumberingSystem = system;
        invalidate();
    }

    /**
     * {@code text} with every ASCII digit rewritten in the active {@linkplain #numberingSystem()
     * numbering system}, and everything else untouched. Returns its argument under
     * {@link NumberingSystem#LATN} or when there is nothing to rewrite, so the default locale
     * pays an object comparison and a scan, not an allocation.
     *
     * <p>This is the <b>format-time</b> half of ADR 033: call it on a string the widget itself
     * rendered from a number, never on text an application authored.
     */
    public static String localizeDigits(String text) {
        NumberingSystem system = numberingSystem();
        if (system == NumberingSystem.LATN) {
            return text;
        }
        int first = firstAsciiDigit(text);
        if (first < 0) {
            return text;
        }
        char[] out = text.toCharArray();
        for (int i = first; i < out.length; i++) {
            char c = out[i];
            if (c >= '0' && c <= '9') {
                out[i] = system.digit(c - '0');
            }
        }
        return new String(out);
    }

    /**
     * {@code text} with every digit of every known system folded back to ASCII: the
     * <b>parse-time</b> half of ADR 033, and deliberately independent of the active system —
     * a value pasted under one locale must survive being committed under another.
     */
    public static String toAsciiDigits(String text) {
        char[] out = null;
        for (int i = 0; i < text.length(); i++) {
            int value = NumberingSystem.digitValue(text.charAt(i));
            if (value >= 0 && text.charAt(i) > '9') {
                if (out == null) {
                    out = text.toCharArray();
                }
                out[i] = (char) ('0' + value);
            }
        }
        return out == null ? text : new String(out);
    }

    private static int firstAsciiDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Registers a source of translations; the most recently added is consulted first,
     * so an application's own bundle overrides one the toolkit installed. Prepared for
     * the process locale and every {@linkplain #retainLocale retained} one before it
     * can be seen, then treated as a text change.
     *
     * <p>Registering the same instance twice is a no-op.
     */
    public static void addBundle(StringBundle bundle) {
        checkUiThread();
        Objects.requireNonNull(bundle, "bundle");
        if (BUNDLES.contains(bundle)) {
            return;
        }
        bundle.prepare(locale);
        for (Locale retained : RETAINED.keySet()) {
            bundle.prepare(retained);
        }
        BUNDLES.add(0, bundle);
        invalidate();
    }

    /** Removes a bundle. No-op when it was never registered. */
    public static void removeBundle(StringBundle bundle) {
        checkUiThread();
        if (BUNDLES.remove(bundle)) {
            invalidate();
        }
    }

    /** Subscribes to language changes (idempotent per instance). */
    public static void addChangeListener(Runnable listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Unsubscribes; no-op when it was never registered. */
    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Every key declared so far, mapped to its English, in declaration order: the
     * translation catalog as a runtime fact rather than a document that drifts. A test
     * asserts a bundle covers it; a tool dumps it as a starter {@code .properties}.
     *
     * <p>It sees only what has been loaded: a component's strings are {@code static
     * final}, so they register when its class first initialises. Anything enumerating
     * the catalog must touch the classes it cares about first.
     */
    public static Map<String, String> declaredKeys() {
        synchronized (DECLARED) {
            return Map.copyOf(DECLARED);
        }
    }

    /** The registered bundles, newest first. */
    public static List<StringBundle> bundles() {
        return List.copyOf(BUNDLES);
    }

    // ------------------------------------------------------------------ text

    /** The declared override, or null while the UI locale decides (the default). */
    private static volatile Locale declaredTextLocale;

    /**
     * The language text is ordered and case-mapped in: the {@linkplain #locale() locale in
     * effect here}, unless {@link #setTextLocale} declared the content is in another language.
     * The two are different facts — an English interface listing Swedish names must still put
     * ä after z — but almost every application never needs to say so, and the default keeps
     * order and case in the language the user is reading. Following {@code locale()} rather
     * than the process locale is what makes a type-ahead fold, or a sort run inside a pass,
     * answer for the subtree it is working inside (ADR 035), exactly as digits do; the
     * declared override stays process-wide and wins everywhere, for ADR 034's reason.
     */
    public static Locale textLocale() {
        Locale declared = declaredTextLocale;
        return declared != null ? declared : locale();
    }

    /**
     * Declares the language of the content being ordered and case-mapped; {@code null}
     * returns to following the UI locale. Treated as a text change, exactly as
     * {@link #setLocale} is: the epoch bumps and listeners run, because every order an
     * application built through {@link #collator()} is now stale and the application's own
     * change listener is where it re-sorts.
     */
    public static void setTextLocale(Locale next) {
        checkUiThread();
        if (Objects.equals(declaredTextLocale, next)) {
            return;
        }
        declaredTextLocale = next;
        invalidate();
    }

    /**
     * A collator for the {@linkplain #textLocale() text locale}, for ordering what a user
     * reads: list items, table rows, anything sorted for display. A machine order — a key, a
     * slug, a file format — keeps {@code compareTo}.
     *
     * <p>Every call answers a fresh instance, because a {@link Collator} carries mutable
     * per-comparison state and must not be shared across threads. Fetch one per sort, not
     * one per comparison:
     *
     * <pre>{@code
     * names.sort(I18n.collator());
     * items.sort(Comparator.comparing(Item::label, I18n.collator()));
     * }</pre>
     */
    public static Collator collator() {
        return Collator.getInstance(textLocale());
    }

    /**
     * {@code text} upper-cased in the {@linkplain #textLocale() text locale}: the case
     * mapping for something a user reads. A machine format keeps {@code Locale.ROOT} — under
     * Turkish this maps {@code i} to {@code İ}, which is exactly right in a list of cities
     * and exactly wrong in a hex color.
     */
    public static String toUpperCase(String text) {
        return text.toUpperCase(textLocale());
    }

    /** The lower-case twin of {@link #toUpperCase}, in the same locale. */
    public static String toLowerCase(String text) {
        return text.toLowerCase(textLocale());
    }

    /** Records a key and catches the one mistake that would surface as a mistranslation. */
    static void declare(String key, String english) {
        String previous = DECLARED.putIfAbsent(key, english);
        if (previous != null && !previous.equals(english)) {
            throw new IllegalStateException("i18n key '" + key + "' is declared twice with "
                    + "different English: '" + previous + "' and '" + english + "'");
        }
    }

    /** The first bundle with an answer, else the English. Package-private: {@link I18nString}. */
    static String resolve(String key, String english, Locale target) {
        for (StringBundle bundle : BUNDLES) {
            String translated = bundle.lookup(key, target);
            if (translated != null) {
                return translated;
            }
        }
        return english;
    }

    private static void prepareAll(Locale target) {
        for (StringBundle bundle : BUNDLES) {
            bundle.prepare(target);
        }
    }

    private static void releaseAll(Locale target) {
        for (StringBundle bundle : BUNDLES) {
            bundle.release(target);
        }
    }

    private static void invalidate() {
        epoch++;
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    /**
     * The UI-thread check, skipped when no runtime is installed so headless tests and
     * plain unit tests can register bundles without standing up a window.
     */
    private static void checkUiThread() {
        if (Ui.isInstalled()) {
            Ui.checkUiThread();
        }
    }
}
