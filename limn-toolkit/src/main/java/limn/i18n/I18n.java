package limn.i18n;

import limn.concurrent.Ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide UI language: the current {@linkplain #locale() locale}, the
 * registered {@linkplain #addBundle bundles}, and the {@linkplain #epoch() epoch}
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

    private I18n() {
    }

    /** The language the UI is in; {@link Locale#getDefault()} until something says otherwise. */
    public static Locale locale() {
        return locale;
    }

    /**
     * Switches the UI language. No-op when unchanged. Every registered bundle is
     * {@linkplain StringBundle#prepare prepared} first (so a file-backed bundle
     * reads from disk here rather than inside the next measure pass), then the epoch
     * is bumped and listeners run, which is what re-lays-out every live scene.
     */
    public static void setLocale(Locale next) {
        checkUiThread();
        Objects.requireNonNull(next, "locale");
        if (locale.equals(next)) {
            return;
        }
        locale = next;
        prepareAll(next);
        invalidate();
    }

    /**
     * The counter every {@link I18nString} memo is keyed on. Bumped by a locale change
     * and by any bundle registration; both change what a key resolves to.
     */
    public static long epoch() {
        return epoch;
    }

    /**
     * Registers a source of translations; the most recently added is consulted first,
     * so an application's own bundle overrides one the toolkit installed. Prepared for
     * the current locale before it can be seen, then treated as a text change.
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

    /** Records a key and catches the one mistake that would surface as a mistranslation. */
    static void declare(String key, String english) {
        String previous = DECLARED.putIfAbsent(key, english);
        if (previous != null && !previous.equals(english)) {
            throw new IllegalStateException("i18n key '" + key + "' is declared twice with "
                    + "different English: '" + previous + "' and '" + english + "'");
        }
    }

    /** The first bundle with an answer, else the English. Package-private: {@link I18nString}. */
    static String resolve(String key, String english) {
        for (StringBundle bundle : BUNDLES) {
            String translated = bundle.lookup(key, locale);
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
