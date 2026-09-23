package limn.demo.a11y;

import limn.demo.a11y.AccessibilityGallery.ReaderScript;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.input.Keys;

import java.util.List;

import static limn.accessibility.Accessible.Role.BUTTON;
import static limn.accessibility.Accessible.Role.CELL;
import static limn.accessibility.Accessible.Role.COLUMN_HEADER;
import static limn.accessibility.Accessible.Role.GROUP;
import static limn.accessibility.Accessible.Role.SPIN_BUTTON;
import static limn.accessibility.Accessible.Role.SWITCH;
import static limn.accessibility.Accessible.Role.TREE_ITEM;
import static limn.accessibility.Accessible.State.ENABLED;
import static limn.accessibility.Accessible.State.EXPANDABLE;
import static limn.accessibility.Accessible.State.EXPANDED;
import static limn.accessibility.Accessible.State.SELECTED;
import static limn.demo.a11y.AccessibilityGallery.Fact.cursor;
import static limn.demo.a11y.AccessibilityGallery.Fact.focused;
import static limn.demo.a11y.AccessibilityGallery.Fact.row;
import static limn.demo.a11y.AccessibilityGallery.Fact.shown;

/**
 * The steps each reader run drives on its gallery entry: what a person at the keyboard presses,
 * in order, what each press does to the widget, and what the published trees hold once it is
 * done. The guest recipes wait on the step lines the driver prints and label their snapshots with
 * these labels, so a step is renumbered only with the recipes that name it.
 *
 * <p>Every step changes what the entry publishes or announces something, and every step's facts
 * are true once it is done; {@code ReaderStepsTest} runs each script headlessly and fails on a
 * silent step or a false fact. Nothing here is a platform fact: the keys are the toolkit's own,
 * the command modifier is resolved on the machine that runs the step, and the facts are read off
 * the toolkit's own tree.
 */
public final class ReaderScripts {

    private ReaderScripts() {
    }

    /**
     * "Tree with branches that load". Steps 1 to 15 are the ones {@code --scene tree-reader} drove
     * on 2026-09-13, over the same first rows, so the recipes' "after step 14 RIGHT: Remote opened
     * and loaded" still reads true; 16 to 21 add the fetch that finds nothing and the folder that
     * was always empty. A row whose children are known carries their count in its
     * name ("Reports 2"); one never fetched, or fetched and found empty, carries none.
     */
    public static final ReaderScript TREE_LOADING = new ReaderScript("tree-loading", List.of(
            Step.press(Keys.DOWN, "lands on Documents")
                    .expecting(cursor(TREE_ITEM, "Documents 2").with(EXPANDED)),
            Step.press(Keys.DOWN, "moves to Reports")
                    .expecting(cursor(TREE_ITEM, "Reports 2").with(EXPANDED)),
            Step.press(Keys.LEFT, "closes Reports")
                    .expecting(cursor(TREE_ITEM, "Reports 2").with(EXPANDABLE).without(EXPANDED)),
            Step.press(Keys.RIGHT, "opens Reports again")
                    .expecting(cursor(TREE_ITEM, "Reports 2").with(EXPANDED)),
            Step.press(Keys.DOWN, "moves to the first report").expecting(cursor(TREE_ITEM,
                    "Q3 regional revenue and headcount, consolidated (final).pdf")),
            Step.press(Keys.DOWN, "moves to 2026.pdf")
                    .expecting(cursor(TREE_ITEM, "2026.pdf")),
            Step.press(Keys.DOWN, "moves to the meeting notes").expecting(cursor(TREE_ITEM,
                    "meeting notes from the Tuesday planning session.md")),
            Step.press(Keys.DOWN, "moves to Media, closed")
                    .expecting(cursor(TREE_ITEM, "Media 2").with(EXPANDABLE).without(EXPANDED)),
            Step.press(Keys.RIGHT, "opens Media")
                    .expecting(cursor(TREE_ITEM, "Media 2").with(EXPANDED)),
            Step.press(Keys.DOWN, "moves to clip.mp4")
                    .expecting(cursor(TREE_ITEM, "clip.mp4")),
            Step.press(Keys.LEFT, "climbs back to Media")
                    .expecting(cursor(TREE_ITEM, "Media 2").with(EXPANDED)),
            Step.press(Keys.LEFT, "closes Media")
                    .expecting(cursor(TREE_ITEM, "Media 2").without(EXPANDED)),
            Step.press(Keys.DOWN, "moves to Remote, whose children are not fetched")
                    .expecting(cursor(TREE_ITEM, "Remote").with(EXPANDABLE).without(EXPANDED)),
            Step.press(Keys.RIGHT, "opens Remote, busy while it fetches")
                    .expecting(cursor(TREE_ITEM, "Remote").with(EXPANDED)),
            Step.press(Keys.DOWN, "moves to index.json, fetched")
                    .expecting(cursor(TREE_ITEM, "index.json")),
            Step.press(Keys.LEFT, "climbs back to Remote")
                    .expecting(cursor(TREE_ITEM, "Remote").with(EXPANDED)),
            Step.press(Keys.LEFT, "closes Remote")
                    .expecting(cursor(TREE_ITEM, "Remote").without(EXPANDED)),
            Step.press(Keys.DOWN, "moves to Trash")
                    .expecting(cursor(TREE_ITEM, "Trash").with(EXPANDABLE).without(EXPANDED)),
            Step.press(Keys.RIGHT, "opens Trash, whose fetch finds nothing")
                    .expecting(cursor(TREE_ITEM, "Trash").with(EXPANDED)),
            Step.press(Keys.DOWN, "moves to Empty folder")
                    .expecting(cursor(TREE_ITEM, "Empty folder").without(EXPANDED)),
            Step.press(Keys.RIGHT, "opens Empty folder, empty from the start")
                    .expecting(cursor(TREE_ITEM, "Empty folder").with(EXPANDED))));

    /**
     * "Table with a header and rows": the table's reader recipe. The wheel away from the cursor row
     * the recipe ends with is a pointer gesture and not a step; a reader run does it by hand. In
     * MULTI a plain arrow selects the row it lands on, so the Space of step 9 takes the row step 1
     * selected back out of the selection.
     */
    public static final ReaderScript TABLE = new ReaderScript("table", List.of(
            Step.press(Keys.DOWN, "moves the focus row to Caucasus")
                    .expecting(cursor(CELL, "Caucasus"), row("Caucasus").with(SELECTED)),
            Step.chord(Keys.TAB, Keys.MOD_SHIFT, "moves into the header, on Range")
                    .expecting(cursor(COLUMN_HEADER, "Range")),
            Step.press(Keys.RIGHT, "moves the header cursor to Continent")
                    .expecting(cursor(COLUMN_HEADER, "Continent")),
            Step.press(Keys.SPACE, "sorts by Continent, ascending")
                    .expecting(cursor(COLUMN_HEADER, "Continent").described("Sorted ascending")),
            Step.press(Keys.TAB, "moves back to the rows, on Caucasus where the sort put it")
                    .expecting(cursor(CELL, "Caucasus").inRow("Caucasus")),
            Step.press(Keys.RIGHT, "moves to Caucasus's continent")
                    .expecting(cursor(CELL, "Europe").inRow("Caucasus")),
            Step.press(Keys.RIGHT, "moves to its summit")
                    .expecting(cursor(CELL, "5,642").inRow("Caucasus")),
            Step.press(Keys.RIGHT, "moves to its Visited switch")
                    .expecting(cursor(SWITCH, "Visited").inRow("Caucasus")),
            Step.press(Keys.SPACE, "takes Caucasus out of the selection")
                    .expecting(cursor(SWITCH, "Visited").inRow("Caucasus"),
                            row("Caucasus").without(SELECTED)),
            Step.press(Keys.DOWN, "moves the focus row to Pyrenees, in the Visited column")
                    .expecting(cursor(SWITCH, "Visited").inRow("Pyrenees")),
            Step.chord(Keys.DOWN, Keys.MOD_SHIFT, "extends the selection from the anchor to Urals")
                    .expecting(cursor(SWITCH, "Visited").inRow("Urals"),
                            row("Pyrenees").with(SELECTED), row("Urals").with(SELECTED)),
            Step.press(Keys.END, "moves to the last row, Andes")
                    .expecting(cursor(SWITCH, "Visited").inRow("Andes")),
            Step.chord(Keys.A, Step.COMMAND, "selects every row")
                    .expecting(row("Andes").with(SELECTED), row("Urals").with(SELECTED))));

    /**
     * "Announcements": the application speaking. Nothing else in the gallery calls
     * {@code Scene#announce}, so this is the only script that can put a bridge's announcement path
     * in front of a reader. Both politeness levels are pressed, because the three platforms map
     * them to different values.
     *
     * <p>Every step's fact is only where the cursor stands, and that is the point: a press that
     * announces changes nothing in any tree, so {@code ReaderStepsTest}'s "changed something"
     * rule is satisfied by the announcement alone, and a scene that stopped announcing would fail
     * it as a silent step.
     */
    public static final ReaderScript ANNOUNCEMENT = new ReaderScript("announcement", List.of(
            Step.press(Keys.SPACE, "presses Save, which announces \"Saved\" politely")
                    .expecting(cursor(BUTTON, "Save")),
            Step.press(Keys.TAB, "moves to Stop")
                    .expecting(cursor(BUTTON, "Stop")),
            Step.press(Keys.SPACE, "presses Stop, which announces assertively and cuts in")
                    .expecting(cursor(BUTTON, "Stop"))));

    /**
     * "Calendar grid": September 2026 with the 15th selected, days before the 2nd refused, Sundays
     * refused and the 21st marked (the cursor stops on a refused day and says so).
     * The climb to the months lands the cursor on the month on show, so step 12 names it like
     * every other step (fixed 2026-09-15; it used to say only that the months were
     * shown, because no month was published as the cursor at all).
     */
    public static final ReaderScript CALENDAR = new ReaderScript("calendar", List.of(
            Step.press(Keys.RIGHT, "moves to the 16th")
                    .expecting(cursor(CELL, "September 16, 2026")),
            Step.press(Keys.DOWN, "moves to the 23rd")
                    .expecting(cursor(CELL, "September 23, 2026")),
            Step.press(Keys.LEFT, "moves to the 22nd")
                    .expecting(cursor(CELL, "September 22, 2026")),
            Step.press(Keys.LEFT, "moves to the 21st, marked")
                    .expecting(cursor(CELL, "September 21, 2026, holiday")),
            Step.press(Keys.LEFT, "moves to Sunday the 20th, refused")
                    .expecting(cursor(CELL, "September 20, 2026").without(ENABLED)),
            Step.press(Keys.END, "moves to Saturday the 26th, the last day of the week")
                    .expecting(cursor(CELL, "September 26, 2026").with(ENABLED)),
            Step.press(Keys.HOME, "moves back to Sunday the 20th, the first day of the week")
                    .expecting(cursor(CELL, "September 20, 2026").without(ENABLED)),
            Step.press(Keys.PAGE_DOWN, "pages to October the 20th")
                    .expecting(cursor(CELL, "October 20, 2026")),
            Step.press(Keys.PAGE_UP, "pages back to September the 20th")
                    .expecting(cursor(CELL, "September 20, 2026")),
            Step.chord(Keys.PAGE_DOWN, Keys.MOD_SHIFT, "pages a year ahead")
                    .expecting(cursor(CELL, "September 20, 2027")),
            Step.chord(Keys.PAGE_UP, Keys.MOD_SHIFT, "pages a year back")
                    .expecting(cursor(CELL, "September 20, 2026")),
            Step.chord(Keys.UP, Step.COMMAND, "climbs to the months")
                    .expecting(cursor(CELL, "Sep, on show")),
            Step.press(Keys.RIGHT, "moves to the next month")
                    .expecting(cursor(CELL, "Oct")),
            Step.press(Keys.ENTER, "descends into that month's days")
                    .expecting(cursor(CELL, "October 1, 2026").without(SELECTED)),
            Step.press(Keys.ENTER, "picks the day under the cursor")
                    .expecting(cursor(CELL, "October 1, 2026").with(SELECTED))));

    /**
     * "Date field, segmented": the date's segments, typing into one, clearing one, then the clock
     * field and the empty field. The English segments run month, day, year and the clock ends on
     * its half of the day; pt-BR's run day, month, year and end on the minute, which the labels are
     * worded to fit.
     */
    public static final ReaderScript DATE_FIELD = new ReaderScript("date-field", List.of(
            Step.press(Keys.RIGHT, "moves to the second segment")
                    .expecting(cursor(SPIN_BUTTON, "Day")),
            Step.press(Keys.RIGHT, "moves to the third segment")
                    .expecting(cursor(SPIN_BUTTON, "Year")),
            Step.press(Keys.HOME, "moves to the first segment")
                    .expecting(cursor(SPIN_BUTTON, "Month")),
            Step.press(Keys.END, "moves to the last segment")
                    .expecting(cursor(SPIN_BUTTON, "Year")),
            Step.press(Keys.UP, "steps the last segment up")
                    .expecting(cursor(SPIN_BUTTON, "Year").valued("2027")),
            Step.press(Keys.DOWN, "steps it back down")
                    .expecting(cursor(SPIN_BUTTON, "Year").valued("2026")),
            Step.press(Keys.HOME, "moves to the first segment")
                    .expecting(cursor(SPIN_BUTTON, "Month").valued("9")),
            Step.type('1', "types a first digit")
                    .expecting(cursor(SPIN_BUTTON, "Month").valued("1")),
            Step.type('1', "types a second digit and rolls on to the next segment")
                    .expecting(cursor(SPIN_BUTTON, "Day"), shown(SPIN_BUTTON, "Month").valued("11")),
            Step.press(Keys.DELETE, "clears the segment")
                    .expecting(cursor(SPIN_BUTTON, "Day").valued("empty")),
            Step.press(Keys.TAB, "moves to the appointment field")
                    .expecting(focused(GROUP, "Appointment"), cursor(SPIN_BUTTON, "Month")),
            Step.press(Keys.END, "moves to its last segment")
                    .expecting(focused(GROUP, "Appointment"),
                            cursor(SPIN_BUTTON, "Before or after noon").valued("PM")),
            Step.press(Keys.UP, "steps the clock")
                    .expecting(cursor(SPIN_BUTTON, "Before or after noon").valued("AM")),
            Step.press(Keys.TAB, "moves to the empty due date")
                    .expecting(focused(GROUP, "Due date"),
                            cursor(SPIN_BUTTON, "Month").valued("empty")),
            Step.press(Keys.UP, "fills the empty segment from today")
                    .expecting(focused(GROUP, "Due date"),
                            cursor(SPIN_BUTTON, "Month").valued("9"))));

    /**
     * "Date picker, closed": the single picker opens its calendar (a window of its own by
     * default, in the scene under {@code --presentation in-scene}), walks and picks, climbs to the
     * months and backs out one level at a time, then Tab leaves for the period. The keyboard stays
     * in the field natively and moves into the popup in the scene, so the facts name where the
     * reader stands and whether the field says it is open, which both presentations share; step
     * 7 names the month the climb lands on, as every other step names where the cursor is
     * (fixed 2026-09-15).
     */
    public static final ReaderScript DATE_PICKER = new ReaderScript("date-picker", List.of(
            Step.chord(Keys.DOWN, Keys.MOD_ALT, "opens the calendar")
                    .expecting(shown(GROUP, "Delivery date").with(EXPANDED),
                            cursor(CELL, "September 9, 2026, today").with(SELECTED)),
            Step.press(Keys.RIGHT, "moves the calendar cursor a day")
                    .expecting(cursor(CELL, "September 10, 2026")),
            Step.press(Keys.DOWN, "moves it a week")
                    .expecting(cursor(CELL, "September 17, 2026")),
            Step.press(Keys.PAGE_DOWN, "pages to October")
                    .expecting(cursor(CELL, "October 17, 2026")),
            Step.press(Keys.ENTER, "picks the day and closes")
                    .expecting(shown(GROUP, "Delivery date").without(EXPANDED),
                            cursor(SPIN_BUTTON, "Month").valued("10")),
            Step.chord(Keys.DOWN, Keys.MOD_ALT, "opens the calendar again")
                    .expecting(shown(GROUP, "Delivery date").with(EXPANDED),
                            cursor(CELL, "October 17, 2026").with(SELECTED)),
            Step.chord(Keys.UP, Step.COMMAND, "climbs to the months")
                    .expecting(cursor(CELL, "Oct, on show")),
            Step.press(Keys.ESCAPE, "backs out to the days")
                    .expecting(cursor(CELL, "October 17, 2026").with(SELECTED)),
            Step.press(Keys.ESCAPE, "closes the calendar")
                    .expecting(shown(GROUP, "Delivery date").without(EXPANDED),
                            cursor(SPIN_BUTTON, "Month").valued("10")),
            Step.press(Keys.TAB, "moves to the Open calendar button")
                    .expecting(cursor(BUTTON, "Open calendar")),
            Step.press(Keys.TAB, "moves to the period's start date")
                    .expecting(cursor(SPIN_BUTTON, "Month").valued("9"))));
}
