package limn.demo.a11y;

import limn.demo.a11y.AccessibilityGallery.ReaderScript;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.input.Keys;

import java.util.List;

/**
 * The steps each reader run drives on its gallery entry (decision 24; H2, DT8, B9, T7): what a
 * person at the keyboard presses, in order, and what each press does to the widget. The guest
 * recipes wait on the step lines the driver prints and label their snapshots with these labels,
 * so a step is renumbered only with the recipes that name it.
 *
 * <p>Every step changes what the entry publishes or announces something; {@code ReaderStepsTest}
 * runs each script headlessly and fails on a silent one. Nothing here is a platform fact: the
 * keys are the toolkit's own, the command modifier is resolved on the machine that runs the step.
 */
public final class ReaderScripts {

    private ReaderScripts() {
    }

    /**
     * "Tree with branches that load". Steps 1 to 15 are the ones {@code --scene tree-reader} drove
     * on 2026-09-13, over the same first rows, so the recipes' "after step 14 RIGHT: Remote opened
     * and loaded" still reads true; 16 to 21 add the fetch that finds nothing and the folder that
     * was always empty (decision 45).
     */
    public static final ReaderScript TREE_LOADING = new ReaderScript("tree-loading", List.of(
            Step.press(Keys.DOWN, "lands on Documents"),
            Step.press(Keys.DOWN, "moves to Reports"),
            Step.press(Keys.LEFT, "closes Reports"),
            Step.press(Keys.RIGHT, "opens Reports again"),
            Step.press(Keys.DOWN, "moves to the first report"),
            Step.press(Keys.DOWN, "moves to 2026.pdf"),
            Step.press(Keys.DOWN, "moves to the meeting notes"),
            Step.press(Keys.DOWN, "moves to Media, closed"),
            Step.press(Keys.RIGHT, "opens Media"),
            Step.press(Keys.DOWN, "moves to clip.mp4"),
            Step.press(Keys.LEFT, "climbs back to Media"),
            Step.press(Keys.LEFT, "closes Media"),
            Step.press(Keys.DOWN, "moves to Remote, whose children are not fetched"),
            Step.press(Keys.RIGHT, "opens Remote, busy while it fetches"),
            Step.press(Keys.DOWN, "moves to index.json, fetched"),
            Step.press(Keys.LEFT, "climbs back to Remote"),
            Step.press(Keys.LEFT, "closes Remote"),
            Step.press(Keys.DOWN, "moves to Trash"),
            Step.press(Keys.RIGHT, "opens Trash, whose fetch finds nothing"),
            Step.press(Keys.DOWN, "moves to Empty folder"),
            Step.press(Keys.RIGHT, "opens Empty folder, empty from the start")));

    /**
     * "Table with a header and rows": ADR 041 §11's B9 recipe. The wheel away from the cursor row
     * the recipe ends with is a pointer gesture and not a step; a reader run does it by hand.
     */
    public static final ReaderScript TABLE = new ReaderScript("table", List.of(
            Step.press(Keys.DOWN, "moves the focus row to Caucasus"),
            Step.chord(Keys.TAB, Keys.MOD_SHIFT, "moves into the header, on Range"),
            Step.press(Keys.RIGHT, "moves the header cursor to Continent"),
            Step.press(Keys.SPACE, "sorts by Continent, ascending"),
            Step.press(Keys.TAB, "moves back to the rows, on Caucasus where the sort put it"),
            Step.press(Keys.RIGHT, "moves to Caucasus's continent"),
            Step.press(Keys.RIGHT, "moves to its summit"),
            Step.press(Keys.RIGHT, "moves to its Visited switch"),
            Step.press(Keys.SPACE, "adds Caucasus to the selection"),
            Step.press(Keys.DOWN, "moves the focus row to Pyrenees, in the Visited column"),
            Step.chord(Keys.DOWN, Keys.MOD_SHIFT, "extends the selection from the anchor to Urals"),
            Step.press(Keys.END, "moves to the last row, Andes"),
            Step.chord(Keys.A, Step.COMMAND, "selects every row")));

    /**
     * "Calendar grid": September 2026 with the 15th selected, days before the 2nd refused, Sundays
     * refused and the 21st marked (decision 30: the cursor stops on a refused day and says so).
     */
    public static final ReaderScript CALENDAR = new ReaderScript("calendar", List.of(
            Step.press(Keys.RIGHT, "moves to the 16th"),
            Step.press(Keys.DOWN, "moves to the 23rd"),
            Step.press(Keys.LEFT, "moves to the 22nd"),
            Step.press(Keys.LEFT, "moves to the 21st, marked"),
            Step.press(Keys.LEFT, "moves to Sunday the 20th, refused"),
            Step.press(Keys.END, "moves to Saturday the 26th, the last day of the week"),
            Step.press(Keys.HOME, "moves back to Sunday the 20th, the first day of the week"),
            Step.press(Keys.PAGE_DOWN, "pages to October the 20th"),
            Step.press(Keys.PAGE_UP, "pages back to September the 20th"),
            Step.chord(Keys.PAGE_DOWN, Keys.MOD_SHIFT, "pages a year ahead"),
            Step.chord(Keys.PAGE_UP, Keys.MOD_SHIFT, "pages a year back"),
            Step.chord(Keys.UP, Step.COMMAND, "climbs to the months"),
            Step.press(Keys.RIGHT, "moves to the next month"),
            Step.press(Keys.ENTER, "descends into that month's days"),
            Step.press(Keys.ENTER, "picks the day under the cursor")));

    /**
     * "Date field, segmented": the date's segments, typing into one, clearing one, then the clock
     * field and the empty field (decisions 16, 49 and 53).
     */
    public static final ReaderScript DATE_FIELD = new ReaderScript("date-field", List.of(
            Step.press(Keys.RIGHT, "moves to the second segment"),
            Step.press(Keys.RIGHT, "moves to the third segment"),
            Step.press(Keys.HOME, "moves to the first segment"),
            Step.press(Keys.END, "moves to the last segment"),
            Step.press(Keys.UP, "steps the last segment up"),
            Step.press(Keys.DOWN, "steps it back down"),
            Step.press(Keys.HOME, "moves to the first segment"),
            Step.type('1', "types a first digit"),
            Step.type('1', "types a second digit and rolls on to the next segment"),
            Step.press(Keys.DELETE, "clears the segment"),
            Step.press(Keys.TAB, "moves to the appointment field"),
            Step.press(Keys.END, "moves to its last segment"),
            Step.press(Keys.UP, "steps the clock"),
            Step.press(Keys.TAB, "moves to the empty due date"),
            Step.press(Keys.UP, "fills the empty segment from today")));

    /**
     * "Date picker, closed": the single picker opens its calendar (a window of its own by
     * default, in the scene under {@code --presentation in-scene}), walks and picks, climbs to the
     * months and backs out one level at a time, then Tab leaves for the period.
     */
    public static final ReaderScript DATE_PICKER = new ReaderScript("date-picker", List.of(
            Step.chord(Keys.DOWN, Keys.MOD_ALT, "opens the calendar"),
            Step.press(Keys.RIGHT, "moves the calendar cursor a day"),
            Step.press(Keys.DOWN, "moves it a week"),
            Step.press(Keys.PAGE_DOWN, "pages to October"),
            Step.press(Keys.ENTER, "picks the day and closes"),
            Step.chord(Keys.DOWN, Keys.MOD_ALT, "opens the calendar again"),
            Step.chord(Keys.UP, Step.COMMAND, "climbs to the months"),
            Step.press(Keys.ESCAPE, "backs out to the days"),
            Step.press(Keys.ESCAPE, "closes the calendar"),
            Step.press(Keys.TAB, "moves to the Open calendar button"),
            Step.press(Keys.TAB, "moves to the period's start date")));
}
