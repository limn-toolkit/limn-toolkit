# Charts

`limn.components.chart`: `Chart` and its three shapes (`BarChart`, `LineChart`,
`DonutChart`) plus `ChartSeries`, `ChartAxis`, `ChartPalette`, `ChartPoint` and
`ChartFormats`. Read this before changing any of them; the invariants below are the ones
the Javadoc deliberately leaves out because they are about how the pieces fit rather than
about what one method promises.

## The shape of the thing

A chart is **one widget that paints everything**. There is no scene graph of marks, no
per-datum widget, and only one exception to "no children at all": `DonutChart.setCenter`,
which puts a real widget in the hole (see below).

```
Chart              data, palette, title, legend, tooltip, click, animation
├── CartesianChart scale, gutters, grid, axis labels, category ↔ pointer
│   ├── BarChart   slots, stacking, rounded ends
│   └── LineChart  runs, curves, area fills, markers
└── DonutChart     arcs, the hole, per-slice visibility
```

Three abstract hooks carry the whole subclass contract: `paintContent` (or `paintMarks`
below the Cartesian layer), `pickAt` (which datum is under this point) and (for the
Cartesian pair) `pointOf(series, category)`, which is what makes a tooltip row and a hover
report agree without either knowing how the other drew.

## Two rules that everything else hangs off

**1. The geometry itself is recomputed on both paths; only its inputs are cached.**
`layoutRegions` (title/legend/plot split) and `layoutPlot` (gutters, plot rect) run at the
top of *both* `onPaint` and the pointer path, and neither result is carried between them.
That looks redundant and is not: a hover that resolved a different plot rectangle than the
frame the user aimed at reports a different bar than the one under the cursor, and the
failure is invisible in a screenshot.

What *is* cached is the expensive input each of them reads (the resolved scale, the widest
tick label, the widest category label) against an explicit key (data generation, UI
language, font instance). The distinction is the whole safety argument: those inputs change
only when something bumps the key, while the rectangles depend on the widget's box, which
changes without telling anyone. Cache a rectangle and a stale key becomes a wrong answer;
cache a measurement and a stale key becomes a stale number that its own key already covers.

**2. The scale reads the data, the marks read the animation.**
`resolveScale` uses `ChartSeries.value(i)`, what the application set. Every mark uses
`drawnValue(series, i)`, the animated in-between. If the scale followed the animation, the
axis would slide under bars growing into it and every tick label would change on every frame.

## The animation

One `Transition` per chart, 0 → 1, driving *value interpolation* rather than pixel
interpolation:

```
drawn(i) = from[i] + (target(i) - from[i]) * progress(i)
```

- `from == null` means "start from the axis baseline": the first appearance grows out of
  the axis rather than fading in at a random height.
- Every mutation that moves a mark calls `beginDataChange()` **before** it mutates, which
  snapshots the currently drawn values into `from`, and `endDataChange()` after. Snapshot
  after the mutation and the snapshot is the destination: the chart jumps.
- A hidden series targets the baseline rather than its own value. That is what makes a
  stacked column re-flow (the segment shrinks to nothing while what was above it slides
  down) instead of punching a hole.
- `progress()` returns 1 whenever the duration is 0 **or the chart has no scene**. That is
  the headless guarantee: a test, a screenshot and an off-screen chart all render final
  values, never a frame of an animation with no clock to run on.

`elementProgress(i, n)` staggers elements for the wipe a bar chart wants. `LineChart`
deliberately does not stagger: staggering the points of a line animates a wave through it.

**The donut cannot use any of this for its entry**, and the reason is worth knowing before
"fixing" it: a donut's shares are ratios, so interpolating every value from zero leaves
every ratio exactly where it started and the ring appears complete on the first frame.
`DonutChart` therefore multiplies the *sweep* by `progress()`, but only while
`series.from == null`, i.e. on a first appearance. A change of numbers morphs instead,
because re-wiping the whole ring every time a value arrives makes a live chart unreadable.

## Hiding and showing

Two mechanisms, because a bar chart's legend names series and a donut's names slices:

| | what a legend entry is | what hiding does |
| --- | --- | --- |
| Bar / line | a series | `ChartSeries.setVisible`, and the value animates to the baseline |
| Donut | a slice | a per-slice weight `Transition`, and the ring closes over it |

In a **stacked** bar chart a hidden series keeps its slot (its stack key is still there,
because its neighbours share it), so it shrinks and fades in place. **Grouped**, the slot
itself is gone the moment the series is hidden and the survivors take the width in one step.
That jump is accepted, not overlooked: animating slot width means animating the category
layout, which every geometry path would then have to read from the animation instead of from
the box.

## The hole is a widget slot

`DonutChart.setCenter(Widget)` is a normal `add()`. Consequences worth keeping straight:

- It is laid out in `onLayout`, which must call `updateRegions()` first, because the ring's
  centre and radius come from the content region, and during layout that region is whatever
  the last paint left behind unless it is recomputed.
- It is measured against the **inscribed square** (`innerRadius * outerRadius * 1.414`), not
  against the hole's diameter, or its corners would sit outside the ring.
- It takes input first, because `Widget.hitTest` reaches children before their parent. A
  button in the hole is clickable, and the chart's own `pickAt` returns `null` inside the
  inner radius, so the ring's hover stops at the ring.

## Tooltips are painted, not `Widget.setTooltip`

The toolkit's hover tooltip appears after a dwell, near the pointer, with one string. A
datapoint tooltip has to appear immediately, follow the pointer, and carry a row per series
with a colour swatch each. So `Chart` paints its own panel in `onPaintOverlay` and clamps it
inside its own box, which is also why `paintOutset()` stays 0 and partial rendering needs
nothing special here. `Widget.setTooltip` still works on a chart and means the ordinary
thing; nothing sets it by default.

## Text is `I18nString`, like everywhere else

The title, every series name and every category label are held as `I18nString` and resolved
at paint time, with `String` overloads that wrap a literal. That is not decoration: `Scene`
subscribes to `I18n.addChangeListener`, so a language change already re-measures and
repaints the tree; a chart holding raw `String`s would simply keep yesterday's language
while every `Label` around it changed.

**Every cache that holds resolved text folds `I18n.epoch()` into its key**: the legend
entries, the tick labels, the widest-category-label scan, the tooltip rows. A cache that
did not would survive the relayout and be exactly the bug the type was introduced to
prevent. There is no `setLabels(List<I18nString>)`, because it erases to the same signature
as the `List<String>` form; the varargs overload is the way in.

## Allocation and per-frame cost

Charts repaint on hover, so anything per-frame matters. Two different problems: garbage, and
work that scales with the data rather than with what is drawn.

**Resolved once per data change, not per frame**, each keyed on `dataGeneration()` (bumped
by every value, visibility and axis change) plus the language, and where text metrics are
involved, the font instance:

- the value scale and its tick labels, including the widest one, which sets the gutter;
- the widest category label, which sets the other gutter and the label-skip step;
- `LineChart`'s point buffers, also keyed on the animation's progress and the plot rect,
  because one hover asks for the same series three times (pick, report, paint);
- the tooltip's rows, which report values as set and so change only with the data.

`ChartLayoutCostTest` counts text measurements and fails if any of that moves back onto the
per-frame path: it is the difference between 8442 measurements for twenty pointer moves and
fewer than 200.

**Garbage:**

- Legend entries are cached, as is `stackKeys()`; the latter is walked once per bar, and
  rebuilt per call it allocated a list for every bar drawn.
- `BarChart.markRect` writes into one reusable `float[4]`; `LineChart` keeps its point
  buffers and one `Path2D` across frames; legend hit rectangles live in one `float[]`.
- Bold faces are memoized per base font. `Font.bold()` allocates a fresh record and the
  backend resolves fonts through an `IdentityHashMap`, so deriving one per paint misses that
  memo every frame *and* grows it forever, the same trap `Label` documents.

## The palette

`ChartPalette` ships two sets (one per surface mode) that are the same eight hues stepped
for their background. **The slot order is the colour-blindness mechanism, not decoration**:
adjacent slots are the pairs a stacked bar and a legend put side by side, and the order was
chosen so every adjacent pair clears OKLab ΔE 8 under simulated protanopia and deuteranopia
and ΔE 15 under normal vision. `ChartPaletteTest` pins every hex; changing one means
re-running the palette validation for both modes first.

Contrast against the surface is *not* a gate for these marks (several slots sit below 3:1),
which is legal only because identity is never carried by colour alone: a chart with two or
more entries always draws a legend, and the tooltip names what it reports. Keep that relief
if you change the defaults.

Past the eighth slot the palette repeats, shaded. That keeps a ninth series drawable; it
does not make it readable. Fold the tail into an "Other" series instead.

## What is deliberately not here

- **Keyboard interaction.** A chart is not focusable and has no arrow-key traversal of its
  datapoints. Adding it means a focus ring, a "current datum" that the tooltip follows, and a
  decision about what Enter does (worth doing, not free).
- **A second ring, a second axis.** `DonutChart` draws its first visible series and no more,
  and there is one value axis. Two scales on one chart is the most common way to draw a
  correlation that is not in the data.
- **Log scales, time axes, dual stacking modes (percentage stacks).** All fit the existing
  `ChartAxis`/stacking seams; none are implemented.
