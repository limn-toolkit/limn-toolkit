# ADR 067: Four components before the contract, and how a project is told about a flaw

- **Status:** Accepted and implemented, 2026-10-14; built the same day (section 8). The owner chose
  what enters and how each behaves.
- **Date:** 2026-10-14
- **Scope:** tabs that close and move, a section that opens and closes, a slider of two thumbs,
  a text field that types to a pattern; and the files a person reporting a defect or a
  vulnerability is shown.
- **New public API:** `TabbedPane` closing and moving its tabs; `Expander` and
  `ExpanderGroup`; `RangeSlider`; a mask on `TextField`; and for a widget of an application's own
  that holds children, `Widget.moveChild(child, index)`, which reorders a child without taking it
  out, and `moveChild` and `remove` with the `Change.Origin` of who asked (section 8).
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
- **Closing, the content answers nothing** (amended 2026-10-16, the owner's decision after the
  final audit found a press and Tab still reaching the content while it slid shut). From the
  moment the close starts the content is inert: still drawn, as it was and not greyed, but a press
  on it reaches no control, Tab and an access letter pass it by, no focus can land in it, and an
  assistive technology's verbs on any node inside are refused: the nodes stay published, ENABLED
  as the application left them, with no verb, as the options of a combo's list fading out are
  (ADR 068), and the scene's gate refuses a verb taken off an older snapshot. The focus inside,
  if any, has already gone to the header. It is not disabled: its enabled state stays the
  application's, unchanged, and a disabled control would have been painted greyed for the length
  of the slide. The mechanism is the scene's (an inert subtree, which the pointer's hit, the focus
  funnel, Tab's order, the access letters, the accessible walk's verbs and the verb gate skip),
  lent to the component through `limn.scene.internal.SceneAccess.setInert`, not public API.
  Opened again, the content answers as before; a content replaced while closing is given back
  reachable. (The reader's verbs were added the same day, the owner's answer to the first
  version, which left them working through the slide.)
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
- *Amended 2026-10-16:* **text between single quotes is literal**, so `0`, `A` and `*` can be
  literals (`+'0' 000`), and two single quotes are one apostrophe inside quotes and out: the
  quoting of `java.text.SimpleDateFormat`, the owner's choice. The language had no escape, and
  one added after 1.0 would change what patterns already written mean. A pattern that opens a
  quote and never closes it is refused, naming the pattern.
- *Amended 2026-10-17:* **a literal never takes a character a position would.** The fitting
  took a literal from the text whenever the next character was the same one, so under
  `+55 (00) 00000-0000` a stored `51987654321` read `+55 (19) 87654-321` and lost its area
  code, and the quoted `0` above ate a value's leading 0. A run of literals is now taken from the
  text only when the text carries it: every literal the next position would take is there, and,
  if the run has any, one the position would refuse (`+`, a space) is there too. A run made
  only of characters its position takes cannot be told from a value and reads as formatted;
  where the field knows it holds a value's characters alone, after Backspace steps over a
  literal, it reads no literal at all.
- *Amended 2026-10-17:* **a keystroke lands where it shows.** Trying the Showcase, the owner
  found the caret of an empty `'DE'00 …` and `+1 (000) …` drawn before the guide's literals,
  and a `D` or `E` typed by hand taken in silence until a digit wrote the run. The caret at the
  end of the text is now drawn past the literals the guide shows before the next position, and
  the input method's rectangle, a reader's caret box and a click past the end go with it; the
  text is unchanged, so an empty field stays empty and holds no literal by being focused. A
  literal typed by hand is written as it is typed when it shows the text is being written with
  its literals (a character the next position would refuse, or one after such a character), so
  `D`, `DE`, `DE8`; a lone digit that equals a literal still goes to the position. A literal the
  text ends in is therefore kept, and Backspace takes it back one character at a time instead
  of stepping over it, which it still does where the fitting would put the literal back.
- *Amended 2026-10-19:* **an edit is made to the value, not to the text.** The 2026-10-19
  audit found that an edit anywhere before the end still fitted the whole edited text afresh, so
  a literal the edit shifted could stop matching where the pattern has it and be taken by the
  next position: `+1 (555) 123-4567`, Home, `9` read `+1 (915) 551-2345`, the 1 of `+1` taken
  as a digit and the 7 pushed off, with `isMaskComplete()` still true; `****-****` took its own
  dash after a Backspace, and a quoted `9` became a value. An edit of part of the text now maps
  the range it replaced to the value's characters, removes those, inserts the characters of
  what was typed or pasted that a position takes (read from the entry where they land, so a
  fragment pasted with its literals, or the literal under the caret typed over, is still
  taken as literals), and writes the text again from the value; reading the whole text afresh
  is kept for a whole new value (set from code, typed or pasted over everything, set by an
  assistive technology), and an undo or a redo reads the text it puts back as the field wrote
  it. **Inserting into a field with no position free changes nothing**: the inserted characters
  give way, never the value's, which is what typing at the end of a full field already did and
  what "what does not fit after the last position is dropped" meant for a paste; the other
  reading, pushing the last character off, is the silent loss the audit reported. Typing the
  literal under the caret mid-text now steps over it, as it does at the end. Left then for the
  owner: on a pattern that mixes kinds of position (`AAA-0000-**`) the characters after an edit
  moved along and one that landed in a position refusing it was passed over, lost
  (`ABC-1234-XY`, Backspace after the 3, gave `ABC-124`); see the next amendment.
- *Amended 2026-10-19:* **on a pattern that mixes kinds of position, an edit that would lose a
  character is refused.** The owner's decision, of the three the audit offered (lose the later
  characters, as it did; refuse the edit; keep later sections in place, which needs a model of
  holes in the value): an edit moves the value's characters after it by as many positions as it
  inserted more, or fewer, than it took, and when one of them would land in a position that does
  not take it (a letter on a digit's place, a digit on a letter's), the edit is not made. The
  field does nothing, as a full field does: the text, the caret and the selection stay, no
  handler runs, no watcher or reader hears of it, and no undo step is left, so a redo is kept.
  `ABC-1234-XY` with Backspace after the 3, and `ABC-1234-X` with `Q` typed after the `B`, are
  refused. An edit whose moved characters all still fit is made: a digit moving within its run
  of digits with nothing of another kind after it (`ABC-1234`, Backspace after the 1, gives
  `ABC-234`), a digit or a letter moving on into an any position (`ABC-1234-X`, `5` after the 3,
  gives `ABC-1235-4X`), any character moving within the any positions, and an edit that puts back
  as many characters as it took, each of its kind, which moves nothing. On a pattern whose
  positions are all of one kind every character always fits, so nothing there changes. To change
  a letter of `ABC-1234-XY`, select it and type over it. A full field refusing an insertion now
  keeps a selection it had, too, where it used to collapse it. (Added 2026-10-20: a refused Cut
  writes nothing to the clipboard; it wrote the selection before offering the removal to the
  mask, so a refused Cut was a Copy.)
- *Amended 2026-10-19:* **a masked field lays its text out left to right in every language.**
  The 2026-10-19 audit found the Showcase in Arabic showing the card `4111 1111 1111 1112` as
  `1112 1111 1111 4111` and the phone placeholder `+1 (415) 555-0123` as `555-0123 (415) 1+`: a
  value of digits, spaces and punctuation has no strong character, so its line took the layout's
  direction as its base and the groups ran right to left. The owner's decision: no new API; a
  masked `TextField` always lays its text out left to right, while its chrome keeps following the
  layout. The text's paragraph is shaped left to right (the display line, a composition, the
  guide), so the caret, the selection, hit testing and the arrow keys follow written order;
  letters written right to left keep their own order inside the line (a Hebrew value under
  `AAA-AAA` reads right to left, its first group on the right, as one run). The text is aligned
  to the **left** edge of the content area, not to the start of the layout: the guide continues
  the text to the right, and anchoring the line's left edge keeps every character where it is
  as a position fills, where anchoring the right edge would shift the whole value by the
  difference between a digit and a low line at each keystroke and again when the guide comes
  and goes with the keyboard; it is also where a field written left to right in a right-to-left
  form starts its text. The caret past the guide's literals works there as it does in a
  left-to-right layout. A placeholder on a masked field stands at the same edge and reads left
  to right unless its own words say otherwise, since it is most often an example of the value.
  The leading icon, the trailing button and the pads still swap sides with the layout.
- *Amended 2026-10-20:* **typing over a selection overwrites its positions.** The review of the
  refusal above found that the correction route it, the Javadoc and the forms guide recommend,
  "select them and type over them", was refused on a mixed pattern for any selection of two or
  more characters with something of another kind after it: typing is one keystroke at a time,
  and the first key replaced the whole selection with one character, which moved what followed
  (`ABC-1234-XY`, `12` selected, `56` typed: nothing changed). The owner's decision: typing over a
  selection in a masked field, of any pattern, **overwrites** the selected positions one by one
  and moves nothing. `12` selected, `5` replaces the 1 and the selection becomes the 2; `6`
  replaces the 2 and the caret stands after it; a user who stops midway leaves what is still
  selected as it was. The typed text is read from the entry the selection begins on, as an edit
  reads it, so a literal carried with it is taken as the literal; a character the next selected
  position refuses is refused, the selection unchanged. A paste over a selection fills the
  selected positions in order with the characters each takes, leaves those it does not reach as
  they were and still selected, and drops the rest of the paste: nothing is pushed into the
  positions after the selection. Each keystroke is its own undo step, as before. A selection
  holding only literals is typed over as before (an insertion where it begins), and Backspace,
  Delete and Cut over a selection remain removals under the refusal above. Selecting everything
  and typing therefore overwrites from the first position rather than clearing the field;
  Delete clears it. `insertText`, a caller's write, still replaces the selection.
- *Amended 2026-10-20:* **a masked line keeps its groups in written order whatever the digits'
  script.** The review of the amendment above found that a left-to-right paragraph is not enough
  with Arabic-Indic digits (U+0660 to U+0669, which an Arabic keyboard types and the mask takes as
  digits): the bidirectional algorithm counts numbers as right to left for the neutrals between
  them, so the space or dash between two groups resolved right to left and `٤١١١ ١١١١ ١١١١ ١١١٢`
  drew its first group on the right, the second group appearing left of the first while typing,
  with the guide after the first. The owner's decision: a masked line keeps its **groups** (the
  stretches between literals) in left-to-right order whatever the digits' script, and inside a
  group each script keeps its own direction, so a group of Hebrew letters reads right to left
  within the group (this replaces "a Hebrew value under `AAA-AAA` reads right to left, its first
  group on the right": the first group is now on the left). The line is shaped group by group:
  each group and each run of literals alone in a left-to-right paragraph, set side by side in
  written order into one line in the text's own index space, so the caret, the selection, the
  arrows, hit testing, a double click and the caret box the input method is given follow it
  with no change of their own. It applies to the value, a composition in progress (what is being
  composed counts as value), the guide and its unfilled tail, and a placeholder the pattern would
  write. A group boundary inside a grapheme cluster moves to the cluster's end. One shape is left
  as the algorithm reads it: a quoted literal of letters written right to left beside a group of
  such letters is one run with it, since no level can keep two adjacent right-to-left runs apart.
- *Amended 2026-10-16:* **the reader no longer hears the pattern.** Read aloud, `AA-0000` is
  "A A dash zero zero zero zero" where it means two letters and four digits, in every language,
  and as the field's description it pushed out the tooltip, which the walk gives only to a node
  with no description. Wording a pattern in the toolkit was weighed and refused: its literals and
  counts are the application's, so each language would need plurals and names for characters the
  toolkit cannot know. The field describes nothing; the literals it writes are read as part of
  its text, and the application says what the field expects with a tooltip or
  `setAccessibleDescription`, as the accessibility gallery's masked fields now do.

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

(Amended 2026-10-16: "the accessible tree on the three bridges' shapes" was each component's own
tests, not the shape contracts of ADR 045. The contracts now run over the `RangeSlider`'s thumbs,
each a `VALUE` with the bounds the other thumb leaves it, the `Expander`'s header, a
`POPUP_OWNER` that discloses in place and so says no `HAS_POPUP`, and the tab strip's list button,
a `POPUP_OWNER`. The value contract learned to take one node of several and the popup-owner
contract a disclosure for it.)

What remains: a closable tab's button is a button inside a tab, a structure the shapes of ADR 045
do not cover, and it has not yet been heard by the three screen readers of the lab. The two
settings `SECURITY.md` relies on, private reporting and Discussions, are the owner's to turn on.

