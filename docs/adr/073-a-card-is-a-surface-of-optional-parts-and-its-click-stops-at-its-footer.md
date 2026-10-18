# ADR 073: A card is a surface of optional parts, and its click stops at its footer

- **Status:** Proposed, 2026-10-18. The owner chose on 2026-10-18 that a card enters before 1.0,
  with every part optional, an icon accepted as media, three styles with `ELEVATED` the default,
  the footer at the trailing edge, both orientations and all three media fits in the first
  version, and an optional activation that combines with footer actions. The same day he settled
  how the two combine (section 2.6): the click is the card's everywhere outside the footer, the
  footer is a bar of buttons of its own, and a card with no footer is one click as a whole.
  Section 4 holds what is still open.
- **Date:** 2026-10-18
- **Scope:** a container with media, a header (a leading part, a title over a subtitle, a trailing
  part), content and a footer of actions; its layout in two orientations, its three looks on
  ADR 057's elevation, its media fits, its activation, its keyboard and focus, what each platform's
  reader is given, right to left, and what a partial frame repaints.
- **New public API (proposed):** `limn.components.Card` with `Card.Style`, `Card.Orientation` and
  `Card.MediaFit`; one `SizeTokens` column (question 6).
- **Compatibility:** additive. The Showcase's `Page.card` helper goes (section 3).

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

## 2. Decision (proposed)

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

    public Card setStyle(Card.Style style);                // ELEVATED (default) | OUTLINED | FILLED
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
2. **The focus ring of a card with a footer.**
   - *Recommended:* around the activatable area, inside the card's edge, ending on the divider.
   - *Why:* the ring shows what Enter will press, and the footer is not pressed by it.
   - *Cost:* a ring with square bottom corners, unlike every other ring in the toolkit.
   - *The alternative:* around the whole card, outside it, as on a card with no footer: one look
     for both, which says the footer is part of what Enter presses.
3. **Hover and press visuals.**
   - *Recommended:* a state layer over the area only (the fill toward `surfaceRaised` on hover, a
     step further while pressed), the same for the three styles; no change of shadow.
   - *Why:* the pointer then shows where a click lands, and the footer band, where it does
     nothing, shows nothing.
   - *The alternative:* the whole card lifts on hover (a stronger shadow for `ELEVATED`), which
     reads as one object, at the cost of a lift shown over the footer band where a click does
     nothing.
4. **A divider above the footer of an activatable card?**
   - *Recommended:* yes, a hairline of `outline` across the card.
   - *Why:* the boundary of the click is then visible before anyone clicks.
   - *The alternative:* none, the gap alone; a card not activatable has no divider either way.
5. **The subtitle of a card that is not activatable, to a reader.**
   - *Recommended:* plain text after the heading.
   - *Why:* a reader walking the card reads it once, in order.
   - *The alternative:* the group's description too, so a reader entering the group from a control
     inside hears it, at the price of hearing it twice when walking. (An activatable card's
     subtitle is its description in any case, since its texts are not read otherwise.)
6. **The padding.**
   - *Recommended:* a new `SizeTokens` column, `cardPad`, 16 at `MEDIUM`, what the Showcase uses.
   - *Cost:* one column; no theme file changes.
   - *The alternative:* `spacingLarge` (20 at `MEDIUM`) or `spacingMedium` (12), no new column.
7. **The corner radius.**
   - *Recommended:* `radiusMedium` (8 at `MEDIUM`), as `Surface` and `Page.card` draw.
   - *The alternative:* `radiusLarge` (14), which some libraries give cards to set them apart from
     the controls inside.
8. **The title's size.**
   - *Recommended:* the body font, strong, as the Showcase's cards are.
   - *The alternative:* the title token, a step larger, as some libraries draw it.
9. **A header that replaces title and subtitle with a widget?**
   - *Recommended:* no: a header that is not text is content.
   - *Cost of adding it:* a fourth header setter, and a card whose name has to come from somewhere
     else.
10. **The footer of a horizontal card.**
    - *Recommended:* the card's whole width, under the media and the text.
    - *Why:* the activatable area stays a rectangle.
    - *The alternative:* the footer in the text column only, the media down the card's full
      height; the area is then an L, its published bounds overlap the footer, and the hit test
      needs a rule for the corner.
11. **The colour of a tintable icon used as media.**
    - *Recommended:* the accent (`primary`).
    - *The alternative:* `textMuted`, quieter; an application wanting another colour passes an
      `ImageView` of the icon with its own tint.
12. **`Page.card`.**
    - *Recommended:* gone, its 18 calls on `Card`.
    - *The alternative:* kept as a one-line wrapper over `Card`, which saves nothing.

## 5. Not in this record

- **Master-detail.** Refused by the owner on 2026-10-18: a list beside a record is a `SplitPane`,
  and a panel over a table is a `Surface` (ADR 057 §4).
- **A pressable group with readable controls inside the area** (question 1's alternative).
- **A heading level**, a role chosen per card (`setAccessibleRole` exists for that), a selected or
  checked card, a card dragged to reorder a grid of cards, an editable dashboard.
- **A title that wraps.** One line with an ellipsis; a later `setTitleWrap` is additive.
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
