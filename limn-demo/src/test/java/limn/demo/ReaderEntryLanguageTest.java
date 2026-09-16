package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.demo.a11y.GalleryStrings;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.ReaderDriver;
import limn.demo.a11y.Transcript;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.Scene;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader pass speaks one language (decision 68 of the 2026-09-13 pass, 2026-09-15): an entry a
 * reader run drives says nothing in English to a pt-BR run, and nothing in Portuguese to an English
 * one.
 *
 * <p>The run's language reaches everything the toolkit says by itself, and always did; what it did
 * not reach was the gallery's own words — a caption bound to a field, the name of a switch in a
 * table cell, the mark on a calendar day, and the two sentences the {@code Announcements} entry
 * speaks straight through from the application, which are the only strings in the gallery a reader
 * reads as a sentence rather than as a widget's name. {@link GalleryStrings} is where they live
 * now, and this is what holds them there: a caption added back as a bare English literal fails
 * {@link #eachReaderEntrySpeaksOneLanguage}, because it is then the one thing both runs say.
 *
 * <p>{@link #DATA} is the declared exception and the reason it is legitimate is recorded at it.
 */
class ReaderEntryLanguageTest {

    /** The language a reader run speaks on the guests (decision 65). */
    private static final Locale READER = ReaderDriver.READER_LOCALE;

    /**
     * What a reader entry publishes the same way in every language: the records the table holds and
     * the folders and files the tree holds, which are an application's data and not the toolkit's
     * words.
     *
     * <p>Not a convenience. A reader script identifies a row by the name of its first cell in both
     * languages — {@code AccessibilityGallery.Fact}: "a run in another language compares the roles,
     * the states and the rows, whose first cell holds data no language translates" — so a
     * translated record would take every pt-BR step that names a row down with it. A composite row
     * name (a folder's name and its child count) is listed as it is published.
     */
    private static final Set<String> DATA = Set.of(
            // the table's records: the ranges realized in its 240-point box, and their continents
            "Alps", "Andes", "Atlas", "Carpathians", "Caucasus",
            "Europe", "South America", "Africa",
            // the tree's folders, its two composite row names and the files under them
            "Documents", "Documents 2", "Reports", "Reports 2", "Media", "Media 2",
            "Remote", "Trash", "Empty folder",
            "Q3 regional revenue and headcount, consolidated (final).pdf", "2026.pdf",
            "meeting notes from the Tuesday planning session.md");

    /**
     * Every entry a reader run drives is named by the catalogue, so the picker a reader is pointed
     * at is in the run's language. An entry renamed in {@code entries()} and not in
     * {@link GalleryStrings} falls back to its English name in silence; this is what says so.
     */
    @Test
    void everyReaderEntryIsNamedByTheCatalogue() {
        List<String> bare = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            if (entry.label().isLiteral()) {
                bare.add(entry.name());
            }
        }
        assertTrue(bare.isEmpty(), "a reader run is driven over these entries and the picker lists "
                + "them in English whatever the run's language, because GalleryStrings has no name "
                + "for them (decision 68): " + bare);
    }

    /**
     * Every string in the catalogue is in both languages. A constant whose key the pt-BR file does
     * not carry resolves to its English and would be spoken in the middle of a Portuguese sentence
     * — which is the whole defect decision 68 names, arriving one key at a time.
     */
    @Test
    void everyCatalogueStringIsTranslated() {
        List<String> untranslated = new ArrayList<>();
        try {
            I18n.setLocale(READER);
            for (I18nString string : GalleryStrings.ALL) {
                if (string.get().equals(string.english())) {
                    untranslated.add(string.key() + " = \"" + string.english() + "\"");
                }
            }
        } finally {
            I18n.setLocale(Locale.ENGLISH);
        }
        assertTrue(untranslated.isEmpty(), "these catalogue keys are missing from "
                + "/i18n/gallery_" + READER.toLanguageTag() + ".properties, so a "
                + READER.toLanguageTag() + " reader run speaks them in English: " + untranslated);
    }

    /** Each entry, built in both languages, holding nothing of the other's. */
    @TestFactory
    Stream<DynamicTest> eachReaderEntrySpeaksOneLanguage() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            tests.add(DynamicTest.dynamicTest(entry.reader().id(), () -> oneLanguage(entry)));
        }
        return tests.stream();
    }

    private static void oneLanguage(Entry entry) {
        Set<String> english = publishedWords(entry, Locale.ENGLISH);
        Set<String> reader = publishedWords(entry, READER);

        List<String> survived = new ArrayList<>();
        for (I18nString string : GalleryStrings.ALL) {
            String inEnglish = string.english();
            String inReader = translation(string);
            for (String spoken : reader) {
                if (saysWords(spoken, inEnglish)) {
                    survived.add("the " + READER.toLanguageTag() + " run says \"" + spoken
                            + "\", which holds the English \"" + inEnglish + "\" ("
                            + string.key() + ")");
                }
            }
            for (String spoken : english) {
                if (saysWords(spoken, inReader)) {
                    survived.add("the English run says \"" + spoken + "\", which holds the "
                            + READER.toLanguageTag() + " \"" + inReader + "\" (" + string.key()
                            + ")");
                }
            }
        }
        assertTrue(survived.isEmpty(), "\"" + entry.name() + "\" is not monolingual in one of its "
                + "two runs (decision 68):\n  " + String.join("\n  ", survived));

        Set<String> both = new TreeSet<>(english);
        both.retainAll(reader);
        both.removeIf(spoken -> DATA.contains(spoken) || saysNoWord(spoken));
        assertTrue(both.isEmpty(), "\"" + entry.name() + "\" publishes these the same way in "
                + "English and in " + READER.toLanguageTag() + ", and they are neither the entry's "
                + "declared data nor a number: a word a reader run speaks belongs in "
                + "GalleryStrings (decision 68), and a record the scripts match rows by belongs in "
                + "this test's DATA with why: " + both);
    }

    /**
     * The two sentences, in the run's language: the announcement entry's own pass, because an
     * announcement is the one thing here a reader speaks straight through from the application and
     * not as the name of a node, so no tree holds it and the pass above cannot see it.
     */
    @Test
    void theAnnouncementsSpeakTheRunsLanguage() {
        assertEquals(List.of(GalleryStrings.SAVED.english(), GalleryStrings.STOPPED.english()),
                announced(Locale.ENGLISH));
        assertEquals(List.of(translation(GalleryStrings.SAVED), translation(GalleryStrings.STOPPED)),
                announced(READER), "a " + READER.toLanguageTag() + " pass hears the two sentences "
                        + "in " + READER.toLanguageTag() + " (decision 68, which the "
                        + "Announcements entry is the load-bearing case of)");
    }

    /** @return what the announcement entry announced, in order, when driven in {@code locale} */
    private static List<String> announced(Locale locale) {
        Entry entry = AccessibilityGallery.readerEntry("announcement");
        List<String> spoken = new ArrayList<>();
        try (Harness harness = new Harness(Palette.LIGHT)) {
            I18n.setLocale(locale);
            harness.show(entry);
            Scene scene = harness.scenes.get(0);
            harness.built.focus().requestFocus();
            harness.settle();
            HeadlessWindow window = harness.windows().get(0);
            window.bridge().events.clear();
            for (Step step : entry.reader().steps()) {
                step.sendTo(scene);
                harness.settle();
            }
            for (AccessibleEvent event : window.bridge().events) {
                if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                    spoken.add(String.valueOf(event.newValue()));
                }
            }
        } finally {
            I18n.setLocale(Locale.ENGLISH);
        }
        return spoken;
    }

    /**
     * @param entry  a gallery entry
     * @param locale the language to build it in
     * @return every name, description and value text its windows publish, the window nodes aside:
     *         a window's name is the title the harness gave it, which is the entry's own English
     *         name and not something the entry says
     */
    private static Set<String> publishedWords(Entry entry, Locale locale) {
        Set<String> found = new LinkedHashSet<>();
        try (Harness harness = new Harness(Palette.LIGHT)) {
            I18n.setLocale(locale);
            harness.show(entry);
            harness.settle();
            for (HeadlessWindow window : harness.windows()) {
                AccessibleTree tree = window.bridge().tree();
                assertTrue(tree.nodeCount() > 1, "\"" + entry.name() + "\" published nothing in "
                        + locale.toLanguageTag() + ": " + Transcript.of(tree));
                for (int i = 0; i < tree.nodeCount(); i++) {
                    AccessibleNode node = tree.node(i);
                    if (node.role() == Accessible.Role.WINDOW) {
                        continue;
                    }
                    add(found, node.name());
                    add(found, node.description());
                    if (node.value() != null) {
                        add(found, node.value().text());
                    }
                }
            }
        } finally {
            I18n.setLocale(Locale.ENGLISH);
        }
        return found;
    }

    private static void add(Set<String> found, String text) {
        if (text != null && !text.isBlank()) {
            found.add(text);
        }
    }

    /** @return {@code string} in the reader's language */
    private static String translation(I18nString string) {
        try {
            I18n.setLocale(READER);
            return string.get();
        } finally {
            I18n.setLocale(Locale.ENGLISH);
        }
    }

    /**
     * Whether {@code spoken} says {@code words} as words of its own, and not inside a longer one:
     * a day cell that says "21 de setembro de 2026, feriado" is what a mark has to be caught
     * inside, while "Continente" holding the letters of "Continent" is a cognate and not the
     * English word surviving.
     *
     * @param spoken what a node publishes
     * @param words  a catalogue string in one of the two languages
     * @return whether the one is inside the other with nothing alphanumeric on either side
     */
    private static boolean saysWords(String spoken, String words) {
        for (int at = spoken.indexOf(words); at >= 0; at = spoken.indexOf(words, at + 1)) {
            boolean before = at > 0 && Character.isLetterOrDigit(spoken.charAt(at - 1));
            int end = at + words.length();
            boolean after = end < spoken.length()
                    && Character.isLetterOrDigit(spoken.charAt(end));
            if (!before && !after) {
                return true;
            }
        }
        return false;
    }

    /** @return whether nothing in {@code spoken} is a letter: a number, a time, a separator */
    private static boolean saysNoWord(String spoken) {
        for (int i = 0; i < spoken.length(); i++) {
            if (Character.isLetter(spoken.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
