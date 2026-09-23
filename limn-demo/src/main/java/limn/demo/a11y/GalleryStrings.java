package limn.demo.a11y;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

import java.util.List;
import java.util.Map;

/**
 * Every word a screen reader speaks in a gallery entry a reader run drives, in the run's language.
 *
 * <p>A reader pass is monolingual or it is not a pass: the driver pins pt-BR, the guests' reader
 * language, and everything the toolkit says — a spin button's "Dia", a day cell's
 * "21 de setembro de 2026", the tree's "Carregando…" — already follows it, because those strings
 * are the toolkit's own and ship in 21 locales. What did not follow it was the gallery's own
 * English: the caption bound to a field, the name of a switch in a table cell, the mark on a
 * calendar day, and the two sentences the {@code Announcements} entry speaks straight through from
 * the application. A pt-BR run heard "Due date" in the middle of a Portuguese sentence.
 *
 * <p>So the entries a run drives take their captions, labels and marks from here, and the run's
 * language chooses between the English each constant carries and the {@code /i18n/gallery} bundle
 * beside it. Two languages, not twenty-one: the catalogue exists for the reader passes, which run
 * in pt-BR, and for the English every test and every site capture is taken in. {@code KitchenStrings}
 * is the shape it follows — one domain, registered where its constants are. A run asked for a third
 * language ({@code --locale fr}) hears the toolkit in it and these words in English, which is what
 * a bundle with no file for a language always does; the decision names two.
 *
 * <p><b>What is deliberately not here: the entries' data.</b> The table's mountain ranges and
 * continents, and the tree's folder and file names, stay as they are in every language, because a
 * reader script identifies a row by the name of its first cell in <em>both</em> languages
 * ({@code AccessibilityGallery.Fact}: "a run in another language compares the roles, the states and
 * the rows, whose first cell holds data no language translates"). Translating a record would break
 * every pt-BR step that names a row, and an application's records are not the toolkit's words
 * anyway. {@code ReaderEntryLanguageTest} holds exactly that line: everything a reader entry
 * publishes in both languages at once is the data it declares, and nothing else.
 *
 * <p>The entries no reader run drives keep their plain English: nothing reads them aloud, and the
 * completeness invariants and transcript goldens they exist for are taken in English.
 */
public final class GalleryStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/i18n/gallery"));
    }

    // ------------------------------------------------- the entries' names, as the picker lists them

    /** "Table with a header and rows". */
    public static final I18nString ENTRY_TABLE =
            new I18nString("gallery.entry.table", "Table with a header and rows");
    /** "Tree with branches that load". */
    public static final I18nString ENTRY_TREE_LOADING =
            new I18nString("gallery.entry.treeLoading", "Tree with branches that load");
    /** "Announcements". */
    public static final I18nString ENTRY_ANNOUNCEMENTS =
            new I18nString("gallery.entry.announcements", "Announcements");
    /** "Calendar grid". */
    public static final I18nString ENTRY_CALENDAR =
            new I18nString("gallery.entry.calendar", "Calendar grid");
    /** "Date field, segmented". */
    public static final I18nString ENTRY_DATE_FIELD =
            new I18nString("gallery.entry.dateField", "Date field, segmented");
    /** "Date picker, closed". */
    public static final I18nString ENTRY_DATE_PICKER_CLOSED =
            new I18nString("gallery.entry.datePickerClosed", "Date picker, closed");

    // ------------------------------------------------------------------ what the entries say

    /** The caption of the table entry's table. */
    public static final I18nString MOUNTAIN_RANGES =
            new I18nString("gallery.table.caption", "Mountain ranges");
    /** The table's first column. */
    public static final I18nString RANGE = new I18nString("gallery.table.range", "Range");
    /** The table's second column. */
    public static final I18nString CONTINENT =
            new I18nString("gallery.table.continent", "Continent");
    /** The table's numeric column. */
    public static final I18nString SUMMIT = new I18nString("gallery.table.summit", "Summit");
    /** The table's widget column, and the name of the switch in every cell of it. */
    public static final I18nString VISITED = new I18nString("gallery.table.visited", "Visited");

    /** The caption of the tree entry's tree. */
    public static final I18nString FILES = new I18nString("gallery.tree.caption", "Files");
    /** The button an application puts in a document's row. */
    public static final I18nString OPEN = new I18nString("gallery.tree.open", "Open");

    /** The button that announces politely. */
    public static final I18nString SAVE = new I18nString("gallery.announcement.save", "Save");
    /** What it announces. */
    public static final I18nString SAVED = new I18nString("gallery.announcement.saved", "Saved");
    /** The button that announces assertively. */
    public static final I18nString STOP = new I18nString("gallery.announcement.stop", "Stop");
    /** What that one announces: the one whole sentence the gallery speaks. */
    public static final I18nString STOPPED =
            new I18nString("gallery.announcement.stopped", "Stopped, nothing was saved");

    /** The caption of the calendar entry's grid, and of the picker entry's single picker. */
    public static final I18nString DELIVERY_DATE =
            new I18nString("gallery.date.deliveryDate", "Delivery date");
    /** The mark on the 21st. */
    public static final I18nString HOLIDAY = new I18nString("gallery.date.holiday", "holiday");
    /** The caption of the date field entry's first field. */
    public static final I18nString INVOICE_DATE =
            new I18nString("gallery.date.invoiceDate", "Invoice date");
    /** The caption of its second field, the one with a clock. */
    public static final I18nString APPOINTMENT =
            new I18nString("gallery.date.appointment", "Appointment");
    /** The caption of its third field, the empty one. */
    public static final I18nString DUE_DATE = new I18nString("gallery.date.dueDate", "Due date");
    /** The caption of the picker entry's range picker. */
    public static final I18nString STAY = new I18nString("gallery.date.stay", "Stay");

    /** Every constant above, for a test that holds the catalogue to both languages. */
    public static final List<I18nString> ALL = List.of(
            ENTRY_TABLE, ENTRY_TREE_LOADING, ENTRY_ANNOUNCEMENTS, ENTRY_CALENDAR, ENTRY_DATE_FIELD,
            ENTRY_DATE_PICKER_CLOSED, MOUNTAIN_RANGES, RANGE, CONTINENT, SUMMIT, VISITED, FILES,
            OPEN, SAVE, SAVED, STOP, STOPPED, DELIVERY_DATE, HOLIDAY, INVOICE_DATE, APPOINTMENT,
            DUE_DATE, STAY);

    /**
     * The name of every entry a reader run drives, keyed by the English name
     * {@link AccessibilityGallery.Entry#name()} keeps. The name stays English and identifies the
     * entry — the picker's argument, an exemption's key, a transcript's window title — and this is
     * what is <em>shown</em>, so a reader pointed at the gallery window reads the list in the run's
     * language too.
     */
    private static final Map<String, I18nString> ENTRY_NAMES = Map.of(
            ENTRY_TABLE.english(), ENTRY_TABLE,
            ENTRY_TREE_LOADING.english(), ENTRY_TREE_LOADING,
            ENTRY_ANNOUNCEMENTS.english(), ENTRY_ANNOUNCEMENTS,
            ENTRY_CALENDAR.english(), ENTRY_CALENDAR,
            ENTRY_DATE_FIELD.english(), ENTRY_DATE_FIELD,
            ENTRY_DATE_PICKER_CLOSED.english(), ENTRY_DATE_PICKER_CLOSED);

    /**
     * @param name an entry's name, the English one it is identified by
     * @return what to show for it: the catalogue's string for an entry a reader run drives, and the
     *         name itself for one no run drives
     */
    public static I18nString label(String name) {
        I18nString known = ENTRY_NAMES.get(name);
        return known != null ? known : I18nString.literal(name);
    }

    private GalleryStrings() {
    }
}
