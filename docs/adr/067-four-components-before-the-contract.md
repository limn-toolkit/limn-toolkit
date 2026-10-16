# ADR 067: Four components before the contract, and how a project is told about a flaw

- **Status:** Accepted, 2026-10-14; built the same day (section 8). The owner chose what enters and how each behaves.
- **Date:** 2026-10-14
- **Scope:** tabs that close and move, a section that opens and closes, a slider of two thumbs,
  a text field that types to a pattern; and the files a person reporting a defect or a
  vulnerability is shown.
- **New public API (proposed):** `TabbedPane` closing and moving its tabs; `Expander` and
  `ExpanderGroup`; `RangeSlider`; a mask on `TextField`.
- **Compatibility:** additive. Nothing that exists changes behaviour unless asked.

---

## 1. Why

Asked what is left before 1.0, the owner went over the survey of 2026-09-08 and what it missed.
The contract of 1.0 is a matter of API shape, and every component here is additive: none of
them has to come before it. They come because the owner wants the 1.0 more complete for the
applications it is aimed at, business desktop applications.

Refused on the way, and why, so that they are not proposed again: a switch, which `Checkbox`
already draws (`Variant.SWITCH`); a link widget, whose look is the web's and which a label with
a click listener is on a desktop; a toast, and then notifications drawn by the toolkit in windows
of their own, given up because on Wayland a window cannot place itself, and with its owner
minimized or hidden in the tray not even a popup has a parent to be placed by; rich text and
Markdown.

## 2. Tabs that close and move

- **Closing.** A tab is closable when the application says (`setTabClosable(index, true)`, or for
  every tab added after `setTabsClosable(true)`). A closable tab shows its close button when it
  is selected or under the pointer, so a row of tabs does not read as a row of buttons; a middle
  click closes it too, and Ctrl+W (Cmd+W on macOS) closes the selected one while focus is in the
  pane.
- **A close can be refused,** as a window's close can (ADR 048): a handler is asked with a request
  it accepts or refuses, now or later, so a document with unsaved changes can ask first. Without a
  handler the tab closes. Closing the selected tab selects the one after it, or before it when it
  was the last.
- **Moving.** A tab is dragged along the bar to a new place, the others making room as it passes,
  and moved from the keyboard with Ctrl+Shift+PageUp and PageDown (Cmd on macOS). The reader is
  told the new position. A tab is not torn off into a window of its own.
- **The pane gains** what it lacked for both: `removeTab`, `moveTab`, `tabContent`, and a watcher
  of what changed, in the shape ADR 040 gives every component.

## 3. A section that opens and closes

- **`Expander`:** a header, an arrow that turns, a title, an optional icon at its start and an
  optional summary at its end, over content that opens below it. It opens and closes by click, by
  Space or Enter on the focused header, and from the API. The transition is the toolkit's own, and
  ends at once when the system asks for less motion (ADR 047). The reader hears a button that is
  expanded or collapsed and controls the content.
- **`ExpanderGroup`:** like `ButtonGroup`, at most one of its expanders open, an accordion. An
  expander needs no group. Like a radio in its group since ADR 064, a section out of the scene it
  was in is out of its group until it is back (added 2026-10-16, when the final audit found a group
  holding every section of every dialog rebuilt against it).

## 4. A slider of two thumbs

- **`RangeSlider`, a class of its own,** as the owner chose: a lower and an upper value over the
  same track, steps as `Slider` has, the span between the thumbs filled. (Corrected 2026-10-16:
  this said "steps and marks"; neither slider has marks.)
- **A thumb stops at the other,** or at a minimum distance the application sets (at least a day
  between a start and an end). A click on the track moves the nearer thumb. Each thumb is a control
  of its own for the keyboard and for the reader, named for what it is, the lower or the upper.

## 5. A field that types to a pattern

- **A mask on `TextField`:** a pattern where `0` is a digit, `A` a letter, `*` any character, and
  anything else a literal that enters by itself (`000.000.000-00`). No pattern of any country ships
  in the API: the application writes its own. Numbers and currency stay with `Spinner`, which
  already follows the locale.
- **The guide shows only while the field has focus** (`___.___.___-__`); without focus and empty,
  the field shows its placeholder. Literals enter as one types, and pasting the value with or
  without them gives the same result.
- **The application reads** the value without the literals, the text as shown, and whether every
  position is filled. The reader hears the pattern as the field's description.

## 6. Reporting a defect or a vulnerability

- **`SECURITY.md`** sends a vulnerability to GitHub's private reporting, which keeps a report
  private and puts no address in public; the owner turns it on in the repository's settings.
- **Issue templates** for a defect (version, system, steps, what was expected) and for a feature,
  and a question sent to Discussions.

## 7. Tests

Each component is tested as the others are: the model, the keyboard, the pointer, right to left,
the accessible tree on the three bridges' shapes, and the count of work of ADR 062 where a
component animates. A new widget is heard by the screen readers of the lab (ADR 045's rule), for
what its shapes do not already cover.

## 8. How it was built, and what building found

All four components and the two files are built, each with its model, keyboard, pointer, right to
left and accessible tree tested, a row in the damage and notification contracts, an entry in the
accessibility gallery and the site's, and, for the two that move, a count of the frames they ask
for in the work budget (a section opening, a tab moving). What building found:

- **A slider whose value moves must not ask for a layout.** The `RangeSlider` placed its thumbs by
  asking for a layout on every step, and a layout pass repaints the window: the damage contract
  caught it on the first run. The thumbs are placed where they belong and the slider repaints
  alone.
- **A widget could not reorder its children.** Taking a child out and putting it back detaches
  it, which revokes the focus, the hover and the press inside it, and a tab dragged along its bar
  is a press that must survive the move. `Widget.moveChild` reorders without detaching, and lays a
  parent that clips its children out alone.
- **A contained layout needs a size to compare.** The tab strip was placed and never measured,
  so the scene had nothing to compare and laid the window out for every move. Measured before it
  is placed, a move repaints the pane. Proven both ways: the move repaints the whole window
  again when the measure is taken out.
- **One change, one announcement.** Removing or moving a child announces `CHILDREN` from the one
  funnel every child passes, always as code; a close the user asked for would then have been
  announced twice, once as code and once as the user's. `remove` and `moveChild` take who asked,
  and the funnel's announcement is the only one.
- **The close button is drawn, not a widget.** Hover goes to the deepest widget under the
  pointer, so a button inside a tab took the hover from the tab as the pointer reached it. The tab
  draws its own and publishes it to a reader as a button under the tab, the same whether or not
  it is drawn.
- **A group of sections speaks as a group of radios does.** Opening one section closes another,
  both settle before either announces, and the handlers run after both: the order `ButtonGroup`
  keeps, for which the contract on who may call the two halves of an announcement now names
  `Expander` beside `RadioButton`.
- **A mask fits the text after every edit, inside that edit.** Fitting as a separate edit would
  make undo take back only the fitting, which the next fitting puts back. The fitting is folded
  into the edit it follows, so one undo takes both. And Backspace and Delete after a literal leave
  the same text, so the key says which character the user meant.

What remains: a closable tab's button is a button inside a tab, a structure the shapes of ADR 045
do not cover, and it has not yet been heard by the three screen readers of the lab. The two
settings `SECURITY.md` relies on, private reporting and Discussions, are the owner's to turn on.

