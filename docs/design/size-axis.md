# The size axis

Background for `ControlSize`, `SizeTokens` and `Strokes`. The decision itself, with the
alternatives that were weighed, is ADR 002; this file holds what a contributor needs
before changing any of the three.

## Why the numbers are hand-authored

`ControlSize` is a design axis, not a scale factor, and not a DPI setting: that is
`Canvas.contentScale()`, applied downstream in the backend. The two multiply, in that
order, and conflating them produces a UI that is either blurry or wrong.

Per step, type, control heights and spacing move on three *different* hand-authored
ramps. Nothing anywhere is `base * k(step)`. Strokes do not move at all.

## Locked versus tabled

A quantity is **locked** (a constant in `Strokes`) if it expresses a weight, a
rasterization correction derived from a weight, a device fact, or a human motor
constant. It is **tabled** (a row in `SizeTokens`) if it expresses an extent or an
optical gap.

The separation is structural rather than editorial: `Strokes` members are
`public static final float` and `SizeTokens` has no row for any of them, so there is
nowhere to put the five columns a scaled value would need. The moment one five-column
row holds one value five times, the enforcement is back to being a habit.

`Snapping.strokeWidthDev(w, scale) = max(1, round(w × scale))` does the rest: a locked
1 pt border is 1, 1, 2, 2 device px at content scales 1.0 / 1.25 / 1.5 / 2.0, and it is
identical across all five steps at a given scale.

## Baseline spread when steps are mixed

The cross-step baseline offset reduces to `body × 0.341796875`, a function of the type
ramp alone, which no choice of control heights can cancel. That is where the 0.69 pt
(adjacent steps) and 2.73 pt (full ramp) figures in `ControlSize`'s documentation come
from. It is the reason `CrossAlignment.CENTER` is the wrong choice for a row that mixes
steps and carries text.

## The accessibility floor, and why a clamp can be a defect

The height ramp pays the 24 pt WCAG floor **in paint**: XSMALL is 24, so no control
needs a pointer target wider than its painted box. There is no `Widget.hitOutset()` and
no two-pass hit test, only a `Math.max` clamp on the few axes that can fall short.

The one that actually binds is the Slider's height: its natural height is 21 at XSMALL,
because the focus ring needs absolute room the knob ramp does not predict. Checkbox and
RadioButton keep sub-24 rows they already had at MEDIUM.

A clamp that can never bind is a defect rather than defensive coding: it claims an axis
can fall below the floor when it cannot, and the next reader has to re-derive the ramp
to find out. One was removed from `TextField.trailingWidth` for that reason.

## Cost of letting steps coexist

Each step visible *at the same time* multiplies the glyph-atlas working set for the
faces it uses. Negligible for Latin; measurable for CJK- or emoji-heavy interfaces,
where five simultaneously visible steps push the atlas past its soft page cap. Eviction
degrades gracefully rather than thrashing.

## Traps

- **Never resolve a step in a constructor or field initializer.** A widget has no parent
  while it is being built, so it captures the process default permanently. There is no
  reparent hook to correct it.
- **Resolve once per pass.** Two resolutions that disagree inside one component route
  clicks to the wrong segment, row or field.
- **Migrating a stroke literal:** the four text-cluster components draw one rounded rect
  whose width is `BORDER + (FOCUS_RING - BORDER) * focus`. Replacing that with
  `focus > 0 ? FOCUS_RING : BORDER` deletes the focus animation on all four. A test that
  records stroke widths must paint at a settled transition (`focus` exactly 0, then
  exactly 1) or it records frame-dependent fractional widths.
