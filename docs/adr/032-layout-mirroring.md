# ADR 032. Layout mirroring: direction is an inherited axis, not a transform

- **Status:** **Accepted and implemented, 2026-08-31.** §1's decisions are in the tree, in the
  six phases of §6. §9 records what the implementation settled, including the places this document
  turned out to be wrong; read it beside §0, because three of §0's findings do not survive contact
  and one of them is the finding the design was built on.
- **Date:** 2026-08-31
- **Scope:** how a Limn interface lays out right to left — where the direction lives, how it
  resolves, what mirrors and what does not, what the arrow keys mean, where a horizontal scroll
  starts, how an application says an icon is directional, and what a mirrored screen does to the
  test suite, the demo and the capture harness. **Text shaping and bidi ordering are not here**:
  ADR 031 shipped those, and this ADR is the item its §7.1 names as the largest thing still
  missing. Vertical writing is not here either (§4).
- **Audience:** whoever implements it. Every claim in §0 is tied to a `file:line` in this repo or
  to a measurement this document says how to reproduce. ADRs are the only place in this repository
  that may cite a line number (`docs/design/README.md`); nothing here may be copied into a design
  note or a Javadoc.

---

## 0. What was measured before anything was decided

Every count below was taken on 2026-08-31 against this worktree. Where an earlier estimate existed
it is quoted and corrected, because a wrong number that survives into a plan is worse than no
number: three of them made the work look larger than it is, and one made it look smaller.

### Finding 1: the published alignment API already reads in logical terms, and nothing else does

`Flex.MainAlignment` is `START, CENTER, END, SPACE_BETWEEN` (`Flex.java:16`), `Flex.CrossAlignment`
is `START, CENTER, END, STRETCH, BASELINE` (`Flex.java:29`), and `Label.HAlign` is
`START, CENTER, END` (`Label.java:59`). No published signature has to move for this work, and no
application source has to change to keep compiling. `Label`'s own paint already carries the note
that this is pending: *"HAlign is unmirrored on purpose: START is left in a right-to-left paragraph
too, until the mirroring ADR says otherwise"* (`Label.java:508`).

That is where the naming discipline stops. An earlier draft of this analysis said the discipline
"was done years ago"; it was done on **the enum constants and nowhere else**. Inside the widgets the
vocabulary is physical throughout: `leading` and `trailing` appear as identifiers in exactly one
widget, `TextField` (`setLeadingIcon` at `:183`, `setTrailingButton` at `:194`, `leadingInset` at
`:419`, `trailingInset` at `:439`), and every other use of those two words in `limn-toolkit` is
bidi vocabulary inside `ShapedText` and `TextEditModel` meaning the two sides of a caret. So
`TextField` is the one widget that already has the right names and the wrong implementation, which
makes it the cheapest place to prove the axis.

### Finding 2: `Insets` needs nothing, and the reason is narrower than "no asymmetric insets exist"

Six `new Insets(...)` sites in the whole repository. Three are `Insets`' own factories
(`Insets.java:6,16,21`), one is vertical-only (`ColorPicker.java:368`, `new Insets(gap, 0, 0, 0)`),
and **two are in the demo, one of which is genuinely asymmetric**: `ListScene.java:211` is
`new Insets(12, 16, 12, 12)` — the record is `(top, right, bottom, left)` (`Insets.java:4`) — with
the comment *"extra right pad for the overlay bar"*. So the claim "no asymmetric left/right anywhere
in the toolkit" is true of the toolkit and false of the repository, and the one counter-example is
exactly the mirroring-relevant kind: space reserved for an overlay scrollbar.

The stronger fact is downstream of the type. `Insets.left()` and `Insets.right()` are read in
**four places in the entire repository**: `Constraints.java:47` (which sums them, so it is
direction-blind) and `Padding.java:71,77,78`. That is the whole consumer surface. A leading/trailing
`Insets` is therefore not a migration; it is one container's `onLayout`. The migration that dominates
this work in most toolkits does not exist here — but the reason is that almost nothing uses `Insets`,
not that the sites that do were written carefully.

### Finding 3: a horizontal `Row` mirrors in one line

`Flex.onLayout` walks a cursor and places each child at `child.layoutBox(cursor, crossPos, …)`
(`Flex.java:335`). Mirroring a horizontal `Flex` is `width() - cursor - childMain` at that one
expression. `MainAlignment.END`, `SPACE_BETWEEN`, `CENTER` and the gap arithmetic all fall out
unchanged, because the cursor walk is untouched and only the final coordinate is reflected.

`Dialog` is the demonstration that Finding 1 pays for itself: its button row is
`Flex.MainAlignment.END` in a `Row` (`Dialog.java:186`), so a mirrored `Row` moves Cancel/OK to the
leading edge with **no change in `Dialog` at all**. `ToolBar.onLayout` (`ToolBar.java:141`) is the
same cursor-walk shape and takes the same one-line treatment.

### Finding 4: directional *characters* already mirror; directional *ink* is one mark

An earlier estimate said "sixteen `Path2D.moveTo` sites across five widget files". That number
conflates two unrelated methods. `ColorPicker` has ten `moveTo` calls and **none of them is a
path**: `moveTo(float t)` is the abstract "put the value at t of its range" on its `Rail` base
(`ColorPicker.java:808`). The real count: exactly four widget classes construct a `Path2D` —
`ComboBox` (`:77`), `PopupMenu` (`:660`), `Checkbox` (`:68`), `Spinner` (`:114`) — with six
`Path2D.moveTo` calls between them, plus three in chart geometry.

Of those six, one is horizontally directional. `ComboBox`'s caret (`:574,576`) and `Spinner`'s
triangles (`:791,793`) point up and down; `Checkbox`'s check (`:216`) is a tick, which no platform
mirrors. **`PopupMenu.paintArrow` (`PopupMenu.java:954`) is the only one**, and mirroring it is a
sign flip on its `back` and `tip` offsets. `PopupMenu.paintCheck` (`:946`) is an asymmetric mark too,
but it is a check, and it is drawn with `drawLine` rather than a path — worth naming so a later
sweep of `Path2D` does not read as exhaustive.

The conclusion is stronger than the count, because the shaper already does the other half. Measured
against the vendored faces at 16 pt through `ShapingRuler`: `"("` shapes to glyph **13** under an
LTR base and glyph **14** under an RTL one, and `"(a"` comes back as `13 70` against `70 14`. The
toolkit already applies Unicode bidi mirroring, so every mirrorable *character* — brackets, quotes,
comparison operators, arrows in text — is handled by ADR 031's pipeline and is not this ADR's
problem. What is left is ink the toolkit draws itself, and that is one chevron.

### Finding 5: the arrow keys are eleven decisions, not twelve files, and two of the thirteen files are not about direction at all

Thirteen files in main sources mention `Keys.LEFT` or `Keys.RIGHT`. `Scene.java:917-920` and
`MenuBar.java:400` and `Accelerator.java:64` are `Keys.LEFT_SHIFT`/`RIGHT_ALT`-style **modifier
identity**, not direction, and drop out. `Accelerator.java:188,189,207,208` is a third kind: it
renders `Keys.RIGHT` as `→` and `"Right"` for a **shortcut label**, which names a physical key.
What remains is eleven widgets that decide something on a horizontal arrow, enumerated and decided
in §1.3.

Two of them are already right. `TextField.java:796,805` and `TextArea.java:1114,1123` went visual in
ADR 031 and carry the argument at the call site; they need nothing. One of them is already
**wrong**, and not because of mirroring: `Spinner`'s inline editor calls `edit.moveLeft/moveRight`
(`Spinner.java:971,972`), which `TextEditModel` documents as *"the logical step, which is not what
the Left arrow key does once anything reorders"* (`TextEditModel.java:383`). That is a bidi caret
defect ADR 031 left behind in the one text-editing widget it did not convert, and it is found here
because this is the sweep that reads all eleven.

### Finding 6: the horizontal scroll origin is physical everywhere, and nothing persists it

`ScrollView.offsetX()` is documented *"{@code 0} at the left edge"* (`ScrollView.java:148`), the
content is placed at `child.layoutBox(-offsetX, -offsetY, …)` (`:245`), `scrollTo` clamps to
`[0, maxOffsetX()]` (`:201`), the viewport clip is `clipRect(0, 0, viewportWidth(), …)` (`:292`), and
`revealRect` (`:174`) already writes *"off the leading edge"* at `:180` while meaning left.
`TextField` (`:510`) and `TextArea` (`:422`) clamp their own `scrollX` to `[0, overflow]`
independently, and `TextArea` paints at `canvas.translate(padX - scrollX, …)` (`:827`).

`TextArea.scrollXOffset()` (`:241`) is the only horizontal scroll position on a published API, and
**nothing in this repository persists a scroll position** — no save/restore of `offsetX` in the demo,
the theme editor or the site harness. So the "persisted scroll position" the web spent years on is a
hazard this repository can still choose to avoid rather than one it has to migrate.

**The vertical scrollbar's side is four `layoutBox` calls, not one**: `ScrollView.java:258`,
`TextArea.java:470`, `ListView.java:412`, `ComboBox.java:959`. Each is `width() - thickness`. Under
`ScrollGutters.Layout.RESERVED` the viewport clip at `ScrollView.java:292` has to move with it,
which is a fifth site of a different kind.

### Finding 7: the base direction reaches the whole toolkit through exactly one line

`ShapedText.Direction.of(text, whenNeutral)` (`ShapedText.java:1549`) takes the neutral fallback as a
parameter and says why: *"the right answer there is the direction of the surrounding user interface,
which nothing in this package owns yet … this parameter is the seam where that gets fixed"*.

It has **one caller in main sources**: `TextRuler.java:108`, inside the two-argument
`shape(text, font)` default, which passes `Direction.LTR`. Every one of the sixteen `.shape(` call
sites in main sources uses that two-argument form — `Label` ×7, `TextArea` ×3, `TextField` ×2,
`MenuInk` ×1, `GlCanvas` ×2, and one in Javadoc — and **nothing in the repository calls the
three-argument overload**. So the entire toolkit's paragraph direction is decided at one line, and
that line is the seam.

### Finding 8: the base direction changes a mixed line's *width*, and the amount is a face's opinion of a space

This is the finding that changes the design, and it was not predicted. Measured through
`ShapingRuler` against the vendored faces, headless, no GL:

| text | width, LTR base | width, RTL base | delta |
| --- | --- | --- | --- |
| `"42"`, `"(42)"`, `"(...)"`, `"12:30"`, `"3.14"`, `"42%"`, `"[1] (2) {3}"`, `"<-->"`, `"«42»"`, `""` | — | — | **0.000000** for all ten |
| `"ريال "` (trailing space) | 29.9208 | 30.1120 | **+0.191250** |
| `"שלום world"` | 79.0851 | 79.4364 | **+0.351242** |
| `"Total: 42 ريال (SAR)"` | 136.2879 | 136.4792 | **+0.191254** |
| `" ريال"` (leading space) | — | — | 0.000000 |

The mechanism is exactly ADR 031 §7's seam gap, reached through a door nobody had opened. The
paragraph direction decides which bidi level a **boundary neutral** takes, that decides which run it
extends, and that decides which face resolves it. Under an LTR base the trailing space of `"ريال "`
is a separate run at level 0 and is measured in Roboto; under an RTL base it joins the Arabic run at
level 1 and is measured in Noto Sans Arabic. The space advances at 16 pt, read off the faces
themselves: **Roboto 3.968750, Noto Sans Arabic 4.160000, Noto Sans Hebrew 4.320000**. The two
deltas above are those differences to six decimal places, which is what turns this from a
correlation into a mechanism.

Two properties of it matter for the design, and they pull in opposite directions.

**It does not accumulate.** One trailing space, two, five, ten, twenty: the delta is `+0.1913` every
time, because only the *boundary* neutral changes run membership — an interior space already extends
the run it follows under either base. So this is a bounded sub-point offset, not the linear drift
that ADR 031 §7 had to defend `TextArea`'s scroll extent against. It scales with size as one would
expect: `+0.131485` at 11 pt, `+0.155390` at 13, `+0.191250` at 16, `+0.209179` at 17.5,
`+0.239063` at 20, `+0.382500` at 32.

**It is nonetheless not zero, and two caches do not know about it.** `ShapingRuler`'s memo key is
`Key(String text, Font font, ShapedText.Direction base)` (`ShapingRuler.java:80`) and is safe. But
`ShapedText.matches(text, font, ruler)` (`ShapedText.java:262`) compares text, font and epoch and
**never the direction** — so every widget holding a shaped value (`Label`, `TextField`, `TextArea`,
and `MenuInk` through the memo) would keep drawing a value shaped for yesterday's direction and be
told it is current. And `Widget.measure`'s cache is keyed on `(needsMeasure, resolvedControlSize,
constraints)` (`Widget.java:675`) with no direction in it, so a mixed-content `Label` would report a
stale size across a direction change. Both are defects this ADR creates; both are fixed in the same
change, and §1.1 is shaped by them.

### Finding 9: the inherited-axis precedent is complete, and it is the right size

`ControlSize` (ADR 002) is an axis with no numbers on it: an enum in `limn.scene`, a per-widget
declared value, a resolution chain, a process default with listeners, and one global epoch. The
resolution is `Widget.resolveControlSize()` (`Widget.java:563`): declared → parent → scene default →
host → process default, with the scene-before-host order documented as load-bearing because every
popup, menu and dialog panel is a hosted parentless root. `Widget.controlSize()` (`:538`) memoizes
against `controlSizeEpoch`, `bumpControlSizeEpoch()` (`:96`) invalidates every memo at once, and
`measure` keys its cache on the resolved value so a container's change re-measures exactly the
subtree that changed. `ControlSize.java` itself carries the two rules that matter — never read it in
a constructor, resolve it once per pass into a local.

Copying this is not a convenience. Finding 8 says the direction axis has the same three obligations
as `ControlSize` — an epoch, a memo, and a measure-cache key — for the same reason: it changes what
a widget measures to.

### Finding 10: a quarter of the test suite is horizontally sensitive, which is small enough to matter

2171 `@Test` methods across 242 test files. An earlier estimate said "~2240", and said "many of them
assert x coordinates".

Counted rather than guessed: **135 assertions naming a horizontal coordinate** (`.x()`, `caretX`,
`cursorX`, `scrollX`, `offsetX`, `localToSceneX`) across **34 files**, and **228 pointer drives
through `Scene.mouseMoved` / `Scene.mouseButton`** — each carrying an `(x, y)` — across **39 files**.
The union is **62 of 242 files**, with 11 in both.

A quarter, not "many of 2240". That is the number that makes an LTR default affordable: the 180 files
that never name an x are untouched by definition, and the 62 that do become the enumerable list of
places where a per-subtree opt-in has to be added deliberately before a mirrored assertion means
anything.

### Finding 11: the widget layer is 108 sites across 29 files, and that is the real cost

The work is not `Insets` (Finding 2) and not icons (Finding 12). It is hand-computed horizontal
geometry inside `onPaint` and `onLayout`. Counting `width() -`, `canvas.drawText(`, and `.layoutBox(`
across `limn.components` and `limn.components.chart`: **108 occurrences in 29 files**, led by
`TabbedPane` (16), `ComboBox` (13), `TextField` (8), `TextArea` (8), `Spinner` (7), `Chart` (7),
`Dialog` (6), `CartesianChart` (6) and `ScrollView` (5).

Of the 35 `canvas.drawText(` calls in those files, **31 pass a `String`** and position it at an x
computed physically — `t.fieldPadH()` for a left pad (`ComboBox.java:562`),
`x + col.w - t.menuArrowGutter() - accelW` for a right-aligned accelerator (`PopupMenu.java:897`) —
and 4 pass a held `ShapedText`. Every one of those 31 is a leading-or-trailing decision written as a
left-or-right one.

### Finding 12: icon mirroring is not the toolkit's problem, and the numbers say why

`limn-icons-tabler` ships **5130 icon constants across 44 generated enums** (the split exists because
a class initialiser is capped at 64 KB of bytecode), backed by a 3.6 MB blob. Classifying those by
hand is not a task anyone should start, and it would be the wrong task: only the code that *placed* an
icon knows whether an arrow means "back" (mirrors), "download" (does not), or is decoration in a
logo (must not). The toolkit cannot know, and a curated list would be wrong for every application
that ships its own icon.

The seam is small and already in the right shape. `Icon.paint` is a `default` method on a published
interface (`Icon.java:62`) and is *"the one idiom every component uses to draw an icon"* — six call
sites in main sources. Widgets take an `Icon` at six setters: `Label.java:325`, `Button.java:63`,
`TextField.java:183,194`, `TabbedPane.java:121,126`. So "this icon is directional" is one flag at the
use site plus one `default` overload, and no classification of anything.

### Finding 13: the demo and the capture harness key on locale, and nothing keys on direction

`Main.SCENES` lists **80 capture scenes** (`Main.java:27`), including `bidi` and `bidi-light`, and
the demo takes `--locale` (`DemoOptions.java:55`). The screenshot path already holds a capture until
the background faces fold in, gated on `localeNeedsAFallbackFace` (`Main.java:81`, consulted at
`:442`) with the defect it once had recorded in place: *"`--locale` turns ANY scene into one that
needs a fallback face"*.

The site gallery is 30 entries (`GalleryScenes.entries()`), each captured in two palettes, and it
already pins `I18n.setLocale(Locale.ENGLISH)` per entry (`Gallery.java:174`) because *"a capture run
on a machine set to another language published that language onto an English page"*. That is the
exact bug a direction axis reintroduces in a new dimension, and the fix is already written down.

The site's own locale set is `en, pt-BR, ja, zh-Hans, ko, de, fr, es, ru, zh-Hant`
(`site/src/lib/i18n.ts:9`) — no `ar`, no `he`, and no `dir=` attribute anywhere in `site/src`. The
website is not part of this work.

---

## 1. Decision

1. **Direction is an inherited per-widget axis, resolved exactly like `ControlSize`.** A new
   `limn.scene.LayoutDirection { LTR, RTL }` with a declared value per widget, a resolution chain,
   a scene default, a process default with change listeners, and one global epoch. It is **not**
   read from `I18n.locale()` anywhere, ever (§1.2).
2. **The default is `LTR`, everywhere, and mirroring is opt-in per subtree.** `processDefault()` is
   `LTR`. Nothing in the 2171 existing tests changes meaning until a test declares otherwise
   (Finding 10). An application in Arabic writes one line at its scene root.
3. **There is no mirror transform at the canvas root, and there must never be one.** Mirroring is a
   *placement* decision resolved during layout and paint. A global flip would turn correctly shaped
   text into a mirror image needing a per-run un-flip, would flip every image and every video frame,
   and would put an inverse transform on the hot path of every hit test.
4. **`Insets` stays `(top, right, bottom, left)`.** No leading/trailing variant is added. The four
   readers (Finding 2) are handled where they are: `Padding.onLayout` places the child at
   `insets.leading(dir)`, a private resolution, and `Constraints` needs nothing because it sums.
   Adding a second `Insets` shape for four call sites would cost every application a decision it
   does not have.
5. **`Stack.Alignment` gains six logical constants and keeps the nine physical ones.**
   `TOP_START`, `CENTER_START`, `BOTTOM_START` and their `_END` partners are added; `TOP_LEFT` …
   `BOTTOM_RIGHT` stay, and stay physical. Counted for this decision: the nine are named **eight
   times in main sources — seven of them `CENTER`, one `BOTTOM_RIGHT` (`WidgetsScene.java:104`) —
   and zero times anywhere in `limn-toolkit`**. Renaming would be a source-breaking change to a
   published enum in order to fix one line of demo code; adding is free, and the default stays
   `TOP_LEFT` so no existing `new Stack()` moves.
6. **`ShapedText.matches` gains the direction, and `Widget.measure` keys on it.** Both are forced by
   Finding 8: a held shaped value and a cached measurement are both wrong across a direction change,
   by a bounded but nonzero amount, and neither cache can currently see it.
7. **The neutral fallback is the resolved direction of the widget that owns the text**, passed
   explicitly. `TextRuler.java:108` keeps `LTR` — a ruler has no widget and must not guess — and
   widgets move to the three-argument `shape(text, font, base)` overload that already exists and is
   currently called from nowhere (Finding 7).
8. **The toolkit mirrors its own ink and never an application's icon.** One chevron
   (`PopupMenu.java:954`) and one new opt-in flag at the six icon setters (§1.5).

### 1.1 The axis

```java
public enum LayoutDirection {                       // limn.scene
    LTR,
    RTL;

    public static LayoutDirection processDefault();
    public static void setProcessDefault(LayoutDirection d);   // UI thread; bumps the epoch
    public static void addChangeListener(Runnable listener);
    public static void removeChangeListener(Runnable listener);
}
```

Carrying no numbers and no theme knowledge, for the same reason `ControlSize` carries none: it lets
a raw `limn.scene.layout.Row` act as a direction scope for the widgets inside it.

On `Widget`, mirroring `ControlSize`'s surface exactly:

```java
public final LayoutDirection declaredLayoutDirection();      // null when inheriting
public final LayoutDirection layoutDirection();              // resolved, never null
public final void setLayoutDirection(LayoutDirection d);     // null restores inheritance
```

and on `Scene`, `layoutDirection()` / `setLayoutDirection(…)` beside the two `controlSize` methods it
already has (`Scene.java:342,351`).

**The resolution chain is `ControlSize`'s, in the same order and for the same reason**
(`Widget.java:563`): declared → parent → scene default → host link → process default.

Two things about it are copied deliberately rather than re-derived. **The scene-before-host order**:
every popup, menu and dialog panel is a parentless hosted root, so consulting the host first would
make `Scene.setLayoutDirection` unreachable for every one of them. And **the host link is the
existing one, reused rather than duplicated**. `setControlSizeHost` (`Widget.java:617`) is public and
has five callers in main sources — `ComboBox.java:403,463`, `PopupMenu.java:281`,
`Dialog.java:528,667` — and every one of them means "this parentless panel belongs to that widget",
which is not a fact about size. A second host link that could name a different widget would be a bug
with no honest resolution. So the field is shared, the setter is renamed `setInheritanceHost`, and
`setControlSizeHost` stays as a deprecated delegate: five call sites to update inside the repository
and none outside it.

**Three obligations follow, and all three are `ControlSize`'s:**

- **One epoch, one memo.** `layoutDirection()` memoizes against a `layoutDirectionEpoch` and
  recurses one link on a miss, so a top-down pass re-memoizes the tree in O(n) links.
  `bumpControlSizeEpoch` and its direction twin stay separate counters: a direction change must not
  invalidate a size memo, and a repository that merges them cannot say which axis a re-measure was
  for.
- **`measure` keys on the resolved direction**, alongside the resolved step (`Widget.java:675`).
  Finding 8 is why: a mixed-content `Label` genuinely measures 0.19 pt wider in an RTL paragraph, and
  a size cache that cannot see the axis returns a stale one.
- **The two constructor rules are inherited verbatim.** Never read it in a constructor —
  `Widget.add` assigns the parent after the child is fully constructed, so `new Button("OK")`
  resolves to the process default whatever its eventual parent declares, and a captured direction is
  permanently wrong with no path to recovery. Resolve once per pass into a local: two resolutions
  that disagree inside one `onPaint` put the caret on one side and the selection band on the other.

### 1.2 Direction is a property of the subtree, not of the locale

`I18n.locale()` (`I18n.java:57`) is never consulted by the axis. It is the shortcut that reads as
obviously right and closes the door on the case that matters: a Hebrew UI holding an LTR code editor,
a log pane, a URL bar, a JSON viewer. Each of those is a subtree that reads left to right inside an
interface that does not, and an axis derived from a process-wide locale cannot express one.

The consequence is that an application in Arabic writes one line:

```java
scene.setLayoutDirection(LayoutDirection.RTL);
```

and a subtree that disagrees writes one more. That is the same bargain `ControlSize` makes, and the
same one ADR 006 §4 named when it deferred per-subtree locale and pointed at the `ControlSize`
inheritance chain as the shape the escape hatch would take.

The bridge to `I18n` is a **convenience, at the application's call site, not in the axis**: a static
`LayoutDirection.forLocale(Locale)` that an application may pass to `setProcessDefault`. It is a
function from a locale to a direction and it is never called by the toolkit itself.

### 1.3 The eleven arrow-key decisions

Not a blanket flip. Each one, with the reason.

| # | Site | Decision | Why |
| --- | --- | --- | --- |
| 1 | `TextField.java:796,805` | **no change** | Already visual, with the argument at the call site (ADR 031). |
| 2 | `TextArea.java:1114,1123` | **no change** | Same. |
| 3 | `Slider.java:313,314` | **mirrors** | A horizontal value axis. `Slider` has no vertical mode (no `Orientation` in the file), so Left is unambiguously the low end, and in RTL the low end is on the right. Every desktop platform does this. `HOME`/`END` stay `min`/`max`: they name the value, not a side. |
| 4 | `ColorPicker` rails, `:885,886` | **mirrors** | `Rail` is the base of `ChannelTrack` (`:588`) and `AlphaRail` (`:949`); both are horizontal tracks, and a channel value is the same kind of axis as a slider. |
| 5 | `ColorPicker` SV field, `:498,499` | **does not mirror** | The saturation/value plane is a colour space, not a reading axis: the gradient runs white → the pure hue across (`:1147`) and the marker is placed at `saturation * w` (`:1152`). Mirroring it would move the white corner, which is a picker convention, not a text direction. |
| 6 | `ColorPicker` hue ramp | **not a site at all** | An earlier estimate called the hue strip "genuinely arguable". `HueRamp` (`:1190`) is **vertical** — painted top to bottom in six bands, dragged through `trackVertical` (`:1088`) — and has no `onKeyEvent`. There is nothing here to decide. |
| 7 | `SegmentedControl.java:464,468` | **mirrors** | A strip of segments in reading order; Left must select the visually-left segment or the control disagrees with the pointer. |
| 8 | `TabbedPane.java:827,831` | **mirrors** | The same, and it is the control `SegmentedControl` is documented as modelled on. |
| 9 | `MenuBar.java:285,289` + `PopupMenu.java:1244,1253` | **mirrors, together** | These are one decision in two files: `PopupMenu` calls `onRootRight` / `onRootLeft` back into `MenuBar` to walk between menus (`:1249,1257`), and Right opens a submenu while Left closes one. A menu bar that walks one way while its submenus open the other is unusable. Both flip. |
| 10 | `RadioButton.java:244,248` | **mirrors** | `LEFT` is grouped with `UP` and the comment says why: *"a group may be laid out in a row or a column and the widget cannot see which"*. Mirroring the horizontal half is correct and costs the vertical half nothing. |
| 11 | `SplitPane.java:506,513` | **mirrors, and only for a horizontal split** | `matchesAxis` (`:537`) already refuses the keys that do not belong to the split's own axis, which is the guard that makes this safe: a vertical split is untouched. For a horizontal one the divider is a position on the reading axis, and Left must move it visually left. |
| — | `Spinner.java:939,946` (numeric) | **mirrors** | A value nudge; same class as `Slider`. |
| — | `Spinner.java:939,946` (`Mode.TIME`) | **does not mirror** | `setField(0)`/`setField(1)` select the hh and mm fields of a time drawn as `hh:mm`, and `hh:mm` is a digit run that shapes left-to-right inside an RTL paragraph. The fields do not move, so neither does the key that selects them. |
| — | `Spinner.java:971,972` (editing) | **fixed, not mirrored** | Finding 5: this calls the logical `moveLeft`/`moveRight`. It moves to `moveVisualLeft`/`moveVisualRight` against the field's own shaped line, which is what `TextField` does, and the fix is owed whether or not this ADR is approved. |
| — | `Accelerator.java:188,189,207,208` | **does not mirror** | A shortcut label names a physical key. `Ctrl+→` is the key with the arrow printed on it, and in a mirrored UI it is still that key. |

`HOME` and `END` stay logical everywhere, which is ADR 031's decision and is forced by the same
argument: `Shift+Home` must produce one contiguous range of the string.

### 1.4 The scroll origin

**`scrollX == 0` is the leading edge**, which is the right edge in RTL. `maxOffsetX()` stays a
positive magnitude and the range stays `[0, maxOffsetX()]`.

The alternative — `0` stays the left edge and the range becomes negative in RTL — was rejected, and
the reason is that the web shipped both and the bug reports were all in one direction. Under this
decision:

- Every existing clamp is unchanged in *form*: `Math.max(0, Math.min(x, max))` at
  `ScrollView.java:201`, `TextField.java:510`, `TextArea.java:422`. Only the translation that
  consumes the value knows the direction.
- "Scrolled to the start" is `0` in both directions, so a widget that resets a scroll on a content
  change (`TextArea.java:181`, `TextField.java:124`) needs no branch and cannot get it wrong.
- `revealRect`'s existing comment — *"off the leading edge: scroll back"* (`ScrollView.java:180`) —
  becomes true instead of aspirational, and its arithmetic is unchanged.
- The published `TextArea.scrollXOffset()` (`:241`) keeps its type and its range. Its Javadoc gains
  one sentence saying which edge zero is, and that sentence is the whole API change.

**The test that pins it, and it is the first thing Phase 2 writes**: a `ScrollView` in RTL whose
content is twice its viewport, asserting that at `offsetX() == 0` the child's **right** edge is at
the viewport's right edge, that `scrollBy(+10, 0)` moves the child right by 10 in scene coordinates,
and that `maxOffsetX()` is the same positive number it is under LTR. Three assertions, and they are
the difference between a decision and an intention.

The translation itself is one expression, in `ScrollView.onLayout` (`:245`):
`child.layoutBox(rtl ? viewportWidth() - childWidth + offsetX : -offsetX, -offsetY, …)` — the
*viewport* width and not `width()`, because under `ScrollGutters.Layout.RESERVED` the two differ by
the gutter, and using the box width would slide every RTL layout under the scrollbar. The same
substitution goes into `TextArea`'s paint translate (`:827`).

### 1.5 How an application says an icon is directional

A `Mirroring` enum, passed at the setter:

```java
public enum Mirroring { NEVER, IN_RTL }              // limn.graphics, beside Icon
```

`Icon.paint` gains a `default` overload taking it, which is what keeps every existing implementation
compiling (`Icon.java:62` is already `default`, so the interface has the precedent). The six widget
setters gain an overload each — `Label.java:325`, `Button.java:63`, `TextField.java:183,194`,
`TabbedPane.java:121,126` — and the existing signatures keep meaning `NEVER`.

`NEVER` is the default because a wrong `NEVER` is a back-arrow pointing the wrong way in one place,
and a wrong `IN_RTL` is every logo, brand mark, chart glyph and photograph in the application
flipped. The toolkit classifies nothing: the 5130 Tabler constants are application content
(Finding 12), and the code that placed one is the only code that knows what it means.

Mechanically it is a negative x scale about the icon's own box, applied inside `Icon.paint` where the
destination rect is already computed. It does not compose with §1's no-root-transform rule, because
it is one image and not the tree.

---

## 2. The work, file by file

**New.** `limn/scene/LayoutDirection.java` — the enum, the process default, the listener list. The
whole file is `ControlSize.java` with the numbers taken out, and it should be read against it.

**`limn/scene/Widget.java`** — the declared field, `layoutDirection()`, `declaredLayoutDirection()`,
`setLayoutDirection`, `resolveLayoutDirection()`, a second epoch and its bump, and the direction
added to `measure`'s cache key (`:675`). Roughly 60 lines, all of them structurally identical to
lines 505–640 that are already there. Plus the `setControlSizeHost` → `setInheritanceHost` rename
(`:617`, §1.1) and its five in-repository callers: `ComboBox.java:403,463`, `PopupMenu.java:281`,
`Dialog.java:528,667`.

**`limn/scene/Scene.java`** — `layoutDirection()` / `setLayoutDirection`, beside `:342,351`, and the
process-default subscription in the constructor that `ControlSize` already has.

**`limn/scene/layout/Flex.java`** — one expression at `:335` (Finding 3). Nothing else in the file
moves, and `MainAlignment`/`CrossAlignment` are untouched.

**`limn/scene/layout/Padding.java`** — `:77` places the child at the leading inset instead of
`insets.left()`; `:71,78` sum and are unchanged.

**`limn/scene/layout/Stack.java`** — six constants added to the enum (`:14`) and six arms to the `cx`
switch (`:53`). The `cy` switch is untouched.

**`limn/graphics/ShapedText.java`** — `matches` (`:262`) gains the direction it is being asked about.
This is the smallest diff in the list and the one that a reviewer must not wave through: without it
every held value in the toolkit survives a direction change silently.

**`limn/graphics/Icon.java`** — the `Mirroring` enum and one `default` overload of `paint` (`:62`).

**`limn/components/TextField.java`** — the reference conversion, because `leadingInset` (`:419`) and
`trailingInset` (`:439`) already exist and only their bodies change. Then `:263` moves to the
three-argument `shape`, `:576` composes the origin from the resolved direction, `:741,751` mirror the
hit test, and `:950` the caret position. Eight sites (Finding 11).

**`limn/components/TextArea.java`** — the same conversion, plus the scroll translate (`:827`), the
clamp's consumer (`:422`), the click mapping (`:1042,1048`) and the scrollbar side (`:470`).

**`limn/components/ScrollView.java`** — `:245` (the origin, §1.4), `:258` (the bar's side), `:292`
(the viewport clip under `RESERVED`).

**`limn/components/ListView.java` `:412`, `limn/components/ComboBox.java` `:959`** — the other two
vertical bars.

**`limn/components/PopupMenu.java`** — `paintArrow` (`:954`), the only mirrored ink; `positionRoot`
(`:739`) so the column aligns to the anchor's trailing edge and the fallback flips; `positionSubmenu`
(`:756`) so a submenu opens toward the trailing side first; the arrow gutter and the accelerator's
right-alignment (`:891,897`); and the two arrow keys (`:1244,1253`) with `MenuBar.java:285,289`.

**`limn/components/ComboBox.java`** — thirteen sites, the most after `TabbedPane`: the text clip
(`:561`), the caret's x (`:568`), the popup's anchor clamp (`:797`), the item text (`:1023`), and the
dropdown's own bar.

**`limn/components/TabbedPane.java`** — sixteen sites, the largest single file: header placement,
the overflow chevrons, the indicator, and `:827,831`.

**The rest**, each 1–7 sites and each mechanical once the axis exists: `Spinner` (7, plus the
`moveVisualLeft` fix of Finding 5), `Chart` (7), `Dialog` (6, and Finding 3 says most of them are
already free), `CartesianChart` (6), `Label` (3), `Button` (3), `SegmentedControl`, `SplitPane`,
`Slider`, `ColorPicker`, `RadioButton`, `Checkbox`, `MenuBar`, `ToolBar`, `VideoView`, `Viewport3D`,
`ColorPickerButton`, `TokenBox`, `Separator`, `ScrollBar`, `ContextMenus`, `DonutChart`,
`ListView`.

**`limn-demo`** — a `--direction ltr|rtl` option beside `--locale` (`DemoOptions.java:55`), a mirrored
capture of the kitchen sink, and `ListScene.java:211`'s asymmetric inset (Finding 2) made leading /
trailing, since it is the repository's one real example of the pattern.

**`limn-demo/site/Gallery.java`** — pin `LayoutDirection.LTR` per entry beside the
`I18n.setLocale(Locale.ENGLISH)` already at `:174`, for the reason written there.

**Docs** — `docs/design/size-axis.md` gains the direction axis beside the size one, or a
`docs/design/direction-axis.md` is written next to it; `docs/design/text-and-input.md` and
`docs/design/text-shaping.md` gain what a direction does to a held value. Neither may cite a line
number or a count.

---

## 3. Cost

- **No new dependency, no new native, no new font.** Nothing in §1 needs anything that is not
  already in `java.base` or already shipped by ADR 031. This is the cheapest large ADR in this
  repository on that axis and the most invasive on every other one.
- **One new public enum** (`LayoutDirection`), **one more** (`Mirroring`), **three new methods on
  `Widget`**, **two on `Scene`**, **six constants on `Stack.Alignment`**, **one `default` overload on
  `Icon`**, **six overloaded icon setters**, **one deprecation** (`setControlSizeHost`, delegating to
  `setInheritanceHost`), and **one changed signature**: `ShapedText.matches` gains a parameter. That
  last one is source-breaking for a caller outside this repository, and it is the only one; ADR 031
  §7 recorded the same shape of mistake in `TextField`'s display hooks and said it should have been
  stated up front rather than discovered, so it is stated up front here.
- **108 sites in 29 widget files** (Finding 11). This is the bulk of the work and it does not
  compress: each one is a judgement about whether an x is a leading edge, a trailing edge, or a
  centre.
- **A second per-widget epoch and one more field in the measure key**, both on the hot path.
  `ControlSize`'s steady-state cost is one `long` compare and one field read; this doubles that and
  adds one reference compare to the measure cache. Not measured — nothing exists to measure — and
  named here as the one performance claim this ADR does not get to make.
- **A bounded sub-point width difference across a direction change** (Finding 8): +0.19 pt per
  Arabic boundary neutral, +0.35 pt per Hebrew one, at 16 pt, not accumulating. It is a real cost
  because two caches have to learn about it, not because 0.19 pt is visible.

---

## 4. What this deliberately is not

- **Not vertical writing.** Mongolian and CJK vertical layout need a second axis through the whole
  layout system, not a direction flag. Unchanged from ADR 031 §4.
- **Not a mirror transform.** Decision 3, restated here because it is the thing a reader who skips
  §1 will assume was done.
- **Not an icon classification.** Finding 12. The toolkit ships 5130 icon constants and will not
  say what any of them mean.
- **Not the website.** `site/` has no `ar` or `he` locale and no `dir` attribute (Finding 13). The
  site is a separate document's problem if it ever has one.
- **Not a leading/trailing `Insets`.** Decision 4.
- **Not a rename of `Stack.Alignment`'s nine physical constants.** Decision 5.
- **Not per-subtree locale.** ADR 006 §4 deferred it and this ADR does not deliver it. Direction and
  language are different axes, and this one being inherited does not make the other one so. A Hebrew
  subtree inside an English UI still shows English strings; it just lays them out right to left,
  which is the honest half.
- **Not mirrored video, not mirrored `Image`.** A frame of video is content. `VideoView`'s one
  horizontal site is its message pill (`:765`), and that mirrors; the picture does not.
- **Not right-to-left *numbers*.** Arabic-Indic digit shaping is ADR 006 §4's "non-ASCII digits",
  still deferred, and it is a locale question rather than a direction one.

---

## 5. Risks and open edges

- **A held `ShapedText` outliving a direction change is the failure this ADR most likely ships**
  (Finding 8). It is invisible in a screenshot, it is a fraction of a point, and it is exactly the
  class of bug ADR 031 §5 warned about: right-looking and wrong. `matches` gaining the direction is
  the fix, and a test that flips the direction and asserts the held value is *not* `matches` is what
  keeps it fixed.
- **Two axes, two epochs, one place to conflate them.** Merging the epochs would be an obvious
  simplification and a wrong one: a theme change that bumps the size epoch would then re-shape every
  string in the process for a direction that did not move.
- **The measure cache now has three keys, and a fourth would be a smell.** If a future axis wants
  into that key, the right move is a single `resolvedAxes` value rather than a third field, and it
  should be noticed the first time rather than the second.
- **`SplitPane`'s divider is the decision most likely to be argued.** It is decided as mirroring
  (§1.3), and the honest position is that the pointer drag and the key must agree: a divider dragged
  right by the mouse and moved left by the Right arrow is worse than either convention alone. If it
  is reversed later, both halves reverse together.
- **`Spinner`'s `Mode.TIME` decision rests on a claim about how `hh:mm` shapes.** It is stated as a
  decision (§1.3) and it should be pinned by a test that shapes `"07:30"` under an RTL base and
  asserts the hours are still the leading run, rather than trusted.
- **The 62 horizontally-sensitive test files** (Finding 10) are where regression and intent become
  indistinguishable if the default is ever changed to `RTL` "just for the suite". It must not be.
  Every mirrored assertion declares its own subtree.
- **`Path2D` was the wrong thing to grep for** (Finding 4), and the next person sweeping for
  directional ink will make the same mistake. `PopupMenu.paintCheck` (`:946`) draws an asymmetric
  mark with `drawLine` and is not a path; so might something added later. The sweep is over *drawn
  asymmetric marks*, not over one API.
- **The demo is 80 scenes** (Finding 13) and mirroring one of them proves nothing about the other
  79. The capture that matters is the kitchen sink, which is the one screen carrying enough
  different widgets for a mirroring bug to have somewhere to hide.

---

## 6. The work, phase by phase

Each phase ends in something that can be demonstrated and tested on its own, and no phase leaves the
tree in a state where the default behaviour has changed.

**Phase 1 — the axis, and nothing reads it.** `LayoutDirection`, `Widget`'s new members and the
second epoch, `Scene`'s two, the measure key, and the `setInheritanceHost` rename. Tests are
`ControlSize`'s own inheritance tests rewritten for the new axis: declared beats parent, parent beats
scene, scene beats host, host beats process default, several directions coexist in one tree in one
frame, a constructor-time read resolves to the process default. **Nothing renders differently**, and
the whole suite passes untouched — which is the phase's acceptance criterion, not a hope. The one
signature that is not source-compatible, `ShapedText.matches` (Decision 6), lands here too rather
than in Phase 4 where it is first needed, because it has 17 call sites in two test classes
(`ShapedTextTest`, `ShapingRulerTest`) and updating them alongside the tests that prove the axis is
cheaper than doing it inside a phase whose diff is about something else.

**Phase 2 — the scroll origin, pinned before anything consumes it.** §1.4's three assertions, plus
`TextField` and `TextArea`'s clamps. Demonstrable as a `ScrollView` in RTL that starts at its right
edge and scrolls the right way. This is deliberately before the widget work: it is the one decision
that, taken late, has to be un-taken in five files.

**Phase 3 — the containers.** `Flex.java:335`, `Padding.java:77`, `Stack`'s six constants. At the
end of this phase a `Row` of `Button`s in an RTL subtree is in the right order with the right gaps,
and each `Button` still paints its own contents left to right. That intermediate state is ugly and it
is the honest halfway point; screenshot it.

**Phase 4 — `TextField` and `TextArea`.** The reference conversion, because the names are already
right (Finding 1) and because these two are where a direction bug is most visible: the caret, the
selection band, the placeholder, the leading icon, the trailing button, the horizontal scroll. At the
end of this phase an Arabic form is correct.

**Phase 5 — the rest of the widgets, and the one mirrored mark.** The remaining ~90 sites, the
eleven arrow-key decisions of §1.3 with a test each, `PopupMenu`'s chevron and its two placement
methods, and the four scrollbar sides. Also `Spinner`'s `moveVisualLeft` fix (Finding 5), which is
the one item here that is worth doing even if this ADR is rejected.

**Phase 6 — icons, the demo, the harness, the docs.** `Mirroring` and the six setters; `--direction`
on the demo; the mirrored kitchen-sink capture; `Gallery.java:174`'s per-entry pin extended to the
direction; the design notes. The site is untouched (§4).

---

## 7. Open questions

- **Should `Flex` mirror, or should `Row` mirror?** §1 mirrors `Flex` when `!vertical`, so a
  `Column` is untouched. The alternative is to mirror only the `Row` subclass, which reads more
  honestly but leaves a raw `Flex` constructed horizontally unmirrored. Decided as `Flex` on the
  grounds that the axis is a property of the layout and not of the class, but it is one line either
  way and it is worth ten minutes of argument at review.
- **Does `TabbedPane`'s overflow scrolling mirror its chevrons, or its content?** Sixteen sites in
  one file, and this is the only one of them that is not mechanical. Deliberately not decided here:
  it needs the widget in front of you.
- **What does a mirrored `SegmentedControl` do to a `ControlSize` audit screen?**
  `ControlSizeAuditScene` places rows of controls at fixed x offsets, and it is the one demo scene
  whose purpose is comparison across a horizontal ramp. It may need to stay LTR permanently, which
  would be the first place the opt-in default is used as a deliberate pin rather than an inheritance.
- **Is one process default enough, or does the direction want a per-`Scene` default with no process
  default at all?** `ControlSize` has both and the process default is the "compact mode" switch.
  Direction has no equivalent global gesture — an application is in one direction or the other at
  startup — so the process default may be dead weight that only exists to copy the precedent. Kept in
  §1 because a missing one is an asymmetry to explain, and flagged here because an unused knob on a
  published enum is permanent.
- **Should `LayoutDirection.forLocale` ship at all?** §1.2 makes it a convenience. It is also the
  exact function that, once it exists, someone will call from inside a widget — which is the thing
  §1.2 exists to prevent. The alternative is to write the two-line mapping in the demo and let every
  application write it too.

---

## 8. What will still not be done when this is finished

Stated now, so the document does not overclaim later.

- **Vertical writing.** §4. A second axis through the layout system.
- **Arabic-Indic and Devanagari digits.** ADR 006 §4's "non-ASCII digits", untouched. An Arabic
  interface will show `42`. *Since closed by ADR 033*: digits follow the locale's numbering
  system at format time, with a process override.
- **Per-subtree locale.** ADR 006 §4, still open. This ADR delivers a per-subtree *direction*, which
  is the shape the escape hatch would take, and does not deliver the language. *Since closed by
  ADR 035*: the locale inherits through the same chain, and the pass holds a widget's effective
  locale in scope so string lookups, formatting and digits follow the subtree.
- **Locale-aware collation and case mapping.** Unchanged from ADR 006 §4 and ADR 031 §7.1. *Since
  closed by ADR 034*: a collator and whole-string case mapping in the language the text is in.
- **Bold and italic in the four RTL and complex-script faces.** One Regular face each, unchanged
  from ADR 031 §7.1. *Since, 63f7869*: each family ships its Bold; Italic is closed as not
  applicable, because upstream publishes none for these scripts, and italic renders upright.
- ~~**Soft wrap in `TextArea`.** Unchanged from ADR 031 §7.1; `Label` is still the only widget that
  breaks lines.~~ **Done, 2026-09-01** (recorded in ADR 031 §7.1): opt-in via `setSoftWrap`, and
  §1.4 holds unchanged — wrapped, nothing overflows the reading axis, so `scrollX` sits at the
  leading edge's `0` in both directions, and every row is flush against the edge reading starts
  from, exactly as unwrapped lines are.
- ~~**A mirrored website.** Finding 13. `site/` has no RTL locale and this work gives it none.~~
  **Done, 2026-09-01** (ffc88f1): `site/` gained an Arabic catalog, and one `dir` attribute on the
  page mirrors it, on the same reasoning as §1 — direction is declared beside the language, not
  derived from it.
- **Icon classification.** Finding 12, by decision. Every one of the 5130 Tabler constants is
  `Mirroring.NEVER` until an application says otherwise, and that includes the ones whose names
  contain the word `LEFT`.
- **The `Insets` leading/trailing type.** Decision 4. If a fifth reader of `left()`/`right()` ever
  appears the decision should be re-taken; four is why it was not taken now.

---

## 9. What the implementation settled

Written after the work, against the tree it produced. §0's measurements were re-taken rather than
trusted, and the ones that moved are recorded here with what they were checked against.

The suite went from **2374 executed tests to 2719, with zero existing expectations changed** —
only the `matches` call sites gained an argument, and two comments were added. That number is the
evidence for Decision 2: an LTR default is affordable, and it stayed affordable through 26 widget
files.

### 9.1 The measurements

**Finding 8's table reproduces exactly**, to the six decimal places it quoted, and it is now
asserted rather than quoted: `"ريال "` +0.191250, `"שלום world"` +0.351242,
`"Total: 42 ريال (SAR)"` +0.191254, a leading space 0.000000, and the whole size ramp
(0.131485 / 0.155390 / 0.191250 / 0.209179 / 0.239063 / 0.382500). The mechanism is exactly as
described. That is the half of the finding the design rests on, and it held.

**Finding 8's "it does not accumulate" is wrong**, and it is the one measurement error that
mattered. Measured: one trailing space +0.191250, two +0.382500, three +0.573746, six +1.147499 —
exactly linear, one face-difference *per trailing neutral*.

The cause is a generalisation from the wrong case. The finding's reasoning — "an interior space
already extends the run it follows under either base" — is *true*, and was checked: an interior
neutral between two Arabic words measures 0.000000 either way, and so does a leading one. But a
**trailing run of neutrals sits at the paragraph's edge in its entirety**, so all of it takes the
paragraph level and all of it changes face with the base. The finding tested the boundary case and
described the interior one.

What this costs: the sentence "so this is a bounded sub-point offset, not the linear drift that
ADR 031 §7 had to defend `TextArea`'s scroll extent against" is not a claim this ADR gets to make.
It *is* a linear drift, in the number of trailing neutrals. It stays sub-point on any real line
because real lines carry a handful of trailing spaces at most, which is a different and weaker
argument than the one that was made. Nothing in §1 changes: the two caches needed the direction
either way, and they need it slightly more than the ADR thought.

**Finding 11's 108 sites across 29 files reproduces exactly** under its own instrument, and that is
the problem — see 9.2.

### 9.2 Where this document was wrong

Ordered by what each one would cost a reader who trusted it.

**1. Decision 7 is unreachable for most of the toolkit, and §2 hides it as "mechanical".**
*Found here, and since closed; what closing it took is at the end of this item.*
Decision 7 says widgets "move to the three-argument `shape(text, font, base)` overload". Only a
widget that *holds* a `ShapedText` can. `Canvas.drawText(String, x, y, font, paint)` and
`TextRuler.measure(String, Font)` take no base direction at all, and the canvas shapes through the
ruler's two-argument default, which passes `LTR`. So for every widget that draws a plain string —
`Button`, `Checkbox`, `RadioButton`, `SegmentedControl`, `Chart`, `Spinner`, `MenuBar`,
`PopupMenu`, `ComboBox`, `VideoView`, `Viewport3D`, the `Scene`'s own tooltip — honouring
Decision 7 is not passing an argument, it is converting the widget onto a held shaped line and
changing where its natural width comes from.

The cause is Finding 7: it observed that the three-argument overload is called from nowhere and
read that as an unused seam waiting for widgets, rather than as evidence that the widgets which
draw plain strings *have no seam*. Finding 11 then counted those same call sites as
leading-or-trailing *placement* decisions without noticing they are shaping decisions too. One
call, two obligations, counted once. Six implementers reached this independently.

**Closed, and the shape of the fix is the part worth carrying forward.** The route was not the one
this item's own last sentence implies and not the one §9.5 went on to propose. Neither
`TextRuler` nor `Canvas` was touched. `TextRuler` is a `@FunctionalInterface` that every test fake
in the repository satisfies with a lambda, and §3 states that `ShapedText.matches` was the only
source-breaking change this work would make; adding a direction to `measure` or to
`drawText(String, …)` would have falsified both, to buy a seam that turned out not to be needed.

What was needed instead, per widget: hold or shape the line the widget draws, with the widget's own
resolved direction as the neutral fallback, take the natural width from `line.metrics().width()`
rather than from a `measure` call, and draw that line. The width is the half that is easy to skip
and the whole of what goes wrong when it is skipped — a column sized from one shaping and painted
from another disagrees by the width of a face's opinion of a space, which is invisible until a
label sits a fraction of a point outside its gutter or a click lands on the neighbouring menu.

Three things fell out of it that were not foreseen here:

- **`MenuInk` stopped taking a ruler and a string and started taking the line.** It re-shaped to
  place its mark, which could reproduce the glyphs and could not reproduce the direction, because
  the direction is a fact about the widget and not about the string. Both its callers now hold a
  line and hand it over.
- **`Spinner`'s inline editor could not stay on prefix measurement.** The condition
  `text-and-input.md` stated for it — that the widget accepts only characters which neither join
  nor reorder — does not survive a base direction: a minus sign is a neutral at the paragraph's
  edge, so `-42` in a mirrored form is drawn `42-`. The editor moved onto `caretX`, `hitTest` and
  `selection`, which is what every other text widget already did.
- **`Home` and `End` in a mirrored `Spinner` changed sides**, and this is a behaviour change rather
  than an implementation one. They name the paragraph's two edges — the side
  `TextEditModel.moveHome` has always documented and takes `UPSTREAM` affinity for. A prefix width
  has no side at all, so the old editor could only ever produce the run's edge, which in a
  right-to-left form meant `Home` landing where `End` belongs. One assertion pinned the old
  behaviour and was changed deliberately, with the maintainer's agreement, rather than worked
  around.

**This is now closed for every widget in the toolkit.** `Checkbox` was the last one measuring its
label and handing the canvas a `String`, and it took the same conversion as the rest. Nothing in
`limn.components` draws a plain string any more, which is asserted rather than left to a grep:
`NeutralBaseShapingTest` paints each converted widget onto a canvas that overrides both
`drawText` seams and counts them apart, so a later edit reaching for the `String` form is caught
there rather than by a direction that quietly stops arriving.

`Checkbox` carried one wrinkle the others did not, and it is worth recording because it looks like
a conflict and is not. `Checkbox` and `RadioButton` are in declared lockstep — interchangeable in a
form column — and `RadioButton` places its label against a *measured* width while `Checkbox` now
places against the *shaped* line's. Those agree, and not by luck: the only text whose base the
fallback gets to decide is text with no strong character anywhere in it, and such a text takes the
paragraph's level in its entirety, so it is one run with one face resolution and one width. A width
moves only when a neutral at the paragraph's edge changes which run it extends, and that needs a
strong run to change away from — which would have decided the base itself and never consulted the
fallback. The lemma is asserted against the vendored faces, because a direction-blind fake ruler
would pass whether or not it held.

**2. Finding 11's census instrument is structurally blind, so §2's per-file counts are not a
schedule.** Counting `width() -`, `canvas.drawText(` and `.layoutBox(` cannot see a gradient's
endpoints, a `clipRect`, a `translate`, a `fillRect`, a hit test, a key handler, or a shaping call.
`ColorPicker` scores essentially zero on that instrument and has nine real sites, six of them
gradient endpoints. `SegmentedControl` is filed as "mechanical" and is a hand-rolled scrolled
viewport with an outbound origin, an inbound inverse that must flip in lockstep, two chevron zones
that swap gutters and an animated indicator. `Spinner`'s two worst sites contain none of the three
spellings. In the other direction, `Separator`, `TokenBox` and `DonutChart` are listed as having
sites and have **none**, and `Dialog` needed no change at all — which Finding 3 predicted and is
the one place the estimate was right for the right reason.

**3. Decision 8's "one chevron" is at least three.** `TabbedPane`'s PREV and NEXT chevrons and
`SegmentedControl`'s two scroll arrows are directional toolkit ink, drawn with `drawLine`. The
cause is Finding 4 sweeping `Path2D` construction; §5 already names that as the wrong instrument
("the sweep is over *drawn asymmetric marks*, not over one API") without going back and re-running
the count, so §5 and Finding 4 disagree about the same files and Decision 8 was written from the
wrong one.

Decision 8's *model* of the fix is also wrong for one of them: a sign flip on a mark's own offsets
is right for `PopupMenu`'s fixed submenu arrow and wrong for a scroll arrow in a gutter, where the
correct edit swaps which gutter each arrow occupies and leaves the ink alone. Mechanically
sign-flipping those would point both arrows into the strip.

**4. Finding 8's cache taxonomy has two holes, and the second one has no rule anywhere.** It
enumerates a cache holding a *shaped* value and one holding a *measured* size, and Decision 6 fixes
those two. Neither reaches:

- **A hand-written key that never calls `matches`.** `TextField`'s display line, `TextArea`'s line
  window and its spill, both composed lines, and `Button`'s caption key on
  `TextEditModel.textVersion()` or on the text itself, precisely because `matches` would miss its
  identity fast path and pay a character scan per line per paint. `matches` gaining a direction does
  nothing for a cache that never asks it. Every such key had to gain the direction itself.
- **A cache holding a horizontal *coordinate* across frames.** `TabbedPane`'s selected-tab indicator
  holds two physical x values behind a hand-written snap-versus-animate key; a direction flip that
  coincided with a tab change would animate the indicator across the whole reflected strip. `Chart`
  holds a hovered `ChartPoint` anchored at coordinates computed under the previous direction. This
  is a third kind of stale value and the ADR has no rule for it. Any widget animating an x is one.

**5. Decision 6 does not reach a measured width, and cannot.** `TextRuler.measure(text, font)` has
no base parameter, so keying a width cache on the direction only re-runs a direction-blind
measurement and files the same number under a second key. A widget that sizes itself from `measure`
and paints from `shape(…, base)` is measuring one line and drawing another.

**6. "Merging the epochs would be a defect" is a cost claim, not a correctness one.** §1.1 and §5
both say a merged counter would be wrong. Measured against the code, a merged counter gives every
right answer: a memo is a pure function of its inputs, so a spurious bump forces a re-resolution
that arrives at the same value, and `measure`'s key compares resolved *values* rather than epochs,
so an unchanged axis still hits its size cache. What merging costs is the re-resolution — every
widget walking one link on the next read of an axis that did not move — and the ability to say
which axis a re-measure was for. Worth two `long`s; not a defect. The justification offered ("a
theme change that bumps the size epoch") also names a bump that does not exist: no theme change
bumps that counter.

**7. §1.4's "`revealRect`'s arithmetic is unchanged" is false, by exactly one sign.** The rect
arrives in the scroller's own *physical* coordinates, so nothing the method computes knows a
direction — which is what made the claim look right. But the offset it hands back moves the content
one way in one direction and the other way in the other, so the computed `dx` needs one negation at
the end. Without it, revealing a rectangle scrolls *away* from it by exactly twice the gap;
verified by removing the flip and watching the assertion move from −40 to −60.

**8. §4's claim that `VideoView`'s message pill mirrors is wrong.** The pill's left edge is
`(width() - pillWidth) / 2` and the text is centred inside the pill, so the reflection evaluates to
the same number: there is no leading edge in the expression for a direction to act on. The cause is
classifying the site by where it sits rather than by where its x comes from. What that site
genuinely owes the axis is a shaping base, not a placement — and the site taxonomy has no category
for "owes the shaper a base, owes placement nothing", which is also the whole of `Viewport3D`.

**9. Published physical enums were never enumerated.** Finding 1 audited the published alignment
enums *by name* — `Flex.MainAlignment`, `Flex.CrossAlignment`, `Label.HAlign` — concluded "no
published signature has to move", and never reached `TabbedPane.TabAlignment{LEFT, CENTER, RIGHT}`
or `Chart.LegendPosition{LEFT, RIGHT}`. Under a mechanical mirror their signatures do not move and
their *meanings* do, which is exactly the breakage Decision 2 forbids. Both were implemented as
physical, on Decision 5's precedent; Decision 5 is written about `Stack.Alignment` specifically
rather than as a general rule, so that is an inference the next reader should not have to make.

**10. §1.3 decides per key, and handlers bundle keys per arm.** `Slider` groups `LEFT` with `DOWN`
and `RIGHT` with `UP` in single switch arms; `RadioButton` does the same. "The horizontal half
mirrors and the vertical half is untouched" was not *expressible* until those arms were split, and
flipping an arm as written would have inverted Up and Down. §1.3 reports the cost of these rows as
zero; it is a handler restructure at every such site.

§1.3 is also silent on `PAGE_UP`/`PAGE_DOWN` while deciding `HOME`/`END`, which are the same class;
and its `HOME`/`END` rule is justified entirely in text terms ("`Shift+Home` must produce one
contiguous range of the string") while being relied on by `Slider` and `SplitPane`, which hold no
string. The honest reason there is that the key names a *value* and not a side.

**11. §1.3 and §2 address sites by `file:line`, and the citations were already stale.** `Label`'s
two citations pointed at the wrong methods in this worktree before any edit was made, and every
citation an edit authorises is stale the moment it is applied. The rows should name the key and the
method, which are stable.

**12. The host link is not the path into an in-scene popup panel.** §1.1 treats it as the way the
axis reaches every popup. A `ComboBox`'s in-scene panel is *added as a child*, so resolution finds
the parent and never consults the host link at all. The same hole already existed for `ControlSize`;
this ADR did not create it and does not fix it.

**13. Live resolution is not live repaint.** A hosted root in its own scene resolves the new
direction on its next pass, but nothing marks that scene dirty when the owner's axis changes, so an
open native dropdown paints the old direction indefinitely. `ComboBox` now asks for the pass
itself. Symmetric with the size axis, and pre-existing there.

**14. §5's "if it is reversed later, both halves reverse together" describes a switch that does not
exist.** `SplitPane`'s pointer half reverses by converting a coordinate and its key half by choosing
a sign; they are independent seams in different methods and nothing couples them. What actually
holds them together is an assertion that the arrow and the drag land the divider in the same place,
which is why that test exists.

**15. The ADR scopes coordinates and not prose.** Several widgets' Javadoc asserted a physical side
as the *justification* for something — `Checkbox` and `RadioButton` both explained a damage rect by
"flush with the widget's left edge". A decision that moves an edge has to name the sentences that
describe it, or documentation quietly stops being true.

**16. `CrossAlignment` on a `Column` is a reading decision, and §2 says it is untouched.** §2's
entry for `Flex` reads "one expression … Nothing else in the file moves, and
`MainAlignment`/`CrossAlignment` are untouched." That is right for a `Row`, whose cross axis is
vertical, and wrong for a `Column`, whose cross axis *is* the reading axis. `CrossAlignment.START`
on a column of labels means the edge reading starts from; left physical it pins a whole form's text
to the left inside a right-to-left interface, which is most of what a form is.

The cause is that Finding 3 analysed `Flex` as "a horizontal `Row` mirrors in one line" and the
decision inherited that framing: a `Flex` has two axes and the direction reaches whichever of them
is horizontal, which is the main one for a `Row` and the cross one for a `Column`. Both reflections
are the same expression. `CENTER` and `STRETCH` map onto themselves under it and need no arm.

This shipped wrong and was found by looking at a mirrored capture of the list scene — the widgets
were placed correctly and every label inside them was flush with the wrong edge. It is the second
thing a mirrored screenshot is good for, after chrome drawn over the screen.

**17. `Scene`'s tooltip is absent from the ADR**, and it is the one surface a `Scene` paints itself
rather than delegating to a widget. It went out of its way to resolve the hovered anchor's
`ControlSize` live and never its direction, so the panel opened to the right of the pointer in both
directions with its text pinned to the left pad. Now reads both.

**18. Finding 6's "four `layoutBox` calls" is a list, and a list is easy to half-finish.** Two of
the four bar sides — `ListView`'s and `ComboBox`'s — went through the per-file conversion and were
done. The other two, `ScrollView`'s and `TextArea`'s, were scoped out of it because those files had
already been touched by earlier phases, and the earlier phases had done the scroll *origin* and the
text geometry rather than the bar. The bar stayed on the right in the two widgets a reviewer meets
first. Found by a human looking at the running demo, which is exactly the failure a picture catches
and arithmetic does not: every assertion about those two files was true, and none of them was about
the bar.

The fifth site Finding 6 names — the viewport clip under `RESERVED` — is worse than "of a different
kind". When the bar changes side, the *viewport itself* moves: its left edge is the gutter rather
than zero, so the content origin, the clip, the horizontal bar's clear corner and `revealRect`'s
bounds all take the viewport's left edge instead of the box's. `ScrollGutters` answers how much a
strip takes and never which side takes it, which is right — but it means every host has to resolve
the side itself, and the ADR asks four hosts to do that without saying so.

### 9.3 Where §1 held

Decisions 1, 2, 3 and 5 held without qualification, and the mirrored capture is the evidence for 3:
26 files' worth of placement decisions and not one transform.

**Decision 4 held, and its one real-world example wants it.** Finding 2's asymmetric
`Insets(12, 16, 12, 12)` in the demo reserves the extra pad for an overlay scrollbar. `Padding`
reads it as the *leading* inset, which right-to-left is the physical left — which is exactly where
the list's own bar moves to. The repository's single asymmetric inset is direction-sensitive by
nature, so the physical type was correctly not added.

**§5's `Mode.TIME` risk is closed, and the decision it doubted was right.** §5 asked that the
`hh:mm` claim be "pinned by a test that shapes `"07:30"` under an RTL base and asserts the hours are
still the leading run, rather than trusted", and noted that the widget-level test could not do it: a
fake ruler has no faces and so cannot answer a question about shaping. Measured against the vendored
faces, `"07:30"` is one run at level 2 under a right-to-left base — a European digit takes an even
level under either base, so the hours lead, the colon divides and the minutes follow, in both. The
same measurement found the counter-case the decision did *not* cover, which is a leading sign: `-42`
under the same base is one run of digits plus the sign at level 1, drawn last. Digits do not
reorder; a sign beside them does.

**One thing was added that §1 did not have**, after review: `Flex.MainAlignment` and `Label.HAlign`
gain physical `LEFT` and `RIGHT` beside their logical constants, on the precedent Decision 5 set for
`Stack`. Without them there was no way to say "physically left, whatever the direction" for a single
property, and the only escape was pinning a whole subtree — which pins the text direction along with
the edge. Additive, and no existing call site moves.

### 9.4 §7's five open questions, closed

**Should `Flex` mirror, or should `Row`?** `Flex`, as §1 chose. The reflection is one expression on
the placed coordinate guarded by `!vertical`, and a `Column` is provably untouched by it. Mirroring
only `Row` would have left a raw horizontal `Flex` — which the toolkit itself constructs — reading
the wrong way, and would have put the axis in a subclass rather than in the layout.

**Does `TabbedPane`'s overflow scrolling mirror its chevrons or its content?** Both, and the
question was already answered by §1.4 rather than needing the widget. Because the scroll offset is a
distance from the leading edge and stays a positive magnitude, the enable tests and both scroll
calls are direction-free by construction. What remains is forced: the three control boxes reflect,
because PREV must sit beside the tab it scrolls toward, and the two chevrons swap gutters so each
still points away from the strip. §7 was written before §1.4 was fixed as a decision.

**Does `ControlSizeAuditScene` have to stay LTR forever?** No, and the capture is the evidence. Every
widget in it mirrors correctly — the ramp reads the other way, which is what a mirrored audit should
show — because its rows are `Row`s and its padding is symmetric. What broke was the scene's own
annotation overlay, which pinned its per-row verdict to the physical right edge and landed on top of
the controls it annotates. The overlay reads the axis now. The general lesson is worth more than the
answer: the first thing a mirrored screen breaks is the chrome that was drawn *over* it, not the
widgets in it.

**Is one process default enough, or does direction want a per-`Scene` default only?** The process
default earns its place, and not for the reason `ControlSize`'s does. It is not a global gesture — an
application is in one direction at startup — but it is what makes `LayoutDirection.forLocale` a
one-line bridge at an application's own call site, and it is what the demo's `--direction` and the
gallery's per-entry pin both use. A per-scene default alone would have forced every capture harness
to reach into every scene.

**Should `forLocale` ship at all?** Yes, and the fear was worth taking seriously. §1.2's worry is
that once it exists someone will call it from inside a widget. What makes that acceptable is that
nothing in the toolkit calls it and a test says so, so the day someone does, it is a diff and not a
discovery. The alternative — every application writing the same script table — is worse, and the
table is not two lines: it is thirty-two right-to-left script subtags checked before the language,
because a language can be written in either and only the script says which.

### 9.5 §8 re-read against what shipped

§8's list is still accurate, with three corrections and one addition.

- **Bold and italic in the four RTL faces** and **soft wrap in `TextArea`**: unchanged, still not
  done. *(True when written; soft wrap has since closed, 2026-09-01 — §8's entry records it.)*
- **Vertical writing, Arabic-Indic digits, per-subtree locale, collation**: unchanged. *(True when
  written; digits, per-subtree locale and collation have since closed under ADRs 033, 035 and 034,
  and §8's entries say so.)*
- ~~**A mirrored website**: unchanged, and the gallery now pins `LTR` per entry so it stays that way
  deliberately rather than by luck.~~ **Done, 2026-09-01** (ffc88f1), and the gallery still pins
  `LTR` per entry: the site reading right to left and its captures reading left to right are two
  decisions, and the second was taken here on purpose.
- **Icon classification**: correct as written. All six setters named in §1.5 gained their overload
  and every icon is `NEVER` until a call site says otherwise.
- **The `Insets` leading/trailing type**: correct, and 9.3 strengthens the reason.
- **New, and since corrected:** the toolkit's **two text paths** are not both direction-aware.
  Everything that holds a shaped line takes a base; everything that draws through
  `Canvas.drawText(String, …)` or sizes through `TextRuler.measure` resolves its paragraph
  direction with a hard-coded left-to-right fallback.

  What this bullet went on to say was wrong, and wrong in a way that would have cost a later reader
  a much larger change than the one that was needed: *"closing it means giving those two signatures
  a direction."* It does not, and it must not. `TextRuler` is a `@FunctionalInterface` satisfied by
  a lambda in every test fake in the repository, and §3 commits to `ShapedText.matches` being the
  only source-breaking change this work makes. Both signatures are unchanged and both stayed
  unchanged while every widget in §9.2's list but one was converted.

  **What is actually left is the seam and not the widgets.** A widget added later that reaches for
  `Canvas.drawText(String, …)` or sizes itself from `TextRuler.measure` gets a left-to-right
  paragraph for any string with no strong character in it — a count, a year, a price — with nothing
  to warn it. That is a default that is right almost always and silent when it is not, which is the
  same shape of trap Finding 7 fell into.

  **Since closed, by the compile-time push.** `Widget` now carries the pair the converted widgets
  had each grown privately: `neutralBase()`, the widget's resolved direction as the shaper's
  fallback, and `shapeText(text, font)`, the blessed way to a line. The private copies of
  `neutralBase()` were absorbed into it — hoisting a `final` method makes redeclaring the
  signature a compile error, so the dedup was forced rather than hoped for — and the Javadoc of
  both raw signatures now says what they cannot carry and where the answer lives. The raw calls
  still compile; a widget determined to draw a plain string can. What changed is that the right
  way is now also the short way, it is one named method a review can ask for, and a test pins
  that a widget with no idiom of its own gets its own direction by calling it. Neither
  `TextRuler` nor `Canvas` changed shape, which is the constraint the whole route was chosen
  under.

### 9.6 Noticed, and not fixed with the rest

What the work found beside its own path and deliberately did not fold in. Each entry says where
it stands now; the ones since closed were closed in their own changes, after this document was
first written, so their fixes could be judged alone.

- **`ShapedText.matches` treats epoch 0 as current under every ruler**, and `TextRuler.NONE` — what
  a detached widget gets — stamps 0 and measures everything as zero-width. A widget whose first
  shaping happened while detached holds a zero-width line that every real ruler afterwards certifies
  as current. Pre-existing, from ADR 031's held-value idiom, and reachable by any test that measures
  a detached tree. *Since closed:* `NONE` now stamps a reserved epoch of its own — negative, so it
  collides neither with the 0 a fake inherits nor with the process-wide counter, which starts at 1.
  The epoch-0 exemption stays what it was, for fakes and for unstamped geometry; a line shaped while
  detached is still current under `NONE` itself, so a detached widget shapes once, and goes stale
  under the first real ruler, which is the moment a right answer exists. The test shapes through
  `NONE` and asserts both halves.
- **`Scene.windowClosed` unsubscribes three of its four global listeners**, omitting `I18n`. The new
  axis is handled; the omission is pre-existing and is the exact symmetry the axis review looked for.
  *Since closed:* the fourth removal now stands beside the other three, and a test closes a bound
  scene and asserts a locale change no longer wakes it.
- **A memo resolved inside `onDetached` survives the detach as a stale answer**, on both axes: the
  scene funnel bumps up front and the scene field is cleared afterwards, with nothing bumping
  between. Latent — no `onDetached` in the repository reads either axis. *Since closed:* the
  funnel's detaching branch stamps both memos never-current once the field clears, and a lifecycle
  test reads both axes inside `onDetached` and asserts the detached widget then resolves as a fresh
  one would.
- **`ContextMenus.showForFocus` has two candidate widgets** and can only give the popup one. It
  takes the focused widget's direction for the anchor point while the cascade's own growth still
  comes from the region, so a right-to-left field inside a left-to-right region opens away from
  itself. Correct whenever the two agree, which is every ordinary tree. *Since closed:* the popup
  is anchored on the same widget the corner comes from, so its growth, its step and its corner are
  one answer; the mirroring test that had pinned the divergence — deliberately, as a recorded
  defect — was rewritten with it.
- **The demo's bottom statistics bar does not mirror.** It is demo chrome and outside §2's file
  list. *Since closed:* mirrored anyway, as a maintainer's call — the demo is the toolkit's shop
  window, and a bar that ignored the axis under a picker that flips it read as a bug report waiting
  to be filed.
