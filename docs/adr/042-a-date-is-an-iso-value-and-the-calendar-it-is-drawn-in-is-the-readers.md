# ADR 042: A date is an ISO value, and the calendar it is drawn in is the reader's

- **Status:** Accepted, 2026-09-09; phase 1 implemented. §11 says what lands in the first phase and
  what is deliberately left out of it, and §12 what has been verified against a live client and what
  is still owed.
- **Date:** 2026-09-09
- **Scope:** the toolkit's first date widgets: what value they exchange with the application, which
  calendar system they draw, how a date is typed, how a month grid is laid out, selected, bounded,
  filtered and marked, how all three are read by a screen reader, and which of the four shapes a
  corporate form asks for are one class and which are three. A time zone is not here; §9 says why.
- **Compatibility:** nothing published changes. This record adds a package beside the widgets and
  two derived accessors to `SizeTokens`; no existing token row moves, so no shipped control changes
  size by a point.

---

## 0. The starting point

The market survey of 2026-09-08 ranked a table first among what the toolkit still owed a
line-of-business application and a date picker immediately after it, for the same reason: they are
the two controls a form cannot be written without, and both had to be built rather than borrowed.
The table landed as ADR 041. This is the other one.

What exists to build on is more than it looks.

`Spinner` already has a **segmented editor**: in `Mode.TIME` it shows `HH:MM`, the up and down
arrows adjust the focused field, Left and Right move between the two, and typing a digit turns the
whole control into a one-line text edit with the old value selected. Every hard question a
segmented field asks — what a step means per segment, what a paste does, what Escape restores, what
a programmatic set does to an edit in progress — has an answer there, written down and tested. This
record does not re-answer them; it generalizes them from two fixed segments to a list the locale
orders.

`Table` (ADR 041) mapped `TABLE`, `ROW`, `CELL` and `COLUMN_HEADER` and the `table`/`cell` facets
onto AT-SPI2, UI Automation and the AX API, and a live client read all four on each guest on
2026-09-09. **A month grid is a table**, so the accessible half of the hardest widget here was paid
for a day before it was needed, and this record adds no role, no facet and no bridge code. That is
not a happy accident but the argument for building the table first, and it is the reason a calendar
can ship in the same phase as the field that opens it.

The i18n axis is settled and is what makes a calendar possible at all. ADR 033 fixed that a number
is localized when it is formatted and nowhere else; ADR 034 that order and case are facts about a
language; ADR 035 that the locale is a property of the subtree and that a widget's passes carry it
in scope. So a widget that asks `I18n.locale()` while it measures, paints or answers an
accessibility question gets the language of the subtree it is in, and a form in one language inside
an application in another is already correct without this record doing anything about it.

What does **not** exist is any use of `java.time` anywhere in the toolkit. Every date decision below
is therefore a first, and none of them is constrained by a shipped API.

## 1. The value is ISO, and the calendar drawn is a display fact

**Decision.** The application exchanges `java.time.LocalDate`, `LocalTime` and `LocalDateTime`,
always in the ISO calendar. The calendar system a widget *draws* — the month names, the year
number, the length of a month, the era — is a separate, resolved axis: `Chronology.ofLocale` for
the widget's effective locale by default, overridable per widget with `setChronology`.

This is ADR 033's rule pointed at a different type. A number is localized when it is formatted; a
date is converted when it is drawn. Nothing but the paint, the measure and the accessibility hook
ever holds a `ChronoLocalDate`, the conversion happens at the edge of each of those passes, and the
field the application reads back is the ISO one it wrote.

Three consequences are worth stating because each is a bug that did not happen:

- **An application never writes a chronology-dependent branch.** A form that stores
  `LocalDate.of(2026, 9, 9)` gets that value back from a picker a Thai user drove, and the fact that
  the user picked it out of a grid headed `กันยายน 2569` is not the application's problem.
- **The JDK's default is ISO nearly everywhere,** which is measured and not assumed:
  `Chronology.ofLocale` answers `IsoChronology` for `ar-SA`, `th-TH`, `he-IL`, `fa-IR` and `ja-JP`
  alike, and answers something else only when the locale carries a `u-ca` extension
  (`ja-JP-u-ca-japanese` → `JapaneseChronology`). Chronology support is therefore **opt-in by the
  locale the application already sets**, and the default path allocates no conversion at all: the
  ISO chronology's `date(LocalDate)` returns its argument.
- **A chronology has a range, and it is not always wide.** `HijrahChronology` covers AH 1300–1600
  and throws `DateTimeException` outside it. Every conversion in the paint path is therefore
  guarded, and a value the display chronology cannot represent falls back to drawing the ISO
  calendar for that widget rather than throwing inside a frame. The guard is in one place
  (`CalendarChronology.convert`) and both widgets go through it.

**Amendment, 2026-09-14 (DATES-NEW-1): the visible month is the drawn calendar's month.**
`CalendarView.visibleMonth()` answers the ISO date of the first day of the month *in the calendar
being drawn*, and every path that chooses a month — `setVisibleMonth`, paging, a chooser pick, a
cursor walking off the grid, `setClock` — normalizes to that day. The first implementation
normalized to the ISO month's first day and then drew the chronology month holding it, which is
right for ISO and wrong for every calendar whose months start mid-way through ISO ones: "next"
from a Hijri month computed a day whose ISO first was the one already shown and did nothing (95 of
the 132 ISO months of a decade), a selected day could fall off the grid, and a month picked in the
chooser drew the month before it. The guard sentence above is also corrected: the one place is
`CalendarChronology.date`/`iso`/`usableFor` (there is no `convert`), and `firstOfMonth` beside
them is the normalization. Pinned by `CalendarViewAccessibilityTest` (Hijri paging, a Hijri day
on the grid, a Hijri month picked).

**Rejected: a value type of our own.** A `limn.time.CalendarDate` would decouple the toolkit from
the JDK and would cost every application a conversion at every boundary, to buy nothing: `java.time`
is in `java.base`, it is the type every persistence layer, every JSON binding and every SQL driver
already speaks, and a toolkit that refuses it makes the application do the adapting. The toolkit
does not own the domain vocabulary of a date any more than it owns `String`.

**Rejected: exposing `ChronoLocalDate` in the API.** It is the honest type for what is drawn and the
wrong type for what is exchanged: it is not comparable across chronologies, it makes every
application signature generic, and it pushes a display decision into a storage decision. It is used
internally, and does not appear on any public signature.

**Today is a display fact too, and it is the clock's.** The ring on today's cell, the "today" a
reader hears on it, the month a calendar opens on while nobody has chosen one, and an empty
segment's first step all read one clock per widget — `setClock(java.time.Clock)` on
`CalendarView`, `DateField` and `DatePicker`, which hands it to its parts. The default is `null`,
the system clock in the default zone read at each call, exactly what `LocalDate.now()` does. It
was added on 2026-09-11 when the site's gallery turned out to change every day: two captures a day
apart differed in the calendar's ring and in two reader transcripts with nothing else changed. The
capture harness now pins one day for every date widget it films, from outside the scene functions,
so the samples the site publishes carry no clock (ADR 043 §9.3).

## 2. Three widgets, and the four shapes a form asks for

A corporate form asks for four things: a date, a date with a time, either of those with a calendar
to pick from, and a period. They are **three classes**, not four and not one:

| | no calendar | with calendar |
|---|---|---|
| **date** | `new DateField()` | `new DatePicker()` |
| **date + time** | `DateField.dateTime()` | `DatePicker.dateTime()` |

plus `DateField.time()` for a time alone, `DatePicker.range()` for a period, and `CalendarView` on
its own for a screen that shows a month rather than filling a field.

- **`CalendarView`** is a month grid and nothing else: it owns a selection, bounds, a filter, marks
  and week numbers, and it is a public widget because a scheduling screen wants a calendar sitting
  in a panel and not inside a popup.
- **`DateField`** is the segmented editor: the whole of typing a date, with no popup, no calendar
  and no dependency on either.
- **`DatePicker`** composes them: it *holds a `DateField` as a real child* and opens a
  `CalendarView` in a popup.

**Amendment, 2026-09-14 (decisions 12, 47, 48, 51 — DT6): two starts and one knob decide the
shape, and the table above is superseded.** The factories named above never existed as written
(`dateTime()`/`time()`/`range()` are value getters; the shapes were `DateField.ofTime`,
`DateField.ofDateTime`, `DatePicker.ofDateTime`, `DatePicker.ofRange`), and `ofDateTime` and
`setShowSeconds` are gone: a field starts at the year (`new DateField()`, `new DatePicker()`) or
at the hour (`DateField.ofTime()`), and `setGranularity(Granularity)` — a closed enum `YEAR`,
`MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND` — says which segment is its last. A time field refuses
the three date levels loudly. The picker fans the level out to its field (both ends of a period)
and its calendar, which takes its level as a `CalendarView.View` (`setGranularity(View)`; an hour
does not compile there). Segments below the level are cut from the locale's own pattern together
with the literal that joined them (`DatePattern.without`: `dd/MM/y` → `MM/y`, `yy. M. d.` →
`yy. M.`), and the value is what the segments say: a month field told 15 June answers the 1st.

| granularity | `DateField` | `DatePicker` popup | a `RANGE` at this level (decision 51) |
|---|---|---|---|
| `YEAR` | the year alone | the year chooser is terminal: a pick there is the selection | 1 January of the first year to 31 December of the last, in the drawn calendar |
| `MONTH` | month and year | the month chooser is terminal (decision 48: its cells carry a real selection) | the 1st of the first month to the last day (28/29/30/31) of the last |
| `DAY` | the default | the day grid | the two days |
| `HOUR` | plus the hour | the day grid **and a time row under it**; Tab cycles grid → header → row (decision 19) | the start at hh:00:00, the end at hh:59:59 |
| `MINUTE` | plus the minute (`ofTime()`'s default) | as `HOUR` | the end at :59 seconds |
| `SECOND` | plus the second | as `HOUR` | the two instants |

The end of a period is the *end field's* business: `DatePicker.ofRange()` marks its second field
as the end of a period, so it holds "June" and answers the 30th, and a range picker at an hour
granularity answers the last representable instant of its end hour. Pinned by `DateFieldTest`
(the six levels, the refusal, a coarser level dropping the segments it lost), `CalendarViewTest`
(a month picker's terminal pick, Gregorian March–June → 03-01..06-30, a leap February → 02-29, a
year range → 01-01..12-31) and `DatePickerTest` (the time row and its Tab cycle, a month range
picker, an hour range 00:00..23:59:59).

**Why composition rather than one class with modes.** A single `DatePicker` with
`Mode.DATE | TIME | DATE_TIME` and `setCalendarEnabled(false)` is one import and one Javadoc, and
that is the whole of its case. Against it: half its state is mutually exclusive at any moment, its
documentation has to explain which combinations mean nothing, and a screen that wants only a grid
carries the popup machinery, the field, the segment parser and the trailing button to get it. The
split falls where the *responsibilities* fall, which is the same test ADR 041 applied to
`Table` against `ListView` and this repository has applied since: looking alike is not the test.

**Why the field is a real child of the picker rather than painted by it.** Focus traversal, the
caret, the clipboard, the accessible subtree and the text-input plumbing are all things a `Widget`
already has and a painted region does not. `DatePicker.range()` is then two children and one
popup, and Tab moves between the two ends of the period for free. The picker paints exactly one
thing itself — the trailing calendar button — and publishes it the way `SearchField` publishes its
clear button and `Spinner` its two arrows: a synthetic child with a name the toolkit supplies,
because nothing else can name it.

**Amendment, 2026-09-14 (DT4): the button is a real child, and the picker paints more than it.**
The paragraph above describes a first cut. The calendar button is a focusable widget of its own
(`DatePicker.CalendarButton`), the picker's last child in Tab order, named by the toolkit and
published as a plain `BUTTON` with `PRESS` (§8's amendment of decisions 18 and 55); and the
picker paints its box and, for a period, the dash between the two fields, as well as the button.

## 3. The field is segments the locale orders, and one of them is always focused

**Decision.** `DateField` derives its segments from the locale's own short date pattern —
`DateTimeFormatterBuilder.getLocalizedDateTimePattern(SHORT, …, chronology, locale)` — parsed into
an ordered list of **fields** and **literals**. `dd/MM/y` gives day, `/`, month, `/`, year;
`M/d/yy` gives month, `/`, day, `/`, year; `y/MM/dd`, `yy. M. d.`, `d.M.y` and `d‏/M‏/y` each give
their own order, their own separators and — in the Arabic case — the two `U+200F` marks the pattern
carries, kept verbatim as literals so the run reads correctly in a bidirectional line.

The keyboard is `Spinner`'s, generalized: Up and Down adjust the focused segment, Left and Right
move between segments, Home and End go to the first and last, and typing digits fills the focused
segment and rolls on to the next when it can hold no more. Alongside that, and this is what the
answer to "segmented or free text?" actually was: **a long run typed or pasted into the field is
parsed as a whole.** `31122026`, `2026-12-31` and `31/12/2026` all commit the same date, so the
person who types a date faster than they can think about segments is not punished for it, and the
person who arrows through the segments is not made to type separators.

Three details are decisions rather than mechanics:

- **A two-digit year in the pattern is widened to four.** `M/d/yy` orders the fields and names the
  separators, and those are the two things the locale genuinely owns; a two-digit year in an
  *editable* field is an ambiguity the toolkit would be creating on purpose. What is displayed is
  what will be stored.
- **An era segment is read-only.** The Japanese pattern `GGGGGy/M/d` carries one, and editing it —
  with the year renumbering across a transition — is a genuine piece of work that no other
  chronology asks for. It is drawn, it is read out, it follows the value, and it is skipped by
  Left and Right. §11 records it as backlog rather than pretending it is finished.
- **An incomplete field has no value.** A field whose year is blank answers `null` from `date()`
  and publishes `VALIDITY` as invalid; it does not guess a year. Guessing is what makes a form
  submit something nobody typed.

**Amendment, 2026-09-14 (DATES-NEW-9, decision 57, settled typed-iso-run and implausible-year):
what a paste and a typed run do.** "A long run typed or pasted is parsed as a whole" held for a
paste and not for typing: `2026-12-31` typed digit by digit into a day-first field went segment
by segment to 0001-02-20 and called it valid. The field now keeps the characters typed since the
last key and, once they read as an ISO date (`2026-12-31`, or `2026-12` in a month field),
commits that date as a whole, caret on the last segment. A **two-digit year** — pasted as
`31/12/26` (the language's `dd/MM/y` parsed it as the year 26), as the six-digit run `311226`, or
typed as `26` and left with a Right, a Home, a separator, a click or the focus — resolves through
a window around today by the widget's clock: 80 years back and 19 ahead by default (`26` → 2026,
`85` → 1985, `45` → 2045), `setTwoDigitYearWindow(yearsBack)` moves it, and
`REFUSE_TWO_DIGIT_YEARS` turns the guess off (a pasted two-digit year is left blank and the field
incomplete; a typed one stays what was typed — **corrected the same day:** a typed two-digit
year left with the caret is blanked the same way, so a form that refuses to guess is never
handed the year 26 as valid; the earlier reading applied the decision's "left blank" to pastes
only; **corrected 2026-09-15:** "however the caret leaves it" did not yet hold — a year typed as
two digits before an in-scene calendar was opened with Alt+Down lost its count of typed digits to
the picker aiming at the field and to the focus coming back, so when the calendar closed and the
field was left the year stayed 26 and valid, guess on or off; the field now counts the digits
the year holds as typed apart from the segment's run, and resolves or blanks it on that leave
too, pinned by `DatePickerTest.aTwoDigitYearTypedBeforeTheCalendarOpenedInTheSceneStillResolvesWhenTheFieldIsLeft`).
Four digits are what was meant, however small: a
year of 26 is held, and whether it is plausible is the application's bound (`setMinDate`). A
digit run keeps its leading zero (`01022026`), a run longer than a date's or a month or day outside
its range refuses the whole paste and leaves the value untouched, and no paste throws out of the
key handler (`Integer.parseInt` over the whole of a pasted account number did). Pinned by
`DateFieldTest`'s typed-and-pasted cases.

**Amendment, 2026-09-14 (era-year-width, decision 38 — DATES-NEW-7, DATES-NEW-10): the widening
stops at eras, and the era rides in the year's spoken text.** The four-digit widening above
removes a two-digit year's ambiguity; a year of era inside a named era has none, so in a calendar
whose years need their era — read off the year of era today being under a thousand (Japanese,
Minguo), never off a list — the year is drawn at its own width ("R8/9/9", "民國115/9/9", not
"R0008") and typed with one to three digits: "115" rolls on, "8" waits for a Right. The era of an
empty field is today's **by the widget's clock** (§1), not the wall clock's: a field under a clock
set to 2018 types into Heisei. "It is read out" was false until now — the era segment is not
editable and so was never a node — and stays true a different way: the era is no node of its own
(ADR 039 §7's DateField row corrected), and reaches a reader as part of the year segment's value
text, "令和8" for the "8" that is drawn, formatted by the JDK with no string of this toolkit's.
Pinned by `DateFieldTest.anEraYearIsDrawnAtItsOwnWidthAndTypedWithUpToThreeDigits`,
`anEmptyEraFieldTypesIntoTheClocksEra` and
`DateFieldAccessibilityTest.anEraCalendarsYearSegmentSpeaksItsEra`.

## 4. The grid is a table, and that is why it can already be read

`CalendarView` lays out six week rows of seven day cells, plus a weekday header row and, when asked,
a leading week-number column. Six rows always, never five and never a variable count: a grid that
changes height as the months are paged makes the popup resize under the pointer and moves the
button the user was about to press.

Days outside the displayed month fill the leading and trailing cells. They are drawn muted, they
are selectable, and picking one pages the grid to that month — the behaviour every desktop calendar
has, and the one that makes the first three days of next month reachable without a trip through the
header.

**The header climbs, and this is a correction.** The first cut paged a month at a time and nothing
else; §11 recorded a month-and-year chooser as deliberately left out, on the argument that "jumping
to a distant year is what typing into the field is for". That argument is wrong for the one date
every form asks for and nobody reaches by paging: a date of birth is four hundred presses away, and
the field is not always there &mdash; a standalone `CalendarView` has none. So the title is a
button: days climb to the twelve months of the year, months climb to a block of twenty-four years,
and each pick descends one step. `setView` lets an application open at any level, which is what a
birth-date picker wants. Descending is navigation and not a choice: no selection is announced and no
handler runs until a **day** is picked.

**Amendment, 2026-09-14 (DATES-NEW-1, DATES-NEW-10): the year chooser blocks by the proleptic
year and names the era.** A block of twenty-four is aligned on `ChronoField.YEAR`, the one number
every chronology counts without a gap, and not on the year of era: aligned on the year of era, a
Japanese block began at "Reiwa 0", which is not a year, and paging back from Reiwa's block landed
on Heisei's with 2012–2018 in neither. In a calendar whose years are short enough to need their
era — read off the year of era today being under a thousand (Japanese, Minguo), never off a list
of chronologies — a cell is spoken with its era ("平成31", "令和2"), drawn with the era's one
letter only while the block crosses an era ("H31", "R2"), and the title names both ends ("平成28 –
令和21"); the month chooser's title is the same era-qualified year. Pinned by
`CalendarViewAccessibilityTest.aJapaneseYearChooserPagesWithoutSkippingYearsAndNamesTheEra`.

The month is paged by the two header buttons, by PageUp and PageDown, and by arrowing off an edge.
Arrow keys move by a day and a week, Home and End go to the first and last day **of the week**
(they name a position in a row, so they mirror with the row; §8), and the focused day is a *cursor*
that is not the selection: it moves with the arrows and commits with Enter or Space. A grid where
arrowing selected would fire a form's handler seven times crossing a week.

**Amendment, 2026-09-14 (DT3, DT4): Home and End do not mirror, and Shift with a Page key is a
year.** The parenthesis above contradicts §9 and the code: Home and End name the first and last
day of the week, not a side of the row, so they do not mirror (`DateMirroringTest.upAndDownAndHomeAndEndDoNotMirror`);
the cross-reference is §9, not §8. Shift with PageUp or PageDown steps the cursor a year, and in
`RANGE` extends the band from the anchor as Shift with an arrow does — a binding the code always
had and this record did not name (`CalendarViewAccessibilityTest.shiftWithAnArrowPreviewsTheBandFromTheAnchorWithoutClosingIt`).

## 5. Bounds are two dates and a predicate, and all three are enforced twice

`setMinDate`, `setMaxDate` and `setDateFilter(Predicate<LocalDate>)` exist on `CalendarView`, on
`DateField` and on `DatePicker`, which fans out to both of its parts. A day outside the bounds or
refused by the filter is drawn disabled, is skipped by the keyboard cursor, refuses a click, and is
published to a screen reader without a `SELECT` verb — the same "a dead control carries no verb"
rule `SegmentedControl` states for a scroll arrow it cannot use.

The field enforces the same three, and enforces them at a different moment: a typed date that is out
of range or refused does not silently snap to the nearest legal day. It is held, the field publishes
`VALIDITY` as invalid with a message, and the value the application reads stays what it was.
Snapping is the behaviour that loses a user's typing without telling them.

The predicate is called during paint, once per visible cell, and is documented as such: it must be
cheap and it must be pure. A filter that hits a database is a filter that stalls a frame.

**Amendment, 2026-09-14 (decision 30 — DATES-NEW-6): the cursor stops on a refused day and says
so.** "Skipped by the keyboard cursor" above was never what the code did, and the owner chose the
code's behaviour over the record's: the cursor visits every day, Enter and Space are refused on a
refused one (`pick` says no, nothing is announced, no handler runs), and the cell is published
**disabled** — through the model's narrowing-only declaration (`Accessibility.disabled()`, ADR 039
§1.2 amendment of the same date), which is the one route a synthetic child has to be less enabled
than its owner — with no `SELECT` verb, so a reader arrowing across the month hears that the day is
unavailable where a skip would have left a hole nobody was told about. A month or year the
chooser does not offer is published the same way. The sentence about the field ("the value the
application reads stays what it was") is also corrected: `date()` answers the out-of-range date
that was typed, and `isValid()` says separately that it is not acceptable (`DateFieldTest`). Pinned
by `CalendarViewTest.theCursorStopsOnARefusedDayAndEnterIsRefusedThere` and
`CalendarViewAccessibilityTest.aDayTheBoundsRefuseCarriesNoSelectVerb`.

## 6. A period is one grid and two fields

`CalendarView.setSelectionMode(RANGE)` makes the grid select a period: the first click sets an
anchor, the second closes the range, the cells between them are drawn as a band, and moving the
pointer or the keyboard cursor before the second click previews the band it would make. Shift with
an arrow extends from the anchor. (**Corrected 2026-09-14, DT4:** the pointer previews, and the
keyboard previews only with Shift held — a plain arrow moves the cursor and leaves the band alone,
so arrowing away from a half-made period does not drag it along; pinned by
`CalendarViewAccessibilityTest.aPointerMovePreviewsTheBand` and
`shiftWithAnArrowPreviewsTheBandFromTheAnchorWithoutClosingIt`.) A range is `DateRange`, a record of two inclusive `LocalDate`
ends, normalized so `start` is never after `end`; a half-made range is not a `DateRange` and is not
published as one — `selectedRange()` answers `null` until the period is closed.

`DatePicker.range()` is two `DateField`s with an en dash between them and one popup: focus in the
start field opens the grid anchored on the start, focus in the end field on the end, and the grid
writes back into whichever end the picker is filling. The two fields are ordinary children, so the
period is typable end to end without ever opening the calendar.

## 7. Week numbers and marks

`setShowWeekNumbers(true)` adds a leading column carrying the week number from
`WeekFields.of(locale)` — the locale's own convention, not ISO's, for the same reason the first day
of the week is the locale's: a German user is shown the week Germany numbers (minimal days 4,
Monday) and a Brazilian user the week Brazil numbers (minimal days 1, Sunday), and a toolkit that
showed one of them the other's week number would be showing them a number that is wrong where they
work. `setFirstDayOfWeek` overrides the derived day for an application whose own week is fixed.

`setDayMarks(Function<LocalDate, DayMark>)` decorates days: a `DayMark` is a dot colour and an
optional `I18nString` that joins the cell's accessible name, so a holiday, a deadline or a day with
appointments is both visible and spoken. The colour is the application's, because a holiday is red
in one domain and a deadline red in another, and the toolkit has no opinion about which.

## 8. What a screen reader is told

**The grid.** `TABLE` with `table(6 rows, 7 or 8 columns)`; the weekday header is a `GROUP` of
`COLUMN_HEADER`s named with the standalone narrow weekday (**corrected 2026-09-14, DT4:** with
the *full* standalone weekday — the narrow letter is what is drawn, and seven single letters name
nothing to a reader crossing the header; `CalendarViewAccessibilityTest.theHeaderRowNamesEachColumnWithTheWholeWeekdayAndNotTheLetterDrawn`); each week is a `ROW`; each day is a
`CELL` carrying `cell(row, column)`, a `SELECT` verb when it is selectable, `selectionItem` when it
is selected or in the band, and `State.ACTIVE` when it is the keyboard cursor. A day's name is the
**full localized date** and not the bare number — "9 de setembro de 2026" and not "9" — because a
cell heard on its own has to say what it is, and the column header alone does not carry the month.
The mark's description, when there is one, follows the date.

**The field.** `GROUP` holding one `SPIN_BUTTON` per editable segment, exactly the shape
`Spinner` publishes for its two arrows and for the same reason: a segment is separately focusable,
separately adjustable and separately spoken, and a single text field publishing `31/12/2026` gives
a reader no way to say which part the caret is in. Each segment carries `value(v, min, max, 1)`,
its own name from the toolkit's bundle ("day", "month", "year", "hour", "minute", "second"), and
`INCREMENT`/`DECREMENT`, which reach the same private path Up and Down reach. The group itself
publishes **no value facet**: a group has no number, and a minimum, a maximum and a step over a
date would be three lies for a bridge to carry. It publishes `INVALID` with the reason as its
description when the field holds something unacceptable, and the whole date as a string is
`DateField.text()` for an application that wants it. (The first implementation put the whole date
on the group as a `valueText`; §12 records why that reached nobody.)

**Amendment, 2026-09-14 (decisions 16 and 53 — DATES-NEW-8): an empty segment says so.** A
segment nobody has filled published its minimum as its number, so a client reading the number
heard "1" for a day nobody typed and the published verb (a step lands on today's) contradicted
it. It now publishes the model's empty value (ADR 039 §1.2 amendment of the same date: the range
kept, no number, the minimum answered only where a platform must have one) with a spoken word as
its text — "empty", one string in all 21 date locale files — while the dashes stay drawn. Typing
the first digit is a `VALUE_CHANGED` even when the digit is the minimum, and the first step from
empty still lands on today's own value by the widget's clock (§1). Pinned by
`DateFieldAccessibilityTest.aBlankSegmentSaysItIsEmptyAndFillingItIsAValueChange`.

The header's title is published as a `BUTTON` carrying `EXPANDED`, because it is the only way to
the two choosers and a reader never offered it is left paging. In a chooser the grid stays a
`TABLE`; its cells are named with the month or the year, carry `SELECT` where they lead anywhere,
and carry **no** selection facet &mdash; telling a reader a month is "selected" would be telling
them the form holds a value it does not.

**Amendment, 2026-09-14 (decision 37): a day is "15 of 30".** "`selectionItem` when it is
selected or in the band" above was never what the code did: every day cell carries one while the
selection mode is not `NONE`, selected or not (the capture read "item 1 of 42 unselected"), and
what it carried as its position was the cell's flat index over the forty-two cells of the grid —
the geometry, read out as a place in a set. A day cell now publishes its day of the month as the
position and its month's length as the size, in the calendar being drawn and each cell by its own
date, so a leading cell of the month before is "31 of 31" and a Hijri day is numbered in its Hijri
month; a chooser cell keeps its index over the chooser's count, and zero still means "none" on
every platform (semantics 6). Pinned by
`CalendarViewAccessibilityTest.aDayCellIsNumberedByItsDayOfMonthOverTheMonthsLength`.

**Amendment, 2026-09-14 (decision 48, DATES-NEW-11, DT2 — MODEL-NEW-1's widget half): what a
chooser says, and what its nodes are.** The cell holding the month or year on show — filled on
screen — carries the fact in its name, after a comma as "today" does ("Sep, on show"; one string,
`limn.date.onShow`, in all 21 date locale files), in a chooser somebody is passing through; the
first implementation set `CHECKED` on it, which the builder drops (ADR 039 §1.2). The chooser a
month or year picker picks in carries a real selection instead and no word. The two paging
buttons are named for what they page in the view on show — "Previous month"/"Next month" over
the days, "Previous year"/"Next year" over the months, "Previous 24 years"/"Next 24 years" over
the years (four strings, 21 locales) — where before all three views said "month". And a
chooser's rows and cells are keyed in a range of their own rather than the day grid's: a key is
an identity, so a month cell keyed like a day cell *was* that node across a view change, and a
Windows element built for it while it carried no selection item answered no `SelectionItem` for
the day it later stood for; a view change now destroys one set of nodes and mints the other.
(Corrected the same day, DT2's second half: the first cut gave the months and the years one
chooser range between them, so in a month picker — whose months carry a selection item and whose
years do not — the climb to the years and back kept the defect one level up; the month chooser
and the year chooser now have ranges of their own, and the sentence before holds between all
three views.)
Pinned by `CalendarViewAccessibilityTest.theChooserNamesTheCellOnShowAndAMonthPickerSaysItWithASelectionInstead`,
`thePagingButtonsSayWhatTheyPageInEveryView` and `dayAndChooserCellsAreDifferentNodes`.

**Amendment, 2026-09-15 (GALLERY-NEW-2): a chooser somebody has climbed into has a cursor from the
first frame, and it is the cell on show.** The view change cleared the chooser's cursor and only
the first arrow placed it, so after Ctrl (or Cmd) and Up the months were drawn with **no cursor
ring at all** and **no cell published `ACTIVE`** — a reader following the active descendant was
told the grid had moved and not where it now stood, and heard the first month only after an arrow
that had already stepped past it; the two reader recipes had to say "the months are shown" where
every other step names where the cursor is. The cursor is now answered rather than written: until
an arrow (or a reader's `FOCUS`) has moved it, it is `currentChooserCell()`, the month of the month
being drawn or the year of the year block. Answered and not written, because the field is the
user's and the fallback is the calendar's: `setVisibleMonth` while a chooser stands moves what is
on show and must move this with it, and paging with the cursor already placed must not. The climb
announces the arrival as an `ACTIVE` change where the grid holds the keyboard, which is the same
test the describe pass makes, so what is announced and what the tree says are one answer. Arriving
is not moving: the first arrow steps one cell, as it always did. Pinned by
`CalendarViewAccessibilityTest.aClimbToTheMonthsLandsTheCursorOnTheMonthOnShowAndAnnouncesIt`,
`aClimbToTheYearsLandsTheCursorOnTheYearOnShow` and
`theCursorFollowsWhatIsOnShowUntilAnArrowHasMovedIt`, and in both of the picker's presentations by
`DatePickerAccessibilityTest.aClimbToTheMonthsInTheSceneLandsTheEffectiveFocusOnTheMonthOnShow`
and limn-demo's
`DatePickerNativePopupTest.aClimbToTheMonthsInAWindowOfItsOwnLandsTheEffectiveFocusOnTheMonthOnShow`.
Step 12 of the `calendar` recipe and step 7 of `date-picker` now name the month the climb lands on.

**The picker.** The field's subtree, the popup's, and one synthetic `BUTTON` for the calendar
affordance with `EXPANDED` on the picker itself.

**Amendment, 2026-09-14 (decisions 18 and 55, settled calendar-title-verbs — DATES-NEW-4,
DATES-NEW-12, WINDOWS-NEW-10's widget half): the popup is the field's to tell.** The button is a
real widget, not a synthetic child (§12's finding, repeated here wrongly), and the expanded state
moves off the picker and the button onto the **field**, which is the node a reader arrives at:
like `ComboBox` and the ARIA combobox pattern, the field publishes `EXPANDED`, `HAS_POPUP` and the
one verb its state allows — `EXPAND` while closed, `COLLAPSE` while open (Alt+Down and Escape) —
and accepts exactly those; the "Open calendar" button is a plain `PRESS`; the picker's group
carries no expand state and accepts no verb (it accepted `EXPAND`, `COLLAPSE` and `CANCEL` without
publishing them, against ADR 039 §1.5's contract). A **single picker's group is no node at all**:
it declares nothing and the walk hoists its field and button in its place, so a caption bound to
the picker names the field (the walk's label-redirect hook of the same date), where before it
named an unfocusable group and left the focused field nameless. A **range picker** keeps the
group and the label over it, and its two fields are "Start date" and "End date" (21 locales) for
themselves. The calendar's **title** publishes `EXPAND` only while the finest view is showing and
`COLLAPSE` only above it, and accepts exactly those (`COLLAPSE` comes straight back down, as
Escape does); `PRESS` keeps climbing one step. The accessibility gallery gained a "Date picker,
closed" entry so the field is ratcheted as it stands in a form. Pinned by
`DatePickerAccessibilityTest` and
`CalendarViewAccessibilityTest.theTitleExpandsAndCollapsesAsWellAsPressesAndRefusesTheVerbItDoesNotPublish`.

**Amendment, 2026-09-14 (decision 5, semantics 4 — the native presentation's effective focus):
the caret yields to the popup's cursor.** With the calendar in a window of its own the field keeps
the focus and the popup takes none, and the tree's effective focus falls through to the cursor
in the popup's tree only when the focused node's own subtree holds no `ACTIVE` node. The field's
caret segment therefore claims `ACTIVE` while the field holds the focus **except** while its own
popup holds the keyboard (open, and the picker not aiming at the field), and claims it again
the moment the popup closes; until this note the segment stayed `ACTIVE` throughout, so the
cross-window fallback could never fire for a date picker and a reader arrowing across the month
was told the field's day segment. The drawn caret is unchanged. The scene presentation is
untouched: there the overlay holds the focus, the picker aims at the field for the digits, and
the cursor is `ACTIVE` under the overlay. Pinned by limn-demo's
`DatePickerNativePopupTest.whileTheCalendarWindowHoldsTheKeyboardTheEffectiveFocusIsItsCursorNotTheFieldsCaret`,
which reads the host tree's effective focus across the two windows.

**Amendment, 2026-09-14 (decision 2, semantics 5 — the scene presentation's closing verb): the
field publishes `COLLAPSE` only where it can take it.** While the popup is an overlay of the
scene, the scene refuses every verb on a widget beneath the overlay (its input gate), and the
platform is answered from the snapshot before any hook runs; a `COLLAPSE` published on the field
there was reported accepted and closed nothing. So in the scene presentation the open field
publishes `EXPANDED` and `HAS_POPUP` and no verb, the overlay's `CANCEL` is the closing verb,
and the field accepts `COLLAPSE` only where it publishes it — in a window of its own, where the
field keeps the focus and the verb reaches it (the paragraph above's "accepts exactly those"
holds in both presentations, with the list differing between them). `ComboBox` has the same
shape and was left as it is: it is another lane's widget, and its precedent is noted, not
followed. Pinned by `DatePickerAccessibilityTest.theCaptionNamesTheFieldAndTheFieldSaysItHasAPopup`
(in-scene: no verb on the open field, Collapse performed there changes nothing, the overlay's
Cancel closes; and the overlay holds the focus with the cursor `ACTIVE` under it as the tree's
effective focus, decision 1's in-scene half of item 10) and limn-demo's
`DatePickerNativePopupTest` (the field takes `COLLAPSE` in a window of its own).

**Amendment, 2026-09-15 (decision 11, its positive half): a cursor a reader can move.** In all
three widgets the cursor and the value are two things — the calendar's cursor is not its
selection, a chooser's cursor is not the month on show, and which segment holds a field's caret
is not the date — so the items publish `FOCUS`, which moves the cursor and does nothing else. A
**day** publishes it unless the bounds or the filter refuse it (a refused day carries no verb,
decision 30), in every selection mode including `NONE`; performing it gives a standalone calendar
the focus, moves the cursor onto the day as an arrow would (paging when the day belongs to the
month before or after) and selects nothing, and inside an open picker it moves the grid's cursor
and commits nothing into the field. A **chooser cell** that leads somewhere publishes it beside
`SELECT` and moves the chooser's cursor without descending or, in the chooser a month or year
picker picks in, picking. A **segment** publishes it beside `INCREMENT` and `DECREMENT`; performing
it is a click on the segment: the field takes the focus, the caret moves there and no value moves
(a two-digit year the caret leaves resolves, as it does for every other way out of the year,
§3). Before this note no date item published the verb, and the platforms' SetFocus,
`setAccessibilityFocused:` and GrabFocus on one had no candidate (semantics 5). Pinned by
`CalendarViewAccessibilityTest.focusOnADayMovesTheCursorThereAndSelectsNothing` and
`focusOnAChooserCellMovesItsCursorWithoutDescendingOrPicking`,
`DateFieldAccessibilityTest.focusOnASegmentPutsTheCaretThereAndChangesNoValue` and
`DatePickerAccessibilityTest.focusOnADayOfTheOpenCalendarMovesItsCursorAndCommitsNothing`.
**Corrected the same day (semantics 5, decision 30's rule; phase-2 fix review):** a segment
published its three verbs whatever the field's state, so on a disabled field, and on the field
beneath its picker's calendar presented as an overlay of the scene, `INCREMENT`, `DECREMENT` and
`FOCUS` were answered "accepted" from the snapshot and then dropped by the scene, which refuses
every verb on a disabled widget and on one under the overlay (the fact `COLLAPSE` above is gated
on). In both states a segment now publishes no verb and its value read-only, which withdraws the
`SET_VALUE` a writable value implies; a popup in a window of its own leaves the segments operable.
Two things this note does not settle: the field inside a disabled *container*, or beneath some
other in-scene modal, still publishes them, as every widget there does (the rule is the model's
and not this record's); and `FOCUS` out of a year typed as two digits resolves that year, or
blanks it with the guess off (§3, decision 57), so it is the one case where the verb moves a value
&mdash; the owner's to confirm against decision 11's "without committing". Pinned by
`DateFieldAccessibilityTest.aDisabledFieldsSegmentsCarryNoVerbAndAReadOnlyValue` and
`DatePickerAccessibilityTest.theFieldsSegmentsCarryNoVerbBeneathTheCalendarOverlay`.
**Amended 2026-09-15 (fix round 2c; ADR 039 §1.13's amendment of that date):** the overlay half of
that correction, and the 2026-09-14 amendment's `COLLAPSE` withheld from the open field in the
scene presentation, are no longer the field's. The walk takes every verb and every setter off each
node outside the layer that owns input — beneath the picker's own overlay, beneath "some other
in-scene modal", and in a window a native modal blocks — so the field stopped asking where its
popup is drawn: the picker no longer hands it that fact, the field publishes `COLLAPSE` by state
wherever it is enabled and the walk withdraws it beneath the overlay, and a segment's gate is the
field being enabled alone. The disabled *container* stays as this note left it (the scene refuses
there too; the rule for it is not settled here). The case above now also asserts the field and the
calendar button publish no verb beneath the overlay, and it goes red with the walk's rule backed out.
**Amended 2026-09-15 (fix round 2d; ADR 039 §1.5's amendment of that date):** the disabled half is
the walk's as well, and the disabled *container* is settled with it. The walk withdraws every verb
and setter from each node that is not `ENABLED`, so a segment on a disabled field, on a field inside
a disabled container, and a refused day or a chooser cell with no selectable day all publish no
verb and a read-only value without a gate of the field's or the calendar's own: `DateField` declares
its segments' verbs and a writable value and the walk withdraws them, and `CalendarView` declares
`SELECT` on every day of a selecting grid and narrows the refused ones. The `FOCUS` a refused day
withholds in a grid that selects nothing stays the calendar's, because no day is narrowed there.
`DateFieldAccessibilityTest.aDisabledFieldsSegmentsCarryNoVerbAndAReadOnlyValue` still pins the
field.
**Amended 2026-09-15 (the 2d review; semantics 5):** the overlay's `CANCEL`, the closing verb of
the scene presentation above, is published and performed only while the calendar is open. Through
the fade-out after a close the overlay is still drawn and still the layer that owns input, and it
published `CANCEL`, which the picker's `setOpen(false)` dropped for a picker already closed while
the hook answered done. The calendar inside keeps its verbs through the fade, because it still
performs them (a day's `SELECT` one frame after the close picks the date, as a click does); whether
it should is not settled here. Pinned by
`DatePickerAccessibilityTest.throughTheFadeOutTheCalendarsLayerOffersNoCancel` and limn-demo's
`VerbPolicyRatchetTest` (`FADE_OUT_DISMISSAL_ENTRIES`).
**Amended 2026-09-15 (fix round 2e; ADR 039 §1.5's amendment of that date):** "its value read-only"
and "a read-only value" in the notes above are withdrawn. A segment that is not `ENABLED` — on a
disabled field, on a field in a disabled container, beneath the picker's in-scene overlay — keeps
its writable value and accepts no `SET_VALUE`, because a writable value implies the setter only on
an `ENABLED` node; the scene refuses one sent anyway. The field's case is renamed
`DateFieldAccessibilityTest.aDisabledFieldsSegmentsCarryNoVerbAndAcceptNoValue`, and it and
`DatePickerAccessibilityTest.theFieldsSegmentsCarryNoVerbBeneathTheCalendarOverlay` assert that the
value stays writable and is not accepted.

No role is added to the model and no facet: every one of these is a role ADR 041 or ADR 039 already
mapped on all three platforms. **That is the whole of the accessibility cost of this record**, and
it is why the calendar could be built at all without reopening the bridges, which live in their own
repository since the fourth split. (**Corrected 2026-09-14, DT4:** the bridges live in
`limn-backend-lwjgl` since 03fd728 of 2026-09-06, three days before this record; the sentence
was written against the split it reversed.)

## 9. Reading right to left

The grid mirrors as a grid: the first day of the week is drawn at the edge reading starts from, the
week-number column moves to that same edge, and the paging buttons swap ends and swap their
pointing, so "previous month" is on the side the reader comes from. Left and Right mirror, because
they name a *side* of a row; Up and Down do not, because a week is below a week in every language;
Home and End do not name sides and stay the first and last day of the week; PageUp and PageDown
stay previous and next month.

The field does **not** reorder its segments under a right-to-left layout, and this is the one place
where the obvious answer is wrong. A date is a run of numbers, and a run of numbers keeps its own
left-to-right order inside a right-to-left line — this is the Unicode bidirectional algorithm's
rule, not a toolkit convention, and it is exactly the reading `Spinner` already documents for its
`HH:MM`. What mirrors is where the run sits in the box and which side the trailing button takes.
The Arabic pattern's own `U+200F` marks, carried through as literals, are what make the separators
sit correctly, and dropping them as "punctuation" would be the actual bug.

There is **no time zone anywhere in this record.** A `LocalDate` has none, a picker that offered one
would be offering a value the application did not ask it to hold, and a zone is a property of an
instant rather than of a calendar day. An application that needs one converts at its own boundary,
where it knows which zone it means.

## 10. Two channels, one seam each

Every widget here follows ADR 040 without an exception. The handler (`onSelect`, `onChange`) runs
only for `Origin.USER`; `observeChanges` hears everything. The aspects each announces:

- `CalendarView`: `SELECTION` when the selection or the range moves, `ACTIVE` when the keyboard
  cursor moves, `VALUE` when the displayed month pages, `RANGE` when bounds or the filter change.
- `DateField`: `VALUE` when the value moves, `ACTIVE` when the focused segment changes, `TEXT` for
  an edit, `VALIDITY` when what it holds becomes legal or stops being, `COMMITTED` when an edit
  commits.
- `DatePicker`: `EXPANDED` when the popup opens or closes, and the value aspects it forwards from
  its field so that a watcher on the picker does not have to know it has children.

**Amendment, 2026-09-14 (decision 59 — DATES-NEW-13): a change of view is a `VALUE` too, and
Escape from the header announces the cursor.** `CalendarView` announces `VALUE` when what it
shows moves between days, months and years — the person's (`USER`) from the title, from Ctrl
with an arrow, from a chooser's Escape and from a picker backing out of a chooser; the caller's
(`CODE`) from `setView`; the calendar's own (`ADJUSTMENT`) when `setGranularity` lifts a finer
view to the new level (added the same day: the first cut assigned the view there directly and
announced nothing, which a date picker converting its level with the card open reaches); nothing
for a view already showing — where before only the title's
published `EXPANDED` flipped and a watcher heard a layout. Escape on a header control puts the
cursor back on the grid and now announces `ACTIVE` for it, as Down always did. A date field's
arrow between segments is the same kind of move and reaches a reader as one
`ACTIVE_DESCENDANT_CHANGED` on the field (decisions 6 and 49; the model's rule since phase 1),
pinned here beside the roll-on case. Pinned by
`CalendarViewTest.climbingTheHeaderAnnouncesTheViewAsAValueAndSoDoesACallersWrite`,
`CalendarViewTest.escapeFromTheHeaderAnnouncesTheCursor` and
`DateFieldAccessibilityTest.anArrowBetweenSegmentsMovesTheFieldsCursorOnce`.

`CalendarView` has two handler slots and not one: `onSelect(Consumer<LocalDate>)` in `SINGLE` mode
and `onSelectRange(Consumer<DateRange>)` in `RANGE`. One slot taking a widget, or a slot taking
`Object`, would make every application cast; two slots, each documented as its mode's, is the
smaller price and each is guarded by `Checks.handlerSlot` like every other.

## 11. What lands in phase 1, and what is deliberately left out

**In phase 1** — everything above: the three widgets, the four shapes, the segment parser, the
chronology axis, bounds, the filter, ranges, week numbers, day marks, the accessible trees, the
mirroring, the notification contract, the demo scene and the guide.

**Struck off this list on 2026-09-09, the day it was written:** the month and year choosers. They
were left out here with a reason that did not survive the first person to use the widget, and §4
now carries the decision instead. A record that keeps an argument after it has been shown wrong is
worse than one that never made it.

**Left out, deliberately, and each with its reason:**

- **A time row inside the popup.** The popup does not take focus — that is the toolkit's popup
  contract, and every key a `ComboBox` popup answers is forwarded from the field that owns it. A
  segmented time editor inside a surface that cannot be focused would need either a focus contract
  this record has no business changing or a second key-forwarding path. `DatePicker.dateTime()`
  therefore edits its time in the field, which is where a keyboard user would type it anyway.

  **Amendment, 2026-09-14 (decision 19; DT6, DATES-NEW-3): withdrawn.** The second forwarding
  path existed already — the picker forwards Tab into the calendar's header — and it now carries
  characters too. A picker at `HOUR` or finer has a time row under the grid: a real `DateField`
  that never takes the focus, driven exactly as the grid is (the keys and the digits are handed to
  it while Tab has put the keyboard on it), and Tab cycles grid → paging arrows and title → time
  row → grid in both presentations. What it types goes into the field through the row's own
  origin, so the application's handler runs as if the person had typed into the field. The
  in-scene presentation, where the overlay holds the focus, forwards every key and character it
  does not answer to the field whose caret is showing, which is what a day typed over an open
  calendar needed and did not have (DATES-NEW-3: the digits died at the overlay's root). Pinned by
  `DatePickerTest.aPickerDownToTheMinuteCarriesATimeRowInItsPopupAndTabCyclesThroughIt` and
  `whileTheInSceneCalendarIsOpenTypedDigitsAndBackspaceStillReachTheField`.
- **A footer of shortcuts** — "Today", "Clear", "last 7 days". Cheap to add and deliberately not
  added: which shortcuts a form wants is an application's decision, and a toolkit that ships three
  guesses ships three that are wrong somewhere.
- **Editing an era** (§3), and with it the Japanese calendar's era transitions.
- **A multi-month grid.** Two months side by side is the range-picker convention on the web; it is
  also twice the popup, and a period is typable in the two fields without opening anything.
- **Time zones and `Instant`** (§9).

## 12. Verification

Headless, in this repository: the segment parser against the eight locale patterns the probe
recorded (`M/d/yy`, `dd/MM/y`, `dd.MM.yy`, `y/MM/dd`, `yy. M. d.`, `d.M.y`, `d‏/M‏/y`,
`GGGGGy/M/d`), the chronology axis against Hijrah, Japanese, Thai Buddhist and Minguo including the
AH 1300–1600 boundary, the grid's geometry and paging, the range band, bounds and the filter, both
accessible trees against `AccessibleTestBase`, the mirroring of both widgets, and the four ADR 040
obligations through `NotificationContractTest`.

**What use found, and the record now says instead of what it said first.** Three things, all from
the first interactive session rather than from any test. The popup opened once and then never
again: the grid is one widget, each presentation built a fresh panel, and `Widget.add` refuses a
child that already has a parent &mdash; correctly, since it fires `onDetached` over a whole subtree
and silently stealing a child would run that behind the caller's back. The throw happened inside a
click, the scene contained it, and the picker was left reporting itself open with nothing drawn,
which is the worst shape a defect can take. The month header read as a sentence fragment, because
Portuguese writes months in lower case inside a sentence and a heading is not one; the first
character is now upper-cased through `I18n`, so Turkish gets its dotted capital and caseless
scripts are untouched. And the navigation was unusable for a date of birth, which is §4's
correction above.

**What the implementation found, and the record now says instead of what it said first.**
`Accessibility.valueText` gives an *existing* value a display form; a node with no value facet has
nothing for it to land on, so the whole date published on the field's group was silently reaching
nobody. Found by `DateFieldAccessibilityTest`, which asked the tree for it. The group now publishes
no value at all and the segments carry them, which is §8 as it now reads.

**Amendment, 2026-09-14 (DT3): what is really tested, by name.** The first paragraph of this
section claimed more than the tests held, and seven implemented behaviours had no test at all.
As of this date: the segment parser is `DatePatternTest`, where
`everyPatternTheProbeRecordedParsesIntoItsOwnOrderAndSeparators` parses all eight patterns named
above verbatim (`dd.MM.yy` and `d.M.y` were named and parsed by no test until it), beside the
short-date orders of pt-BR, en-US, ja, ko and ar through the JDK. The chronology
axis: `CalendarViewTest.theCalendarDrawnIsIsoUntilTheLocaleOrTheApplicationSaysOtherwise`
(Japanese by locale, Thai Buddhist by call) and `aChronologyThatCannotHoldTheMonthFallsBackRatherThanThrowing`
(1750, outside the Hijri range); Hijrah in `CalendarViewAccessibilityTest.aHijriGridPagesByHijriMonths`,
`aSelectedHijriDayIsOnTheGrid`, `aHijriMonthPickedInTheChooserIsTheMonthShown` and
`aHijriGridAtTheEndsOfItsRange` — the AH 1300 and 1600 ends this section named and no test
reached, and writing that test found that the month before AH 1300 threw `DateTimeException` out
of a frame (`previousMonthLength`), so every chronology step at a range's end now falls back to
ISO (`CalendarChronology.plus`); Minguo in `aMinguoCalendarNamesTheRepublicsYear` and
`DateFieldAccessibilityTest.anEraCalendarsYearSegmentSpeaksItsEra`; Japanese in
`aJapaneseYearChooserPagesWithoutSkippingYearsAndNamesTheEra`,
`DateFieldTest.anEraYearIsDrawnAtItsOwnWidthAndTypedWithUpToThreeDigits` and
`theCaretNeverStopsOnTheEra`. Geometry and paging: `CalendarViewTest.theArrowsMoveByADayAndAWeekAndThePageKeysByAMonth`,
`homeAndEndAreTheEndsOfTheWeekAndFollowTheLocalesFirstDay`,
`theArrowsPageAYearInTheMonthChooserAndABlockInTheYearChooser`,
`CalendarViewAccessibilityTest.theCellsAreCentredOnTheWidgetInEveryView` and
`pickingALeadingOrTrailingDayPagesTheGrid`. The band: `CalendarViewTest.aPeriodTakesTwoPicksAndTheHandlerOnlyRunsForTheSecond`,
`aPeriodPickedBackwardsIsStillOrdered`, `CalendarViewAccessibilityTest.shiftWithAnArrowPreviewsTheBandFromTheAnchorWithoutClosingIt`
(Shift+arrow, and Shift+PageDown a year), `aPointerMovePreviewsTheBand` and
`aSelectedDayAndEveryDayInAPeriodSaysSo`. Bounds and the filter: `CalendarViewTest.theCursorStopsOnARefusedDayAndEnterIsRefusedThere`,
`aFilteredDayIsNotSelectableInEitherMode`, `aMonthOrYearWithNoSelectableDayInItIsNotOffered`,
`DateFieldTest.aDateOutsideTheBoundsIsHeldAndReportedRatherThanSnapped` and
`aFilteredDateIsInvalidWithItsOwnMessage`. Week numbers and marks:
`theWeekNumberColumnAddsAColumnAndNotARow`, `theWeekNumberIsTheLanguagesOwn` (week 1 in en-US
and 53 in de-DE for the same row) and `whatAMarkSaysReachesTheReaderAndNotOnlyTheEye`. Paste and
typed runs: `DateFieldTest.aPastedTwoDigitYearIsTheSameCenturyEveryWayItIsWritten`,
`aPastedRunKeepsItsLeadingZero`, `anOverlongPasteChangesNothingAndThrowsNothing`,
`aPastedImpossibleMonthOrDayIsRefusedWhole` and `aTypedIsoRunCommitsTheSameDayAsTheLanguagesForm`.
The accessible trees: the four `*AccessibilityTest` classes (`DatePickerAccessibilityTest` since
decision 55) and limn-demo's `DatePickerNativePopupTest`; the mirroring: `DateMirroringTest` (columns, the week column, tree
order, Left and Right, Up/Down/Home/End, the field's run, and now the paging buttons' ends); the
ADR 040 obligations: the three rows of `NotificationContractTest`. Two test names overclaimed and
were corrected the same day: `DatePickerTest.whileTheGridIsOpenTheNavigationKeysDriveItAndTheDigitsStillReachTheField`
now types the digits it names, and `boundsAndTheFilterRefuseADayRatherThanMovingIt`, which
pressed no key, is `theCursorStopsOnARefusedDayAndEnterIsRefusedThere`.

**Still owed, and named so it is not forgotten:** a live reader run on each of the three guests over
the demo's date scene — the same discipline ADR 039 and ADR 041 were held to, which is what found
four defects on Windows and two on Linux that no headless test could have. Until that run happens,
what §8 claims is what the headless tree says and not what a reader speaks.

**Amendment, 2026-09-14 (LAB-NEW-13, settled reader-scene-clock): what the run is pointed at
speaks the same day everywhere.** The run goes over limn-demo's `AccessibilityGallery` date
entries ("Calendar grid", "Date field, segmented", "Date picker, open", "Date picker, closed"),
not over the demo's live date scene, which keeps the real clock. Each of those entries is built
on noon of 2026-09-09 UTC and declares English (United States) on its root, whatever the guest's
clock and process locale say: a calendar names its today cell ", today" and the language names
every cell and segment, so an entry on the real clock spoke a different day on each guest (the
Fedora guest's clock ran days behind the others). Pinned by limn-demo's `DateReaderEntriesTest`,
built under a Brazilian Portuguese process locale.
