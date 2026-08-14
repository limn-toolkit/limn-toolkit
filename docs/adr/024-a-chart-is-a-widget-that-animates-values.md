# ADR 024: A chart is a widget, and what it animates is values

- **Status:** Accepted, 2026-08-10. Implemented as `limn.components.chart`: `Chart`,
  `CartesianChart`, `BarChart`, `LineChart`, `DonutChart`, `ChartSeries`, `ChartAxis`,
  `ChartPalette`, `ChartPoint`, `ChartFormats`. Demo scene `--scene charts` and a kitchen-sink tab.
- **Date:** 2026-08-10
- **Scope:** why charts are painted widgets rather than a drawing API or a tree of mark widgets, why
  the animation interpolates data instead of geometry, why the donut's hole is a real widget slot,
  and why the default colours are pinned by a test.
- **Audience:** whoever adds a chart type, changes the defaults, or wonders why the obvious
  alternative was not taken.

---

## 0. The starting point

The demo already had a chart: the performance footer draws a sixty-slot bar chart per gauge,
by hand, in one `onPaint`. It is perfectly good code and it is not an API: every quantity in
it is a constant chosen for that footer, and the thing it cannot do is be used by anybody else.

The question this decides is what the reusable version is, given that the toolkit already has a
`Canvas` anyone can draw on and a `Widget` anyone can subclass.

## 1. Not a drawing API

The first candidate was a set of drawing helpers (`Charts.bars(canvas, rect, values)`), leaving
the widget to the application. It is the smallest thing that could work and it is wrong here,
because a chart's hard parts are not the marks:

- The **scale** has to be rounded outward to numbers a reader can interpolate between, and that
  rounding is what decides the gutters, which decides the plot rectangle, which decides the marks.
- The **tooltip** has to know which datum is under the pointer, which needs the same geometry the
  paint used, which means the geometry has to be resolved by whoever owns the pointer.
- The **animation** needs a frame ticker, which needs a scene, which needs a widget.

A helper API hands all three back to the application, and every application then solves them
differently and slightly wrongly. So: widgets.

## 2. Not a tree of mark widgets

The other extreme (a widget per bar, per point, per slice) buys hit-testing and invalidation for
free from the existing tree. It was rejected on cost and on layout: a twelve-point line with two
series is thirty-six widgets that all have to be created, measured and laid out on every data change,
and a chart's marks are not laid out by any layout the toolkit has. They are positioned by a scale.

One widget paints everything. Hit-testing is `pickAt`, which is the same arithmetic the paint did,
run backwards. The one exception is the donut's hole (§4).

## 3. The animation interpolates values, not pixels

Given "the chart animates", there are two things it could mean.

**Interpolating geometry** (grow the bar rectangle, sweep the arc) is what a chart library
usually does, and it is easy while the data is fixed. It falls apart on the case that matters:
a live chart whose numbers are re-pushed every second. Growing a bar from height A to height B
is only correct if the *axis* did not change, and re-pushed data changes the axis all the time.

**Interpolating values** is what shipped:

```
drawn(i) = from[i] + (target(i) - from[i]) * progress(i)
```

Everything downstream (the pixel, the stack it sits on, the share it is of its stack) is computed
from `drawn(i)` by the same code that computes the settled frame. There is one path, not two, and
"animating" is a number between 0 and 1 rather than a second geometry implementation.

Three things fall out of it for free, and they are the reason it is worth the paragraph:

- **The first appearance is the same code.** `from == null` means "start from the axis baseline",
  so growing out of the axis is not a special case.
- **Hiding a series re-flows its stack.** A hidden series targets the baseline, so the segment
  shrinks to nothing while what was above it slides down; no code anywhere knows that a legend
  click is different from a data change.
- **Headless renders final values.** A `Transition` with no scene snaps to its target, so a test
  and a screenshot see the data. Nothing had to be special-cased for either.

The cost is one case where the model does not apply at all, and it is worth stating plainly rather
than hiding: **a donut's entry animation cannot work this way.** Its shares are ratios, so
interpolating every value from zero leaves every ratio unchanged and the ring is complete on the
first frame. The donut therefore animates its *sweep* on a first appearance and morphs values on
every later change. That is a genuine exception, marked as one in the code.

**Accepted consequence:** the axis does not animate. It is resolved from the values as set, never
from the interpolation, because an axis that followed the animation slides under the marks growing
into it and relabels every tick on every frame. So a data change that changes the scale relabels the
axis in one step while the marks move smoothly. Chart.js animates both; matching it means the marks
would have to be positioned against an animated scale, which is exactly the two-implementations
problem above.

## 4. The donut's hole is a widget slot

A donut with a number in the middle is the reason donuts exist. The options were a `setCenterText`
that the chart draws, or a widget.

`setCenterText` is one method and covers one case. A widget covers every case at the cost of the
chart having a child: a total, a label and a caption, an icon, a button that reloads the data. It is
measured against the largest square that fits inside the ring and takes input before the chart does,
so the ring's own hover stops at the ring, which is the behaviour a button in the hole needs anyway.

The chart stays a leaf everywhere else.

## 5. The palette is validated, and the test says so

Categorical colours are the part of a chart that is usually chosen by eye and is usually wrong for
some readers. The defaults here are two eight-slot sets (one per surface mode, the same hues
stepped) whose **order** was chosen by measurement: every adjacent pair clears OKLab ΔE 8 under
simulated protanopia and deuteranopia and ΔE 15 under normal vision, because adjacent slots are the
pairs a stacked bar and a legend put side by side.

An ordering guarantee that lives only in a comment is an ordering guarantee that lasts until the
next tidy-up, so `ChartPaletteTest` pins all sixteen values. Changing one is then a decision with a
test to update rather than an edit nobody notices.

Two accepted consequences:

- **Contrast against the surface is not a gate for marks.** Several slots sit below 3:1. That is
  legal only because identity is never carried by colour alone here: a chart with two or more
  entries always draws a legend, and the tooltip names what it reports.
- **A ninth series is not a ninth colour.** The palette repeats, shaded lighter and darker. It keeps
  the chart drawable and it is documented as the point at which the tail should be folded into an
  "Other" series instead.

## 6. What this does not decide

Keyboard traversal of datapoints, a second value axis, log and time scales, percentage stacking, and
a second ring in the donut. The seams for the first four exist (`ChartAxis`, the stacking helpers);
none of them are implemented, and the last one is a chart nobody can read.
