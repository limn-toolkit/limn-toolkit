# ADR 073: A card is a surface of optional parts, and its click stops at its footer

- **Status:** Accepted and implemented, 2026-10-18. Proposed the same day. The owner chose on
  2026-10-18 that a card enters before 1.0,
  with every part optional, an icon accepted as media, three styles with `ELEVATED` the default,
  the footer at the trailing edge, both orientations and all three media fits in the first
  version, and an optional activation that combines with footer actions. The same day he settled
  how the two combine (section 2.6): the click is the card's everywhere outside the footer, the
  footer is a bar of buttons of its own, and a card with no footer is one click as a whole, and
  he took the recommendation of every question of section 4. **Built the same day** (section 8):
  both stages and the Showcase's, with the dated notes there where the build differs from the
  text. Section 7.1 has the two measurements; a filled card's muted text under the hover is the
  one number left to the owner, and one listen per reader is owed if he wants the wording.
  **Adjusted the same day** by the owner's answers to what the build left open (questions 13 to
  15, section 9): a fourth style, `PLAIN`; the filled card's fill fixed, so it stands off the page
  and keeps muted text readable under the hover; and the title and the subtitle given a number of
  lines, through a new `Label.setMaxLines`.
- **Date:** 2026-10-18
- **Scope:** a container with media, a header (a leading part, a title over a subtitle, a trailing
  part), content and a footer of actions; its layout in two orientations, its three looks on
  ADR 057's elevation, its media fits, its activation, its keyboard and focus, what each platform's
  reader is given, right to left, and what a partial frame repaints.
- **New public API:** `limn.components.Card` with `Card.Style`, `Card.Orientation` and
  `Card.MediaFit`; one `SizeTokens` column, `cardPad` (question 6); `Wrap.gapsSilently`, a
  protected form of its two gutters for a wrap that pushes them from its measure (section 8).
  Section 9 adds `Card.Style.PLAIN`, `Card.setTitleLines`, `Card.setSubtitleLines` with their
  getters, and `Label.setMaxLines` with `Label.maxLines()`.
- **Compatibility:** additive. The Showcase's `Page.card` helper goes (section 3). A name, a
  description or a tooltip written on a composite whose label target is a part inside it now lands
  on that part (section 8), which a date picker and an editable combo box share with the card.

---

## 0. What this rests on

Read at `e3f602f5` (`claude/review-fixes`). No probe was run; what must be measured or heard is
section 7.

- **`Surface`** (ADR 057) is a final container of one child: a fill (`surface`, or `surfaceRaised`
  from `OVERLAY` up), the theme's shadow for its `Elevation` drawn by `Theme.drawElevation`, the
  corner radius `radiusMedium` of its step unless set, `paintOutset()` equal to the shadow's
  reach, and `paintsDecoration() == true`, so it is no node of the accessible tree.
- **The Showcase builds cards by hand.** `Page.card(content)` is
  `new Surface(new Padding(Insets.all(16), content))`, called 18 times in eight scenes (charts,
  bidi, animations, sections, dialogs, transfer, sprites, tree). `BidiScene.card` stacks a strong
  name, a muted city, a spacer and a paragraph; the animations scene's invoice puts a strong
  number over a muted customer with an amount and a "Details" button at the end of the line;
  `SpritesScene.tile` is a fixed box holding a picture over a muted caption. None of them is
  named to a reader: a `Surface` publishes nothing, so a reader hears the labels in sequence with
  no group around them.
- **What a press does.** A press goes to the deepest widget the hit test finds and bubbles up
  until a handler consumes it; the click follows when the release lands in the pressed widget's
  subtree; focus goes to the nearest focusable ancestor of the press target (`Scene`, the button
  path). Keys bubble from the focused widget the same way. Hover is not bubbled: the scene sends
  enter and exit to the deepest widget alone, which ADR 067 §8 met when a button inside a tab took
  the hover from the tab.
- **Disabled widgets are not hit.** `hitTest` answers `null` for a widget that is not enabled, so
  a press on a disabled button lands on what is beneath it.
- **An inert subtree** (ADR 067 §3, the closing expander) is drawn as it is and answers nothing: no
  press, no focus, no access letter, no reader verb. The mechanism is the scene's, lent to
  components through `SceneAccess.setInert`.
- **The shapes of ADR 045.** `Shape.of` claims `LEAF_ACTION` for the role `BUTTON` or any node
  offering `PRESS` that no earlier shape claimed, and its contract (`LeafActionContract`) holds
  that the node is a pressable leaf with a name, that `PRESS` reaches the handler once, that a
  disabled leaf withdraws it, and that its id survives a press. A `GROUP` with no verb is a
  `SURFACE`. The bridges vend by facet: `PRESS` is Invoke on Windows, `AXPress` on macOS, and an
  action of AT-SPI's Action interface on Linux, whatever the role.
- **The accessible invariants** (`AccessibleInvariants`) refuse a focusable node without a name.
- **A relation to a node that is not published** resolves to that node's nearest published
  ancestor (`Accessible.Relation`).
- **`Expander`** names its content a `GROUP` by `LABELLED_BY` its header, the one container in the
  toolkit named by a caption it owns.
- **`Dialog`** lays its buttons in a row aligned to the end (`MainAlignment.END`) with the
  `gapButtonRow` gutter.
- **`Icon`** is a square, tintable or drawn as authored (`tintable()`), painted by
  `Icon.paint(canvas, x, y, size, tint, dark)`; `ImageView` takes an image or an icon and fits it
  `COVER`, `CONTAIN`, `FILL` or `NONE`. The Tabler pack is a separate artifact; the toolkit draws
  its own glyphs and takes any `Icon` it is given.

## 1. Why

Business desktop applications show records as cards: a customer, an invoice, a dashboard's
figures, a choice of plan or template. The Showcase repeats the pattern in eight scenes, each
time by hand, each time with the same padding, the same strong title over a muted subtitle, the
same trailing group, and each time silent to a reader. Web component libraries give the pattern
one component with optional slots, and that shape is the right one here too; what they do not
offer is a card that is itself clicked, which the owner wants and which section 2.6 designs.

## 2. Decision

### 2.1 The parts

| Part | Setter | What it holds |
| --- | --- | --- |
| media | `setMedia(Widget)`, `setMedia(Icon)` | a picture, a video, an avatar, an icon |
| header prefix | `setHeaderPrefix(Widget)` | before the title, spanning both text lines: an avatar, an icon |
| title | `setTitle(I18nString)`, `setTitle(String)` | one line, strong |
| subtitle | `setSubtitle(I18nString)`, `setSubtitle(String)` | one line, muted, under the title |
| header suffix | `setHeaderSuffix(Widget)` | after the title, spanning both lines: a badge, an amount |
| content | `setContent(Widget)` | one widget; a `Column` for several |
| footer | `setFooter(Widget...)` | actions, at the trailing edge |

Every part is optional and `null` (or `setFooter()` with nothing) removes it. A part that is
absent takes no room and no gap. A subtitle without a title is drawn where the title would be,
muted, and names nothing.

*Note, 2026-10-18 (section 9):* the title and the subtitle take one line by default and as many as
`setTitleLines` and `setSubtitleLines` allow.

*Note, 2026-10-19 (audit AP-7):* a widget is in one place. Every part setter and `setFooter` refuse,
with an `IllegalArgumentException` that their Javadoc names, a widget already in another container
(an action already in this footer is accepted, and put in its new place), one that holds the card,
and an action passed twice; each checks before it changes anything, so a refused call leaves the
card as it was. `setFooter` used to take the old actions out and then fail in the tree with an
`IllegalStateException`, leaving the card with no footer, and the part setters took the old part
out before refusing the new one.

### 2.2 The API

```java
public final class Card extends Widget<Card> {
    public Card();
    public Card(Widget<?> content);

    public Card setMedia(@Nullable Widget<?> media);
    public Card setMedia(@Nullable Icon icon);
    public Card setMedia(@Nullable Icon icon, Icon.Mirroring mirroring);
    public Card setHeaderPrefix(@Nullable Widget<?> prefix);
    public Card setTitle(@Nullable I18nString title);
    public Card setTitle(@Nullable String title);
    public Card setSubtitle(@Nullable I18nString subtitle);
    public Card setSubtitle(@Nullable String subtitle);
    public Card setHeaderSuffix(@Nullable Widget<?> suffix);
    public Card setContent(@Nullable Widget<?> content);
    public Card setFooter(Widget<?>... actions);          // none clears it

    public Card setStyle(Card.Style style);                // ELEVATED (default) | OUTLINED | FILLED | PLAIN (section 9)
    public Card setTitleLines(int lines);                  // 1 (default) or more (section 9)
    public Card setSubtitleLines(int lines);               // 1 (default) or more (section 9)
    public Card setOrientation(Card.Orientation o);        // VERTICAL (default) | HORIZONTAL
    public Card setMediaFit(Card.MediaFit fit);            // NATURAL (default) | STRETCH | COVER
    public Card setMediaAspectRatio(float widthOverHeight); // STRETCH and COVER, vertical; 16/9

    public Card onActivate(@Nullable Runnable handler);    // section 2.6
    public boolean isActivatable();
    // and a getter for every setter
}
```

All setters are fluent and UI thread only, as every component's are. `onActivate` follows
`Button.onAction`: one handler, `IllegalStateException` if one is already registered, `null`
clears the slot, and an activation is announced to watchers as `Change.Aspect.INVOKED`, origin
`USER`, before the handler runs. There is no `setHeader(Widget)` that replaces title and subtitle
(question 9).

### 2.3 Layout

The card is laid out by its own code, in the vocabulary of `Row` and `Column` and with no new
layout type, as ADR 049 and 050 ask of anything that places children.

- **Vertical:** media, header, content, footer, top to bottom, with the card gap between parts
  that are present. Measured height is their sum plus the padding. Given more height than it
  measures (a `SizedBox`, a stretched row), the extra goes to the content and the footer stays at
  the bottom, so cards side by side in a row keep their footers on one line, which the Showcase's
  `band()` pairs want.
- **The header** is the prefix, a text block of the title over the subtitle with no gap between
  them, and the suffix. The prefix and the suffix are centred on the text block and take their
  measured width; the text block takes the rest and ends each line with an ellipsis.
- **Horizontal:** the media takes a column at the leading side, as tall as the header and the
  content beside it; the header and the content take the trailing column. **The footer runs the
  card's whole width under both columns** (question 10). This keeps the part of the card that is
  clicked one rectangle (section 2.6); with the footer only in the trailing column it would be an
  L.
- **The footer** is a `Wrap` aligned to the end, with `Dialog`'s `gapButtonRow` between actions
  and the card gap between runs: actions sit at the trailing edge, as a dialog's buttons do, and
  a narrow card wraps them to a second line rather than clipping them.
- **Padding and gap** come from the step's tokens: `cardPad` (16 at `MEDIUM`, question 6) around
  the parts and `spacingMedium` (12 at `MEDIUM`, the Showcase's `Page.GAP`) between them.

### 2.4 Media, and an icon as media

| Fit | Vertical | Horizontal |
| --- | --- | --- |
| `NATURAL` | the media's measured size, at the leading edge, inside the padding | its measured width, at the top of the leading column |
| `STRETCH` | the inner width, its height from the aspect ratio | the column's full inner height, at most half the card's width |
| `COVER` | as `STRETCH`, but to the card's edges on three sides, with the card's own top corners | as `STRETCH`, to the card's edges on three sides, with the card's leading corners |

- **A widget** in the media slot is told its box and nothing else: an `ImageView` that should crop
  is given `Fit.COVER` by the caller, as today.
- **`COVER` is a clip, not a renderer feature.** The media is laid out to the card's edges and
  drawn under `Canvas.clipRoundRect` with the card's own rounded rectangle, so only the outer
  corners round and the inner ones stay square.
- **An icon** (`setMedia(Icon)`, any `limn.graphics.Icon`, a Tabler glyph included) is not a
  widget the application makes. The card draws it, which is how `Button` and `Label` draw theirs,
  and it is decoration to a reader, as theirs are:
  - `NATURAL`: a square of twice the step's `iconBox` (36 points at `MEDIUM`), at the leading
    edge, with no backdrop;
  - `STRETCH` and `COVER`: the media box is filled with the accent at the opacity the Showcase's
    tinted pills use (`primary` at 16 % in light palettes, 26 % in dark ones), and the icon is
    centred in it at four tenths of the box's shorter side, rounded to a whole point so the
    icon cache keys stay bounded (as `Button.iconAdvance` says of `iconBox`);
  - **colour:** a tintable icon is drawn in `primary` (question 11); one that is not is drawn as
    authored; `Icon.Mirroring` is the caller's, `NEVER` by default, as on every component.

### 2.5 Paint and the three styles

| `Card.Style` | Fill | Edge | Shadow | Is |
| --- | --- | --- | --- | --- |
| `ELEVATED` (default) | `surface` | none in a light palette, a hairline of `outline` in a dark one | `Elevation.RAISED` | what `Page.card` draws today, and ADR 057's "a card" |
| `OUTLINED` | `surface` | one point of `outline`, drawn with the concentric border helper | none | a card on a desktop that says depth by a line |
| `FILLED` | `surfaceRaised` | none | none | a card set apart by tone alone |

*Note, 2026-10-18 (section 9):* `FILLED` fills with `surface`, not `surfaceRaised`, and a fourth
row, `PLAIN`, has no fill, no edge and no shadow.

The corner radius is `radiusMedium` of the step (question 7), the radius `Surface` draws. A
dark palette's hairline on `ELEVATED` is there because ADR 057 §1 found that black on near-black
shows no shadow. The style is one setter with three named looks; the free combination of a
shadow and a stronger line that some libraries allow is left to the theme, by the owner's rule
that a component stays operable with a few setters.

`Card` is not a `Surface`: the toolkit's components are final, and a card that drew through a
child `Surface` would need its own outset, focus ring and hover band beside it anyway. It calls
`Theme.drawElevation` itself, as `Surface` does.

### 2.6 The click, and where it stops (the owner's decision)

A card given `onActivate` is **activatable**. The owner's rule, 2026-10-18: the click is the
card's everywhere outside the footer, the footer is a bar of its own whose buttons are clicked as
buttons, and a card with no footer is one click as a whole. To the user it is always "click the
card".

**The activatable area.**

| | No footer | With a footer |
| --- | --- | --- |
| Area | the whole card | the card minus the footer band |
| Footer band | — | the card's full width, from half a card gap above the footer's first run to the card's bottom edge |
| Hit | a press anywhere on the card | a press above the band; a press in the band, between buttons included, does nothing |
| Focus stops (Tab) | one: the card | the area, then each footer action in order |
| Focus ring | the card's outline, outside it, concentric, as a button's | the area's outline: the card's top corners, a straight bottom edge on the divider, drawn inside the card's edge so it never crosses a footer action (question 2) |
| Hover and press | a state layer over the whole card | a state layer over the area only (question 3) |
| Divider | — | a hairline of `outline` between the area and the footer (question 4) |

*Note, 2026-10-18 (section 9.1):* the band now runs from the divider, half a card gap under the last
part as before, and holds the actions a card padding under it, so it is padded alike on all four
sides; a card that is not clicked keeps its footer a gap under its last part.

**How it is built.** The card holds one private widget, the *activator*, around the media, the
header and the content, and the footer beside it. The activator's box is the activatable area,
so the hit test, the focus, the ring, the hover and the reader's bounds all read one rectangle,
and the footer's band is not in it. Horizontal cards keep that rectangle because their footer
runs the full width (section 2.3).

- **Pointer.** A press inside the area that no descendant consumes reaches the activator by
  bubbling; the activator arms on the press and activates on the click, as `Button` does, so a
  press dragged out of the area and released outside does nothing. A press a descendant consumes
  is that descendant's: an image whose drag is enabled, a chart's legend that turns a series on
  and off. A press in the footer band reaches the footer's widgets or nothing.
- **Keyboard.** The activator is focusable; Enter and Space activate it, armed on the key's press
  and fired on its release, as on a button. Only a key whose target is the activator itself does:
  a key that bubbles up from a descendant does not activate the card.
- **Hover.** Because the scene sends hover to the deepest widget alone, the activator asks the
  scene, through an internal hook in `SceneAccess`, to be told when the hovered widget enters or
  leaves its subtree. The pointer over a label in the area is the card's hover; the pointer over
  the footer band is not. No public API.
- **Cursor:** the pointer cursor over the area.
- **Disabled:** `setEnabled(false)` on the card disables its subtree as on any widget; the card
  draws as disabled and nothing activates.

**A focusable widget inside the area.** The area may hold anything that does not take the
keyboard: labels, pictures, a badge, a chart. A widget that does take it (a button, a field, a
check box, media controls) inside the header prefix, the suffix, the content or the media of an
activatable card would be a control inside a control. Proposed (question 1): the setter that
would make it so throws `IllegalArgumentException` naming the widget's class, as `onActivate` does
when the area already holds one; a focusable widget that arrives later, inside a content the
application changes, is found at the next layout, logged once per class, and made inert, so it is
drawn as it is and every press, key and reader verb in the area stays the card's. A card that is
not activatable takes any widget in any part.

**Runtime changes.** Adding a footer to an activatable card, or taking it away, moves the
boundary at the next layout: the activator's box shrinks or grows, the divider appears or goes,
the focus stays on the activator if it was there (it is the same widget), and the accessible tree
changes as section 2.7 says. Turning `onActivate` on or off makes the activator focusable or not;
a focus on it when it stops being focusable goes where a removed focus goes.

### 2.7 Accessibility, per platform

**Not activatable.**

- **With a title:** the card is a `GROUP` named by its title, the title's `Label` publishing
  `HEADING`, the relation `LABELLED_BY` to it, as `Expander` names its content. The subtitle is a
  `LABEL` after the heading (question 5). Every other part publishes what it publishes. Shapes:
  `SURFACE` for the group, `STATIC` for the texts.
- **Without a title:** the card is decoration, transparent to readers as `Surface` is, so a chart
  in a card is the chart and not a nameless group around it. The application may still name it
  (`setAccessibleName`), and then it is the named `GROUP`.
- **No heading level** is published: the model's `HEADING` carries none, because not every
  platform has the flag.

**Activatable, without a footer.** The card's node is the activator, and it is a pressable leaf:

| | Windows (UI Automation) | macOS (AppKit) | Linux (AT-SPI) |
| --- | --- | --- | --- |
| Role | Button | `AXButton` | push button |
| Name | the title, from content | the title, as `AXTitle` | the title |
| Description | the subtitle, as `HelpText` | `AXHelp` | the description |
| Verb | Invoke | `AXPress` | the action that presses |
| Children | none | none | none |

- The role is `BUTTON` and not a `GROUP` that offers `PRESS`, so the shape does not change with
  state (ADR 045 §1.1): a disabled card loses its verb to the walk and is still a button, heard as
  unavailable.
- The name is the title as the activator's own text (`NameFrom.CONTENT`), with no `LABELLED_BY`
  relation: the title's label is inside the leaf and not published, and a relation to it would
  resolve to the activator itself.
- **What is inside the area is not read.** A leaf has no children, so the media's name, a badge
  in the suffix and the content's text reach a reader only through the name and the description.
  The description is the subtitle; an application whose content carries the fact a reader needs
  (an amount, a status) says it with `setAccessibleDescription` on the card. What the
  application writes on the card (a name, a description) lands on the activator's node, the one a
  reader reaches, as a caption bound to a date picker lands on its field through
  `accessibleLabelTarget`; today the walk redirects a caption that way and not a name or a
  description written on the widget itself, so carrying those two to the activator is the one
  change this record asks of the walk (stage 2). Nothing is composed from the content by the
  toolkit, by ADR 039's rule that a name or a description is declared and never inferred.
- **A card with no title** that is activatable has no name to give; the invariant that a focusable
  node has one refuses it in the gallery and in the contract, the Javadoc says so, and the card
  logs once at its first publish without a name. `setAccessibleName` on the card names it.

**Activatable, with a footer.** The card is a `GROUP` holding the activator and then the footer's
actions:

| Node | Windows | macOS | Linux | Shape |
| --- | --- | --- | --- | --- |
| the card | Group, named by the title | `AXGroup`, named by the title | panel, named by the title | `SURFACE` |
| the activator | Button, as above | `AXButton`, as above | push button, as above | `LEAF_ACTION` |
| each footer action | its own (a button is a Button) | its own | its own | its own |

- The footer's `Wrap` is scaffolding and publishes nothing, so its actions are the group's
  children after the activator, and no tool bar is claimed (a tool bar tells Windows and macOS
  readers to move by arrows, which these buttons do not).
- The group is named by the title with `NameFrom.LABEL` and `LABELLED_BY` the activator, the
  nearest published node that holds the title. It is named so that a reader landing on a footer
  action by Tab is told whose action it is ("Harbor Lane Supplies, group; Approve, button"), which an unnamed
  group would leave out. The cost is that the first stop says the title twice, once as the group
  and once as the button (section 7).

**Changing at runtime.** With the footer added the card's node appears as the `GROUP` around the
activator, whose node and id are unchanged; with it taken away the group goes and the activator
stands in its place. One `CHILDREN` change is announced from the card's funnel, as ADR 067 §8 has
it for any child removed or moved.

### 2.8 Focus and keyboard, all cases

| Card | Tab stops, in order |
| --- | --- |
| not activatable | the focusable widgets of the media, the prefix, the suffix, the content and the footer, in that order (the tree order) |
| activatable, no footer | the card |
| activatable, with a footer | the card's area, then each footer action |

An access letter in a footer button's caption (ADR 070) presses it as anywhere else. The
activator has no access letter: its text is a title, not a command.

### 2.9 Right to left (ADR 032)

Everything is leading and trailing: the prefix leads and the suffix trails, the horizontal media
column is on the leading side and `COVER` rounds the leading corners there, the footer's actions
sit at the trailing edge, and an icon's glyph turns only as its `Mirroring` says. A card whose
own `setLocale` or `setLayoutDirection` differs from its window's, as each customer card of
`BidiScene` does, lays out by its own direction.

### 2.10 What a frame repaints (ADR 043)

- **Outset:** the shadow's reach for `ELEVATED` (the larger of it and the focus ring's reach on an
  activatable card without a footer, whose ring is drawn outside), so a partial frame damages
  what the card draws.
- **Hover, press and focus** repaint the activator's box and its ring, with no layout. The hover
  fade is a `Transition` on the theme's hover timing, as `Button`'s.
- **A footer added or removed, a part set or cleared** is a layout of the card, as any child
  change is.
- The damage contract gains a row per case: a hover over the area repaints the area alone; a
  hover over the footer band repaints nothing of the card.

### 2.11 Theme tokens

- **Sizes:** one new `SizeTokens` column, `cardPad`, `{10, 12, 16, 20, 24}` from `XSMALL` to
  `XLARGE` (question 6). The gap is `spacingMedium`. A token column changes no theme file.
- **Colours, shadow and radius:** none new. `surface`, `surfaceRaised`, `outline`, `primary`,
  `text`, `textMuted`, `focusRing`, the `RAISED` shadow and `radiusMedium` exist.
- **Fonts:** the title is the step's body font, strong; the subtitle the body font in `textMuted`
  (question 8).
- **The theme editor** is a program and keeps its painted preview card; nothing there changes.

### 2.12 Strings

None. The card adds no text of its own in any language.

## 3. What it replaces

- **`Page.card(content)`** becomes `new Card(content)` at its 18 calls, and the helper goes.
- **`BidiScene.card`** becomes a title, a subtitle and a content: the strong and muted labels and
  the six-point spacer go, and each customer card is named to a reader for the first time.
- **The invoice** of the animations scene becomes a title, a subtitle, a suffix holding the
  amount, a footer holding "Details", and the unfolding body as content.
- **`SpritesScene.tile`** becomes media over a caption.
- **The chart cards** stay content only, transparent to readers, as now.
- **Stays a `Surface`:** the colour picker scene's elevation samples and the order panel that
  slides over the table, which show `Surface` itself.
- **New Showcase material:** the three styles, both orientations, the three fits with a real
  picture and with an icon, an activatable card with and without a footer, beside the scene's
  existing variations, as the owner's review of 2026-10-15 asked for every family.
- **Guides:** the layout guide gains a section on cards; the theming guide's elevation section
  names `Card` beside `Surface`; the accessibility guide says what an activatable card publishes
  and that its content is read only through its name and description.

## 4. Questions for the owner

Answered by the owner on 2026-10-18. Each question keeps its recommendation and cost as put; the
answer follows it. Every question took the recommendation.

1. **A focusable widget in the area of an activatable card?**
   - *Recommended:* refused where it can be seen (the setter, or `onActivate` over an area that
     holds one, throws naming the class); one that arrives later is logged once and made inert.
   - *Why:* a button inside a pressable leaf would be a focus stop with no node, which the
     invariants refuse, and a press on it would be two presses in one.
   - *Cost:* a widget drawn as a control and answering nothing, until the application moves it to
     the footer; the log names it in development.
   - *The alternative:* let it win its own click, as the footer's do. The area then holds controls,
     is no longer a leaf, and needs a pressable group with readable children: a shape ADR 045 does
     not have, with a contract, three bridge tests and a reader round on three platforms.
   - *Or:* throw always, which a content the application changes later cannot honour without a
     check on every layout that throws in the middle of a frame.
   - **Answer:** as recommended: the setter, and `onActivate` over an area that holds one, throw
     `IllegalArgumentException` naming the class; one that arrives later is logged once per class
     and made inert.
2. **The focus ring of a card with a footer.**
   - *Recommended:* around the activatable area, inside the card's edge, ending on the divider.
   - *Why:* the ring shows what Enter will press, and the footer is not pressed by it.
   - *Cost:* a ring with square bottom corners, unlike every other ring in the toolkit.
   - *The alternative:* around the whole card, outside it, as on a card with no footer: one look
     for both, which says the footer is part of what Enter presses.
   - **Answer:** as recommended.
3. **Hover and press visuals.**
   - *Recommended:* a state layer over the area only (the fill toward `surfaceRaised` on hover, a
     step further while pressed), the same for the three styles; no change of shadow.
   - *Why:* the pointer then shows where a click lands, and the footer band, where it does
     nothing, shows nothing.
   - *The alternative:* the whole card lifts on hover (a stronger shadow for `ELEVATED`), which
     reads as one object, at the cost of a lift shown over the footer band where a click does
     nothing.
   - **Answer:** as recommended: a state layer over the area only (built as the text colour laid
     over the fill, section 8.3).
4. **A divider above the footer of an activatable card?**
   - *Recommended:* yes, a hairline of `outline` across the card.
   - *Why:* the boundary of the click is then visible before anyone clicks.
   - *The alternative:* none, the gap alone; a card not activatable has no divider either way.
   - **Answer:** as recommended.
5. **The subtitle of a card that is not activatable, to a reader.**
   - *Recommended:* plain text after the heading.
   - *Why:* a reader walking the card reads it once, in order.
   - *The alternative:* the group's description too, so a reader entering the group from a control
     inside hears it, at the price of hearing it twice when walking. (An activatable card's
     subtitle is its description in any case, since its texts are not read otherwise.)
   - **Answer:** as recommended.
6. **The padding.**
   - *Recommended:* a new `SizeTokens` column, `cardPad`, 16 at `MEDIUM`, what the Showcase uses.
   - *Cost:* one column; no theme file changes.
   - *The alternative:* `spacingLarge` (20 at `MEDIUM`) or `spacingMedium` (12), no new column.
   - **Answer:** as recommended: `cardPad`, `{10, 12, 16, 20, 24}`.
7. **The corner radius.**
   - *Recommended:* `radiusMedium` (8 at `MEDIUM`), as `Surface` and `Page.card` draw.
   - *The alternative:* `radiusLarge` (14), which some libraries give cards to set them apart from
     the controls inside.
   - **Answer:** as recommended.
8. **The title's size.**
   - *Recommended:* the body font, strong, as the Showcase's cards are.
   - *The alternative:* the title token, a step larger, as some libraries draw it.
   - **Answer:** as recommended.
9. **A header that replaces title and subtitle with a widget?**
   - *Recommended:* no: a header that is not text is content.
   - *Cost of adding it:* a fourth header setter, and a card whose name has to come from somewhere
     else.
   - **Answer:** as recommended: no.
10. **The footer of a horizontal card.**
    - *Recommended:* the card's whole width, under the media and the text.
    - *Why:* the activatable area stays a rectangle.
    - *The alternative:* the footer in the text column only, the media down the card's full
      height; the area is then an L, its published bounds overlap the footer, and the hit test
      needs a rule for the corner.
    - **Answer:** as recommended.
11. **The colour of a tintable icon used as media.**
    - *Recommended:* the accent (`primary`).
    - *The alternative:* `textMuted`, quieter; an application wanting another colour passes an
      `ImageView` of the icon with its own tint.
    - **Answer:** as recommended.
12. **`Page.card`.**
    - *Recommended:* gone, its 18 calls on `Card`.
    - *The alternative:* kept as a one-line wrapper over `Card`, which saves nothing.
    - **Answer:** as recommended: gone, its 18 calls on `Card`.

Asked after the build, from what section 7.1 measured and the prints showed, and answered by the
owner on 2026-10-18; section 9 has what was built.

13. **The filled card on the Light palette, where it all but vanished.**
    - *Seen:* `surfaceRaised` is the Light page's own tone (#EDF0F6 on #EEF0F4).
    - *The choices put:* a line on a palette whose raised tone is its canvas, or another fill.
    - **Answer:** the look keep a name of its own: a fourth style, `PLAIN`, with no background, no
      border and no shadow, keeping the layout, the padding, the click, the hover and press layer
      and the focus ring; the hover layer and muted text keep 4.5:1 over the page in every
      built-in palette.
14. **A filled card's muted text under the hover** (3.78 to 4.10:1 in twelve palettes of fifteen).
    - *The choices put:* a quieter hover on a filled card, or a stronger tone for its subtitle.
    - **Answer:** neither: `FILLED` fixed to what it was meant to be, a fill visibly distinct from
      the page in every built-in palette, a tone step from the page and not `surfaceRaised`, with
      the hover and press layer over it keeping muted text at 4.5:1 everywhere, measured and pinned
      in tests.
15. **A title that wraps** (section 5 left it out).
    - **Answer:** `setTitleLines(int)` and `setSubtitleLines(int)`, 1 by default, through a new
      `Label.setMaxLines(int)` whose default 1 leaves every label as it is. No getter hands out the
      card's labels, and there is no `setContentLines`: a text content is a `Label` the application
      makes, with its own `setMaxLines`. Not fitted to the height: a label given less height than
      its lines clips, as today.

## 5. Not in this record

- **Master-detail.** Refused by the owner on 2026-10-18: a list beside a record is a `SplitPane`,
  and a panel over a table is a `Surface` (ADR 057 §4).
- **A pressable group with readable controls inside the area** (question 1's alternative).
- **A heading level**, a role chosen per card (`setAccessibleRole` exists for that), a selected or
  checked card, a card dragged to reorder a grid of cards, an editable dashboard.
- **A title that wraps.** One line with an ellipsis; a later `setTitleWrap` is additive.
  *Note, 2026-10-18:* added as `setTitleLines` and `setSubtitleLines` (question 15, section 9),
  a number of lines and not a flag.
- **Refused earlier and not proposed here:** toasts and notifications, a link widget, rich text,
  a switch class, a grid with tracks and spans, persistence.

Considered for the click before the owner's decision, and set aside: the whole card as a button
with nothing focusable inside it (the case of a card with no footer, now one case of two); a
pressable group whose children readers walk, the controls in it winning their own clicks (a new
shape and a reader round); and a primary button inside the card standing in for it, the card's
surface forwarding its clicks (the title read as a button, the group around it duplicated).

## 6. Stages, and what each must prove

- **Stage 1: the card that is not activatable.** Parts, both orientations, three fits, three
  styles, the icon as media. Tests: each part present and absent leaves no gap; the extra height
  goes to the content and footers line up in a stretched row; `COVER` clipped to the outer corners
  only, pixel-checked on a real device; an icon centred and sized as section 2.4 says, tinted or
  not; right to left for every placement and both orientations; the outset damaged by a partial
  frame equals the whole frame pixel for pixel; a titled card publishes a named `GROUP` with its
  heading, a titleless one nothing; the damage and notification contracts' rows; a gallery entry
  and its golden line.
- **Stage 2: the activatable card.** Tests: a click in the area activates once, a press dragged
  out does not, a press consumed by a descendant does not; a click in the footer band activates
  nothing, between buttons included; a footer action pressed runs its own handler and not the
  card's; Enter and Space activate only when the activator is the target, a key bubbling from a
  descendant does not; the Tab order of section 2.8 in the three cases; the hover goes on and off
  at the band's edge; **a footer added and removed at runtime** moves the hit area, the divider,
  the ring and the bounds, keeps the focus on the activator, and changes the published tree from
  a button to a group holding it and back with the activator's id unchanged; a focusable widget
  placed in the area throws at the setter and is logged once and inert when it arrives later;
  `LeafActionContract` runs over the activator in both cases, and the group's name and relation
  are checked; a name and a description written on the card reach the activator's node, with and
  without a footer; the work budget (ADR 062) unchanged for a still card.
- **Stage 3: the Showcase and the guides** (section 3).

## 7. What must be measured or heard

- **Readers.** Every node a card publishes is of a shape ADR 045 already covers: a `SURFACE`
  group, `STATIC` texts, a `LEAF_ACTION` button, each footer action its own. By the owner's rule a
  live reader run is not owed. The one composition no reader has been heard on is a group named
  by the title whose first child is a button named by the title: whether NVDA, VoiceOver and Orca
  say the name twice on the first stop, and whether each names the group when Tab reaches a
  footer action. That is wording, not shape; one listen per reader of the Showcase's activatable
  card with a footer would answer it, if the owner wants the answer before the status moves.
- **Contrast of the state layer** on each built-in palette, measured as the theme's tests measure
  a hovered secondary button, before the colours are fixed.
- **The hover hook's cost:** the scene's work per pointer move with and without an activatable
  card under the pointer, counted by the work budget, not timed.

### 7.1 Measured, 2026-10-18

- **Contrast of the state layer** (`CardStateLayerContrastTest`, the fifteen built-in palettes).
  The layer is the text colour laid over the fill, six hundredths on hover and eleven while
  pressed (section 8.3). The hover moves the card 3.5 to 6.9 L* off its fill, where a hovered
  secondary button moves 5.0 to 7.8; the press a further 2.5 at least. Text over the pressed
  layer keeps 5.96:1 at the least (Darkling, filled). Muted text over the hovered layer keeps
  4.5:1 on a raised or an outlined card in every palette, 4.51:1 at the least (Dark). **On a
  filled card it does not:** muted text over `surfaceRaised` already sits a hair above 4.5:1 at
  rest in most palettes, and the hover takes it to 3.78 to 4.10:1 in twelve of the fifteen
  (Monoko Pro the lowest; Light, Limn Light and High Contrast keep it). The test pins those floors
  so a change that lowers them fails. This is a number that contradicts nothing decided but is
  below the body-text bar while the pointer rests on a filled card; whether a filled card gets a
  quieter hover, or its subtitle a stronger tone, is the owner's.
- **The hover hook's cost** (`WorkBudgetTest`). With the pointer going between the title and the
  content of the first of ten clicked cards and of a hundred, the hovered widget changes on every
  move and the card's hover does not: the frame after a move draws nothing (0 commands), and the
  step costs the same over ten cards and over a hundred, allocation included. While no widget in
  the process observes the hover, a change of the hovered widget costs one comparison more. A
  still clicked card asks for no frame.
- **Readers:** not heard. The listen of section 7 is still owed if the owner wants the wording
  of the first stop of a clicked card with a footer.
- **Seen in the prints, not measured:** a filled card on the Light palette's page all but
  vanishes, `surfaceRaised` being the page's own tone there (#EDF0F6 on #EEF0F4, the same pair the
  0.9.0 slider rail fell into). The style draws what section 2.5 says; whether `FILLED` should take
  a line on a palette whose raised tone is its canvas is the owner's.
- *Note, 2026-10-18:* both answered (questions 13 and 14); section 9.2 has the numbers after.

## 8. Built, 2026-10-18

Branch `claude/adr-073-card`. Where the build differs from the text above, or the text left a
choice open, it is said here.

### 8.1 What landed

- **`Card`**, as section 2.2 has it, with `media()`, `mediaIcon()`, `headerPrefix()`, `title()`,
  `subtitle()`, `headerSuffix()`, `content()`, `footer()`, `style()`, `orientation()`,
  `mediaFit()` and `mediaAspectRatio()` as the getters. The area of section 2.6 is a private
  widget, `Card$Area`; the footer a private `Wrap`, `Card$Footer`, its gutters `gapButtonRow` and
  `spacingMedium` at the card's step through `Wrap.gapsSilently`, the measure-path form `Flex`
  already had (`TokenRow`).
- **`SizeTokens.cardPad`**, `{10, 12, 16, 20, 24}`.
- **The hover hook:** `SceneAccess.observeHoverWithin(widget, observer)`, internal. The scene keeps
  the observing widgets it told "inside" and works out who left from them, since a removal has
  cut the old leaf's ancestry by the time the scene hears of it; when the widget under the pointer
  goes, the subtree hears it has left and is entered again at the next move. The area observes
  only while it is in a scene and the card is clicked.
- **The walk's one change** (section 2.7, stage 2), built generally: a name, a description **and a
  tooltip** written on a composite whose `accessibleLabelTarget()` is a part inside it land on that
  part and not on the composite, the part's own winning, as a caption already did. The tooltip
  was not in the text: left on a card that is otherwise transparent, it published a named group
  around the button. A date picker's field and an editable combo's text are named the same way
  now; no test of theirs moved.
- **`SceneAccess.namedByApplication`**, internal: what the area asks before it logs that a clicked
  card has no title and no name. Logged once per process, not once per card.
- **The Showcase:** `Page.card` gone and its 18 calls on `Card` (section 3); the "Tabs and
  sections" page holds every family under a "Cards" heading, translated into the 21 languages; the
  lab's `cards`, `cards-2`, `cards-states` and `cards-states-2` scenes print them. The pictures are
  painted by the demo (`Pictures`), so it carries no photograph and no licence.
- **The guides:** a "Cards" section in the layout guide, with two snippets from `CardExample`, which
  a test clicks and reads; a sentence in the theming guide's elevation; a paragraph in the
  accessibility guide.

### 8.2 Layout, where the text left room

- **After a covering picture** the next part starts at the card's padding, not at its gap: the
  picture stands in for the card's edge, and the parts sit inside the card as they do under the
  edge. A card that holds a covering picture alone has no padding under it.
- **A horizontal card's media column** is the media's measured width at its natural fit, at most
  half the inner width; when it stretches or covers, **at most a third of the card**, not half,
  which left the text too little on the Showcase's widths; an icon that stretches takes four of
  the step's icon size, also at most a third. A picture keeps at least its own proportions at the
  column's width, and grows with the text beside it.
- **A hidden part takes no room and no gap**, and a footer whose actions are all hidden is no
  footer: no band, no divider, the card a button again. The text said only "absent".
- **The area measures the height its parts need** at the width it is given, so that a part changing
  its size inside it, which lays out in place, climbs to the card; the card lays the area out
  taller when it is given more.

### 8.3 The click, where the text left room

- **The activation is the release over the area**, not the scene's click: the scene sends a click
  only when the release lands in the subtree of the widget pressed, and the widget pressed is the
  deepest, a label, so a press on the title released on the content would not have been a click.
  The area arms on a press nothing took and activates on the release of that button inside its
  own box; released outside, nothing.
- **The state layer** is the text colour over the area, the parts included, six hundredths while
  hovered and eleven while pressed: "the fill toward `surfaceRaised`" of question 3 is no change on
  a filled card, whose fill is `surfaceRaised`, and the record asks for the same layer on the three
  styles. Section 7.1 has what it costs.
- **The ring of a card with a footer** is inset by a border and half its pen, so an outlined
  card's edge, drawn over the area, never covers it; the edge and the divider are the card's
  overlay, drawn over a covering picture too.
- **A control found at a layout** is made inert where it is, and given back to the application,
  inert no more, when it leaves the card or the card stops being clicked. A card that stops being
  clicked with its area holding the keyboard lets the keyboard go as a removed widget does.
  *Note, 2026-10-19 (audit CO-4):* "when it leaves the card" held only when its whole part was
  replaced. A control taken out of a column in the content and handed to `setFooter`, as the
  warning advises, stayed inert in the footer (no click, no focus), and the card kept a reference to
  any such control the application discarded. It is now given back as it leaves the area: by
  `setFooter` at once, by a watch on the window's tree (held only while the card holds a control
  out of reach) as a container takes it out, and every one as the card leaves the window (the
  review of that day found a control taken out of a card that never came back dead and held, and
  one card letting go undoing another's hold on the one flag); the first layout back in a window
  finds again what is still in the area, and the area's attach does it at once (the second review,
  2026-10-20: a card put back held nothing until its next layout, and a click before it reached the
  button). No two cards ever hold one control (a card holds one only while it is under that card's
  area in a window, an outer card's walk stops at an inner clicked card's area, and the watch gives
  a control back before it can arrive elsewhere), so the one inert flag a widget has is enough.
- **The group of a clicked card with a footer** is named by the title. An application that names a
  card with no title names its button; the group around it stays unnamed, the card having no way
  to read the name the application wrote.

### 8.4 Tests, and what each was proved against

`CardTest`, `CardActivationTest`, `CardAccessibilityTest`, `CardLeafActionContractTest` (the leaf
contract over the area, without a footer and with one), `CardDamageTest`, the card's rows in the
damage and notification contracts, `HoverWithinTest`, `AccessibleCarriedOverridesTest`,
`CardStateLayerContrastTest`, two `WorkBudgetTest` scenarios, `CardPixelTest` on a real device (a
covering picture rounded at the card's outer corners only, and a partial frame of the ring outside
a card and of the hover over its area matching the whole frame), `CardExampleTest`, and the
gallery entry. Each key behaviour was run against its reversal and failed: the area reaching into
the footer's band, the setter's refusal taken out, the leaf's children published, the hidden part
kept, the hover hook told on every move, the walk's carry taken out, the clip and the declared
reach taken out.

## 9. Adjusted, 2026-10-18: the owner's answers to questions 13 to 15

Branch `claude/card-adjust`, on `claude/review-fixes` with this record built.

### 9.1 What landed

- **`Card.Style.PLAIN`.** No fill, no edge, no shadow, and no outset but the ring's on a clicked
  card with no footer. It keeps the layout and the padding, the click, the divider above a footer,
  the hover and press layer (the text colour over the page, as over any fill) and the focus ring.
  `Card.fill(style, theme)`, package-private, answers the tone each look fills with, `null` for
  plain, and is what the paint and the contrast test both read.
- **`FILLED` fills with `surface`.** *Note: the answer to question 14 says "a tone step from the
  page"; the build takes the palette's own step, `surface`, because a step computed from the page
  cannot keep its promise on Light (section 9.2).* `surface` is the step every built-in palette is
  held to by `ThemeContrastTest` (5 L* off its page at the least), and the hover over it keeps
  muted text at 4.5:1. The cost: a filled card now differs from a raised one by the shadow alone
  and from an outlined one by the line alone, and on a ground that is itself `surface`, a card in a
  card or a panel, it does not stand out.
- **`Label.setMaxLines(int)`, `maxLines()`,** 1 by default. Above 1 the text breaks across that
  many lines at most by the walk `setWrap` uses (`BreakIterator` under the UI language, so CJK and
  Thai break as they wrap), and the last line is the rest of the text cut as one line is: with the
  measured ellipsis at its logical end under `Overflow.ELLIPSIS` (on the left of a right-to-left
  line), run on to the box's edge under `CLIP`. A line break in the text after the last line is
  more text and puts the ellipsis on a last line that fits. **How it combines with `setWrap`,**
  chosen so that no caller changes: a limit above 1 holds whether wrap is on or off; at 1 `setWrap`
  decides as before, one line without it and as many as the text needs with it. The lines are
  counted, not fitted: a label given less height than its lines clips, as a wrapped one does. A
  change lays out in place, as a change of text does. The reader's name stays the whole text.
- **`Card.setTitleLines(int)`, `setSubtitleLines(int)`, `titleLines()`, `subtitleLines()`,** 1 by
  default, applied to the card's own labels through `setMaxLines`, kept before the title or the
  subtitle exists and applied when it comes. `title()` and `subtitle()` stay the text getters; no
  getter returns a label. The header grows to the lines the text takes; beside a prefix or a suffix
  taller than the text it grows only past them.
- **The footer band padded alike on all four sides** (the owner, 2026-10-18, after the prints).
  Under the divider of a clicked card with a footer the actions stood half a gap (6 points at
  `MEDIUM`) below the line and a card padding (16) from the sides and the bottom. The band's top is
  now `cardPad` too: the divider stays half a gap under the last part and is still where the part
  that is clicked ends, and the actions sit a padding under it, centred in the band, their leading
  edge in line with the text above. Such a card is `cardPad` less half a gap taller (10 points at
  `MEDIUM`). A card with no divider (not clicked, or no footer) keeps its footer a gap under its
  last part. `CardFooterBandTest` holds the four sides equal at every step, both ways round and in
  a card given more height than it needs, and the divider half a gap under the content; against
  the old band its fifteen cases of a clicked card fail and the five of a card not clicked pass.
- **The Showcase.** The looks row is four customers, raised, outlined, filled and plain, each name
  and "Customer since" on two lines, the year to date moved from the header's end into the content
  (at a quarter of the page the amount left the name a few letters; the desk card and the invoices
  still show a suffix). One string is new (the fourth customer's line) and the row's hint names the
  fourth look, in the 21 languages. The lab's card scenes are three so each fits its window, and
  the states scene's last card is plain, so the press of its second pose shows the layer over the
  page.
- **The guides.** The layout guide's Cards section names the four looks and the lines, with a
  third snippet from `CardExample` (a plain card whose long name takes two lines and whose note is
  cut at two), which `CardExampleTest` measures; the text guide says a limited label breaks and is
  cut as the language reads.

### 9.2 Measured

Over the fifteen built-in palettes, with the layer of section 8.3 unchanged (the text colour at six
hundredths on hover, eleven pressed):

| Under the layer | The hover's step | Muted text under the hover | Text over the press |
| --- | --- | --- | --- |
| `surface` (raised, outlined, filled) | 3.8 to 6.9 L* | 4.51:1 at the least (Dark) | 7.25:1 at the least (Nordic) |
| the page (plain) | 4.1 to 6.3 L* | 4.57:1 at the least (Light) | 8.84:1 at the least (Nordic) |

- **A filled card off the page:** 5.25 L* at the least (Light: #FFFFFF on #EEF0F4), 15.7 at the
  most (Limn); it was 0.02 L* on Light.
- **Why not a step from the page toward the text.** Light's muted text is 5.11:1 on its page to
  begin with. A fill 2.9 L* darker keeps it at 4.74:1 at rest and takes it to 4.24:1 under the
  hover; 4.3 L* darker, 4.57:1 and 4.09:1. Every step that shows takes it below 4.5:1 while the
  pointer rests on the card, unless the hover is cut below what can be seen. The dark palettes have
  room for such a step, Light does not, and one rule for all fifteen was wanted.
- Palette values, for the record: Light page #EEF0F4 (L* 94.8), surface #FFFFFF (100), raised
  #EDF0F6 (94.7); Dark page #14181F (8.1), surface #1D242F (14.0); Limn Light page #FFFFFF, surface
  #F1ECFD (94.2); Octo Light page #FFFFFF, surface #ECEEF0 (94.0); Solaris Light page #FDF6E3
  (97.0), surface #E9E3D1 (90.3); High Contrast page #000000, surface #121212 (5.5).

### 9.3 Tests, and what each was proved against

`CardStateLayerContrastTest` now measures all four looks against the tone under them at one bar,
4.5:1 at rest and under the hover, and holds a filled card 5 L* off the page; against the old fills
(the raised tone for `FILLED`, the surface for `PLAIN`) twelve palettes fail the hover bar and Light
fails the step. `CardStyleTest` checks what each look paints (shadow, line, fill, nothing) and that
a plain card that is clicked keeps its hover and its ring; against the old fills it fails twice.
`LabelMaxLinesTest` (two and three lines, LTR and RTL ellipsis placement, CJK, Thai, `CLIP`, the
interplay with `setWrap`, a new text re-broken, a change laid out in place) and
`LabelMaxLinesAccessibilityTest`; against the limit taken out fifteen fail, against the trailing
break rule and against a full layout in place of the in-place one, one each. The existing `Label`
tests pass untouched. `CardLinesTest` (defaults, two and three lines, the limit set before the text,
the subtitle, right to left, the refusal below 1, the whole title to a reader); against the limits
not reaching the labels, five fail. `CardExampleTest` gains the guide's plain supplier.

### 9.4 Owed

Nothing a reader has to hear: the name of a card or a label is its whole text however many lines
show, which the tests read off the published tree, and no node changes shape.
