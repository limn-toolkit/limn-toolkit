package limn.scene;

import limn.concurrent.internal.ChangeListeners;
import limn.graphics.ShapedText;

import limn.concurrent.Ui;
import limn.i18n.internal.LanguageAliases;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
 * Nothing in the scene graph consults {@code I18n.locale()} to decide a direction. Language and
 * direction are different axes: a Hebrew subtree inside an English interface still shows English
 * strings, it just lays them out right to left. {@link #forLocale} exists so an <em>application</em>
 * can bridge the two at its own call site, typically once at startup; no widget and no scene calls
 * it, and none must, since inside a widget it would smuggle the process locale back into the axis
 * this enum exists to keep out of it. The one caller in the toolkit's own modules is the native
 * dialog the Windows backend shows when no window can be made at all: it has no scene to inherit
 * a direction from, and its text is in the process locale.
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
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
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

    /**
     * What a line with no strong character falls back to in a tree that reads this way: the
     * shaper's base for {@link ShapedText.Direction#of}. A fallback and never an imposition
     * &mdash; a Latin caption in a right-to-left tree still reads left to right, because a strong
     * character already decided it. This is the one place that says which text direction a
     * layout direction implies; {@code Widget.neutralBase()} reads it for the widget's own.
     *
     * @return {@link ShapedText.Direction#RTL} for {@link #RTL}, else {@link ShapedText.Direction#LTR}
     */
    public ShapedText.Direction neutralBase() {
        return this == RTL ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
    }

    private static volatile LayoutDirection processDefault = LTR;

    /**
     * Listeners notified when the process default changes. Every live {@link Scene} subscribes in
     * its constructor, so unbound (headless) scenes hear it too. Mirrors
     * {@link ControlSize#observeChanges}.
     */
    private static final ChangeListeners LISTENERS = new ChangeListeners();

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
        LISTENERS.fire();
    }

    /**
     * Subscribes to process-default changes.
     *
     * @param listener what to run when the default direction moves
     * @return a handle that unsubscribes; cancelling it twice is a no-op. UI thread
     */
    public static limn.concurrent.Subscription observeChanges(Runnable listener) {
        return LISTENERS.observe(listener);
    }

    /** How many listeners are registered: what the purge of a collected scene's wrapper leaves. */
    static int listenerCount() {
        return LISTENERS.count();
    }

    /**
     * The four-letter script subtags written right to left. Checked before the language, because a
     * language can be written in either: {@code az-Arab} reads right to left and {@code az-Latn}
     * does not, and only the script says which.
     *
     * <p>Every script with a character of bidi class R or AL in the JDK's Unicode data, which
     * {@code LayoutDirectionScriptsTest} asks, and every script ICU 78.3 runs right to left, the
     * {@code rtl-script} rows of {@code cldr-locale-facts.txt} that
     * {@code scripts/i18n/dump-cldr-locale-facts.mjs} writes and the same test holds this set to.
     * The two agree but for scripts newer than the JDK's Unicode: Garay ({@code Gara}, Unicode 16)
     * and Sidetic ({@code Sidt}, Unicode 17) are here on ICU's word alone.
     *
     * <p>{@code Aran}, Nastaliq, is this table's own and not ICU's, which runs {@code und-Aran}
     * left to right: it is a style of Arabic with a code of its own and no characters of its own,
     * and a locale that names it is Urdu or Persian written in Arabic script, which runs right to
     * left.
     */
    private static final Set<String> RTL_SCRIPTS = Set.of(
            "Adlm", "Arab", "Aran", "Armi", "Avst", "Chrs", "Cprt", "Elym", "Gara", "Hatr",
            "Hebr", "Hung", "Khar", "Lydi", "Mand", "Mani", "Mend", "Merc", "Mero", "Narb",
            "Nbat", "Nkoo", "Orkh", "Ougr", "Palm", "Phli", "Phlp", "Phnx", "Prti", "Rohg",
            "Samr", "Sarb", "Sidt", "Sogd", "Sogo", "Syrc", "Thaa", "Yezi");

    /**
     * The language subtags whose default script is written right to left, for the common case of
     * a locale that names no script at all, and no region that turns the language the other way
     * (the two tables below).
     *
     * <p><b>Measured, not listed from memory</b> (2026-09-21). Until that date this set had
     * fifteen entries, chosen by thinking of the languages one thinks of, and it was wrong about
     * 271 of them — Kashmiri and Avestan among the two-letter codes, and every three-letter code
     * but four, which is most of the Arabic, Persian, Kurdish and Punjabi varieties an
     * application is likely to be handed. {@code scripts/i18n/dump-cldr-locale-facts.mjs} asks
     * ICU for the direction of every two-letter code ISO 639-1 has and all 17,576 three-letter
     * combinations; this is its answer, and {@code CldrLocaleFactsTest} holds the two together.
     *
     * <p>A code ICU reads as another language is not here, though the dump's {@code rtl} rows
     * name it: {@link #forLocale} reads a locale through {@link LanguageAliases} first, so
     * {@code pes} arrives as {@code fa}, and {@code iw} and {@code ji}, which a JVM started with
     * {@code java.locale.useOldISOCodes=true} still spells Hebrew and Yiddish, as {@code he} and
     * {@code yi}.
     */
    private static final Set<String> RTL_LANGUAGES = Set.of(
            "aao", "abh", "abv", "acm", "acq", "acw", "acx", "adf", "ae", "aeb", "aec", "aee",
            "aeq", "afb", "aib", "aii", "aij", "aiq", "amw", "apc", "apd", "ar", "arc", "arq",
            "ars", "ary", "arz", "ask", "atn", "auj", "auz", "avd", "avl", "ayh", "ayl", "ayn",
            "ayp", "azb", "bal", "bdz", "bej", "bft", "bgn", "bgp", "bhe", "bhm", "bhn", "bjf",
            "bjm", "bqi", "brh", "brk", "bsh", "bsk", "chg", "cja", "ckb", "clh", "czk", "dcc",
            "def", "deh", "dgl", "dmk", "dml", "dv", "ecy", "esh", "fa", "fay", "faz", "fia", "fub",
            "gbz", "ggg", "gha", "ghr", "gig", "gjk", "gju", "glh", "glk", "grr", "gwc", "gwf",
            "gwt", "gzi", "hac", "haz", "hbo", "he", "hkh", "hnd", "hno", "hoh", "hrt", "hrz",
            "hss", "huy", "isk", "itk", "jad", "jat", "jbe", "jbn", "jdg", "jnd", "jog", "jpa",
            "jpr", "jrb", "jye", "kbu", "kby", "kcy", "kfm", "khw", "klj", "kmz", "kqd", "ks",
            "ktl", "kvx", "kxp", "lad", "lah", "lhs", "lki", "lrc", "lrk", "lrl", "lsa", "lsd",
            "lss", "luv", "luz", "mby", "mde", "mey", "mfa", "mfi", "mhj", "mid", "mki", "mnj",
            "mve", "mvy", "myz", "mzb", "mzn", "nli", "nlm", "nqo", "ntz", "nyq", "oar", "obm",
            "odk", "oru", "ota", "otk", "oui", "pal", "pbt", "pgd", "phl", "phn", "phr", "phv",
            "plk", "prc", "prd", "prx", "ps", "psh", "psi", "pst", "qxq", "rdb", "rhg", "rmt",
            "sam", "sbn", "scl", "sd", "sdb", "sdf", "sdg", "sdh", "sds", "sgr", "sgy", "shd",
            "shm", "shu", "shv", "siy", "siz", "skr", "smp", "smy", "sog", "sqo", "sqt", "srh",
            "srz", "ssh", "sts", "swb", "syc", "syn", "syr", "tjo", "tks", "tmr", "tov", "tra",
            "trg", "trm", "trw", "ug", "ur", "ush", "uzs", "vaf", "vgr", "vmh", "wbk", "wlo", "wne",
            "wni", "wsv", "xco", "xhe", "xka", "xkc", "xkj", "xkp", "xld", "xly", "xmn", "xmr",
            "xna", "xpr", "xsa", "xsd", "xvi", "ydg", "yhd", "yi", "yih", "yud", "zba", "zdj",
            "zrp", "zum");

    /**
     * The regions that turn a language right to left, keyed {@code language-REGION}: where CLDR
     * takes a language named with no script to be written in a right-to-left script because of
     * the region it is named in. Punjabi is Gurmukhi and runs left to right, and Punjabi in
     * Pakistan is Arabic; Azerbaijani, Kazakh and Kurdish are Arabic in Iran, Uzbek in
     * Afghanistan. {@code und}, a locale that names a region and no language, is read for the
     * language CLDR takes the region to mean: {@code und-SA} is Arabic.
     *
     * <p>The {@code direction} rows of {@code cldr-locale-facts.txt} that name a region and no
     * script, written by {@code scripts/i18n/dump-cldr-locale-facts.mjs} from ICU 78.3 over every
     * two- and three-letter code in every two-letter region, and held to this table by
     * {@code CldrLocaleFactsTest}. A code ICU reads as one of these languages ({@code pan} is
     * {@code pa}), a region named by its UN number ({@code 586} is {@code PK}) and a retired
     * region ({@code NT}, the Neutral Zone, is {@code SA}, and {@code YD}, South Yemen,
     * {@code YE}) have no key of their own, since {@link #forLocale} reads a locale through
     * {@link LanguageAliases} first.
     */
    private static final Set<String> RTL_IN_REGION = Set.of(
            "az-IQ", "az-IR", "ha-CM", "ha-SD", "kk-AF", "kk-CN", "kk-IR", "kk-MN", "ku-IQ",
            "ku-IR", "ku-LB", "ky-CN", "ms-CC", "pa-PK", "tg-PK", "uz-AF", "und-AE", "und-AF",
            "und-BH", "und-CC", "und-DZ", "und-EG", "und-EH", "und-IL", "und-IQ", "und-IR",
            "und-JO", "und-KM", "und-KW", "und-LB", "und-LY", "und-MA", "und-MR", "und-MV",
            "und-OM", "und-PK", "und-PS", "und-QA", "und-SA", "und-SD", "und-SS", "und-SY",
            "und-TD", "und-TN", "und-YE");

    /**
     * The regions that turn a right-to-left language left to right, the other half of
     * {@link #RTL_IN_REGION} and from the same rows: Sindhi is Arabic and runs right to left, and
     * Sindhi in India is Devanagari; Uyghur is Cyrillic in Kazakhstan and Mongolia, and Hassaniya
     * is Latin in Senegal.
     */
    private static final Set<String> LTR_IN_REGION = Set.of(
            "mey-SN", "sd-IN", "ug-KZ", "ug-MN");

    /**
     * The script codes this class writes right to left, for {@code LayoutDirectionScriptsTest},
     * which holds them to the JDK's own Unicode data. Package-private: the table is an
     * implementation of {@link #forLocale} and not something to branch on.
     *
     * @return the codes, unmodifiable
     */
    static Set<String> rightToLeftScripts() {
        return RTL_SCRIPTS;
    }

    /**
     * Every language subtag the language and region tables are keyed on, {@code und} among them,
     * for {@code LayoutDirectionTablesTest}, which holds that none is a code {@link #forLocale}
     * reads as another language before it asks them.
     *
     * @return the subtags, sorted
     */
    static java.util.List<String> tableLanguages() {
        java.util.SortedSet<String> languages = new java.util.TreeSet<>(RTL_LANGUAGES);
        for (Set<String> table : java.util.List.of(RTL_IN_REGION, LTR_IN_REGION)) {
            for (String key : table) {
                languages.add(key.substring(0, key.indexOf('-')));
            }
        }
        return java.util.List.copyOf(languages);
    }

    /**
     * Maps a locale to the direction its script is normally written in: the bridge between
     * {@link limn.i18n.I18n} and this axis, offered so that an application can write
     * {@code LayoutDirection.setProcessDefault(LayoutDirection.forLocale(locale))} at startup
     * instead of writing the same table itself.
     *
     * <p><b>No widget and no scene calls this</b>, and a widget that calls it has reintroduced the
     * process-wide locale as a source of direction, which is the one thing this axis exists to
     * prevent. Its one caller in the toolkit is the Windows backend's no-driver dialog, which
     * runs where no scene exists. It is a function from a locale to a direction, at an
     * application's call site, and a starting point rather than a policy: an application whose
     * user has chosen a direction should honour that choice instead.
     *
     * <p>The script is consulted first, because a language can be written in either direction and
     * only the script says which. A locale that names no script is read, as CLDR reads it, for
     * the script its language is likely written in where the locale is: the language's own,
     * except in the regions that change it. Punjabi runs left to right and {@code pa-PK},
     * Punjabi in Pakistan, is written in Arabic and runs right to left; Sindhi runs right to
     * left and {@code sd-IN} is Devanagari; a named script still decides over the region, so
     * {@code pa-Guru-PK} runs left to right. A region named with no language is read for the
     * language CLDR takes it to mean, so {@code und-SA} runs right to left. Anything
     * unrecognised is {@link #LTR}, which is the same fallback the first-strong rule makes for
     * text with no strong character.
     *
     * <p>The locale is read as ICU reads it before anything is decided: a code ICU replaces with
     * another language is that language ({@code pes}, Iranian Persian, is {@code fa} and
     * {@code pan-PK} is {@code pa-PK}), and a region named by its UN number is the region it
     * stands for ({@code az-364} is {@code az-IR}), as a retired region is ({@code und-YD} is
     * {@code und-YE}). These are the same readings the digits, the
     * plural rules, the string bundles and the calendars take.
     *
     * @param locale the locale to classify
     * @return {@link #RTL} when that locale's script is written right to left, else {@link #LTR}
     * @throws NullPointerException if {@code locale} is null
     */
    public static LayoutDirection forLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Locale read = LanguageAliases.resolve(locale);
        String script = read.getScript();
        if (!script.isEmpty()) {
            return RTL_SCRIPTS.contains(script) ? RTL : LTR;
        }
        // language() again for the legacy spellings Locale keeps under useOldISOCodes: iw is he.
        String language = LanguageAliases.language(read.getLanguage());
        String region = read.getCountry();
        if (!region.isEmpty()) {
            String where = (language.isEmpty() ? "und" : language) + "-" + region;
            if (RTL_IN_REGION.contains(where)) {
                return RTL;
            }
            if (LTR_IN_REGION.contains(where)) {
                return LTR;
            }
        }
        return RTL_LANGUAGES.contains(language) ? RTL : LTR;
    }
}
