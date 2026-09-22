package limn.demo.site;

import limn.i18n.PluralString;

/**
 * The worked example the text guide's "A count in a sentence" section is built from.
 *
 * <p>The region below is what the page shows, and this file is compiled by {@code ./gradlew
 * check}, so the sample a reader copies is a sample that builds; {@code PluralExampleTest}
 * holds what it says for the counts the section quotes.
 */
public final class PluralExample {

    private PluralExample() {
    }

    // #region guide:plural
    /** One declaration, the two forms English has; the other languages' forms live in the catalog. */
    private static final PluralString LOADED = PluralString.of(
            "demo.guide.loaded", "{0}: {1} file loaded", "{0}: {1} files loaded");

    /**
     * @param folder the folder whose load finished
     * @param count  how many files it holds
     * @return the sentence in the interface's language, its number written the way that language
     *         writes numbers
     */
    public static String loaded(String folder, long count) {
        return LOADED.format(count, folder);   // the count is the LAST argument: {1} here
    }
    // #endregion
}
