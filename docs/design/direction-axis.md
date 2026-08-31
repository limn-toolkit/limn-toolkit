# The direction axis

Background for `LayoutDirection` and what mirroring a Limn interface actually consists of. The
decision, with the alternatives that were weighed, is ADR 032; this file holds what a contributor
needs before changing anything that has a horizontal coordinate in it.

Read [size-axis.md](size-axis.md) first. The two axes are deliberately the same machine, and most
of what is true of one is true of the other for the same reason.

## Direction is a placement decision, not a transform

There is no mirror transform at the canvas root, at a widget root, or "just for this subtree", and
there must never be one. Every widget that owns a horizontal coordinate decides, at the point it
computes that coordinate, whether the number means *where reading starts*, *where reading ends*, or
*the middle*.

A global flip is the obvious cheap answer and it is wrong four times over: it turns correctly shaped
text into a mirror image needing a per-run un-flip, it flips every image and every video frame, it
puts an inverse transform on the hot path of every hit test, and it gives no way to hold one subtree
the other way round. The last is not a corner case — a right-to-left interface containing a
left-to-right code editor, log pane, URL bar or JSON viewer is the normal shape of the problem.

The one legal negative scale in the toolkit is inside `Icon.paint`, acts on a single image inside
its own box, and is opt-in per call site.

## It is not the locale

Nothing in the toolkit consults `I18n.locale()` to decide a direction. Language and direction are
different axes: a Hebrew subtree inside an English interface still shows English strings, it just
lays them out right to left. `LayoutDirection.forLocale` exists so that an *application* can bridge
the two at its own call site, typically once at startup. A widget that calls it has smuggled the
process-wide locale back in as a source of direction, which is the one thing the axis exists to keep
out.

## The resolution chain, and the two rules that come with it

Declared, else the nearest declaring ancestor's, else the scene's default, else the host link's,
else the process default — `ControlSize`'s chain, in the same order.

The scene default is consulted **before** the host link, and the order is load-bearing: every popup,
menu and dialog panel is a parentless hosted root, so consulting the host first would make
`Scene.setLayoutDirection` unreachable for all of them.

The host link is **shared** between the two axes and is named `setInheritanceHost` for that reason.
It says "this parentless panel belongs to that widget", which is a fact about neither size nor
direction. A second link that could name a different widget per axis would be a bug with no honest
resolution.

Two rules are inherited verbatim, and both are the size axis's:

**Never resolve a direction in a constructor.** `Widget.add` assigns the parent after the child is
fully constructed, so a widget resolves to the process default whatever its eventual parent
declares, and a captured direction is permanently wrong with no path to recovery.

**Resolve it once per pass into a local.** Two resolutions that disagree inside one `onPaint` put
the caret on one side and the selection band on the other.

## Two epochs, and why merging them would be a defect

Each axis memoizes its resolution against its own global epoch counter. Merging them is the obvious
simplification and the wrong one: a theme change that bumps the size epoch would then re-shape every
string in the process for a direction that did not move, and one counter cannot say which axis a
re-measure was for.

`Widget.measure` keys its cache on the resolved step **and** the resolved direction. A third axis
wanting into that key is the smell: the right move then is a single resolved-axes value rather than
a fourth field.

## What a direction does to a held value

This is the part that is easy to miss, because it is invisible in a screenshot.

A paragraph's base direction decides which bidi level a **boundary neutral** takes, which decides
which run that neutral extends, which decides which face measures it. A line of mixed content is
therefore genuinely a fraction of a point wider in one direction than the other — the difference
between two faces' opinion of a space. It is bounded by the number of neutrals at the paragraph's *edge*: a
trailing run of spaces all sits at that edge, so all of it changes face with the base and the
difference is one face-difference per trailing neutral. What genuinely does not move is an
*interior* neutral, which already extends the run it follows under either base, and a leading one.
Real lines carry a handful of trailing neutrals at most, which is why the effect stays small — but
it is linear in them, not constant.

So a held `ShapedText` and a cached measurement both go stale across a direction change.
`ShapedText.matches` takes the direction for exactly this reason.

**But `matches` is not the whole story, and assuming it is will ship the bug.** Several widgets hold
shaped lines behind hand-written cache keys rather than calling `matches` at all, because
`model.text()` and `model.lineText(i)` build a fresh `String` on every call and `matches` would miss
its identity fast path and pay a character scan per line per paint. Every such key has to carry the
direction itself. If you add a hand-written shaping cache, put the direction in its key, and prove
it with a test that flips the direction and asserts the held value is not current.

## The neutral fallback, and what it is not

A widget passes its own resolved direction to the shaper as the **neutral fallback**, not as an
imposed paragraph direction:

```java
ShapedText.Direction base = ShapedText.Direction.of(text, neutralBase());
ShapedText line = ruler.shape(text, font, base);
```

The first-strong rule still decides everything a strong character can decide, so a Latin string in
an Arabic form still reads left to right, and a Hebrew string in an English form still reads right
to left. What the widget supplies is the answer for the case the rule cannot decide: a phone number,
a price, a bare count — text with no strong character at all, which takes the direction of the
interface around it.

A consequence worth knowing before it surprises you: a direction change drops and re-shapes held
lines whose *resolved* base did not actually move, because the key holds the fallback rather than
the outcome. A direction change already relayouts the tree, so this is paid once at a moment that is
rare and is already expensive.

## Where a horizontal scroll starts

`scrollX == 0` is the **leading** edge, which is the right edge in a right-to-left subtree, and the
range stays `[0, max]` with the extent a positive magnitude. The alternative — zero stays the left
edge and the range goes negative — was rejected because the web shipped both and the bug reports
were all in one direction.

The consequences are what make it worth the trouble. Every clamp keeps its form, so a widget that
resets a scroll on a content change needs no branch and cannot get it wrong. "Scrolled to the start"
is zero either way. Only the expression that turns the offset into a coordinate knows a direction.

One trap, and it bit during implementation: a routine that receives a rectangle in **physical**
coordinates and answers with a **scroll offset** is not direction-free even though nothing it
computes mentions a direction. The offset moves the content one way in one direction and the other
way in the other, so the answer needs one sign flip at the end. `ScrollView.revealRect` is the case;
without the flip, revealing a rectangle scrolls away from it by exactly twice the gap.

## Two vocabularies, kept apart on purpose

Some placements are about reading order and some are about the box. Both are expressible, and which
one a name belongs to is a decision the author makes:

- `Flex`'s `MainAlignment` and `Label`'s `HAlign` are `START` / `CENTER` / `END` and are **logical**:
  they follow the direction.
- `Stack.Alignment` carries both. Its nine physical corners (`TOP_LEFT` … `BOTTOM_RIGHT`) name a
  side of the box and keep naming it whatever the subtree reads; its six logical constants
  (`TOP_START` … `BOTTOM_END`) follow the direction. `TOP_LEFT` remains the default.
- `Insets` stays physical, `(top, right, bottom, left)`. `Padding` resolves the leading side itself,
  because measurement only ever sums the two and a second inset type would cost every application a
  decision it does not have.

The gap in this, stated rather than hidden: outside `Stack` there is no way to say "physically left,
whatever the direction" for a single property. The escape hatch is to pin the subtree with
`setLayoutDirection`, which pins the text direction along with the edge. That is deliberate for now
and it is the first thing to revisit if a real case needs the finer knob.

## What mirrors, and what deliberately does not

Mirroring is decided per site, and several sites are decided *not* to move. Each of these is a
decision with a reason, not an oversight to improve on:

- A **check mark** does not mirror. It is a tick, and no platform mirrors one.
- A **saturation/value colour field** does not mirror. It is a colour space, not a reading axis, and
  moving the white corner would break a picker convention.
- A **vertical** ramp, rail or split is not a site at all.
- A **time spinner's** `hh`/`mm` fields do not move, because `hh:mm` is a digit run that shapes left
  to right inside a right-to-left paragraph; so the key that selects them does not move either.
- An **accelerator label** does not mirror. `Ctrl+→` names the key with the arrow printed on it, and
  in a mirrored interface it is still that key.
- **Video frames, images and 3D renders** are content. A rendered scene that mirrored would have its
  handedness reversed.
- **`HOME` and `END`** stay logical everywhere, so `Shift+Home` produces one contiguous range of the
  string.

Directional *characters* need nothing from any of this: the shaper already applies Unicode bidi
mirroring, so a bracket already comes back as a different glyph under a right-to-left base. Never
add a bracket flip anywhere.

## An icon is the application's word, not the toolkit's

`Icon.Mirroring` is `NEVER` by default and is passed at the use site. The toolkit classifies
nothing, because only the code that placed an icon knows whether its arrow means "back" (mirrors),
"download" (does not), or is a shape inside a logo (must not). A curated list of which glyphs in a
pack are directional would be wrong for every application shipping its own pack.

The default is asymmetric on purpose: a wrong `NEVER` is one back-arrow pointing the wrong way in
one place, and a wrong `IN_RTL` is every logo, brand mark, chart glyph and photograph in the
application flipped.

## Testing it

A screenshot is the wrong instrument for bidi correctness — the failures are fractions of a point
and look right — and the right instrument for a layout that is inside out. So:

- Assert mirrored geometry as **arithmetic**, against a deterministic ruler, with no native and no
  GPU. Logical order in, expected visual positions out.
- Assert the **unchanged** LTR case beside it, every time. Most of the test suite is the safety net
  for the default, and a mirrored assertion means nothing without it.
- Assert the decisions that say **does not mirror**, so a later sweep cannot quietly mirror them.
- Take the screenshot as well, of a screen carrying enough different widgets that a mirroring bug
  has somewhere to hide.
