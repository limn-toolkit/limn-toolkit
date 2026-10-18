# ADR 075: A badge, an avatar, an empty list, a popover, and two button styles

- **Status:** Proposed, 2026-10-18. The owner chose on 2026-10-18 that five small things enter
  before 1.0: a badge, an avatar with a group that ends in "+k", what a table, a list and a tree
  show when they hold no rows, a public popover on the toolkit's anchored popup, and a quieter and
  a destructive button. This record designs each; section 8 holds what is still open.
- **Date:** 2026-10-18
- **Scope:** five additions of the same size, each written as ADR 067 wrote its four: what it is,
  its API, layout and paint, tokens, right to left, what a frame repaints, what each platform's
  reader is given, focus and keys, and strings.
- **New public API (proposed):** `Badge`, `Avatar`, `AvatarGroup` and `Popover` in
  `limn.components`; `setEmptyContent` and `setEmptyMessage` on `ListView`, `Table` and `Tree`;
  `Button.Style`, `Button.setStyle` and `Button.setDestructive`, and `Dialog.addDestructiveButton`.
  `Badge` takes ADR 074's `Tone`.
- **Compatibility:** `Button.setSecondary` is replaced (question 17); everything else is additive.

---

## 0. What this rests on

Read at `e3f602f5` (`claude/review-fixes`). No probe was run.

- **The Showcase draws three of them by hand.**
  - `AppScene.StatusBadge`: a pill of the status's tone at 16 % (26 % in a dark palette), a dot of
    the tone at the leading end, the word in the text colour, published as a `LABEL` named by the
    word with the inner label ignored. `AnimationsScene.StatusChip` paints white on fixed colours.
  - `ListScene.Avatar`: 36 points, a circle of one of five semantic tones (`primary`, `success`,
    `warning`, `info`, `danger`) at 22 % (34 % dark), chosen by the name's hash, the contact's
    initials over it, and the avatar ignored by readers because the row says the name.
  - `EmptyState` (49 lines): the table or the list keeps its box, its header and footer; a message
    is laid out centred over it and shown by `setEmpty(boolean)` from the filter's code. Used by
    the orders table and the contacts list, each with a "no match" line and a button that clears
    the filter.
- **`ComboBox` already counts what it hides:** a private `Badge` draws "+N" from
  `ComponentStrings.COMBO_MORE` ("+{0}", so a language writes its own digits and places its sign),
  and the items are joined for a reader with `COMBO_SEPARATOR`, the language's list comma. A
  public `limn.components.Badge` shadows that private name inside `ComboBox`, which renames it.
- **`ChartPalette.defaultFor(theme)`** gives categorical colours for light and dark surfaces.
- **The anchored popup** (ADR 068, `limn.components.internal.AnchoredPopup`) serves the combo
  box's list, the date picker's calendar and the tab list: a window of its own placed against the
  anchor where the platform allows, the scene where it does not, faded, shadowed (ADR 057),
  closed on a press elsewhere, on the application losing the keyboard and on the owner's
  fullscreen changing, its window's tree grafted into the owner's. Its host says the size, the
  panel, the keys, and what the layer is to a reader (the calendar's is a `GROUP` named
  "Calendar"); the popup adds `CANCEL`. **A popup window takes no focus:** "the field keeps the
  keyboard", and keys and characters are routed to the popup's scene through
  `SceneAccess.KeyRoute`, which carries a key and a character and **no input-method
  composition**. Inside the calendar's card, Tab walks the header, the grid and the time row.
- **The rows engine** (ADR 055, `limn.components.internal.Rows`) is shared by `ListView`, `Table`
  and `Tree`. `Tree` already draws a muted "Empty" line under an open row that holds nothing, not
  focusable, and tells a reader by an announcement, because the cursor steps over the line on every
  platform (ADR 044's amendment of 2026-09-16).
- **`Button`** is `PRIMARY`-looking by default (fill `primary`, hover `primaryHover`, pressed
  `primaryPressed`, ink `onPrimary`) and `setSecondary(true)` makes it a `surface` fill with an
  `outline` border and `text` ink; 81 calls of `setSecondary` in the repository: 71 in the demo, 5
  in the theme editor, `Dialog.addButton` and four tests in the toolkit. The theme has `danger` and no colour for ink on it.
- **Shapes (ADR 045).** A `LABEL` is `STATIC`; an `IMAGE` and a `GROUP` without verbs are
  `SURFACE`; a node with `HAS_POPUP` or an `ExpandFacet` is a `POPUP_OWNER` ahead of a leaf (the
  colour picker button, the tab strip's list button); a list, a table and a tree are `ROWS` or
  `GRID` containers, whose contracts read their members.

## 1. Why

Each answers a pattern every business application draws: a status in a cell ("Overdue",
"Paid"), a person in an assignee column or on a tool bar, a filtered list that found nothing, a
panel of options that opens from a button and closes when done, a "Delete" that must not look
like "Save" and a "Cancel" that must not compete with it. Web component libraries give each of
them a component; the Showcase builds three of them by hand, and the other two are missing from
the toolkit, so an application builds them too.

## 2. Badge

### 2.1 API

```java
public final class Badge extends Widget<Badge> {
    public Badge(I18nString text);            // and String
    public Badge(Icon icon, I18nString text); // the icon leads; the text names it
    public static Badge count(int n);         // the number in the language's digits; "99+" past 99
    public static Badge dot(I18nString name); // a dot alone; the name is for a reader

    public Badge setText(I18nString text);    // and String
    public Badge setIcon(@Nullable Icon icon);
    public Badge setTone(Tone tone);          // NEUTRAL (default), ACCENT, INFO, SUCCESS, WARNING, DANGER
    public Badge setCount(int n);
}
```

### 2.2 Layout and paint

- **One look** (question 1): a pill of the tone at the Showcase's opacity (16 % light, 26 %
  dark), and at its leading end either the icon tinted in the tone or, with no icon, a dot of the
  tone; the text in `text`. The text's contrast is then the palette's text on its surface, which
  the theme already holds, whatever the tone; the colour says the state and the word says it to
  everyone, a reader and a person who does not tell red from green included. `NEUTRAL` is
  `textMuted`.
- **Size:** the step's label font; height the font's line plus `spacingSmall`; `spacingSmall` and
  a half before and after the text; the dot `spacingSmall` across (6 at `MEDIUM`, the
  Showcase's); radius half the height. At
  `MEDIUM` that is the Showcase's pill within a point, with no new token.
- **A count badge** shows the number formatted as `COMBO_MORE` formats its count, in the
  language's digits, and past 99 the string `limn.badge.atLeast` ("{0}+") with 99 (question 2).
- **A dot badge** is the dot alone, twice as wide as a badge's dot, for an "unread" mark beside a row's name.
- **Right to left:** the icon or the dot leads. **Partial rendering:** a text or tone change
  repaints the badge, with a layout only when its width changes.

### 2.3 Readers

| | Windows | macOS | Linux | Shape |
| --- | --- | --- | --- | --- |
| text badge | Text, named by the text | `AXStaticText` | label | `STATIC` |
| count badge | Text, the number as shown | the same | the same | `STATIC` |
| dot badge | Text, the name given | the same | the same | `STATIC` |

A badge takes no focus and has no verb. An application that wants the count read as "3 unread"
names it with `setAccessibleName`; a badge inside an activatable card (ADR 073) or a table row is
read through the card's or the row's own name, as anything inside them is.

## 3. Avatar, and a group that ends in "+k"

### 3.1 API

```java
public final class Avatar extends Widget<Avatar> {
    public Avatar(I18nString name);           // and String
    public Avatar setName(I18nString name);
    public Avatar setInitials(@Nullable String initials); // null: derived from the name
    public Avatar setImage(@Nullable Image image);
    public Avatar setColorIndex(int index);   // default: from the name
}

public final class AvatarGroup extends Widget<AvatarGroup> {
    public AvatarGroup(Avatar... avatars);
    public AvatarGroup add(Avatar avatar);
    public AvatarGroup remove(Avatar avatar);
    public AvatarGroup setMaxShown(int count); // default 4, at least 1
}
```

### 3.2 Layout and paint

- **A circle** (question 4) as wide as the step's control height (32 at `MEDIUM`), so an avatar
  sits in a row of controls at their height; a `SizedBox` makes it larger.
- **An image** is drawn covering the circle, clipped by `clipRoundRect` to it.
- **Without an image,** initials in the label font, strong, in `text`, over the palette colour at
  the Showcase's opacity (22 % light, 34 % dark); the text's contrast is again the palette's text
  on its surface.
- **The colour** is `ChartPalette.defaultFor(theme)` at the index, by default the name's
  `String.hashCode` modulo the palette's size, which is the same on every run and every machine
  (question 5). The semantic tones the Showcase uses are not used: a person drawn in `danger` reads
  as an error.
- **The initials** (question 7), when not given: the first grapheme of the first word and of the
  last, upper-cased in the avatar's locale; one word gives one grapheme; a script without case
  stays as it is. Graphemes come from `BreakIterator`'s character instance, so an accent or a
  joined emoji is one.
- **With neither a name's initials nor an image,** a silhouette of a head and shoulders drawn by
  the toolkit in `textMuted`, as the toolkit draws its own glyphs.
- **A group** lays its avatars in a row, each overlapping the one before by a quarter of its
  width, ringed by two points of `surface` so the overlap reads as a stack. Past `maxShown`, the
  first `maxShown - 1` are drawn and the last place is a circle of `surfaceRaised` holding "+k" in
  `textMuted`, from `COMBO_MORE`, whose tooltip lists the hidden names joined by
  `COMBO_SEPARATOR`. The "+k" takes no click (question 8).
- **Right to left:** a group starts at the leading edge and overlaps toward the trailing one.
  **Partial rendering:** a change of image, name or members repaints the avatar or the group, with
  a layout when the group's count changes.

### 3.3 Readers

| | Windows | macOS | Linux | Shape |
| --- | --- | --- | --- | --- |
| avatar | Image, named by the name | `AXImage`, `AXDescription` the name | image, named | `SURFACE` |
| group | Group, named by the application, `HelpText` every name | `AXGroup`, `AXHelp` every name | panel, described by every name | `SURFACE` |

- **An avatar is an image named by the person** (question 6), so an avatar standing alone (a user
  button's picture, a column of owners) is read. Beside the name it repeats, as in the Showcase's
  contact rows, `setAccessibleIgnored(true)` takes it out, which the Javadoc says.
- **A group** publishes one node: a `GROUP` named by `setAccessibleName` ("Approvers"), described
  by every member's name in order, the hidden ones included, joined by the language's list comma.
  The avatars inside are not published: they would repeat the description, and the hidden ones
  have no box to publish. A reader is not limited by the width the eye is, which is ADR 052's rule
  for the chips of a combo box.

### 3.4 Strings

None new: "+k" is `COMBO_MORE` and the list comma `COMBO_SEPARATOR`, both in the components bundle
in 21 languages.

## 4. What an empty table, list or tree shows

### 4.1 API

On `ListView`, `Table` and `Tree` alike:

```java
public W setEmptyContent(@Nullable Widget<?> content);
public W setEmptyContent(@Nullable Widget<?> content, @Nullable Label message);
public W setEmptyMessage(@Nullable I18nString message); // and String: a muted, wrapped, centred label
public @Nullable Widget<?> emptyContent();
public boolean isShowingEmptyContent();
```

### 4.2 Behaviour

- **Shown while there are no rows** (question 9): a list with no items, a table with no data
  rows, a tree with no root rows. The widget knows its count, so the application does not say
  when; it changes the words for "nothing here" and "nothing matches", which only it knows.
- **Where:** in the rows' viewport, so a table keeps its header and its footer row; centred, at
  most the viewport's width less `spacingLarge` on each side. Written once in the rows engine
  (ADR 055), which owns the viewport of all three.
- **Not the tree's "Empty" line,** which stays under an open row that holds nothing.
- **Focus:** the container stays a Tab stop, as an empty list is one today; the content's own
  focusable widgets (a "Clear filter" button) follow it in the Tab order. Right to left, the
  content lays itself out by its direction. **Partial rendering:** showing and hiding it is a
  layout of the viewport, not of the window.
- **No default text, and no announcement.** An empty table is often simply empty; the words are
  the application's, and so is any announcement (`Scene.announce`), as a filter's result count is.

### 4.3 Readers

| | Windows | macOS | Linux |
| --- | --- | --- | --- |
| container | List, DataGrid or Tree, no items; `HelpText` the message | the list or table (`AXTable` since 2026-09-23), the outline; `AXHelp` the message | list, table or tree; the description |
| content | its own nodes, children of the container after its (no) rows | the same | the same |

- **The container is described by the message** (`DESCRIBED_BY`): the label `setEmptyMessage`
  makes, or the `message` passed with a content. A content passed with no message describes
  nothing, by the rule that a description is declared: the toolkit does not read text out of an
  arbitrary widget. A reader landing on the empty table hears "table, 0 rows, no orders match".
- **The content's nodes are published under the container** (question 10), after its rows, with
  no membership. They are not members, so the rows and grid contracts gain a case: the content is
  published after the rows, belongs to no selection, and the container is described by the
  message while it shows.

## 5. A popover

### 5.1 API

```java
public final class Popover {
    public Popover(Widget<?> content);
    public void showFor(Widget<?> anchor);
    public void close();
    public boolean isOpen();
    public Popover setDisplayMode(DisplayMode requested); // NATIVE_WINDOW by default, as its siblings
    public DisplayMode displayMode();                     // what it was shown as
    public Popover setAlignment(Popover.Alignment a);     // START (default) or END of the anchor
    public Popover setAccessibleName(@Nullable I18nString name);
    public Popover onClose(@Nullable Consumer<Change.Origin> handler);
    public Popover attachTo(Button button);               // the button opens and closes it
}
```

Not a widget, as `PopupMenu` and `Dialog` are not: it shows one.

### 5.2 Behaviour

- **On `AnchoredPopup`,** as a fourth host: placed below the anchor unless it does not fit and
  above has more room, eight points from the edge, lined up with the anchor's leading edge (or
  trailing, `END`), clamped into the work area or the scene; the panel filled with `surfaceRaised`,
  `OVERLAY`'s shadow, `radiusMedium`, `spacingMedium` around the content; its size the content's
  measure within the room (a long content is the application's `ScrollView`).
- **Opened** by `showFor`, or by the attached button's press, which closes it when it is open.
  No hover or focus trigger (question 15): a surface that opens when the pointer passes is a
  tooltip, which every widget has.
- **Closed** by a press outside it or Escape (the user's), by the application losing the keyboard
  or the owner's fullscreen changing (an adjustment), by `close()` (code), which `onClose` hears
  with the origin. Not modal (question 16): a press outside closes it and reaches nothing else, as
  a combo box's list does.
- **Focus** (question 12): on open the keyboard goes to the content's first focusable widget, or to
  the panel when it has none; Tab and Shift+Tab cycle inside, as in a dialog; on close the focus
  goes back to the anchor if it was inside. In the scene the layer holds the focus; in a window of
  its own the owner keeps the desktop's focus and its keys, characters **and input-method
  composition** are routed to the popover's scene (question 13), so a field inside it types as
  any field does, a Japanese or Chinese input method included.
- **Right to left:** `START` is the anchor's leading edge, the right one; the content mirrors
  itself.
- **Partial rendering:** the popup's own, unchanged.

### 5.3 Readers

| | Windows | macOS | Linux | Shape |
| --- | --- | --- | --- | --- |
| the layer | Group, named | `AXGroup`, named | panel, named | `SURFACE`, with `CANCEL` |
| the attached button | Button with ExpandCollapse, expanded or collapsed | `AXButton`, `AXExpanded` | push button, expandable, expanded | `POPUP_OWNER` |
| the content | its own nodes | its own nodes | its own nodes | their own |

- **The layer is a `GROUP`** (question 14) named by `setAccessibleName`, or the anchor's name when
  none is set, `POPUP_FOR` the anchor, grafted into the owner's tree when it is a window, as the
  calendar's is.
- **The attached button** says it opens a popup and whether it is open (`HAS_POPUP`, an
  `ExpandFacet`), the shape the tab strip's list button and the colour picker button already
  have, and runs the `POPUP_OWNER` contract.

### 5.4 Strings

None.

## 6. A quieter button and a destructive one

### 6.1 API

```java
public enum Style { PRIMARY, SECONDARY, TERTIARY }

public Button setStyle(Button.Style style);   // PRIMARY by default, as now
public Button.Style style();
public Button setDestructive(boolean destructive);
public boolean isDestructive();

// Dialog
public Dialog addDestructiveButton(I18nString text, @Nullable String resultValue); // and String
```

`setSecondary(boolean)` goes (question 17); `setSecondary(true)` is `setStyle(SECONDARY)`.

### 6.2 Paint

| | Fill | Border | Ink | Hover / pressed |
| --- | --- | --- | --- | --- |
| `PRIMARY` | `primary` | — | `onPrimary` | `primaryHover` / `primaryPressed` |
| `PRIMARY`, destructive | `danger` | — | `onPrimary` (question 18) | `danger` toward `text` by 8 % / 16 % |
| `SECONDARY` | `surface` | `outline` | `text` | toward `surfaceRaised` / `outline`, as now |
| `SECONDARY`, destructive | `surface` | `outline` | `danger` | as `SECONDARY` |
| `TERTIARY` | none | none | `primary` | `primary` at 8 % / 16 % |
| `TERTIARY`, destructive | none | none | `danger` | `danger` at 8 % / 16 % |

- **The same box at every style:** padding, height and radius are the step's, so a tertiary
  button lines up with the others and keeps a full target; the focus ring is the same.
- **Disabled** draws as today, a tertiary with no fill.
- **Right to left and partial rendering:** unchanged.

### 6.3 Readers and keys

A `BUTTON` at every style, `LEAF_ACTION`. Destructive is not published: no platform carries it
for a reader, and the caption ("Delete order") says what the button does. Keys and access letters
are unchanged.

`Dialog.addDestructiveButton` adds a `SECONDARY` destructive button that is never the dialog's
default (question 19): Return does not delete.

### 6.4 Strings

None.

## 7. What they replace

- **`AppScene.StatusBadge`** becomes `new Badge(status.label).setTone(...)`;
  `AnimationsScene.StatusChip`, which shows a colour transition, stays the scene's own.
- **`ListScene.Avatar`** becomes `Avatar` with `setAccessibleIgnored(true)` in the contact row.
- **`EmptyState`** goes: the orders table and the contacts list take `setEmptyContent(column,
  message)`, and the filters' `setEmpty` calls go with it.
- **`DialogsScene` and `BasicsScene`'s delete buttons** become destructive; the Showcase's
  icon-only tool buttons may become `TERTIARY`; every `setSecondary(true)` becomes
  `setStyle(SECONDARY)`.
- **New Showcase material:** badges of every tone, a count and a dot; avatars with images and
  initials and a group past its limit; a popover holding a filter panel from a tool bar button;
  the three styles, each destructive.
- **Guides:** the widgets guide lists the four classes and the button styles; the lists guide
  shows the empty content; the menus and dialogs guide shows the popover beside the menu and
  the destructive dialog button; the accessibility guide says what each publishes.

## 8. Questions for the owner

1. **The badge's look.**
   - *Recommended:* one look: the tone's tint, a dot or icon in the tone, the word in `text`.
   - *Why:* the contrast is the palette's, already held, at every tone.
   - *The alternative:* also a solid badge, filled with the tone and its text in an ink on it,
     which needs an ink token per tone (`onDanger`, `onWarning`…) and a contrast check for each
     in every palette.
2. **A count past 99.**
   - *Recommended:* "99+", one new string (`limn.badge.atLeast`, "{0}+") in 21 languages.
   - *The alternative:* the whole number, a badge as wide as its digits.
3. **The badge's corners.**
   - *Recommended:* a pill (half the height), as the Showcase draws.
   - *The alternative:* `radiusSmall`, a tag.
4. **The avatar's shape.**
   - *Recommended:* a circle.
   - *The alternative:* a rounded square at `radiusMedium`, which some desktops use for
     organisations rather than people; a setter could add it later.
5. **The avatar's colour.**
   - *Recommended:* the chart palette by the name's hash.
   - *Why:* eight or more categorical colours that carry no meaning.
   - *The alternative:* the Showcase's five semantic tones, which put people in "error" red.
6. **An avatar to a reader, by default.**
   - *Recommended:* an image named by the person; ignored by one call where the name is beside it.
   - *The alternative:* ignored by default, named by one call: quieter in rows, silent where it
     stands alone until the application remembers.
7. **The initials.**
   - *Recommended:* first and last word's first graphemes, overridable with `setInitials`.
   - *The alternative:* the application always gives them; no derivation in the toolkit.
8. **The "+k" of a group.**
   - *Recommended:* drawn, with a tooltip of the hidden names; every name in the group's
     description.
   - *The alternative:* a button that opens a popover listing everyone: a focus stop, a popover per
     group, and a reader round for the button inside a group of images.
9. **When the empty content shows.**
   - *Recommended:* by itself, whenever there are no rows.
   - *The alternative:* `setShowingEmptyContent(boolean)` from the application, as the Showcase's
     `setEmpty` does: one more call in every filter, and a table that can say "no match" over rows.
10. **Where a reader finds the empty content.**
    - *Recommended:* under the container, after its rows, with the container described by the
      message.
    - *Cost:* a node in a list, a table or an outline that is not a row, which no reader has been
      heard on (section 11).
    - *The alternative:* published beside the container, after it, which the walk cannot do
      today (a widget's child is published under its node): a walk feature for one case.
11. **A loading state too?**
    - *Recommended:* not in this record. `Tree` has its row-level loading line; a whole widget
      that is loading is an application's progress bar or busy content today.
    - *Cost of adding it now:* `setLoadingContent` beside `setEmptyContent`, a `BUSY` state on the
      container, which NVDA was measured not to speak (ADR 044), so an announcement besides: a
      day's work with a reader check.
12. **Does the popover take the keyboard?**
    - *Recommended:* yes: the first focusable widget inside, Tab cycling inside, focus back to the
      anchor on close.
    - *Why:* a filter panel or a column chooser is used from the keyboard.
    - *The alternative:* the anchor keeps the keyboard and the popover is reached by the pointer
      alone, as a list's popup: no Tab chain to build, and a panel of controls nobody without a
      pointer can use.
13. **A popover in a window of its own, with a text field inside.**
    - *Recommended:* a window where the platform allows, as its siblings, the input method's
      composition routed with the keys and characters; measured on the four systems with a
      Japanese input method before it ships.
    - *Cost:* the route gains the composition and the candidate window's rectangle, a path no popup
      has today.
    - *The alternative:* a popover always in the scene, where the input method already works: it
      cannot pass the window's edge, and a screenshot of the window holds it.
14. **The popover's layer to a reader.**
    - *Recommended:* a named `GROUP`, as the calendar's layer.
    - *The alternative:* a `DIALOG`: Windows readers then treat it as a dialog (`IsDialog`), which
      a transient panel is not.
15. **Hover and focus triggers.**
    - *Recommended:* none; `showFor` and `attachTo` only.
    - *The alternative:* a trigger setter as some libraries offer, which on a desktop duplicates the
      tooltip and opens surfaces nobody asked for.
16. **A modal popover?**
    - *Recommended:* no: a modal question is a `Dialog`.
17. **`Button.setSecondary`.**
    - *Recommended:* replaced by `setStyle`, its 81 calls in the repository moved.
    - *Why:* three looks are one choice; two booleans for it would allow a "secondary tertiary".
    - *The alternative:* keep `setSecondary` and add `setTertiary`, with a rule for both set.
18. **Ink on a destructive primary button.**
    - *Recommended:* `onPrimary`, measured against `danger` in every built-in palette (section 11);
      a palette that fails gains an `onDanger` colour, a key of the theme file with a default.
    - *The alternative:* `onDanger` now, a theme key and an editor field for every palette.
19. **A dialog's destructive button.**
    - *Recommended:* never the default: Return does not press it, and it looks `SECONDARY` with
      `danger` ink.
    - *The alternative:* the destructive button as the dialog's primary, filled in `danger`: one
      Return away from deleting.

## 9. Not in this record

- **Master-detail.** Refused by the owner on 2026-10-18.
- **A badge inside a tab's caption or a tree row's suffix slot:** a `Tree` row is the
  application's widget and takes a badge; a tab's caption is text and an icon, and a slot there is
  its own decision.
- **An avatar's presence mark** (online, away): a dot badge beside it.
- **A popover opened at a point**, a popover with an arrow, a popover menu: `PopupMenu` is the menu.
- **A loading state** (question 11), and **an editable dashboard**.
- **Refused earlier and not proposed here:** toasts and notifications, a link widget, rich text, a
  switch class, a grid with tracks and spans, persistence.

## 10. Stages, and what each must prove

Each component is a stage of its own, in this order, each with its model, keyboard, pointer, right
to left, accessible tree, a row in the damage and notification contracts, a gallery entry and its
golden line, as ADR 067's were:

1. **Button styles:** each style and tone drawn in each state, pixel-checked; the same box at every
   style; `setSecondary`'s calls moved; a destructive dialog button never default.
2. **Badge:** tones, icon, dot, count and "99+" in a right-to-left language's digits; `STATIC`
   nodes named as section 2.3 says.
3. **Avatar and group:** initials across scripts (a combining accent, a joined emoji, a script
   without case), image clipping, the stable colour, the overlap and "+k" in both directions; the
   group's one node and its description with the hidden names.
4. **Empty content:** shown at zero rows and gone at one, in all three widgets; the table's header
   and footer kept; the Tab order; the container's description; the rows and grid contracts' new
   case; a filter that empties and refills the table repaints the viewport, not the window.
5. **Popover:** placement both ways and at the edges; the four closes and their origins; focus in,
   Tab cycling, focus back; in a window and in the scene; the attached button under
   `PopupOwnerContract`; the composition routed in a window.

## 11. What must be measured or heard

- **Readers, narrowly.** Badges, avatars, the group and the button styles publish only shapes ADR
  045 covers, and owe no run. Two things are not covered:
  - **the empty content:** a node that is not a row under a list, a table and an outline. One
    script per reader (NVDA, VoiceOver, Orca): reach the empty orders table, hear its description,
    then Tab to "Clear filter" and hear it with the table as its context; and reach the button by
    the reader's own navigation, not only by Tab;
  - **the popover in a window of its own:** a Tab chain of arbitrary controls inside a grafted
    window, a text field typed into there, and the focus coming back to the anchor on Escape. One
    script per reader over the Showcase's filter popover.
- **The input method in a popover window** (question 13): composition, the candidate window's
  place, and the committed text, with a Japanese input method on Windows, macOS, GNOME and KDE.
- **Contrast:** `onPrimary` on `danger` and `primary` as text on `surface`, in every built-in
  palette, by the theme's tests, before the destructive and tertiary colours are fixed.
