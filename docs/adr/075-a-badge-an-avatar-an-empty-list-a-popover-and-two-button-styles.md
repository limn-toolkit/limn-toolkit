# ADR 075: A badge, an avatar, an empty list, a popover, and two button styles

- **Status:** Accepted and implemented, 2026-10-18. The owner chose on 2026-10-18 that five
  small things enter before 1.0: a badge, an avatar with a group that ends in "+k", what a table,
  a list and a tree show when they hold no rows, a public popover on the toolkit's anchored popup,
  and a quieter and a destructive button. He answered section 8 the same day: every question took
  its recommendation except question 11, where the loading state comes in now, and he decided the
  tertiary button's ink after a print of the palettes it failed in (section 12.1). Built
  2026-10-18 and 2026-10-19 (section 12), with dated notes where the build departs from the
  text; the input method in a popover's window measured on the macOS guest (section 12.6). Owed:
  the reader runs of section 11 and the input method on Windows, GNOME and KDE (section 12.8).
- **Date:** 2026-10-18
- **Scope:** five additions of the same size, each written as ADR 067 wrote its four: what it is,
  its API, layout and paint, tokens, right to left, what a frame repaints, what each platform's
  reader is given, focus and keys, and strings.
- **New public API:** `Badge`, `Avatar`, `AvatarGroup` and `Popover` in `limn.components`;
  `setEmptyContent`, `setEmptyMessage`, `setLoading` and `setLoadingContent` on `ListView`,
  `Table` and `Tree`; `Button.Style`, `Button.setStyle` and `Button.setDestructive`;
  `Dialog.addDestructiveButton`; `Theme.primaryText`. `Badge` takes ADR 074's `Tone`.
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
    public static Badge ofCount(int n);       // the number in the language's digits; "99+" past 99
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
    public Popover detach();                              // lets go (2026-10-19, section 12.5)
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
  One popover per button and one button per popover (amended 2026-10-19, section 12.5).
  No hover or focus trigger (question 15): a surface that opens when the pointer passes is a
  tooltip, which every widget has.
- **Closed** by a press outside it or Escape (the user's), by the application losing the keyboard
  or the owner's fullscreen changing (an adjustment), by `close()` (code), which `onClose` hears
  with the origin. Also by its anchor leaving the window or no longer being shown, an adjustment
  (amended 2026-10-19, section 12.5). Not modal (question 16): a press outside closes it and
  reaches nothing else, as a combo box's list does.
- **Focus** (question 12): on open the keyboard goes to the content's first focusable widget, or to
  the panel when it has none (in the scene, to its layer: amended 2026-10-19, section 12.5); Tab
  and Shift+Tab cycle inside, as in a dialog; on close the focus goes back to the anchor if it was
  inside. In the scene the layer holds the focus; in a window of
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

Answered by the owner on 2026-10-18. Each question keeps its recommendation and cost as put; the
answer follows it. Every question took its recommendation but question 11, where the loading state
comes in now; question 13 added that the composition is routed and measured first.

1. **The badge's look.**
   - *Recommended:* one look: the tone's tint, a dot or icon in the tone, the word in `text`.
   - *Why:* the contrast is the palette's, already held, at every tone.
   - *The alternative:* also a solid badge, filled with the tone and its text in an ink on it,
     which needs an ink token per tone (`onDanger`, `onWarning`…) and a contrast check for each
     in every palette.
   - **Answer:** the recommendation: one look, the tint, a dot or icon in the tone, the word in `text`. No solid badge.
2. **A count past 99.**
   - *Recommended:* "99+", one new string (`limn.badge.atLeast`, "{0}+") in 21 languages.
   - *The alternative:* the whole number, a badge as wide as its digits.
   - **Answer:** the recommendation: "99+", `limn.badge.atLeast` in 21 languages.
3. **The badge's corners.**
   - *Recommended:* a pill (half the height), as the Showcase draws.
   - *The alternative:* `radiusSmall`, a tag.
   - **Answer:** the recommendation: a pill.
4. **The avatar's shape.**
   - *Recommended:* a circle.
   - *The alternative:* a rounded square at `radiusMedium`, which some desktops use for
     organisations rather than people; a setter could add it later.
   - **Answer:** the recommendation: a circle.
5. **The avatar's colour.**
   - *Recommended:* the chart palette by the name's hash.
   - *Why:* eight or more categorical colours that carry no meaning.
   - *The alternative:* the Showcase's five semantic tones, which put people in "error" red.
   - **Answer:** the recommendation: the chart palette by the name's hash (mixed; section 12.3).
6. **An avatar to a reader, by default.**
   - *Recommended:* an image named by the person; ignored by one call where the name is beside it.
   - *The alternative:* ignored by default, named by one call: quieter in rows, silent where it
     stands alone until the application remembers.
   - **Answer:** the recommendation: an image named by the person, ignored by one call beside the name.
7. **The initials.**
   - *Recommended:* first and last word's first graphemes, overridable with `setInitials`.
   - *The alternative:* the application always gives them; no derivation in the toolkit.
   - **Answer:** the recommendation: first and last word's first graphemes, overridable.
8. **The "+k" of a group.**
   - *Recommended:* drawn, with a tooltip of the hidden names; every name in the group's
     description.
   - *The alternative:* a button that opens a popover listing everyone: a focus stop, a popover per
     group, and a reader round for the button inside a group of images.
   - **Answer:** the recommendation: drawn, with a tooltip of the hidden names, every name in the group's description; no click.
9. **When the empty content shows.**
   - *Recommended:* by itself, whenever there are no rows.
   - *The alternative:* `setShowingEmptyContent(boolean)` from the application, as the Showcase's
     `setEmpty` does: one more call in every filter, and a table that can say "no match" over rows.
   - **Answer:** the recommendation: by itself, whenever there are no rows.
10. **Where a reader finds the empty content.**
    - *Recommended:* under the container, after its rows, with the container described by the
      message.
    - *Cost:* a node in a list, a table or an outline that is not a row, which no reader has been
      heard on (section 11).
    - *The alternative:* published beside the container, after it, which the walk cannot do
      today (a widget's child is published under its node): a walk feature for one case.
    - **Answer:** the recommendation: under the container, after its rows, described by the message.
11. **A loading state too?**
    - *Recommended:* not in this record. `Tree` has its row-level loading line; a whole widget
      that is loading is an application's progress bar or busy content today.
    - *Cost of adding it now:* `setLoadingContent` beside `setEmptyContent`, a `BUSY` state on the
      container, which NVDA was measured not to speak (ADR 044), so an announcement besides: a
      day's work with a reader check.
    - **Answer:** **the fuller option, against the recommendation**: a loading content beside the empty one, the busy state and an announcement at both ends, with the reader check it needs (section 12.4).
12. **Does the popover take the keyboard?**
    - *Recommended:* yes: the first focusable widget inside, Tab cycling inside, focus back to the
      anchor on close.
    - *Why:* a filter panel or a column chooser is used from the keyboard.
    - *The alternative:* the anchor keeps the keyboard and the popover is reached by the pointer
      alone, as a list's popup: no Tab chain to build, and a panel of controls nobody without a
      pointer can use.
    - **Answer:** the recommendation: the popover takes the keyboard.
13. **A popover in a window of its own, with a text field inside.**
    - *Recommended:* a window where the platform allows, as its siblings, the input method's
      composition routed with the keys and characters; measured on the four systems with a
      Japanese input method before it ships.
    - *Cost:* the route gains the composition and the candidate window's rectangle, a path no popup
      has today.
    - *The alternative:* a popover always in the scene, where the input method already works: it
      cannot pass the window's edge, and a screenshot of the window holds it.
    - **Answer:** the recommendation, with the composition routed **first**: a window where the platform allows, the input method's composition routed with the keys and characters, measured with a Japanese input method on this Mac's lab guest before the rest (section 12.6), the other systems owed (section 12.8).
14. **The popover's layer to a reader.**
    - *Recommended:* a named `GROUP`, as the calendar's layer.
    - *The alternative:* a `DIALOG`: Windows readers then treat it as a dialog (`IsDialog`), which
      a transient panel is not.
    - **Answer:** the recommendation: a named `GROUP`.
15. **Hover and focus triggers.**
    - *Recommended:* none; `showFor` and `attachTo` only.
    - *The alternative:* a trigger setter as some libraries offer, which on a desktop duplicates the
      tooltip and opens surfaces nobody asked for.
    - **Answer:** the recommendation: none; `showFor` and `attachTo` only.
    - *Note, 2026-10-19:* `detach()` joins them (section 12.5), to let go of the button
      `attachTo` gave; it opens nothing, so the answer stands: no trigger.
16. **A modal popover?**
    - *Recommended:* no: a modal question is a `Dialog`.
    - **Answer:** the recommendation: not modal.
17. **`Button.setSecondary`.**
    - *Recommended:* replaced by `setStyle`, its 81 calls in the repository moved.
    - *Why:* three looks are one choice; two booleans for it would allow a "secondary tertiary".
    - *The alternative:* keep `setSecondary` and add `setTertiary`, with a rule for both set.
    - **Answer:** the recommendation: `setSecondary` replaced by `setStyle(PRIMARY | SECONDARY | TERTIARY)`, every call in the repository moved.
18. **Ink on a destructive primary button.**
    - *Recommended:* `onPrimary`, measured against `danger` in every built-in palette (section 11);
      a palette that fails gains an `onDanger` colour, a key of the theme file with a default.
    - *The alternative:* `onDanger` now, a theme key and an editor field for every palette.
    - **Answer:** the recommendation: `onPrimary`, measured against `danger` in every palette; no palette failed, so no `onDanger` (section 12.1).
19. **A dialog's destructive button.**
    - *Recommended:* never the default: Return does not press it, and it looks `SECONDARY` with
      `danger` ink.
    - *The alternative:* the destructive button as the dialog's primary, filled in `danger`: one
      Return away from deleting.
    - **Answer:** the recommendation: never the default, `SECONDARY` with `danger` ink.

## 9. Not in this record

- **Master-detail.** Refused by the owner on 2026-10-18.
- **A badge inside a tab's caption or a tree row's suffix slot:** a `Tree` row is the
  application's widget and takes a badge; a tab's caption is text and an icon, and a slot there is
  its own decision.
- **An avatar's presence mark** (online, away): a dot badge beside it.
- **A popover opened at a point**, a popover with an arrow, a popover menu: `PopupMenu` is the menu.
- **An editable dashboard.** (A loading state was here until the owner's answer to question 11
  brought it in; section 12.4.)
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

## 12. Built, 2026-10-18 and 2026-10-19

Built on `claude/adr-075`, from `9ec7847c`. What follows is what the build found
that the text above does not say, dated where it departs from it.

### 12.1 Button styles, and the accent as text

- **The contrast.** `onPrimary` on `danger` clears 4.5:1 at rest, hovered and pressed (`danger`
  moved toward `text` by 8 % and 16 %) in all fifteen built-in palettes, the lowest 5.62:1, so no
  `onDanger` was added; `ThemeContrastTest` holds it. `danger` as text clears 4.5:1 on `surface`
  everywhere (lowest 5.17:1).
- **`primary` as text does not** (2026-10-18). Solved for a fill at 3:1, it falls under 4.5:1 on
  `surface` in four palettes (Arch Dark 3.67, Solaris Dark 3.90, Darkling 4.06, Limn 4.40) and on
  `surfaceRaised` in eleven, down to 3.01 (Arch Dark). After a print of the four worst against one
  that passes, **the owner chose a derived accent for text**: `Theme.primaryText()`, primary moved
  toward the text colour in steps of a hundredth only until it clears 4.5:1 on `background`,
  `surface` and `surfaceRaised`; no key of the theme file, cached per palette, primary itself
  where it already passes (Light, Limn Light, Monoko Pro, High Contrast). Arch Dark goes from
  `#5898E3` to `#93BBEB` (4.54:1 on its raised surface), Solaris Dark `#3D98D7` to `#7DB7DE`
  (4.51), Darkling `#8AA1CB` to `#B4C3DE` (4.50), Limn `#AF7AFF` to `#C39BFE` (4.53). A tertiary
  button's caption is drawn in it, and so is `Tone.ACCENT`, which ADR 074's labels and this
  record's badges use (a dated note in ADR 074). Tests hold every palette at 4.5:1 on the three
  surfaces, each moved one a single step past the last that failed, and the hue within 12 degrees.
- **The calls.** 85 `setSecondary(true)` were moved, not 81: the demo, the site's examples, the
  theme editor, four toolkit tests, `Dialog` and the forms guide. ADR 002 keeps its historical
  spelling.
- `Dialog.addDestructiveButton` adds a `SECONDARY` destructive button through the same path as
  `addButton`, so only a primary can become the default, whatever the order the buttons were added
  in; a dialog whose only answer is destructive ignores Return.
- **Return did delete when the destructive button held the focus** (2026-10-19, the audit of that
  day): the default slot was right, but the dialog gave its first focus to its first button, and a
  focused `Button` presses itself on Return before the card hears the key. "Delete" added ahead
  of "Keep", or alone, took the focus and Return resolved "delete"; the tests had a text field in
  the body, which took the focus instead. Now the first focus is the body's first stop when it
  has one, else the default button, else the first button that destroys nothing, and only in a
  dialog whose every button is destructive the first of those (the keyboard and a reader stay
  on the answer, and the guard below keeps Return off it). A destructive dialog button lets
  Return through, so the card answers it as a key nothing wanted: the default answer, or none.
  Space, a click and a reader's press still press it; a destructive `Button` outside a dialog
  keeps Return, having no default to defer to. Both display modes, without the first stop being
  focused on the way in the scene (`SceneAccess.pushOverlayFocusing`).
  `DialogDestructiveReturnTest` holds every order, the destructive button alone, with and
  without a field, in both modes; one test that walked from Cancel to OK now starts on OK.

### 12.2 Badge

- **A count has no dot** (2026-10-18): the number alone, centred, at least as wide as tall, so
  one digit is a circle. A text badge's mark is 9 points from the pill's end at `MEDIUM`
  (a small spacing and a half), 4 points from the word (two thirds of one); an icon's square is
  the label font's size, integral. A dot badge's box is the dot, twice a small spacing.
- **A badge in a table's or a tree's row is read through the row's name** (2026-10-19): the rows'
  name derivation read `Label`s only, and the Showcase's orders lost the status from every row's
  name the moment the hand-drawn pill, which was named by hand, became a `Badge`. `Table` and
  `Tree` now read a badge's text as they read a label's; `BadgeTest` holds it.
- A text change of the same width repaints the badge alone; a wider one lays it out in place.
  `ComboBox`'s private count is renamed `MoreBadge`.
- **The count's factory is `Badge.ofCount(int)`** (2026-10-19, the owner's decision after the
  audit of that day): section 2.1 named it `count(int)`, and the build added the getter
  `count()` beside `setCount(int)`. With a static `count(int)` and an instance `count()` in one
  class, `inbox.count(7)`, what a reader of `setCount` and `count()` writes for "show 7",
  compiles, calls the factory through the instance, builds a badge nobody keeps and leaves the
  shown one unchanged, with no warning outside `-Xlint:static`. The factory took the new name;
  `setCount`, `count()` and `dot` keep theirs, and section 2.1 shows the new one.

### 12.3 Avatar and group

- **Graphemes by `\X`, not `BreakIterator`** (2026-10-18): the text model's own pattern, which on
  Java 17 keeps a joined emoji whole where the character instance splits it. Upper-casing goes
  through `LocaleServices`, so Turkish gives a dotted capital I.
- **The colour index is the hash mixed** (2026-10-19): `String.hashCode` modulo eight is little
  more than an alternating sum of the letters (31 is minus one modulo eight), and five of the seven
  approvers the Showcase shows fell on one green in the first print. `Avatar.colorIndexOf` mixes
  the hash and scales from its top bits; the same name still has the same colour everywhere.
- The name is published with the provenance `EXPLICIT`, text about the picture, which macOS puts
  in `AXDescription` as section 3.3 says. Initials grow with a `SizedBox`. A group's hidden members
  are not its children (they have no box); the "+k" circle is a private child with the tooltip,
  and the group's child hook ignores every circle.
- **A circle a point or so across** (2026-10-19, the audit's CO-5): the initials grow with the
  circle and are rounded to a whole point, so under about 1.2 points they rounded to no size, the
  font refused it, and the paint threw on every frame, losing what the frame painted after it. The
  tinted circle is drawn alone there, and nothing at all in a box of no size. A group lays its
  circles out at the control height whatever box it is given, so its own circles never get there.
- **Initials clear the overlap** (2026-10-20, the owner's decisions 23 and 26, after the audit's
  SD-4): each circle after the first is drawn over the one before a quarter of a diameter in, with
  its ring, and two-letter initials reached past that quarter, so "AL" lost the foot of its L left
  to right and the A's left side right to left. A circle the next one covers now centres its
  initials on the part it shows: three quarters of the diameter less the ring, from the circle's
  edge at the letters' height on the near side, the side away from the overlap in the group's
  direction (decision 23). Centring alone does not clear every pair: in the shipped face (Roboto
  Bold, `MEDIUM`) that part is 19.51 points wide and "MW" is 22.76, "HM" 20.57, "DW" 19.82. The first
  fix drew each such pair smaller on its own, the fixer's addition and not the owner's choice, which
  gave one group three sizes (11, 12 and 13 points). The owner chose one size per group (decision
  26): every circle, the last one too, draws its initials at the label size or at the size the
  group's widest pair needs to fit that part, whichever is smaller; the audit's group of nine
  (AL, MW, HM, OD, DW, RC, MW, LV, AL) measured with the real shaper draws all nine at 11 points. A
  picture draws no letters and sets no size; the "+k" circle keeps its own label. `AvatarTest`
  holds, with the test ruler, each covered circle's run inside that part both ways, one size in the
  group, and the size following a member joining, changing initials or taking a picture.
  (2026-10-20, the owner's decision 31, after the review's R5C-6: the "+k" circle draws its count at
  the group's one size too, in the label's own weight, where it kept the label size and the
  default group of four showed two sizes side by side, AL, MW and HM at 11 points beside "+6" at
  13; a group too small for letters draws its count no more than its initials, the tooltip still
  naming the rest. `AvatarTest.theCountPastTheLimitIsDrawnAtTheGroupsOneSize`.)
  (2026-10-20, the review's R5C-7: the size was kept while the process's language held, but a
  window's or a subtree's language, `Scene.setLocale` or `Widget.setLocale`, moves only the
  widgets' own, so a member named by a role whose initials change with the window's language kept
  the old size, and the group's description the old names. The size is now kept while the letters
  each circle draws hold, which are the members' own in their language, and the description while
  the group's language holds. `AvatarTest` switches a window to Portuguese and back.)

### 12.4 Empty and loading content

- **Written once in `RowsPlaceholder`, beside the rows engine and not in it** (2026-10-18): the
  engine realizes rows and knows nothing of the viewport's geometry, which each widget computes;
  the placeholder takes the viewport from the widget's layout, attaches the content as its last
  child while it shows, describes the widget and says the load.
- **The three paint their content** (2026-10-19): each paints the rows it mounted and no other
  child, so the first prints showed a content laid out and published and never drawn;
  `RowsPlaceholder.paint` is called inside each widget's row clip, and a test reads the frame.
- **Loading, question 11's fuller option:** `setLoading(boolean)`, `isLoading`,
  `setLoadingContent(content[, message])` and `isShowingLoadingContent`. While loading with no
  rows the loading content shows in place of the empty one (a moving bar over "Loading…" by
  default); over rows they stay. The widget is published `BUSY` throughout, and a reader is told
  "Loading Orders" at the start and "Orders, 12 items" (or the empty message) at the end, the name
  being the application's; the strings are the tree's own, copied into a `rows` bundle family in
  21 languages. `SceneAccess.applicationName` gives the announcement its name.
- **The repaint** (2026-10-18): `refresh()` and a new set of rows asked for a full layout; the
  three measures read no rows, so they ask for a contained one, and a widget that clips its
  children now lays out alone when a child joins or leaves (`Widget.childrenChanged`), as
  `moveChild` already did, the scene falling back to a full pass when the size moved. The full
  toolkit suite stayed green with the change.
- The rows contract gains the case of section 4.3, run by the list, the table and the tree.
- **One parent, one widget** (2026-10-19, the audit's CO-11): a content that already had a
  parent was accepted, and the next frame threw inside the widget's layout; since the placeholder
  had believed the content attached before adding it, it was then laid out and drawn by both
  parents every frame. The setters now refuse, with an `IllegalArgumentException`, a content in
  another container (another widget showing it included) and one that holds the widget; the
  placeholder believes a content attached only once it is; and a content the application puts
  elsewhere after setting it, or that another widget shows first, is not shown, with one warning,
  instead of losing frames. A registry of holders refused a content another widget held but did not
  show; the review of the same day dropped it, because the holder was held weakly and a page rebuilt
  with a fresh list reusing one label was refused or not depending on whether the garbage collector
  had run.
  *Second review, 2026-10-20 (R4C-1):* with the registry gone, a widget that wanted a content
  another one held looked again only at its own next layout, so when the holder let it go later in
  the same frame it stayed blank, and which of two lists stayed blank hung on the order the
  application emptied them in; it also said it showed the content (`isShowingEmptyContent()` and
  its description read what it wanted, not what it held). And a list dropped while it showed its
  content kept it as a child for good, so the rebuilt page's fresh list was refused every time.
  Now a widget waiting for a content watches the holder's children and asks for its layout as the
  holder lets it go (shown at the next frame, whichever order); the getters and the description
  read what the widget holds; and a widget leaving the window lets its content go, showing it again
  when it comes back if it is still free.
  *Third review, 2026-10-20 (R5C-1):* the watch stayed on the first holder it was set on: a list
  that waited for its empty content and then started loading, wanting a loading content a third
  list held, kept watching the empty content's holder, and stayed blank when the loading content
  was let go. The watch now remembers the content and the holder it is for, and a layout that
  wants another, or finds it held elsewhere, watches again. `EmptyContentTest` has the three lists.
- **Said only where it is seen, by the name it is heard by** (2026-10-19, the audit's CO-12 and
  AX-4): a list in a hidden container or on a tab page not chosen, still in the window, said
  "Loading Archived" and "Archived, 0 items", loads the user could not see; it is now busy and says
  nothing. The test is the widget and every ancestor shown, not `isShowing()`, whose clip walk
  counts a list built and set loading in one turn as clipped away inside a scroll view until its
  first layout. And `SceneAccess.applicationName` now names as the walk does, the explicit name,
  then a caption bound with `setLabelFor`, then the tooltip, so a list named by the caption the
  accessibility guide keeps as the idiom says "Loading Contacts" and not "Loading…". A popover's
  name from its button follows the same order. (2026-10-20, at the integration with ADR 072's
  decision 16, review R3C-7: a face a transform does not draw, turned away, scaled to 0 or
  edge-on, is not shown either, so the test is now `SceneAccess.isDrawn`, the one a popup asks
  before it hangs from a widget: the visibility chain and each transform's "draws nothing", still
  without the clips. An opacity group at 0 still speaks: a page fading in starts there, often in
  the turn its load starts, and the accessible tree publishes it showing.
  `EmptyContentTest.aListOnAFaceATransformDoesNotDrawSaysNothingOfItsLoad` failed before.)

### 12.5 Popover

- **The route** (2026-10-18): `SceneAccess.KeyRoute` gains `popupHoldsFocus()` and `preedit(…)`;
  the window's composition is queued in order with the keys while such a route stands, and
  `AnchoredPopup` (`Host.holdsFocus`) feeds the three into the popup scene's own input, whose
  focus, Tab order and Escape answer them. The owner's input method composes for the popup's
  focused field and places the candidate window at its caret, moved by where the two windows
  stand; **the walk then publishes the popup's focused widget, not the owner's, as the one
  `FOCUSED` node**.
- **Built before it is sized** (2026-10-18): the popup scene and its panel are made before the
  window, with the owner's ruler, so a host sized by its panel's measure is measured; the panel is
  measured unbounded first (2026-10-19, after the guest drew one as wide as the window: a form's
  stretching column takes all the width it is let).
- An attached button is a `POPUP_OWNER` and runs `PopupOwnerContract`; its press opens or closes
  the popover before the application's own handler hears it.
- **A popup opened from inside the popover's window reached no reader** (2026-10-19, the audit of
  that day): a combo box's list, a date picker's calendar and a text field's menu are grafted into
  the popover's scene, whose window publishes nothing, and the owner's walk read only its own
  scene's grafts. The walk now follows a graft inside a graft; ADR 039 section 1.11 has the
  amendment, `PopoverNestedPopupAccessibilityTest` the cases.
- **A popover outlived its anchor** (2026-10-19, the audit of that day): the combo box, the
  tab strip and the date picker close their popup in `onDetached`, and a popover, no widget, heard
  nothing. A popover whose Apply dismissed the in-scene dialog it was opened from stayed over the
  window hanging from nothing, holding the keyboard, and the next click only closed it; an anchor
  taken out left it open, and an outer popover closed by code left the inner one saying it was
  open. `AnchoredPopup` now closes itself, so no host can forget: on open it asks its owner scene
  to be told when the anchor leaves (`SceneAccess.observeAnchorLeaving`;
  `Widget.setSceneRecursively` tells the scene a subtree is leaving before any `onDetached`, which
  covers a container, an overlay and the window closing), calls `Host.dismiss(ADJUSTMENT)`, and
  closes after it if the host did not and its handlers did not show it again (see the review
  note below). Closing, it first takes down every popup hung from a widget in its own panel
  (`SceneAccess.closePopupsHungIn`), at once and before it lets go of anything, so the inner
  popover gives the keyboard back to its button and the outer one takes it home. A popup closed
  because its anchor left puts the keyboard on nothing that is leaving; a window that has closed
  gives it to nobody. `PopoverAnchorLeavesTest` has the cases.
- **One popover per button, and `detach()`** (2026-10-19, the owner's decision on the audit of
  that day). A button had one slot that `attachTo` overwrote while the popover kept its own
  reference: after `p1.attachTo(b); p2.attachTo(b); p1.attachTo(c)` the first popover, still
  believing it was on `b`, emptied `b`'s slot, and `b` opened nothing and told a reader it had no
  popup. Now `attachTo(button)` detaches the popover `button` had, which then has no button, and
  detaches this popover from its own, so a popover is on one button at most; attaching it to the
  button it is on changes nothing. `detach()`, fluent as the setters, lets go of the button, which
  is a plain button again; a panel open from that button closes as the code's, one shown for
  another widget stays, and a detached popover still opens from `showFor`. The button holds the
  popover and the popover the button, nothing else, and both sides are cleared together.
  `PopoverAttachTest` has every transition.
- **A popover with nothing focusable, to a reader** (2026-10-19, the audit of that day). In the
  scene the panel took the keyboard, and since the layer is the popover's node there and the panel
  says nothing, the walk published the focus on an unnamed node of role `UNKNOWN` under the named
  group, broke two of `AccessibleInvariants`' rules and logged a warning about `Popover$Panel`,
  a class no application can reach; any in-scene popover logged the "paints its own content"
  warning about it too. In a window of its own the panel, the named group, is the root of a popup
  the owner's keys drive, which the walk publishes not focusable, so it was focused and not
  focusable. Now, in the scene the panel never takes the keyboard and the layer keeps it from the
  push: the focused node is the group named as the popover, with its `CANCEL`, and Escape and the
  way back are the layer's. Naming the panel as well was the other way, and would have published
  the group twice, one inside the other. The panel declares its painting decoration, so the walk
  warns about nothing. In the walk, the root of a keyboard-driven popup is published `FOCUSABLE`
  when it is the node published `FOCUSED`, so the two presentations agree.
  `PopoverNoControlsAccessibilityTest` reads both trees and holds them to `AccessibleInvariants`.
- **The review of these fixes** (2026-10-19, same day). The application's handlers run inside a
  popup's close, and the fixes above had not allowed for it:
  - **A popup shown again by a handler during its own close** (grave): an inner popover's
    `onClose` that showed the outer one again ran half way through the outer one's close, whose
    rest took the fresh presentation down and left the old layer over the window for good, every
    press swallowed. `AnchoredPopup.close` now takes what the presentation holds off its fields
    before any handler runs and tears down only that. After the anchor left it closes only while
    the presentation is the one it was (a count), so a popover that its handler moved on to the
    next anchor stays shown; and it never opens from an anchor not under its scene's root or an
    overlay (`canHangFrom`, `SceneAccess.isInTree`), which a widget being taken out still answers.
  - **The keyboard on a closing panel:** the inner popover's layer fades on top, and the outer
    one could not give the keyboard back beneath it, so for its fade the keyboard stayed on a
    button of the panel going away and Space opened the inner one again. An overlay now gives it
    back when every layer above has already given it back.
  - **One popover per button under handlers:** `attachTo` and `detach` settle both buttons and
    both popovers before any `onClose` runs; whatever a handler does is then the end state. A
    handler that shows the popover for the button it was taken off shows it as `showFor` does,
    from a plain button.
  - **A hidden anchor** (every host, not this record's alone): a popup now closes when its anchor
    is no longer shown, hidden itself or with a container, a tab strip's page or an expander, as
    when it leaves. The tab strip keeps its list button visible while the list is open: the list
    still goes on the next turn once the tabs fit, by the close its layout posts, and the button
    kept visible keeps that close out of the layout pass, where hiding it would now close the
    list. A child a transform stops drawing is the same case for the branch that makes it so
    (`Scene.observeAnchorLeaving` says where it calls in). (2026-10-20, integrated: the scene does
    it, `Scene.childrenDrawnChanged`, from a set at once and after a motion's frame or a layout
    pass; see the second and third reviews below.)
  - **A closing layer took presses and the focus** (every in-scene popup, menus included): a
    press beside the fading card now reaches what is beneath, and the closing layer takes no
    focus (amended by the second review below: a press on the card stays the popup's).
  - **A handler's throw** during a removal aborted it half way; every close runs, the removal,
    hide or overlay removal completes, and the first throw is rethrown after.
  `PopoverReentrancyTest`, `AnchoredPopupHiddenAnchorTest`, `AnchoredPopupClosingLayerTest` and new
  cases in `PopoverAnchorLeavesTest` and `PopoverAttachTest` hold each.
- **The second review** (2026-10-20):
  - **A double click on an item of a fading popup:** passing every press through a closing layer
    sent the second click of a double click on a list's item, a date or a menu item to the control
    under the item, still drawn: a button pressed, a word selected. A press on the fading card is
    the popup's again, swallowed without moving the keyboard; only one beside it passes. A menu,
    which draws its columns itself, says where through `SceneAccess.overlayCard`. In a window of
    its own nothing changed: the second click goes to that window.
  - **A handler that throws inside a close:** a popup's close and a popover's run every step, the
    inner popups' closes, the teardown, the button's notice and the popover's own `onClose`, and
    rethrow the first throw after (`HandlerFailures`); a join (a scene's root adopted elsewhere)
    completes as a removal does; a `VirtualMachineError` leaves at once.
  - **A refused open:** the combo box, the date picker and the tab strip ask
    `AnchoredPopup.canOpen` before they say they are open; a date picker asked to open while
    hidden no longer drives an invisible calendar from its field.
  - **An editable combo box's list** is marked closing as it fades, and an anchored popup's layer
    is focusable only when it takes the keyboard, so a press beside an open editable list leaves
    the keyboard in its field.
  - **Drawn, not only visible:** a popup does not hang from a widget a transform draws as nothing
    (`SceneAccess.isDrawn`, `isShowing()` without its clipping, so a widget scrolled out of view or
    not laid out yet still can). Closing a popup when its anchor stops being drawn is the
    transforms branch's half, at the integration. (2026-10-20, integrated: a set or a motion's
    frame that makes a transform draw its child as nothing closes the popups hung in it, as an
    adjustment, before it takes the focus out; ADR 072 section 2.8.)
  - **Window shortcuts** are offered whenever no overlay holds the keyboard: during a closing
    popup's fade, and while an editable combo box's list is open.
  - A popover moved from the button it was open from tells that button alone it collapsed.
  `ClosingPopupDoubleClickTest` and new cases in `AnchoredPopupClosingLayerTest`,
  `AnchoredPopupHiddenAnchorTest`, `PopoverReentrancyTest` and `PopoverAttachTest` hold each.
- **The third review** (2026-10-20, the integrated branch):
  - **A handler that puts the leaving widget back:** the closes run after the caller has cut or
    set the parent link and before the walk, and the walk went on with the scene the caller passed
    in. A popover's `onClose` that put the card being removed back into a column (its own or
    another) left it in the window's tree with no scene: it took clicks, but no popover opened from
    its buttons again and what its `onAttached` registered never was. Adopting another window's
    root whose popover's handler took it out again left it parentless and attached. Once the walk
    is over, a subtree whose popups' handlers ran joins or leaves again to the scene its place in
    the tree says (its parent's, or the scene it is the root or an overlay of); closing first and
    moving after was the other way, and would have given the keyboard back to a button on its way
    out, a focus and a loss announced in the one call. `PopoverReentrancyTest` has both cases.
  - **A focus handler that throws during a removal:** the completion policy covered a popup's
    handler but not an `onFocusLost`, which taking the focus out of the leaving subtree runs.
    `Scene.removeOverlay` ran that step first and outside its try, so the throw left the overlay
    out of the stack and never walked: still in the scene, never told it left, a fade inside it
    ticking where nothing drew it, and the focus given back to nobody; `Widget.remove` shared one
    try between it and the parent's layout and `CHILDREN` notice. Both now walk first, take the
    focus out after, as `remove` already did, and give each step a try of its own.
    `OpacityTest` has both cases.
  - **An overlay whose attach throws:** the push holds what its walk throws and goes on, and its
    focus step then focused the dialog's first stop, which the walk had never reached: the keyboard
    on a widget in no scene. The keyboard now lands only on a widget in the scene (the funnel's
    test, Tab's stops, and the focus moved content brings along), so such a push focuses what the
    walk reached or nothing. `SceneOverlayTest` has the three pushes.
  - **A `VirtualMachineError` leaves at once** was true of the closes and the held throws, not of
    what followed: a removal, a join, an overlay's removal and the pushes ran the fades' arrivals
    in a `finally` after it, the list of arrivals logged one an `onArrive` threw and went on, and
    the revocation queued after a motion's frame contained it. Now none of them runs application
    code after one: the arrivals run after the steps, not in a `finally` (every step already holds
    an ordinary throw), the list lets one leave and stops, and the queued revocation lets it reach
    the frame's own containment. The helper that holds the throws is one, shared by the scene and
    the components (`limn.internal.lang.HandlerFailures`). A window closing still contains every
    throw of its teardown, this one included, so that what outlives the window is given back.
    `OpacityTest` has the removal's two cases.

### 12.6 The input method in a popover's window, measured

On the macOS guest, 2026-10-19, with the system's Japanese romaji input method,
the lab's `--scene popover` opening its popover by itself, and "nihon", Space, Space, Return
posted by the guest's presser; the guest's input source read before and restored after each run.

- **First run:** the popover's field received plain `n`, `i`, `h`, `o`, `n` and the panel was as
  wide as the window. Both fixed: the popup's own window held the desktop's keyboard in that run's
  timing, and a popup delegated its input method to the owner regardless (now only while its own
  window does not hold the keyboard); and the width as section 12.5 says.
- **After the fix:** the composition is underlined in the popover's field (にほn), the candidate
  window opens right under its caret (にほん, 日本語, 日本), and the conversion to 日本語 shows.
- **The commit on Return was not seen, in the popover nor in a field of the window itself** (the
  control run, the same keys into the window's own search field): 日本語 stays underlined, with the
  presser's Return and with a real key event alike. The guest's 2026-09-29 probe log shows the same
  shape, its commit arriving only when the probe shut down. So the commit is the presser's or the
  backend's and not the popover's; it is recorded here and left to its own look.

### 12.7 Showcase, gallery and guides

The Showcase's `StatusBadge`, `Avatar` and `EmptyState` are gone for `Badge`, `Avatar` and
`setEmptyContent`; its delete buttons are destructive and the delete dialog's safe answer is the
default; the orders screen's status filter is a popover from a Filter button; Buttons and inputs
gains the button styles and a section of statuses and people (portraits drawn by `Pictures`, no
photograph). Fifteen strings in 21 languages. The accessibility gallery gains three entries, the
site's component gallery three, the guides their sections from `StatusExample`, tested.

### 12.8 Owed

- **Readers, as section 11 says.** The empty content: one script per reader (NVDA on the Windows
  guest, VoiceOver on the macOS guest, Orca on Fedora) over the Showcase's orders with the filter
  typed to nothing: reach the table and hear "table, 0 rows, No orders match the filter", Tab to
  Clear filter and hear it, and reach the button by the reader's own navigation (NVDA browse mode
  down arrow, VoiceOver VO+Right, Orca's flat review). The loading state: `table.setLoading(true)`
  then rows and `setLoading(false)` from a lab key, hearing "Loading Orders" and "Orders, 48 items",
  and whether `BUSY` alone is spoken anywhere. The popover in a window: the orders' Filter popover
  (or `--scene popover`), Tab through its controls, type into its field, Escape, hearing the focus
  move inside the group and come back to the button.
- **The input method on Windows, GNOME and KDE**, as section 12.6 on each: `--scene popover` with
  `-Dlimn.demo.popover.open=true`, the system's Japanese input method on Windows, a Japanese engine
  under IBus on GNOME and under Fcitx5 on KDE, "nihon" Space Return, a print of the underline, the candidate
  window's place and the committed text; the control run with `-Dlimn.demo.popover.search=true`.
  On Wayland the popover is in the scene and the route is not taken.
- **The commit on Return on macOS** (section 12.6), for every field.
