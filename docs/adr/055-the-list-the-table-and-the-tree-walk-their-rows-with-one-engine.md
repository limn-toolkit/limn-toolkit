# ADR 055: The list, the table and the tree walk their rows with one engine

- **Status:** Implemented, 2026-10-10, with the owner's answers below; §7 says where the build
  departs from the text.
- **Date:** 2026-10-10
- **Scope:** `ListView`, `Table` and `Tree` keep one copy of what a scrolling run of rows is: the
  anchor and the walk over rows of uneven height, the mean height and the scroll estimate, which
  rows are realized and which of them is kept while it holds the keyboard, the reveal, the
  vertical scroll and the page keys; and, for the two that drag their rows, the lifting and
  sliding. A class of `limn.components.internal`, which the module does not export.
- **Compatibility:** no public type or method changes. Three behaviours do, by the owner's
  choice (§1), and five more as corrections (§3). `ListView`'s default height with none given
  goes from six rows to eight.
- **Closes:** what [ADR 041](041-a-table-is-columns-over-a-list-the-application-owns.md) §9 and
  [ADR 044](044-a-tree-is-an-outline-over-children-the-application-provides.md) §3 and §9 left
  owed, for layout. [ADR 045](045-a-widget-is-one-of-ten-shapes-and-the-bridge-translates-the-shape.md)
  did the same for accessibility and is not touched.

---

## 0. What existed

Three widgets, each `final`, each in a package of its own, each walking its rows by itself. A
map of the three, made before this record, found the walk the same in all of them nearly to the
letter: `anchorIndex` and `anchorTop`, `normalizeUp` and `normalizeDown`, a `placeDown` that
stops at the foot of the viewport, the second pass that closes a gap left at the end, the
realized rows kept in parallel arrays sorted by index, the rule that spares the row holding the
keyboard, a mean row height that the scroll bar's numbers are built from. `ListView` and `Tree`
also carried the same two hundred lines that lift dragged rows out, open room where they would
land and slide everything to its place (ADR 054 §7), written once and copied.

What three copies cost showed twice in theme 0.13. The lifting and sliding was written in the
list and written again in the tree. And a row of a list moved by the keyboard was put at the top
of the list, the rows above it scrolled away, because the list reveals a row that is not
realized by jumping it to the top; the tree, the newest of the three, had already stopped doing
that and settles a reveal inside the layout pass from measured heights. The defect was in one
copy because the correction had been made in another.

Where the three differ, the map lists thirty-one points. Most are the same idea in another
shape. The ones a user can see are §1 and §3.

## 1. The owner's decisions (2026-10-10)

Asked with the options, a recommendation and its cost; he took the recommendation in each.

1. **Page Up and Page Down are the table's, in all three.** The view moves a page and the cursor
   with it, keeping its place on screen, so that paging does not throw the cursor between the
   top and the foot of the box. The list and the tree moved the cursor a page and scrolled the
   least that showed it.
2. **All three tell a watcher that they scrolled.** `Table` notified a `VALUE` change, with its
   origin, at every scroll; the list and the tree said nothing. It is part of what freezes at
   1.0, and an application that keeps two views together or loads more rows near the end needs
   it of all three.
3. **With no height from the layout, all three are eight rows tall.** The list was six, for no
   reason on record. `setVisibleRows` is unchanged.

## 2. The shape

```java
// limn.components.internal, not exported
public final class Rows<E> {

    /** What a widget answers about its own rows. */
    public interface Host<E> {
        int count();                                   // rows as they stand now
        E mount(int index);                            // realize a row: build or rebind what shows it
        void unmount(E row, boolean heldFocus);        // release it; the host pools as it likes
        float measure(E row, int index);               // its height at the width the host lays it out at
        void place(E row, int index, float y, float height);   // put it there
        boolean holdsFocus(E row);                     // a control inside it has the keyboard
        boolean isCursor(int index);                   // the keyboard's row while the widget is focused
        float viewportHeight();                        // the rows' own box, strips and bands taken out
        float seedHeight();                            // a row's height before any is measured
        void scrolled(Change.Origin origin);           // repaint, the bar, the notification of §1.2
    }

    // the anchor and the pass
    public void layout();                              // normalize, settle a reveal, place, keep, mean
    public int anchorIndex();  public float anchorTop();
    public int placedFrom();   public int placedTo();
    public boolean isPlaced(int index);
    public @Nullable E mounted(int index);
    public float topOf(int index);                     // NaN outside the placed run

    // what the scroll bar is told
    public float contentHeight();  public float offset();
    public void scrollTo(float offset, Change.Origin origin);

    // moving
    public boolean scrollBy(float dy, Change.Origin origin);   // whether it moved
    public void reveal(int index);                     // settled by the next pass, nearest edge
    public void page(int from, int to);                // the view goes with the cursor (§1.1)

    // the data changed under it
    public void reset();                               // new rows: unmount all, back to the top
    public void refresh(@Nullable E keep, int keepIndex);      // unmount all but a row to carry over
    public void renumber(IntUnaryOperator newIndexOf); // rows inserted or removed: mounted rows follow

    // rows a drag lifted, for the two widgets that drag
    public RowSlides<E> slides();
}
```

- **`E` is whatever the widget realizes a row as.** A `Widget` for the list and the tree, the
  table's `Slot`, which holds a row's shaped texts and its widget cells and may hold no widget
  at all. The engine never asks what it is: it keeps them sorted by row and hands them back.
- **The host measures and places; the engine decides where.** The tree's indent, its sideways
  offset and its width wider than the box, the table's columns and its header band, the list's
  three ways of making a cell are all behind `mount`, `measure` and `place`. So are pooling and
  recycling: the engine says a row is no longer needed, the host does what it does with it.
- **The engine holds no selection.** The list selects by index, the table by record through a
  permutation, the tree by node. `isCursor` is the one thing the walk needs of it.
- **One instance per widget, held by a field.** No base class: the three are `final` and stay
  so, and what they share is a part, not a kind.
- **`RowSlides`** is the lifting and sliding of ADR 054 §7 as it stands in the list and the tree:
  which rows are lifted, the room and where it begins, where each row belongs and where it was
  drawn, the ticker. The widget says what is lifted and where the room is; the tree's "every
  row under a dragged node" and "no room for a drop into a node" stay in the tree.

## 3. Where they differed, and which way each goes

Taken without asking, each being one copy's correction that the others had not had. The tree's
in every case but the last.

| | List | Table | Tree | Now |
|---|---|---|---|---|
| A row to reveal that is not in sight | jumps to the top, whichever side it was on | to the foot if below, measured outside a pass; to the top if above | settled inside the pass, to the nearest edge, from measured heights; nothing if it turns out in sight | the tree's |
| A reveal asked before the first layout | an index kept, consumed after the pass by asking again | the same | the node kept and settled in the pass | the tree's, by a key the host turns into an index |
| A scroll after a reveal was asked | the reveal still lands | the same | the scroll cancels it | the tree's |
| Mean row height | every realized row, the kept ones outside the box too | the same | the placed run alone | the tree's: with a kept row of another height the other form stops the wheel short of the end (ADR 044 §3) |
| The offset told to the bar, and a drag of the thumb | clamped at the end; the thumb only at zero | the same | the offset not clamped at the end; the thumb at both | clamped at both, in both |
| A row kept outside the box | laid out at the distance the estimate gives | the same | flush against the edge it lies beyond | the tree's |
| A scroll of more than two viewports | jumps by the estimate and builds no row it passes | the same | walks | the list's and the table's: `FarScrollTest` pins it for two and the tree gains it |

Left as they are, being the widget's own and not the walk's: the list has no sideways scroll
and its wheel no second axis; a table's refresh carries the row whose control has the keyboard
over to its record, where the list and the tree let go of every row (`refresh(keep, …)` is there
for the table and the other two pass none); the look of a selected row.

## 4. What is left out

- **Selection.** Three identities, each with its reason, and ADR 045's shapes already make them
  one for a screen reader.
- **The sideways axis.** The table's columns and the tree's indent share an offset and a clamp
  and nothing else.
- **A `TreeTable`.** ADR 044 §9 names it as what the engine is owed for. It is a widget, and a
  theme of its own; this record is what makes it a matter of a host.
- **Painting.** Each widget paints its rows; the engine says which are placed and where.

## 5. How it is known to be right

Some two hundred and fifty tests stand round the three widgets, and the map names the ones that
pin each point of §3: `ListViewTest`, `ListViewFocusedRowTest`, `TableTest`,
`TableFocusedRowTest`, `TreeTest`, `TreeAccessibilityTest`, `FarScrollTest`,
`ReservedBarStripTest`, `SelectionDamageTest`, `DamageContractTest`, the two drag tests, the
demo's accessibility transcripts and the bridges' scene tests. They are the guard: a widget is
moved onto the engine with its tests unchanged, and a test changes only where §1 or §3 says the
behaviour does, in the commit that changes it and saying which row of the table it is.

The engine has tests of its own, against a host of plain numbers, for what no widget test can
reach without a scene: the walk over uneven heights, a reveal from each side, the end closed
after the last rows shrink, a far scroll that mounts nothing on the way.

The gallery's films of the list, the tree and the table are taken again and looked at. A film
found the defect §0 tells of, which no assertion had.

## 6. The order of the work, and when it is done

1. `Rows` and its tests, with no widget on it.
2. `Tree` onto it. It is the reference, so this step changes no behaviour but the far scroll.
3. `ListView` onto it, taking the rows of §3 that are the list's, and eight rows for six.
4. `Table` onto it.
5. `RowSlides`, out of the list and the tree.
6. Page keys and the scroll notification in all three (§1.1, §1.2).
7. The guide where it says what Page does and what a watcher hears; the CHANGELOG.

Each step is a commit with the whole check green. Done when the three widgets hold no anchor,
no placed range, no realized-row arrays and no slide state of their own, the tests of §5 pass,
and the three films show what they showed.

## 7. As built

Steps 1 to 4, 6 and 7 (2026-10-10), each a commit with the whole check green, 6,575 tests.

- **`Rows<E>`** is as §2 shows it, with these differences. `mount` is told how many realized
  rows come before the one it makes, which is where the widget puts the child; `shift` moves a
  realized row at once, for a scroll, which the host does with `moveChild` and no layout.
  `reveal` takes what answers the row's index when the pass comes, since a tree's rows are
  renumbered between the asking and the pass. `page(from, to)` is `pageWith`, beside
  `isWhollyInSight` and `moveViewTo`, which the tree uses in two steps: its cursor's own
  reveal scrolls at once for a row partly in sight, so the view is moved from where it stood
  before the cursor went. The placed run is kept across a refresh and a renumbering, stale
  until the next pass, as each widget kept it.
- **The tree** was the reference and changed in one thing, the far scroll. Its cells carry the
  node they draw and the name derived for them in one small class. The cursor's row is looked
  up once a pass: the tree finds a node's index by walking its rows.
- **The list** took the table's rows of §3 that were its own, and a fourth thing the record did
  not foresee: a reveal of a row already in the box must ask for no pass at all, or an arrow
  between two rows in sight repaints the whole list (`DamageContractTest` and
  `SelectionDamageTest` said so at once). The same guard is in the table.
- **The table** went onto the engine with every one of its tests unchanged. A scroll on both
  axes still repaints and notifies once.
- **Tests changed**, and only where §1 or §3 says the behaviour does: the list's default height
  (four tests, six rows to eight) and where three pages leave the view
  (`ListViewFocusedRowTest`, five expectations: the top of the box, then the foot by §3, then
  the cursor's own slot by §1.1). New: `RowsTest`, thirteen cases against a host of plain
  numbers, and `RowsPageAndScrollTest` for what the list and the tree took from the table.
- **The films** of the list, the tree and the table were taken again after step 4 and their
  last frames are what they were.
- The list, the table and the tree are 2,525, 4,573 and 4,255 lines where they were 2,783,
  4,756 and 4,490, and the engine is 708.

Step 5 (2026-10-10):

- **`RowSlides`** holds what is left of each row's slide, where each last belonged, the rows a
  drag gave back and not yet drawn, and what was drawn before the rows changed; and does the
  arithmetic and the ticking. It is not reached through `Rows` as §2 sketched but made beside
  it by the widget, with a host of its own: which rows are lifted, where the room begins, what
  a row shows (by which it is known again after a change), and where the layout put it. The
  list knows a row again by identity, two rows being free to show equal items, and the tree by
  equality, as it knows a node; the tree works out its lifted rows, every row under a dragged
  node, when it is asked.
- **The room** is one sum in one place. The list's guard (no room for the keyboard's place) and
  the tree's (none for a drop into a node) are each widget's answer to where the room begins.
- **Known to be the same** by the films: all 231 frames of the list's and the tree's, which
  move rows by the keyboard and so run the remembering, the sliding and the ticker, are what
  they were before the step, frame for frame. What a pointer's drag adds, the lifting and the
  room, no film shows, for ADR 054 §7's reason; `RowSlidesTest` holds its arithmetic against
  rows of plain numbers, and the two drag tests its outcomes.
- The list and the tree are 2,345 and 4,077 lines, and `RowSlides` 345. With the engine the
  three widgets and what they share are 12,048 lines where the three alone were 12,029: what
  was removed is 1,034 lines of the three, and what it is now written once in is 1,053, half
  of it the words that say why.

