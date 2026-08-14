# Theming

`Theme`, `Theme.Builder`, `Theme.Token`, `ThemeFormat`, `ColorPickerButton`, and the
`limn-theme-editor` module. Why a palette is shaped the way it is, and the failure modes
that are not obvious from the Javadoc.

Decisions and the alternatives weighed live in
[ADR 029](../adr/029-a-palette-is-a-value-and-the-screen-that-builds-one-is-a-module.md);
this is what a contributor needs before changing any of it.

## A palette is colour plus one metric, and the boundary is load-bearing

Colours, a name, a mode, and `cornerScale`. No spacing and no type: those come
from the process-wide `SizeTokens` table and are identical in every palette.

**Why a radius gets across the boundary and nothing else does.** `setCurrent` assigns a
volatile field and notifies nobody, so a palette may only carry a metric nothing *measures*
from. A radius is the only one: no `onMeasure`, `onLayout`, `baselineOffset` or
`paintOutset` in the toolkit reads one. `ThemeShapeTest.shapeMovesNoWidgetByOnePoint` lays a
tree of ~20 widget types out at scale 0 and scale 3, at all five size steps, and demands
identical boxes; `shapeMovesTheInkItIsSupposedTo` demands that the ink *did* move, so a scale
that quietly did nothing would not pass either.

A palette at the default scale returns the shared table itself (`assertSame`), so nothing
about identity or the backend's font memo changed for the fifteen built-ins.

**A radius is only ever read through the size row.** `Theme` carried `radiusSmall/Medium/Large`
once; they were compile-time constants, so they could not follow `cornerScale` and were the wrong
number on any shaped palette. They were removed rather than deprecated; `tokens(step).radiusMedium()`
is the only read. The `spacingSmall/Medium/Large` trio stays a constant on purpose: nothing scales
spacing, so inlining it is still correct, and the static initializer at the foot of `Theme` fails
class init if a literal ever drifts from the `MEDIUM` row.

Two things depend on the rest of the boundary, and both break quietly if it stops being true:

- **A theme switch is a repaint, not a relayout.** `ThemeEditor.refreshScene` invalidates
  the scene root instead of marking it for layout, and it runs on *every frame of a colour
  drag*. Marking layout there would re-measure the whole tree sixty times a second. If a
  palette ever gains a metric, that line has to become `markNeedsLayout` **and** the drag
  has to be throttled. One without the other is a stutter or a wrong measurement.
- **The six float tokens are compile-time constants.** `spacingLarge` and its siblings are
  JLS 4.12.4 constant variables, so `theme.spacingLarge` compiles
  to `ldc 20.0f` in this repository *and in every application built against it*. They
  cannot be overridden, subclassed around, or rebased onto `SizeTokens` without changing
  every consumer's bytecode. The static initializer in `Theme` checks them against
  `SizeTokens.MEDIUM` so drift is a hard failure at class init rather than a silent 2pt
  offset.

`ThemeBuilderTest.aCustomPaletteMeasuresLikeEveryOther` is what keeps the first of those
true. It is not a smoke test; it is the guarantee the repaint optimisation rests on.

## Eight bits, and why the derived palettes moved

`Theme.Builder` rounds every channel to `n/255` on the way in.

Without it, `ThemeFormat` is lossy: `primary.lerp(WHITE, 0.16f)` lands between two 8-bit
values, `toHex` rounds, and the palette that comes back from a file is not the one that
went in. Then `Theme.equals` (which the editor's "has this changed" check and every
round-trip test rest on) reports a palette as modified purely for having been saved.

The cost was one 8-bit step on the derived tones of the eleven `make`-built palettes.
Nothing asserts their hex values; the two palettes that *are* solved for exact contrast
targets (`Theme.limn`, `Theme.limnLight`) were already on the grid, because every tone in
them was written as a literal.

`ThemeBuilderTest.everyToneLandsOnTheEightBitGrid` asserts it for every built-in and for an
interpolated palette.

## The token enum is what stops a tone from disappearing

Three consumers iterate `Theme.Token` rather than naming every tone: `ThemeFormat`,
`ThemeEditor`'s rows, and `ThemeAudit`. **None of them fails to compile when a tone is added
to `Theme`**; they just quietly stop covering it. A palette would round-trip without its
new colour, the editor would have no row for it, and the audit would never measure it.

Two tests hold the line, and both belong to that decision rather than to their own files:

- `ThemeBuilderTest.everyColourFieldHasAToken`: reflection over `Theme`'s public `Color`
  fields against `Token.values()`, in both directions.
- `ThemeEditorTest.everyToneIsEditable` and `everyToneHasACaptionOfItsOwn`: the editor has
  a row and a caption for each.

Adding a tone to `Theme` therefore means: the field, the `Token` constant, and a caption in
`ThemeEditorStrings`. The tests name the two you forgot.

## `scrim` is the one tone with an alpha, and everything else assumes there is none

Every other tone is a surface or an ink drawn on one, and both the audit's contrast maths
and the editor's colour wells are built on that: a translucent tone composites against
whatever happens to be behind it, which is exactly what a palette cannot know, so
`ThemeEditor.tokenRow` turns alpha *off* for every well but the scrim's.

The scrim is the exception because compositing is its whole job: it is the veil an in-scene
modal paints over the window, and its alpha is the strength of the dimming. Consequences
worth knowing before touching it:

- **It is the only tone the audit measures alone,** and `ThemeAudit.Finding` is shaped by it:
  `Metric.isPairwise()` decides whether a finding names an `against` at all, the compact
  constructor refuses either mismatch, and `Bound` exists because this is the first rule with
  a ceiling. Naming a second tone anyway (the subject twice, or whichever surface looks
  likely) would state a comparison nobody made, and the next reader acts on it.
- **Both ends are a WARNING, never an ERROR or an INFO.** WCAG has no bar for a modal veil:
  an error would set a number this project invented beside the guideline's, and INFO is for a
  bar the guideline *exempts*; it has not exempted this one, it has never considered it.
- **The bars are steps of 1/255, not fractions of 1.** A palette snaps every channel onto that
  grid, so an alpha line set to 35% lands on 89 and never on 0.35. A bar written as `0.35`
  is one the author's own control cannot reach: they would type the number the report asked
  for and the finding would stay. `ThemeAudit` and `ThemeContrastTest` both hold the same two
  steps.
- **A composite bar is the wrong test and looks like the right one.** Veiling the canvas and
  measuring how far it moved reports zero for High Contrast, whose canvas is already black
  and which ships the *heaviest* veil of the fifteen. What its veil dims is the ink.
  `ThemeAuditTest.theHeaviestShippedVeilIsCleanAndACompositeWouldCallItNothing` pins the
  premise and the verdict, so the "improvement" cannot be made quietly.
- **A window blocked by a native modal is dimmed by `Scene`, not by this.** That veil is in
  `limn-toolkit`, which cannot see a palette at all, so the two are alike by hand and the
  scrim token does not move it.

## The derivations are the shipped ramps

`deriveAccentStates`, `deriveDisabled` and `deriveSemanticStates` are not conveniences
bolted on for the editor: `make` is written in terms of them, so the eleven derived
built-ins *are* their output.
`ThemeBuilderTest.theShippedRampsAreTheDocumentedDerivations` asserts that palette by
palette. Change one of those expressions and eleven palettes change with it, loudly.

The four hand-solved palettes (`Light`, `Dark`, `Limn`, `Limn Light`) deliberately do not
use them, and `theHandSolvedRampsAreNotWhatTheDerivationWouldProduce` pins why: the generic
20%-toward-black pressed tone drops `Limn`'s dark label under 4.5:1 against its own light
accent. Moving those palettes onto `make` would reintroduce exactly that.

Each `derive` reads the tones set **before** it and never re-runs. Change the accent
afterwards and the ramp stays where the previous accent put it, which is why the editor
re-derives on a button rather than on every edit.

## The audit measures; it does not have taste

`ThemeAudit` returns findings at three levels. The levels are the guideline's, not a
severity scale invented here:

- **ERROR**: a WCAG 2.1 bar for text (4.5:1). Something is hard to read.
- **WARNING**: a bar for non-text (3:1), an elevation step, or a distinctness rule.
- **INFO**: below a bar the guideline *exempts*. Disabled text is the only one today, and
  reporting it as an error would teach a palette author to make a switched-off control
  shout.

Elevation is measured in CIE L*, never in contrast ratio. The step `Limn` uses between its
canvas and its cards is 1.46:1; reproducing that ratio from a near-white canvas would
require a luminance above 1, so the ratio calls two palettes that read identically wildly
different. `ColorContrastTest.elevationIsVisibleInLightnessAndInvisibleInTheRatio` pins it.

Two rules exist that no contrast check would find on its own:

- **focusRing against primary.** A ring the same colour as the accent vanishes the moment
  it lands on an accent-filled control (a selected segment, a primary button), which is
  where a keyboard user most needs it. Eight of the shipped palettes are built that way.
- **primaryHover against primary.** A hover state that cannot be told from rest is a button
  that does not answer.

`ThemeAuditTest` deliberately does **not** assert the shipped palettes pass. Several miss a
bar; that is a known finding about those palettes, not about the audit, and a test pinning
today's list would need editing every time one is corrected. It asserts one synthetic
palette per rule, plus that the two palettes solved for these bars carry no failing text.

## ColorPickerButton: cancel is a change

The button's colour is the single source of truth, and `onChange` fires on every move of
the picker **and once more with the previous colour when the dialog is dismissed**. A
caller that simply applies what it is handed is correct with no bookkeeping. That is the
whole reason the listener fires on the way back, and the difference between this and the
hand-rolled swatch-plus-button it replaced.

`setColor` does *not* notify: the application is the source of that change, and echoing it
back invites a loop through a listener that writes state.

The caption defaults to the colour's hex and follows it. `apply` marks layout only when the
hex's **length** changes (`#RRGGBB` gaining an alpha pair), because it runs on every frame
of a drag; marking layout unconditionally is how a colour drag turns into a full relayout
per frame.

## The editor's own traps

- **The builder is replaced, not mutated, by `setTheme` and paste.** The derive buttons are
  built once and must therefore name a derivation rather than capture the builder. A button
  holding the original would derive into a palette nobody is editing, and the tone on
  screen would not move. `aDerivationRunsOnTheBuilderTheEditorIsCurrentlyHolding` pins it.
- **`syncing` guards the round trip** both ways: writing a control from the builder must not
  be read back as an edit, and the controls do not agree on whether writing one notifies;
  `TextField.setText` and `ComboBox.setSelectedIndex` echo back through their listeners,
  `Slider.setValue` does not. The guard is what makes the editor correct either way, so it
  stays even where today's widget happens to be silent.
- **The "start from" index is held, not derived.** Picking a base keeps the user's *name*, so
  the result never equals the built-in it came from; deriving the combo's selection with
  `indexOf` answered -1 and snapped the control back to the first entry on every pick; the
  palette changed and the control denied it. `theBaseComboKeepsShowingWhatWasPicked` pins it.
- **The report is rebuilt only when the verdict moves.** Findings are records, so list
  equality is the check. Rebuilding thirty labels per drag frame would put a tree edit in
  the middle of a gesture.
- **Both `ScrollView`s are vertical-only.** One that also scrolls sideways hands its child
  an unbounded width, and a `Label` given unbounded width never wraps.
- **`ThemePreview` paints itself.** A preview built out of real widgets would show
  `Theme.current()`: either the palette being edited (saying nothing the window does not)
  or the wrong one entirely. It is a swatch board with the shape of a window, held simple
  on purpose so a change to a component's paint cannot make it a lie.
- **`detachLive` is not automatic.** A widget cannot tell being removed from a scene apart
  from being moved to another one, so an application that opens the editor and wants the
  palette back has to say so.

## Where the strings live

The three words the picker dialog needs (*Colour*, *OK*, *Cancel*) are in
`ComponentStrings` and translated into all nineteen shipped locales, because they are
component chrome and this is the first place the toolkit itself has had to supply them (a
`Dialog` an application builds brings its own captions).

`limn-theme-editor` declares its vocabulary in `ThemeEditorStrings` and ships **no**
translation, unlike every other module. That is a decision with a reason attached in that
file: what the editor names are the tokens of a design system, and a palette author who
reads `surfaceRaised` in the API is not helped by a screen that calls it something else.
Every string is still a key, so an application that disagrees can translate all of them.
