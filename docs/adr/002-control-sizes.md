# ADR 002. Control sizes: an inherited, per-widget size axis

- **Status:** Accepted, 2026-07-29; implemented on `main`. All eight open questions were
  resolved that day and §11 records each answer; §11.10 lists what landed and the residual items
  that were deliberately left (the toggle column gap with no automatic consumer, the two
  indicator rows under the 24 pt target, and `AREA_WIDTH` not re-derived). One table diverged in
  implementation: `SLIDER_RAIL` at MEDIUM is 6, not the 5 in §8.2, and `SizeTokens` records that
  where the row is defined.
- **Date:** 2026-07-29
- **Scope:** a deliberate multi-step size axis for every component, inherited down the
  widget tree with several steps coexisting in one window
- **Target:** `limn-toolkit` + `limn-components` + `limn-demo`

Language rule: this document, and every identifier, comment and UI string it introduces, is English.

> Revision 2 applies an adversarial review on three axes (visual, engineering, completeness).
> Every number in every table below has been re-derived, and every claim about today's code
> re-read against the files. §12 lists what changed and why.

## 0. Decisions taken: 2026-07-29

The maintainer resolved all eight open questions. **These decisions are authoritative and
override any conflicting recommendation later in this document.** Three of them diverge from
what §11 recommended; §11 records each answer, its reasoning and its consequences.

| # | Decision | vs. §11's recommendation |
|---|---|---|
| 1 | `limn.scene.ControlSize { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }`, default `MEDIUM` | as recommended |
| 2 | Five steps, **no per-component clamping**: every component honours every step | **diverges** (§11 wanted `XLARGE → LARGE` clamping) |
| 3 | **MEDIUM control height is exactly 32**; D1 lands in phase 1, not phase 5 | **diverges** (§11 wanted 32.40625 preserved through phase 3) |
| 4 | Height floor **24** at XSMALL; **no `hitOutset()`, no two-pass hit test** | **diverges** (§11 wanted a 20 pt floor plus the hit mechanism) |
| 5 | `ScrollBar` does not participate in v1 | as recommended |
| 6 | `Label.Role { BODY, LABEL, TITLE }` ships | as recommended |
| 7 | The ComboBox popup dot scales (1.75 / 2 / **2.5** / 3 / 3.5) | as recommended |
| 8 | `Tokens` + `TokenRow` / `TokenColumn` / `TokenPadding` ship; `Padding` loses `final` | as recommended |

**The resulting height ramp is 24 / 28 / 32 / 40 / 50**: every step an even integer, so
`height / 2` is integral at every step. That is the property that matters (§3.3 item 4): it
drives pill radii, thumb centres and rail centring.

**Status: implemented.** Phases 0-5 have landed; §7 was rewritten from the shipped code rather
than propagated from the plan, and §11 records the eight decisions and their consequences. What
remains open is listed at the end of §11.10.

---

## 1. Summary and recommendation

We ship **`limn.scene.ControlSize { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }`** (five steps, `MEDIUM` the default) as an **inherited, per-widget, nullable attribute on `Widget`**, resolved as *own declared value → nearest declaring ancestor → this widget's scene default → host link → process default*. Several steps therefore coexist in one window in one frame; any subtree overrides its ancestors.

The enum carries **no numbers**: the axis lives in the toolkit (so a raw `limn.scene.layout.Row` can be a size scope), and the numeric table lives in `limn.components.SizeTokens`, reachable via `Theme.tokensFor(widget)`. Quantities that are *weights* rather than *sizes* (every border stroke, every focus ring and its gap, hairlines, check-mark strokes, carets, AA bleeds, damage margins, wheel steps, the accessibility hit floor) live in `limn.components.Strokes` and are **identical at every step by construction**, because there is no five-column row for any of them.

Three independently hand-authored ramps, not one factor: **type 11/12/14/16/19** (span 1.73×), **control height 24/28/32/40/50** (span 2.08×), **spacing 3/4/6/8/10** (span 3.33×). Nothing anywhere is `base × k(step)`. Every control height is an even integer, so `height / 2`, which drives pill radii, thumb centres and rail centring, is integral at every step (decision 3).

Resolution is memoized per widget against a global epoch, and (the load-bearing correctness decision) **the resolved step joins `Widget.measure()`'s cache key**, so a container's size change re-measures exactly the descendants whose step actually changed and leaves overriding subtrees on their caches. No new deep-invalidation API is needed.

**Every MEDIUM *cell* reproduces today's literal except the control height**, which decision 3 changes from 32.40625 to 32 so the whole ramp lands on the pixel grid: Button, TextField and ComboBox each lose 0.40625 pt, Spinner 2 pt, SegmentedControl none. That is the D1 unification, promoted into phase 1, and it is the feature's one deliberate MEDIUM change, plus the two already documented in §8.3 D15 and §8.4. Each gets its own commit and re-baseline. "Zero regression" is a constraint on the MEDIUM column, *not* a licence to copy MEDIUM's known warts into the four new steps: where MEDIUM carries a fudge (Spinner's `+2`, SegmentedControl's `−1` pill radius, TabbedPane's non-concentric radii, `Slider.pad()`'s missing stroke, the fractional icon box), the four new cells are **correct from day one** and only the MEDIUM cell is pinned.

One mechanism ships earlier than a naive plan would put it, because the ramp is unsound without it: **`Flex.CrossAlignment.BASELINE` + `Widget.baselineOffset()`**, without which a row that mixes steps, the feature's own mandate (§3.7), has no alignment story at all. The cross-step baseline offset reduces to `body × 0.341796875`, a function of the type ramp alone, so no choice of heights can substitute for the mechanism.

`Widget.hitOutset()` and the two-pass hit test are **not shipping** (decision 4). The height floor is 24 instead of 20, so no control on the ramp needs a hit region wider than its paint box, and input dispatch is untouched. `MIN_HIT_TARGET` survives only as a `Math.max` clamp on two width-axis floors. §11.4 records the one loose end this leaves, on Checkbox and RadioButton.

`ScrollBar` does not participate in v1.

---

## 2. Nomenclature

### 2.1 Prior art

| System | Type / API | Steps | Default | Count |
|---|---|---|---|---|
| AppKit | `NSControl.ControlSize` | `mini, small, regular, large` | `regular` | 4 |
| SwiftUI | `ControlSize` | `mini, small, regular, large, extraLarge` | `regular` | 5 (`extraLarge`→`large` on macOS) |
| Swing / Nimbus / Aqua | client property `JComponent.sizeVariant` | `"mini", "small", "regular", "large"` | `"regular"` | 4 |
| FlatLaf | `JComponent.sizeVariant` + type styles | `mini, small, (default), large` | default | 4 |
| Qt (macOS only) | `WA_MacMiniSize`/`WA_MacSmallSize`/`WA_MacNormalSize` | mini, small, normal | normal | 3 |
| WinUI | Compact Sizing resource dictionary | standard 32 / compact 24 | standard | 2 |
| MUI | `size` prop | `small, medium, large` | `medium` | 3 |
| Material 2 / material-web | `--mdc-density-scale` (**density**) | `0, -1, -2, -3` (−4 dp/step) | `0` | 4 |
| Ant Design 5 | `size` + `controlHeight*` | `small, middle, large` (`XS` token unused) | `middle` | 3 |
| Fluent UI v9 | `size` prop | `small, medium, large` | `medium` | 3 |
| Carbon (IBM) | `size` prop (density is a separate axis) | `xs, sm, md, lg, xl, 2xl` | `md` | 6–7 |
| Primer (GitHub) | `size` + `--control-*-size` | `small 28, medium 32, large 40` | `medium` | 3 |
| Chakra v2 | `size` prop | `xs, sm, md, lg` | `md` | 4 |
| Bootstrap 5 | modifier classes | `.btn-sm`, n/a, `.btn-lg` | n/a | 3 |
| Tailwind | scale naming | `xs, sm, base, lg, xl, 2xl…` | `base` | 6+ |
| Radix Themes | `size="1..4"` + nestable `<Theme scaling>` | `1, 2, 3, 4` | `2` | 4 |
| Polaris | `size` prop | `micro, slim, medium, large` | `medium` | 4 |
| Atlassian ADS | `spacing` prop | `default, compact, none` | `default` | 3 |

Two structural findings matter more than the naming:

1. **No mature system ships a process-wide switch as the mechanism.** Every system that supports coexisting sizes does it per element (`size=`) or through a *scoped subtree*: CSS cascade (JavaFX, GTK, Bootstrap), a nestable theme scope (Radix `<Theme scaling>`), or a resource dictionary merged into a subtree (WinUI). Our inherited-attribute design is the native-toolkit form of the same thing.
2. **The one toolkit that tried a non-inherited per-widget attribute got burned.** Qt's `WA_MacSmallSize` is documented per widget; the standing complaint is that setting it on a form reaches spin boxes and button boxes but leaves labels at full size. Nimbus/Swing has the same shape and the tutorial only ever shows it set on individual components. Prior art is a *warning*, not a counterexample: **inheritance is required.**

**Density comparison, for calibration.** XSMALL ÷ MEDIUM control height is **0.750** (24 ÷ 32) after decision 4. AppKit mini is 0.53 of regular (16 pt vs 30), FlatLaf mini ≈ 0.60, Carbon `xs` ÷ `md` = 0.60, WinUI compact ÷ standard = 0.75, Chakra `xs` ÷ `md` = 0.75. So Limn's dense step sits with WinUI's *compact* and Chakra's `xs` rather than with AppKit's *mini*: the 24 pt floor buys an untouched input path, and the price is that XSMALL is a compact step, not a mini one. Revision 2 reached 0.617 by pairing a 20 pt paint box with a 24 pt hit region; that trade was declined (§11.4). If a genuinely mini step is wanted later it needs the hit mechanism, and it should be a sixth constant rather than a redefinition of XSMALL.

### 2.2 Decision

```java
package limn.scene;
public enum ControlSize { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }
```

Accessors: `controlSize()` (resolved, never null), `declaredControlSize()` (nullable), `setControlSize(ControlSize)`, plus `setControlSizeHost(Widget)` for out-of-tree roots (§5.5).

- **`ControlSize`** is the exact term of art in the two systems that ship this feature natively on desktop (AppKit, SwiftUI), and it reads unambiguously next to `limn.scene.Size` (the `onMeasure` result, imported by ~30 components) because the qualifier *narrows* ("the control's size class") rather than restating.
- **`XSMALL…XLARGE`** is the dominant step vocabulary (Carbon, Chakra, Tailwind, Primer, Fluent, Ant). It reads as a symmetric monotonic ramp, which matters because `ordinal()` indexes the token table, and `MEDIUM` in the middle reads correctly as the default.
- **Five steps**, as the maintainer asked. Adding a constant later breaks exhaustive `switch` and every third-party per-step table; removing one is worse. Since each step is a hand-authored row, five rows cost table-authoring effort, not architectural risk. Policy asymmetry, recorded: **`XSMALL` is load-bearing** (dense inspectors, data grids, packed toolbars; AppKit `mini`, FlatLaf `mini`, Carbon `xs`), while **`XLARGE` must justify itself per component**; a component may clamp `XLARGE→LARGE` where it has an irreducible glyph, exactly as macOS does for `extraLarge`. If anything is ever cut, cut `XLARGE`.
- **No `INHERIT` constant.** Inheritance is `null` on the widget's field. A constant would pollute the ramp, break ordinal indexing and force a dead row in every table.

**The irreducible-glyph clamp cuts both ways.** Revision 1 granted XLARGE a clamp and let the *dense* end shrink glyph half-extents 22–30 % below MEDIUM while their pens stayed pixel-locked, which is where the arithmetic actually breaks (a 1.8 pt pen on a 3.5 pt chevron run is 51 % ink). Every locked-pen glyph is therefore **floored at its MEDIUM extent for XSMALL and SMALL** (§4.4). This costs nothing at MEDIUM, nothing in gutter width, and it is what closes Open Question 5 without a survey.

### 2.3 Rejected

| Rejected | Why |
|---|---|
| `Size` | `limn.scene.Size` already exists: `record Size(float width, float height)`, imported by `Button.java:13`, `Checkbox.java:13`, `ToolBar.java:6`, `SegmentedControl.java:12`. Non-negotiable collision. |
| `WidgetSize` | In a package exporting `Size` as a measured dimension, this reads as "the widget's dimensions". Comprehension trap. |
| `Density` | Term of art in this industry for **spacing compression at constant type size** (Material `--mdc-density-scale`, −4 dp/step, font untouched; Carbon `condensed`/`normal`; WinUI Compact Sizing; MUI DataGrid). This axis scales type. It would mis-teach every reader and burn the name for a genuine future second axis. |
| `Scale` | Head-on collision with `Canvas.contentScale()` (`Icon.java:56`, `GlCanvas.java:563`, `Scene.java:1531`) and the `--scale` demo flag. The spec's central rule is that a size variant is *not* a DPI scale; a type named `Scale` guarantees the conflation. |
| `SizeVariant` | Valid Swing/FlatLaf precedent, but `Variant` already means *visual* variant in a widget toolkit (`Button.setSecondary`, a future `Button.Variant`), and `setSizeVariant(SizeVariant.LARGE)` stutters at 83 call sites. |
| `MINI/SMALL/REGULAR/LARGE/XLARGE` | AppKit-faithful, but `REGULAR` collides with font-weight vocabulary (`FontStore.resolveUncached` speaks of "the family's regular"), `MINI` is macOS jargon, and `mini…large` + `XLARGE` is the asymmetry Apple itself regrets. |
| **`TINY/SMALL/MEDIUM/LARGE/EXTRALARGE`** (the user's suggestion) | Semantically fine, lexically weakest. `TINY` has no precedent in any surveyed system; Java convention forces `EXTRA_LARGE`, which is long at every call site; and `XSMALL`/`XLARGE` say the same thing in the vocabulary every reader already knows. **Recommend against.** |

---

## 3. The three ramps

### 3.1 The ramps side by side

Ratios are relative to `MEDIUM`. Roboto's vertical metrics (em 2048, ascent 1900, descent −500, lineGap 0) give `lineHeight() == height() == 1.171875 × size` and `ascent() == 0.927734375 × size`.

| quantity | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE | span |
|---|---|---|---|---|---|---|
| **type** `body` | 11 · 0.79× | 12 · 0.86× | **14 · 1.00×** | 16 · 1.14× | 19 · 1.36× | **1.73×** |
| **height** `controlHeight` | 24 · 0.75× | 28 · 0.88× | **32 · 1.00×** | 40 · 1.25× | 50 · 1.56× | **2.08×** |
| **spacing** `spacingSmall` | 3 · 0.50× | 4 · 0.67× | **6 · 1.00×** | 8 · 1.33× | 10 · 1.67× | **3.33×** |
| **strokes** (all) | 1.00× | 1.00× | **1.00×** | 1.00× | 1.00× | **1.00×** |

There is no separate "resolved height" row any more: **decision 3 makes the declared height the
resolved height at every step**, including MEDIUM. `height / 2` is 12 / 14 / 16 / 20 / 25, integral
throughout, which is the property §3.3 item 4 identifies as the one that actually matters.

Derived ratios, which *are* the optical argument:

| | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE |
|---|---|---|---|---|---|
| `lineHeight ÷ height` | 0.537 | 0.502 | **0.513** | 0.469 | 0.445 |
| `padH ÷ height` | 0.500 | 0.571 | **0.625** | 0.650 | 0.640 |
| `radiusMedium ÷ height` | 0.208 | 0.214 | **0.250** | 0.250 | 0.240 |
| `border ÷ height` | 4.2 % | 3.6 % | **3.1 %** | 2.5 % | 2.0 % |
| **effective** `padV` = (h − lineHeight)/2 | 5.555 | 6.969 | **7.797** | 10.625 | 13.867 |

**The ink fraction is no longer strictly monotone, and test #3b must be dropped rather than
satisfied.** SMALL sits at 0.502 against MEDIUM's 0.513, a 2.1 % relative dip, so SMALL is
marginally *airier* than MEDIUM instead of denser.

This is not a tuning failure; it is arithmetically forced by the three constraints decisions 3
and 4 chose together. Monotonicity requires SMALL's ink fraction to fall strictly inside
`(0.513, 0.537)`; with `body = 12` (so `lineHeight = 14.0625`) that pins SMALL's height to the
open interval `(26.19, 27.41)`, whose only integer, **27**, is odd, which breaks the integral
`height / 2` rule. Trying `body = 11` gives `(24.0, 25.1)` → 25, odd; `body = 13` gives
`(28.37, 29.70)` → 29, odd. **Monotone ink fraction, all-even heights and a 24 pt floor cannot
hold simultaneously.** Two of the three were chosen deliberately, so the third goes.

The dip costs nothing perceptually and the argument §3.2 already makes covers it: at the dense
end the differentiation between XSMALL and SMALL comes from *padding* (`padH` 12 vs 16, −25 %)
and from the height, not from the type. The same is now true of SMALL against MEDIUM.

### 3.2 Why the type ramp is the flattest (1.73×)

Increments **+1, +2, +2, +3**; step ratios 1.091 / 1.167 / 1.143 / 1.188 are roughly constant, which is what perceptual (Weber–Fechner) uniformity requires under integer quantization.

- **11 pt is a hard floor.** Roboto's x-height is 1082/2048 em = 0.528, so 11 pt at 1× is 5.8 device px: the point at which unhinted grayscale AA still resolves the crossbar of `e` and the bowl of `a`. Limn has no hinting (`stbtt` grayscale into `GlyphAtlas`), so 10 pt is not available to us even though hinted stacks ship it.
- **11 → 12 is only +1, deliberately.** Both are dense sizes competing only on legibility. The differentiation between XSMALL and SMALL comes from **the box** (20 vs 26, −23 %) and from padding (`padH` 12 vs 16, −25 %), not from type. That asymmetry is the feature.
- **14 → 16 is +2, not +1.** 15 pt is a 7 % delta from 14, below the just-noticeable difference for type size at these sizes. 16 is the first value that reads as *larger*.
- **16 → 19 is +3.** 18 would give ratio 1.125, colliding with LARGE's 1.143 and making XLARGE redundant; 20 would give 1.25, a visible jump out of the ramp.
- A pure multiplier from 14 would give 8.4 / 11.2 / 14 / 16.8 / 22.4: fractional sizes that hint badly and an unreadable XSMALL.
- This ramp *is* FlatLaf's additive ladder (`mini = default − 3`, `small = default − 2`, `large = default + 2`), which is itself Nimbus's and Aqua's.

**`label` is a ratio ramp, not `body − 1`:** 10 / 11 / **13** / 14 / 17.

| | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `label` | 10 | 11 | **13** | 14 | 17 |
| body→label contrast | 9.1 % | 8.3 % | **7.1 %** | 12.5 % | 10.5 % |

A constant absolute −1 would make the contrast shrink monotonically (9.1 / 8.3 / 7.1 / 6.3 / 5.3 %), i.e. the LABEL role becomes invisible exactly where there is most room for hierarchy, which is the same curve this section rejects one paragraph above when it refuses a 15 pt body step at 7 %. Every cell of the row above is at or above that threshold. MEDIUM stays 13, so §8.1 is unaffected. (Revision 1 claimed "the body/label contrast is identical at every step"; it was identical in *points*, and points are not how type contrast is perceived.)

`title` = 15 / 17 / **20** / 24 / 29, giving `title ÷ body` = 1.36 / 1.42 / 1.43 / 1.50 / 1.53. **Decision:** monotonically growing display contrast, because at XSMALL there are too few pixels for hierarchy so titles stay near body; at XLARGE there is room for real contrast. 28 would make the ratio dip (1.50 → 1.47), a non-monotonic curve with nothing to justify it.

### 3.3 Why the height ramp sits in the middle (2.08×)

**One formula for every text-bearing control:**

```
height = max(controlHeight, lineHeight(body) + 2 · padV)
```

| step | body | lineHeight | padV | `+ 2·padV` | floor | **resolved** | binder |
|---|---|---|---|---|---|---|---|
| XSMALL | 11 | 12.890625 | 5 | 22.890625 | **24** | **24** | floor |
| SMALL | 12 | 14.062500 | 6 | 26.062500 | **28** | **28** | floor |
| **MEDIUM** | 14 | 16.406250 | **7** | 30.406250 | **32** | **32** | **floor** |
| LARGE | 16 | 18.750000 | 10 | 38.750000 | **40** | **40** | floor |
| XLARGE | 19 | 22.265625 | 13 | 48.265625 | **50** | **50** | floor |

Four things this buys, each a deliberate decision:

1. **The box binds at every step, so every height is an even integer** (decision 3). `padV` is the
   *declared* token; because the floor always wins, what a widget actually centres against is the
   **effective** `padV = (height − lineHeight)/2` from §3.1 (a centring offset, not a box
   dimension, and therefore free to be fractional). `padV = 7` at MEDIUM is what makes 32 the
   binder: `16.40625 + 14 = 30.40625 < 32`.
   **What this costs, stated plainly:** MEDIUM is no longer bit-identical. `Button.java:108`
   computes `lineHeight + 2·spacingSmall + 4` = **32.40625** today, so Button, TextField and
   ComboBox each lose 0.40625 pt (1.25 %) of height, and the undocumented `+ 4` literal is
   deleted rather than absorbed. This is D1 promoted into phase 1: it unifies Button 32.40625 /
   SegmentedControl 32 / Spinner 34 onto one number, and it is the whole reason every step now
   lands on the pixel grid. §8 is restated accordingly: one enumerated change at MEDIUM instead
   of a proof of zero change.
2. **The accessibility floor is paid in paint, not in hit-testing** (decision 4). WCAG 2.2
   SC 2.5.8 measures the *target* (the region that accepts a pointer action), so a 20 pt control
   with a 24 pt hit region would conform, and revision 2 planned exactly that via
   `Widget.hitOutset()` plus a two-pass hit test. That mechanism is **not shipping**: the floor is
   24 instead, and no control on this ramp needs a hit region wider than its paint box. The trade
   is 4 pt of XSMALL density for leaving input dispatch untouched, the one behavioural change the
   feature would otherwise have made. Two width-axis floors survive, both no-ops at MEDIUM and
   both plain `Math.max` clamps rather than a hit mechanism: `MenuBar.titleWidth` and
   `SegmentedControl` segment width, each `≥ MIN_HIT_TARGET`. **Checkbox and RadioButton are the
   one loose end**: their rows are 18 pt at MEDIUM *today* and were the main consumers of
   `hitOutset()`; see §11.4.
   The 24 / 28 / 32 / 40 / 50 spine is the industry consensus outright: Primer 28/32/40,
   Ant 24/32/40, Fluent 24/32/40, Chakra 24/32/40/48, Radix 24/32/40/48, WinUI 24/32.
3. **Uniform binding, deliberately.** Revision 2 bound MEDIUM to the font and the other four to
   the box, and defended the mix as letting a taller CJK or emoji fallback grow the control.
   That defence does not hold in this codebase: `StbFont.measureWithFallback`
   (`StbFont.java:174-176`) documents that "the line's vertical metrics stay this (primary)
   face's, so line height is stable regardless of which glyphs fall back". `lineHeight` is a pure
   function of the font size (`1.171875 × size` for Roboto), so there was nothing for the mixed
   binding to protect. The `max()` still guards the real case: an app that calls `setFont` with
   something large gets a taller control instead of clipped text.
4. **`lineHeight ÷ height` falls 0.537 → 0.445, with one 2.1 % dip at SMALL.** An XSMALL control
   is nearly all text (that is density); an XLARGE control is mostly air (that is spaciousness). A
   control whose type grew proportionally with its box is a **zoomed screenshot**, not a larger
   control. Carbon proves the extreme case: it doubles button height 24 → 48 while holding type at
   14 px and moves padding on a separate axis. §3.1 proves the dip is unavoidable given
   decisions 3 and 4, and why it does not matter.

**Increments 4 / 4 / 8 / 10**: the dense half steps by 4 and the display half by 8 then 10, which is the height ramp's own restatement of §3.4: compression at the bottom, expansion at the top. **Why 50 and not 48:** 50 preserves the 1.25 step ratio established by 32 → 40. 48 would give 1.20 *and* would flatten the ink fraction to 22.265625 ÷ 48 = 0.4639 against LARGE's 0.46875, erasing the added airiness that is XLARGE's entire reason to exist.

**Why every height is even.** Not because of borders: a 1 pt stroke centred in an even box lands on a **half**-pixel boundary, which is precisely why the codebase carries `HALF_PIXEL_INSET = 0.5` and applies it at 12 sites, and `GlCanvas.drawText` rounds baselines to whole device pixels regardless of box parity. The real reason is that **`height / 2` drives pill radii, thumb centres and rail centring, and must be integral**; the general rule behind it is that *any extent centred inside another must share that other's parity*, not merely be an integer.

That rule is not theoretical. `Slider` shipped a 5 pt rail centred in a 26 pt box, putting `trackTop` at 10.5 and rendering the toolkit's longest straight run (a 220 pt rail) as 4 solid device rows between two partial ones (measured coverage 0.776 and 0.318 at 1×, against 0.084 and 0.952 once the rail became even). Fills are not pixel-snapped; only strokes are, in the backend's `Snapping`. The parity rule was written here and violated three sections later, which is why §10.2 asserts it per step rather than trusting the table.

### 3.4 Why the spacing ramp is the steepest (3.33×)

| token | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE | ratio to MD |
|---|---|---|---|---|---|---|
| `spacingSmall` | 3 | 4 | **6** | 8 | 10 | 0.50 → 1.67 |
| `spacingMedium` | 6 | 9 | **12** | 16 | 20 | 0.50 → 1.67 |
| `spacingLarge` | 12 | 16 | **20** | 26 | 32 | 0.60 → 1.60 |
| `padV` (control, **declared**: a measure-only floor input) | 3 | 5 | **8** | 10 | 13 | 0.38 → 1.63 |
| `padV` (control, **effective** = (h − lineHeight)/2) | 3.555 | 5.969 | **8.000** | 10.625 | 13.867 | 0.44 → 1.73 |
| `padH` (control, per side) | 12 | 16 | **20** | 26 | 32 | 0.60 → 1.60 |
| `gapLabel` (control ↔ its label) | 4 | 5 | **6** | 8 | 10 | 0.67 → 1.67 |
| `gapIcon` (icon ↔ text inside a control) | 5 | 6 | **8** | 9 | 11 | 0.63 → 1.38 |
| `gapButtonRow` (Dialog's action row) | 4 | 5 | **6** | 8 | 10 | 0.67 → 1.67 |

- **The declared and effective `padV` columns are both printed, and every optical argument is made on the effective one.** Because the floor binds at four of five steps, the declared row never renders there; revision 1 argued "padV 0.63 → 1.63" about numbers that do not reach the screen. **`padV` is a measure-only input: it must never appear as a paint or hit-test coordinate** (§7.8, and reviewer checklist item 9).
- **At the small end, whitespace absorbs the compression** because legibility cannot. `spacingSmall` halves while `body` falls only 21 %.
- **At the large end, whitespace grows faster than ink** (1.67× vs 1.36×). A 50 pt button with 19 pt text and 32 pt gutters reads generous and calm; the same button with 22 pt text reads as accessibility mode.
- **Optical gaps are split out of the layout ramp.** `gapLabel`, `gapIcon` and `gapButtonRow` are *text-adjacent* gaps: they must read as constant relative to the type they separate, so they are tuned in ems, not on `spacingSmall`'s 0.50 → 1.67 curve. In em: `gapLabel` 0.364 / 0.417 / 0.429 / 0.500 / 0.526; `gapIcon` 0.455 / 0.500 / 0.571 / 0.563 / 0.579. Revision 1 tied all three to `spacingSmall` = 3 at XSMALL, which is 0.27 em: a checkbox glued to its label, a dialog with two activatable rects 3 pt apart (which also defeats the 2.5.8 *Spacing* exception the same document leans on), and a direct contradiction of §7.3's own trap 6 ("column gaps ≥ 11 pt at XS"). MEDIUM cells are 6 / 8 / 6, unchanged, so §8.1 is unaffected.
- `spacingMedium ÷ spacingSmall` is deliberately **not** constant (2.00 / 2.25 / 2.00 / 2.00 / 2.00): the bump at SMALL is why this must be a table and not a factor.
- Material's density steps are exactly −4 dp; Carbon jumps between spacing tokens; Atlassian frames its whole axis as `spacing: default|compact`. Even quanta and token selection, never a float multiply.

### 3.5 What holds the five steps together

`padH ÷ height` stays in **0.600 – 0.650** and `radiusMedium ÷ height` in **0.231 – 0.250** across a 2.50× height range. The control *silhouette* is therefore nearly invariant even though nothing inside it is; that is the difference between "the same button, larger" and "a different design". Both bands are asserted as tests (§10.2 #15).

Honest statement of the residual: `radiusMedium ÷ height` **dips 7 % at SMALL** (0.231) rather than holding perfectly flat, because a large radius consumes a small box's straight run and 6 pt is the value that reads right on a 26 pt box. The band is asserted at `[0.22, 0.26]`, not claimed to be constant. (Revision 1 claimed flatness while its own table dipped 20 %, from 0.247 at MEDIUM to 0.208 at XSMALL.)

Radius scales, and this is not a violation of the pixel-lock rule: a radius is **shape proportion**, not stroke weight. A fixed 8 pt on a 20 pt XSMALL control is 0.40 of the height (a pill); on a 50 pt XLARGE control 0.16 (a hard rectangle). The ramp keeps the ratio inside a ±4 % band.

| token | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE | note |
|---|---|---|---|---|---|---|
| `radiusSmall` | 3 | 3 | **4** | 5 | 6 | repeats 3 at XS/SM: 2 disappears at 1× |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 | sub-linear; a proportional XL (16) eats the fill's straight run |
| `radiusLarge` | 9 | 11 | **14** | 17 | 20 | even, and keeps `radiusLarge ≤ popupItemHeight/2 + popupPadV` at every step (9 ≤ 15 at XS, 20 ≤ 31 at XL) |

### 3.6 Half-points, integer extents, and parity

Half-points are permitted in exactly three places and forbidden elsewhere:

1. **Circle geometry**: `sliderKnob` 6.5, `sliderKnobHover` 6.5/12.5, `switchThumbInset` 2.5/3.5, `indicatorInset` 3.5/5.5, `popupDotCol` 8.5. The rasterizer antialiases a perimeter regardless; there is no pixel grid to land on and the half buys a materially better ratio.
2. **Glyph half-extents**: `chevronHalfW/H`, `arrowHalf`, `checkGlyphW`, `popupDotRadius`, `scrollChevronHalf`, `tabChevron`. A 0.5 in a half-dimension is a whole point in the drawn shape.
3. **`tabIndicator`**, whose MEDIUM value is 2.5 today.

**Every box-extent *token* is an integer at every step, and every control height is even.** The rule is about the table, not about every resolved number; two classes of *derived* extent are font-derived and legitimately fractional, and are allowlisted here so nobody "fixes" them:

- the effective `padV = (height − lineHeight)/2` (§3.1), a centring offset, not a box dimension;
- `TabbedPane`'s strip height, `lineHeight + 2·tabPadV`, fractional at all five steps.

Decision 3 removed the third class revision 2 allowlisted here (the resolved control height at MEDIUM) along with the only fractional box extent in the whole table.

**Parity rule: any extent that is *centred* inside another must share its parity.** Integrality alone is not enough: `Slider:174` computes `trackTop = height()/2 − TRACK_THICKNESS/2`, so the rail's two long straight edges land on integer device rows only when `(height − rail)` is even. This was violated in shipped code: a 5 pt rail in a 26 pt box put `trackTop` at 10.5 (measured edge coverage 0.776 / 0.318 at 1×; 0.084 / 0.952 after the rail became even). Fixed ahead of this feature by making `TRACK_THICKNESS` 6, with `SliderTest` asserting the invariant. The same rule governs `progressThickness` if a bar is ever centred in a taller row, and `tabIndicator`, which escapes it because it is bottom-anchored (`y = height() − thickness`) rather than centred.

**A second, independent source of blur that this feature does not fix.** A widget's *own* device-space origin is fractional whenever its container stacks font-derived heights above it; a `Column` of labels at `1.171875 × body` accumulates fractions. Measured on the `controls` scene, `Slider` sits at y ≈ 81.7 logical, so even a perfectly even-parity rail antialiases there. Parity is necessary and not sufficient: it converts "blurred no matter what the container does" into "crisp as soon as the container cooperates". Snapping container offsets to the pixel grid is a layout-level decision, out of scope here, and worth its own ADR.

**With decision 3 there is no fractional-extent exception left, so mixed-step rows align cleanly.** Under `CrossAlignment.START` (`Flex`'s default, `Flex.java:23`) a MEDIUM control's bottom edge and a LARGE one's now both land on integers relative to the row origin. Baselines still differ across steps, but that is a separate matter, and §3.7 handles it.

### 3.7 Cross-step composition: how a mixed row aligns

The mandate is that several steps coexist in one window in one frame. `Flex` offers `START / CENTER / END / STRETCH`, all of which align **boxes**; Button, TextField and ComboBox all centre their text (`Button:152`, `TextField:353`, `ComboBox:312` are the same expression, `(height() − metrics.height())/2 + ascent()`), so a row that mixes steps aligns boxes and *misaligns baselines*. Same-step rows are fine (all three height formulas resolve identically at each step and all three centre), but cross-step there was no mechanism at all.

Two things ship:

**(a) `Flex.CrossAlignment.BASELINE`, backed by a new `Widget` hook.**

```java
    /**
     * Distance from this widget's top edge to its first text baseline, in logical
     * points: the alignment reference for {@link Flex.CrossAlignment#BASELINE}.
     * Default {@code height()} (align on the bottom edge, the correct fallback for a
     * widget with no text). Text-bearing components return {@code textTop() + ascent}.
     * Valid only after {@link #measure}.
     */
    protected float baselineOffset();
```

Overridden by `Button`, `Label`, `TextField`, `PasswordField`, `SearchField`, `ComboBox`, `Spinner`, `Checkbox`, `RadioButton` and `TabbedPane`'s headers. `BASELINE` places children so their `baselineOffset()`s coincide, then sizes the row to the deepest descender.

**(b) A documented tolerance for `CENTER`, since that is what mixed rows will use before (a) lands.** With each control centred, its baseline sits at `rowHeight/2 + (baseline_local − height/2)`; that bracketed term is the only thing that differs between steps:

| | XSMALL | SMALL | **MEDIUM** | LARGE | XLARGE |
|---|---|---|---|---|---|
| `textTop` = (h − lineHeight)/2 | 5.555 | 6.969 | **7.797** | 10.625 | 13.867 |
| local baseline = `textTop + ascent` | 15.760 | 18.102 | **20.785** | 25.469 | 31.494 |
| `baseline − h/2` (the CENTER offset) | 3.760 | 4.102 | **4.785** | 5.469 | 6.494 |

The `textTop` and baseline rows moved with decision 3's height ramp; **the CENTER offset row did not, and cannot.** It reduces to `ascent − lineHeight/2` = `body × 0.341796875`, a function of the type ramp alone, because the height cancels out. So no height ramp can fix cross-step baseline alignment, which is what makes `BASELINE` a mechanism rather than a tuning exercise.

So a `CENTER` row mixing **adjacent** steps misaligns baselines by ≤ 0.69 pt (SMALL beside MEDIUM is exactly 0.683 pt) and across the full ramp by 2.73 pt. `ControlSize`'s javadoc states this, recommends `BASELINE` for text rows and `CENTER` (never `START`) otherwise, and §10.2 #22 asserts it for all ten step pairs.

---

## 4. What never scales

### 4.1 The rule

> A quantity is **pixel-locked** if it expresses a *weight*, a *rasterization correction derived from a weight*, a *device fact*, or a *human motor constant*. It is **tabled** if it expresses an *extent* or an *optical gap*.

Enforcement is structural, not editorial: pixel-locked values live in `limn.components.Strokes` as `public static final float`, and `SizeTokens` has **no row** for any of them and **no `double` field of any kind** (so an animation duration cannot be smuggled in). A stroke cannot accidentally be scaled because there is nowhere to put the five columns. `MIN_HIT_TARGET` lives in `Strokes` too; it is a *layout* input rather than a stroke, but the moment one five-column row holds one value five times the enforcement is editorial again, and the taxonomy note belongs in a comment, not in a location.

`Snapping.strokeWidthDev(w, scale) = max(1, round(w * scale))` then does the rest: a locked 1 pt border is exactly `max(1, round(1 × scale))` device px (1, 1, 2, 2 at scales 1.0 / 1.25 / 1.5 / 2.0), **identical across all five steps** at a given content scale. That is the user's requirement, mechanically guaranteed, and §10.3(f) checks it from PNGs.

### 4.2 The inventory

| constant | value | drawn at | why it never moves |
|---|---|---|---|
| `BORDER` / `HAIRLINE` | 1 | 17 sites: `Button:131`, `ToolBar:73`, `SegmentedControl:134`, `TextField:337`, `TextArea:350`, `ComboBox:305`, `Spinner:281/319/320`, `Separator:61/64`, `MenuBar:107`, `PopupMenu:642/659`, `TabbedPane:370`, `Dialog:482/489`, `Checkbox:154` | The user's invariant, literally. A toolbar and the buttons inside it share one border weight; scaling either breaks that read. |
| `HALF_PIXEL_INSET` | 0.5 | every `drawRoundRect(0.5f, 0.5f, w−1, h−1, …)` (12 sites) | Half of `BORDER`. Lands a 1 pt stroke on a whole device pixel instead of straddling two and going grey. A function of the stroke, not the size: at XLARGE it must still be 0.5. |
| `FOCUS_RING` | 2 | **Two separate `drawRoundRect`/`drawCircle` calls only: `Button:139` and `Slider:188`.** The four text-cluster components do **not** draw a second ring; see the note below. | WCAG-style visible-focus indicators are specified in *absolute* thickness. Uniform focus affordance across a form that mixes steps is the whole point. |
| `FOCUS_RING_THIN` | 1.5 | `Checkbox:125`, `RadioButton:136`, `TabbedPane:598`; `ListView:428` is `1.5 + 0.5·focusFade` | The thin focus weight used on indicator-scale controls. Two named weights, not an accident. |
| `FOCUS_GAP_BUTTON` | 2 | `Button:138` (ring at −2, extent +4) | The user named focus rings **and their gaps**. A 2 pt stroke centred 2 pt out leaves exactly 1 pt clear. |
| `FOCUS_GAP_CHECK` | 1 | `Checkbox:124` | idem. Preserved at 1 (not reconciled to 1.5); see §8.3 D3. |
| `FOCUS_GAP_RADIO` | 1.5 | `RadioButton:136` | idem. |
| `FOCUS_GAP_SLIDER` | 3 | `Slider:35`, applied `:188` | idem. Consequence accepted deliberately: Slider's height derives from `2·(knobHover + gap + BORDER)`, so pinning the gap makes small steps proportionally taller than the knob ramp predicts. The ring needs absolute room. |
| `FOCUS_GAP_TAB` | 2 | `TabbedPane:598` | idem, and `tabHoverInset > FOCUS_GAP_TAB` must hold at every step (§7.25). |
| `FOCUS_RING_OUTSET` | 3 | new `paintOutset()` overrides | The declared paint reach of a ring at −2 with a 2 pt centred stroke. Locked because the ring is. |
| `INDICATOR_BORDER` | 1.5 | `Checkbox:137/138`, `RadioButton:123`, `Slider:192` | The brief verbatim: "a 1.5 pt border stays 1.5 pt at XSMALL and at XLARGE". |
| `CHECK_MARK` | 2 | `Checkbox:143` | Named explicitly. The mark's *extent* scales (via `indicator` fractions); the pen does not. With `indicator = 15` at XSMALL (§7.3) the mark keeps **1.83 pt** of clearance from the box border's inner ink edge, against 1.75 pt at MEDIUM, so the 2 pt pen is optically sound at every step and the two-tier fallback is not needed. Open Question 5 is closed. |
| `MENU_CHECK_PEN` | 1.8 | `PopupMenu:704-705` | A tick is heavier than an arrow by convention. |
| `ARROW_PEN` | 1.6 | `Spinner:343`, `PopupMenu:695-699/711`, `TabbedPane:496` | Icon line weight. The glyph grows, the pen does not: the clearest expression of size-vs-weight separation in the toolkit. Requires the §4.4 glyph floors to stay legible at the dense end. |
| `COMBO_CARET_PEN` | 1.8 | `ComboBox:325` | Preserved at 1.8 (not unified to 1.6); see §8.3 D4. |
| `TAB_INDICATOR` | 2.5 | `TabbedPane.INDICATOR_THICKNESS:51` | **Decision: locked, not tabled.** Over a 2.1× strip range 2.5 pt reads correctly at both ends, and locking it keeps the sliding indicator inside `height()` for free (`y = height() − thickness`), so no `paintOutset` question arises on `TabStrip` and the §3.6 parity rule does not apply. |
| `CARET` | 1 | `TextField:362/377/434/671`, `TextArea:399/437/693` | Named in the brief. **Five literal copies today**; extracted to one constant *before* any step ships, or one gets scaled by accident. |
| `INK_BLEED` | 1 | `TextField:362/370/409/411/669`, `TextArea:399/437` | The ±1 pt overshoot/inset that makes a caret bracket its glyphs and a selection band clear the tallest ascender. Optical hairline correction; the `metrics.height()` term inside already scales. **Only meaningful if it is measured from the ink box**: §7.8 fixes the anchor that made it swing between 0.03 and 1.97 pt. |
| `AA_BLEED` | 2 | `TextField:352` clip (−2/+4) | Antialiasing fringe allowance, an absolute device effect. Scaling it would let 2 pt of the leading pad be overwritten at XLARGE. |
| `DAMAGE_MARGIN` | 2 | `TextField:717`, `TextArea:735` | Commented "margin for AA + hairline snap". Growing it at large steps inflates per-blink damage and defeats the partial-rendering optimization the codebase exists to have. |
| `CLIP_CLEARANCE` | 1 | `TextField:274/275`, `TextArea:316` | 1 pt of slack so a 1 pt caret clears a hard-edged clip. `:274` and `:275` must stay *identical* or the caret oscillates per keystroke. |
| `MIN_SELECTION_SLIVER` | 2 | `TextArea:388` | Visibility floor so a zero-width selection on an empty line still shows. 2 pt is the minimum that survives AA at any size. |
| `IME_UNDERLINE` / `_ACTIVE` | 1 / 2 | `TextField:424/426`, `TextArea:428/430` | Platform IMEs draw a hairline regardless of text size. The 1-vs-2 *contrast* carries the meaning (converting block vs rest); a scaled 0.5-vs-1 pair at XS would erase it. |
| `ROW_CLIP` | 1 | `PopupMenu:648/679/682/690`, `ComboBox:520` | Its value *is* the border width. |
| `ROW_GUTTER` | 1 | `ComboBox:528/529` | The 1 pt gap that stops two highlighted rows fusing. At XLARGE a scaled 3 pt gap becomes a deliberate-looking stripe. |
| `SUBMENU_OVERLAP` | 2 | `PopupMenu:577/579` | Exactly twice the border, existing to hide the seam between two columns' borders. 4 pt at XL looks glued; 1 pt at XS reveals a gap. |
| `SPINNER_HOVER_INSET` | 1 | `Spinner:331` | Its value is the divider width: the hover fill must never cover the 1 pt dividers. |
| `SCROLLBAR_MARGIN` | 2 | `ScrollBar:68`, used `:189/:206/:214/:216/:279` | Chrome breathing room; also keeps `thickness() = WIDE + 4` trivial. Used in several places that must move in lockstep or dragging desynchronises from painting, and one constant guarantees that. |
| `MENU_SCROLL_HINT_H` | 12 | `PopupMenu:63` | A *control* band that intercepts clicks before item activation (`:762`, `:883-888`). Below ~10 pt the scroll/activate boundary is unaimable. Locked at 12. |
| `WHEEL_STEP` | 48 | `ScrollView:24/244`, `TabbedPane:55`, `TextArea:35/479`, `ListView:65/470`, `PopupMenu:64/805/887`, `ComboBox:43/569` | **A wheel detent is a device unit.** The same physical flick must move the same physical distance in a dense list and a roomy one; making a compact list travel *less* per notch is backwards. This one rule closes five separate "needs-decision" items and keeps `ScrollViewTest:62` and `TextAreaTest:79` green as *guards*. Consequence documented: a notch covers ~1.7 menu rows at MEDIUM and ~2.0 at XSMALL. |
| `MIN_HIT_TARGET` | 24 | `Widget.hitOutset()` consumers and the two width-axis floors (§3.3 point 2) | WCAG 2.2 SC 2.5.8 (AA). The accessibility floor does not scale. **In `Strokes`**, not in `SizeTokens` (§4.1). |
| `DRAG_SLOP` | 2 | `Viewport3D:162` | Hand jitter, a motor constant. Growing it at XLARGE would make deliberate small drags register as clicks. |
| `ICON_OPTICAL_BUMP` | 2 | `Button:69`, `Label:164` | Icon glyphs have no ascender/descender slack and must be drawn slightly larger than the text box to read at the same weight. A constant visual correction, not a proportion. |

**Correction to the focus-border inventory, and it matters.** Revision 1 listed `FOCUS_RING = 2` as "drawn at `TextField:386`, `TextArea:351`, `ComboBox:306`, `Spinner:282`" and separately listed `BORDER = 1` at the same statements. There is no literal `2` at any of those lines. All four draw **one** rounded rect whose stroke width is the expression `1 + focus`, so the border thickens continuously 1 → 2 as the focus transition runs (verified: `TextField.java:385-386`, `TextArea.java:350-351`, `ComboBox.java:305-306`, `Spinner.java:281-282`). A contributor extracting the constant and writing `focus > 0 ? FOCUS_RING : BORDER` would **delete the animation on four components**. The migrated form is exactly:

```java
canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, t.radiusMedium(),
        Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus, borderColor);
```

Consequently §10.2 #13 must paint at a **settled** transition state (`focus` exactly 0 or 1) or it records frame-dependent fractional widths from these four and is flaky by construction.

### 4.3 Also locked, and not strokes

- **All six animation tokens** (`animEasing`, `animHover` 0.12, `animFocus` 0.14, `animFade` 0.18, `animTab` 0.22, `animWindow` 0.16; `Theme.java:72-82`). Motion duration tracks perceptual constancy: an XSMALL button fading in 0.08 s beside an XLARGE one at 0.20 s feels like two products. They stay on `Theme`. This is the most likely accidental inclusion in a mechanical token refactor, which is why `SizeTokens` declares no `double`.
- **Free-axis preferences the parent overrides**: `Slider.PREFERRED_WIDTH` 220 and `ProgressBar.DEFAULT_WIDTH` 220 (which must stay *equal* so a slider and a bar stacked in a form align), `ListView`'s 240 unbounded-width fallback, `Separator.FALLBACK_LENGTH` 24, `Viewport3D` 360×260, `ImageView` natural size and `setPreferredSize`.
- **Value-domain increments**: `Slider:152/156` key and page step (a compact slider must not jump in coarser steps), `ScrollBar:269` page = one viewport, `ListView:540` rows-per-page, `PopupMenu`'s reveal in rows, `TextArea:359`'s `+1` overdraw line, `ListView:239`'s `6 × avgRowHeight` (the 6 is a row *count*).
- **Every threshold, epsilon and alpha**: `0.001` focus visibility, `0.05` check/dot visibility and `ScrollBar` hit gate, `0.5` overflow and scroll dead-bands (`TabbedPane:300/337/338`, `ScrollBar:146`, `PopupMenu:678`), `0.02` paint gate, `Math.max(1, …)` divide-by-zero guards, the `10_000` measure sentinel, the `−1` preferred-size sentinel, `Constraints.UNBOUNDED_LIMIT`, `ProgressBar.SWEEP_FRACTION` 0.35 and `SWEEP_SECONDS` 1.1, `TabbedPane.SCROLL_STEP_FRACTION` 0.75, `Viewport3D.SAMPLES` 4, `ComboBox.EDGE_MARGIN` 8 (screen furniture, not component density), `Dialog.SLIDE_DISTANCE` 14, the tooltip pointer offsets `+12 / +20 / −8` and the 4 pt screen margins (`Scene.java:1815-1819`, screen furniture; the tooltip *box* does scale, §7.31).
- **`Viewport3D:224-225`**: `max(1, round(extent × canvas.contentScale()))`. The one `contentScale()` use in `limn-components`, and the clearest illustration that a size step is not a DPI scale. Nothing about the axis may enter it.

### 4.4 Glyph floors: the other end of the irreducible-glyph clamp

A pixel-locked pen meeting a shrinking glyph is the one place where locking a weight *destroys* the shape. The rule: **for every locked pen, `pen ÷ glyphMinExtent ≤ 0.45` at all five steps** (§10.2 #17). The dense cells are therefore floored at their MEDIUM extent:

| glyph half-extent | XS | SM | **MD** | LG | XL | pen | `pen ÷ minExtent` at XS |
|---|---|---|---|---|---|---|---|
| `chevronHalfW` (ComboBox; half-h = `/2`) | 5 | 5 | **5** | 6 | 7 | 1.8 | 0.36 (was 0.51 at 3.5) |
| `arrowHalf` (Spinner) | 4 | 4 | **4** | 5 | 6 | 1.6 | 0.40 (was 0.53 at 3) |
| `scrollChevronHalf` (PopupMenu) | 4 | 4 | **4** | 4.5 | 5 | 1.6 | 0.40 (was 0.53 at 3) |
| `tabChevron` (TabbedPane) | 5 | 5 | **5** | 6 | 7.5 | 1.6 | 0.16 |
| `checkGlyphW` (PopupMenu tick) | 9 | 9 | **9** | 10.5 | 12 | 1.8 | 0.36 (was 0.50 at 6.5) |
| `menuArrowW × H` | 5×8 | 5×8 | **5×8** | 6×9.5 | 7×11 | 1.6 | 0.20 |

Costs nothing at MEDIUM, nothing in gutter width (`2·arrowHalf ≤ spinnerButtonW − 4` still holds: 8 ≤ 14 at XSMALL), and keeps the chevron's half-height at exactly `halfW / 2` so the arrow *angle* stays invariant. **Clamp the glyphs OR two-tier the pens, never both**; clamping is preferable because it keeps the brief's invariant literally true.

### 4.5 Icon boxes: integer at four of five steps, and a raster-cache budget

Icon extents are font-derived (`measure("Hg", body).height() + ICON_OPTICAL_BUMP` for Button/Label; the same without the bump for TabbedPane headers), which follows the ramp for free but produces five fractional keys per content scale. `SvgIcon` keeps an LRU of rasterizations bounded at `MAX_CACHED_SIZES = 8` (`SvgIcon.java:34-44`), keyed on `round(size × contentScale())` (`Icon.paint`), with a single-entry `lastSize/lastImage` fast path in front of it.

| icon box | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| Button / Label (`+2`) | 15 | 16 | **18.40625** | 21 | 24 |
| TabbedPane header (no bump) | 13 | 14 | **16.40625** | 19 | 22 |
| TextField in-field (`round(lineHeight)`, floor 8) | 13 | 14 | **16** | 19 | 22 |

The four non-MEDIUM cells are the font-derived value **rounded to an integer**; only MEDIUM keeps the fraction, so D6 shrinks to "round MEDIUM's cell too". Two required consequences, which revision 1 budgeted for the glyph atlas and not at all for icons:

- **Raise `MAX_CACHED_SIZES` to 16** (5 steps × up to 3 simultaneously live content scales, plus headroom). A two-display app at 1.0 and 2.0 already needs 10 keys per icon; at 8 the LRU evicts on every paint and `rasterizer.rasterize` re-runs per icon per frame.
- `BitmapIcon.image` picks "the smallest variant that still covers" and `Icon.paint` then scales it into the box, so a bitmap icon is resampled wherever the box is fractional, i.e. at MEDIUM only, after this change. §7.10 trap 2's demand that the cache "key on `(icon, resolvedSize, contentScale)`" is **already satisfied**; the real hazard is the bound, and naming the wrong one hid it.

---

## 5. Architecture

This section follows the inheritance deep-dive, which was written against the files rather than against the earlier memo. Four of its findings override the memo and drive everything below:

1. `Widget.measure()` is the **sole gateway** to `onMeasure` in the whole repo (`Widget.java:387-396`, verified by grep), and it caches on `(needsMeasure, lastConstraints)`. Adding the resolved step to that cache key makes deep invalidation **unnecessary**: no `markSubtreeNeedsLayout` API.
2. `Widget.parent` is assigned in exactly **two** places (`Widget.java:74` in `add`, `:83` in `remove`), but the *scene* of a subtree, which is also a resolution input, is written by **five** sites, all funnelled through `Widget.setSceneRecursively` (`:92`): `add`, `remove`, `Scene`'s constructor (`Scene.java:169`), `Scene.pushOverlay` (`:275`) and `Scene.removeOverlay` (`:293`). That funnel, not `add`/`remove`, is what makes a memo provably exact (§5.3).
3. `Scene.pushOverlay` (`Scene.java:266-280`) adds to a list and calls `setSceneRecursively` but **never sets a parent**, so an in-scene `Dialog` (and an in-scene `PopupMenu`, which is the path every headless test and MenuBar's fullscreen fallback take) is parentless *inside the same window*. The out-of-tree problem is not limited to popup windows.
4. `Scene`'s font-change listener is subscribed **only inside `bind(NativeWindow)`** (`Scene.java:222-223`). Every headless scene is therefore unreachable from any global metrics change, and `layoutPass` early-returns on `!layoutDirty` at the same size (`:2047-2049`). §5.4 fixes this or nothing global works in tests.

### 5.1 Module layering

| Concern | Home | Depends on |
|---|---|---|
| The 5-value ordinal, the per-widget slot, the walk, the process default, `hitOutset`, `baselineOffset` | **`limn.scene.ControlSize`** + fields/methods on `limn.scene.Widget` / `Scene` / `Flex` | nothing but `limn.concurrent.Ui` |
| What a step *means* (fonts, heights, paddings, radii) | **`limn.components.SizeTokens`** + `Theme.tokens(...)` / `Theme.tokensFor(...)` | `limn.scene.ControlSize` (components → toolkit, allowed) |
| What deliberately does not scale | **`limn.components.Strokes`** | nothing |
| Pushing tokens into geometry-only containers | **`limn.components.Tokens`** + `TokenRow` / `TokenColumn` / `TokenPadding` | both of the above |

The enum belongs in `limn.scene` for two concrete reasons, not for tidiness:

- **Resolution becomes a plain field walk over `Widget.parent`**: no `instanceof`, no side table, no allocation. Same shape as `Scene.applyCursor` (`Scene.java:1246-1257`).
- **Raw scene containers become size scopes.** Every demo scene is `Padding(Column(...))` (`CaptureScenes.java:230-239`) and `Bench.java:120` is `Padding → Column → Expanded → ScrollView → Column → Row → …`. If the enum lived in `limn-components`, a `Column` could neither declare a step nor be walked through, and the chain would silently skip the layer that is *always* present. `new Row().setControlSize(SMALL)` making its whole subtree compact is the primary ergonomic win and it requires this split.

**Rejected: an opaque `int` slot on `Widget`** (the "toolkit knows nothing" variant). `row.setControlSize(2)` is unreadable and unvalidatable, and both the token table and the demo matrix need `ControlSize.values()`.

**Rejected as the primary mechanism: the `installTooltipStyle` SPI** (`Scene.java:1713-1715`, installed from `Theme.java:213-220`). That pattern solves "the toolkit needs a *value* it cannot name" (the token direction). The axis direction needs a mutable per-widget field and a walk over `parent`, and an SPI can host neither. It is the right pattern for the wrong half. It is kept for exactly one real consumer, §5.7.

### 5.2 Resolution rule

```
own declared value
  →  nearest declaring ancestor (walk Widget.parent)
  →  this widget's Scene's declared default        (nullable)
  →  host link (§5.5): recurse from the host, which continues through the host's own scene
  →  ControlSize.processDefault()
```

**The scene default precedes the host link, and the order is load-bearing.** Every hosted root (`ComboBox.PopupPanel`, `PopupMenu.MenuSurface`, `Dialog`'s panel in both modes) has a host link. With the host link consulted first (revision 1's order), `Scene.setControlSize` would be **unreachable for every popup, menu and dialog scene in the process**, while §6.3 advertises it as "the per-window root of the inheritance chain (a compact tool window beside a regular main window)", and a compact tool window is exactly a window whose root is a hosted panel. `Scene.controlSize()` is therefore **nullable**, and a null scene default is precisely what lets a popup scene fall through to its host instead of stopping at its own empty scene.

Resolution is **lazy, at measure/paint/event time, never in a constructor**. Two independent reasons, both in the code:

- `Widget.add` (`:62-77`) assigns `child.parent` **after** the child is fully constructed, so `new Button("OK")` runs with `parent == null` and would resolve to the process default no matter what its eventual parent declares.
- `Widget` has **no reparent hook**. `onAttached()` fires only on the `null → scene` edge (`Widget.java:97-101`), so the universal builder order (`toolbar.setControlSize(SMALL); toolbar.add(button); scene.root().add(toolbar);`) never fires it for the button, and a move between two already-attached parents fires nothing. Attach-time push-down is not merely inconvenient, it is **incorrect for how every demo scene is written**.

### 5.3 Caching

A memo per widget, validated against a **global epoch**:

```java
private static long controlSizeEpoch = 1;      // UI-thread confined; a plain long suffices
private ControlSize declaredControlSize;       // null = inherit
private Widget controlSizeHost;                // logical parent when parent == null
private ControlSize resolvedControlSize;       // memo
private long resolvedEpoch;                    // valid iff == controlSizeEpoch
```

`controlSize()` returns the memo when `resolvedEpoch == controlSizeEpoch`, otherwise resolves **one step up and delegates** (`parent`, then `scene`, then `controlSizeHost`, then the process default) and memoizes. Recursion, not a loop, on purpose: the parent memoizes its own answer first, and both the measure pass and the paint pass walk top-down, so after one cold pass every widget resolves in a long compare plus a field read. The chain is walked **once per epoch per path**, not once per read. Recursion depth is the tree depth, shallower than `paintWidget`'s.

The epoch is bumped by exactly **five** writers of resolution inputs, each O(1):

| writer | why |
|---|---|
| `Widget.setControlSize` | the declared value |
| `Widget.setControlSizeHost` | the out-of-tree edge |
| **`Widget.setSceneRecursively`** | the single funnel for `add`, `remove`, `Scene`'s constructor, `pushOverlay` **and** `removeOverlay` |
| `Scene.setControlSize` | the per-window default |
| `ControlSize.setProcessDefault` | the process default |

Bumping in `setSceneRecursively` rather than in `add`/`remove` is the correction that makes the memo *provably* exact rather than hopefully exact: `pushOverlay`/`removeOverlay` move a whole **parentless** subtree between scenes without touching any parent field, so a widget that resolved and memoized before being pushed into a scene with a different default would otherwise keep a stale answer forever. Asserted by §10.2 #7b.

| Strategy | Read | Mutation | Fails on |
|---|---|---|---|
| Plain walk, no memo | O(depth) **per read**, 2–6 reads/widget/frame | 0 | nothing, just wasteful |
| **Memo + global epoch + parent recursion (chosen)** | O(1) steady; O(1) amortized on the first pass after a bump | O(1) | nothing |
| Memo + per-subtree invalidation | O(1) | O(subtree) per `add`/`remove` | host-linked subtrees have no back-pointer and go stale silently |
| Push down on attach | O(1) | O(subtree) writes per mount | `onAttached` never fires for the builder pattern; and `ListView` mounts cells from inside `onLayout` (`ListView.java:324`), so a push would re-dirty layout mid-layout |

The chosen scheme's worst case is a **degradation, never a wrong answer**: if the epoch churns, reads fall back to the walk. That is why a global counter is preferred over precise subtree invalidation.

### 5.4 Invalidation

**The resolved step joins the measure cache key.** This is the design decision that deletes the memo's proposed `markSubtreeNeedsLayout`:

```java
public final Size measure(Constraints constraints) {
    ControlSize size = controlSize();
    if (!needsMeasure && size == measuredControlSize && constraints.equals(lastConstraints)) {
        return lastSize;
    }
    lastSize = Objects.requireNonNull(onMeasure(constraints), "onMeasure returned null");
    lastConstraints = constraints;
    measuredControlSize = size;
    needsMeasure = false;
    return lastSize;
}
```

`toolBar.setControlSize(SMALL)` then needs nothing but `markNeedsLayout()`:

1. `markNeedsLayout()` sets `needsMeasure` up the ancestor chain and calls `Scene.markLayoutDirty()` → `layoutDirty = true; requestRender()` (`Scene.java:631-634`).
2. The next `layoutPass` re-measures from the root and calls `child.measure(...)` on everything.
3. Each descendant sees `needsMeasure == false` but `controlSize() == SMALL != measuredControlSize == MEDIUM` → **re-measures**, recursively.
4. A descendant that **declares its own** step compares equal → **cache hit, subtree untouched.** "Do not relayout overriding subtrees" is satisfied structurally, not as an optimization someone has to remember.

Correctness is now a property of the cache key: the only way a widget can return a stale size is if its resolved step *and* its constraints *and* `needsMeasure` all say nothing changed, in which case nothing did.

The scene and process defaults change every widget's resolved step, so they go through `Scene`:

```java
// Scene: per-window root of the chain
public void setControlSize(ControlSize size);   // bumps the epoch, then relayout()

public void relayout() {                        // renamed from relayoutForFontChange
    root.markMeasureDirtyDeep();
    for (int i = 0; i < overlays.size(); i++) {
        overlays.get(i).markMeasureDirtyDeep();   // overlays are NOT in root's tree
    }
    layoutDirty = true;
    requestRender();
}
```

The **global** path must go through `Scene`, never through app code, precisely because of that overlay loop: an app calling `root.markNeedsLayout()` would miss every open dialog and menu.

**Prerequisite fix, without which the whole global path is dead in tests: move the listener subscription out of `bind` into the `Scene` constructor.** `Scene.fontChangeListener` is registered only at `Scene.java:222-223`, inside `bind(NativeWindow)`, and removed at `:1999`. So today an unbound scene never hears a global metrics change, and that covers *all* 99 component tests, `ComboBox`'s popup scene, and `Dialog.modalScene` between `new Scene(panel)` (`:300`) and `bind` (`:314`); because `layoutPass` early-returns on `!layoutDirty` at an unchanged size, nothing re-measures. §10.2 #5, #11, #12 and the `setProcessDefault(MEDIUM)` reset §10.1 adds to `@BeforeEach` would all **silently do nothing**. The wrapper is generalized from `FontChangeListener` to `GlobalMetricsListener`, subscribing to `Fonts` and `ControlSize`, constructed and registered in the `Scene(Widget, LongSupplier)` constructor and removed on close at `:1999` as today. The reference must stay **weak** for the reason its own javadoc gives (`Scene.java:188-194`): a scene replaced on a live window never receives `windowClosed`, and a strong process-wide listener would pin the abandoned tree forever. `bind` keeps its remove-before-add so a rebind never double-registers. This also closes the same latent hole for runtime font-family switches on unbound scenes.

**Pre-existing documentation bug fixed here.** `Theme`'s class javadoc (`Theme.java:17-18`) tells callers to call `root.markNeedsLayout()` after a theme switch "so sizes/typography update". That has never worked: `markNeedsLayout()` walks *up*, so children return cached sizes under unchanged constraints, and it misses overlays. It is harmless today only because all 13 palettes share byte-identical radii, fonts and spacing, so a theme switch changes only colours. It must be corrected to point at `Scene.relayout()` in the same change. The `kitchen-toggle` capture (`Main.java:174-176`) exercises exactly this path. See §7.30 for the decision that keeps it harmless *permanently*.

**Damage / partial rendering: nothing to add, and nothing to optimize.** `requestRender()` sets `fullDamagePending = true` (`Scene.java:440-443`), and independently the frame loop enforces it: *"A layout pass can move any widget without it invalidating its old bounds: layout frames are always full, which is a structural invariant, not a convention"* (`Scene.java:1404-1409`). A step change produces exactly one full frame, which is the only correct answer, since the widget's *old* bounds are already gone by the time anything could damage them. Do not attempt to make size changes partial.

### 5.5 Reparenting and out-of-tree roots

`add` and `remove` write `parent` and both funnel through `setSceneRecursively`, which bumps the epoch, so the resolved value is correct immediately after either. Two consequences of the *existing* hooks:

- `remove()` sets `parent = null` **before** `setSceneRecursively(null)` fires `onDetached()` (`Widget.java:83-84`, `:110-115`), so a widget resolving `controlSize()` inside `onDetached` gets the scene default, not its former inherited step. Nothing does this today; the javadoc says why not to. (It is the *scene* default and no longer the process one, since `scene()` now answers the scene being left for the duration of that hook; the parent chain is what is gone by then, not the scene.)
- `ListView` mounts cells with `add(cell)` from inside `onLayout` (`ListView.java:324`) and unmounts with `remove(cell)` (`:338`), every frame while scrolling. The epoch bump is O(1) and triggers no cascade, so mounting stays safe mid-layout. Recycled cells (`adapter.recycle`) resolve against their new chain on the next `measure()` and the cache key catches the change; a push-down design would have needed an explicit re-push hook here.

The parent walk cannot cross a `Scene` boundary or an overlay, so **five** sites need an explicit host link (revision 1 listed four and missed the in-scene menu overlay, which is the path every headless test takes):

| Site | Why the walk stops | Link, and *when* |
|---|---|---|
| `ComboBox.PopupPanel` (`ComboBox.java:242-243`) | `new Scene(popupPanel)` → `parent == null` | `popupPanel.setControlSizeHost(this)` at creation, alongside `inheritRenderingFlags` (`:245`) |
| `PopupMenu` **native window** (`PopupMenu.java:307-320`) | `popupScene = new Scene(surface)` | `surface.setControlSizeHost(anchor)` **before** `surface.reposition()` / `boundingBox()` at `:311-315`, which size the window from the resolved row metrics |
| `PopupMenu` **in-scene overlay** (`PopupMenu.java:260-270`) | `owner.pushOverlay(surface)` never sets a parent | same host, installed **before** `pushOverlay` at `:270` |
| `Dialog` native window (`Dialog.java:300`) | `modalScene = new Scene(panel)` | `panel.setControlSizeHost(owner.root())` immediately after `:300`, i.e. **before** the sizing `panel.measure(...)` at `:305-306` |
| `Dialog` **in-scene** overlay (`Dialog.java:366-368`) | `pushOverlay` never sets `parent` | `panel.setControlSizeHost(owner.root())` before `pushOverlay` |

**The ordering constraint is not cosmetic.** `Dialog.presentNative` sizes the native window from `Math.min(MAX_PANEL_WIDTH, window.logicalWidth())` and `panel.measure(...)` at `:305-306`, *before* `modalScene.bind(modalWindow)` at `:314`. Install the host after that and the window is sized at the process default while the content then re-measures at the owner's step inside a wrongly-sized window, so the card is clipped or floats. §7.13 trap 2 makes exactly this point for the ComboBox popup; it applies verbatim here and to `PopupMenu.createNativeWindow`.

**`PopupMenu` needs new public API before any of this can be wired**, because its entire public surface takes a `Scene`:

```java
// today: no anchor widget exists anywhere, so "host = the menu widget that opened it"
// is unimplementable as written
public void showAt(Scene ownerScene, float x, float y);                                  // :160
public void showAnchored(Scene ownerScene, float ax, float ay, float aw, float ah);      // :170

// added
public PopupMenu setControlSize(ControlSize size);            // explicit override, forwarded to surface
public void showAt(Widget anchor, float x, float y);          // host = anchor
public void showAnchored(Widget anchor, float ax, float ay, float aw, float ah);
```

The `Scene` overloads keep working and host on `ownerScene.root()`; that fallback is documented, because it is the difference between "a context menu over an XSMALL data grid is XSMALL" and "it is whatever the scene says". `MenuBar.openMenu` (`:239`) passes `this`, which is what makes §7.21 trap 1 implementable at all. An app context menu becomes `new PopupMenu(m).showAt(grid, x, y)`.

The link is **live**, so a later change on the owner reaches an open popup. Explicit forwarding (`popupPanel.setControlSize(controlSize())`) would not do that, and would additionally convert an *inherited* value into a *declared* one, pinning the popup if the process default changed underneath it. `setControlSizeHost` validates the chain is acyclic (`add` already rejects tree cycles, so by induction no cycle exists when the walk runs).

`controlSize()` and `setControlSize` are **`final`** on `Widget`. A subclass that computed a step on the fly would produce sizes the cache key cannot see; a composite that owns widgets outside its subtree reaches for the host link instead.

### 5.6 HiDPI composition

```
logical points = token(step)                       ← this feature   (limn.scene + limn.components)
device pixels  = logical points × contentScale()   ← backend only   (GlCanvas, Icon, Snapping, Scene)
```

They compose by multiplication, in that order, and must never be conflated. No layout code reads `contentScale()` today and the axis must not either. Three snapping sites, all in the backend, all in *device* space: `Snapping.strokeWidthDev`/`snapCenter` for strokes, `GlCanvas.drawText`'s rounding of run origin and baseline to whole device pixels (`:576-592`, so letters do not dance during a live resize), and outward snapping of damage rects (`Scene.java:1527-1542`).

Fractional per-step **font sizes are harmless**: device font size is quantized to 1/8 device px (`GlyphAtlas.SIZE_QUANTUM = 8`) and the advance error is corrected exactly by a size ratio (`GlCanvas.java:569-574`). Fractional **box extents** are constrained by §3.6.

**Glyph atlas.** `GlyphAtlas` keys on `(faceId, quantizedDeviceSize, codepoint)` with 1 MiB pages and a soft cap of 4. Latin: a 14 pt glyph at 1× is ~11 × 15 = 165 texels, so a page holds ~6300; five steps × three weights × ~200 glyphs ≈ 3000 glyphs: **well under one page, non-issue.** CJK at 2×: ~28 × 28 = 841 texels, so a 3000-glyph working set is ~2.4 pages *per step*; five simultaneously visible steps → ~12 pages against a 4-page cap. Eviction degrades gracefully rather than thrashing (pages untouched by the frame that just ended are dropped; *"when every page is hot… staying over budget beats re-rasterizing the whole atlas every frame"*), so the outcome is ~12 MiB resident instead of ~4 MiB with no churn. Documented in `ControlSize`'s javadoc: each *simultaneously visible* step multiplies the glyph working set, which is meaningful only for CJK/emoji-heavy UIs. `StbFont.scaleForSize`'s single-entry memo misses once per size switch, not per glyph (size is constant within a run), so it costs tens of JNI calls per frame; no action.

**Icon raster caches get the same treatment, in §4.5.** They were the omission here: five steps produce five keys per content scale against an LRU bound of 8, which is the one place this feature can turn a cache into a per-frame rasterizer.

### 5.7 Geometry-only containers, and the one place the tooltip SPI still earns its keep

**A raw `Flex.gap(float)`, `Padding` insets or `SizedBox` extent is a *literal* and does NOT follow the step.** `Flex.gap` (`Flex.java:29-32`) and `Padding.insets` (`Padding.java:11`, `final`) hold plain floats, so `row.setControlSize(SMALL)` compacts every child and leaves the gutters *between* them at whatever number the app typed, and any app writing `col.gap(theme.spacingMedium)` bakes MEDIUM permanently. The spacing ramp is the widest of the three (3.33×) and revision 1 left it unreachable for the containers that own spacing, which also invalidated its own proof: §10.3(a)'s flagship capture builds rows through `CaptureScenes.column()` (`gap 10`, `:230-233`) inside `scene()`'s `Padding(Insets.all(22))` (`:236-238`), so all five rows of the PNG a reviewer is told to judge would show **identical gutters and identical page padding**.

Shipped API instead of a one-off Dialog recipe:

```java
// limn.components
public final class Tokens {
    public enum Role { SMALL, MEDIUM, LARGE }        // maps to spacingSmall/Medium/Large
    /** Idempotently sets {@code flex}'s gap from the step resolved on {@code flex}. */
    public static void applyGap(Flex flex, Role role);
    /** Idempotently sets {@code padding}'s insets from the step resolved on it. */
    public static void applyInsets(Padding padding, Role role);
}
public final class TokenRow     extends Row     { /* applyGap in onMeasure */ }
public final class TokenColumn  extends Column  { /* applyGap in onMeasure */ }
public final class TokenPadding extends Padding { /* applyInsets in onMeasure */ }
```

`Padding` loses its `final` (source- and binary-compatible) and gains a guarded `setInsets(Insets)` over a non-final field; `Flex.gap` gains an equality guard. Both also gain a `protected final` *silent* form used only from the measure path; see §5.9b for why an unguarded `markNeedsLayout()` from inside `onMeasure` is worse than wasteful. `Dialog` uses `TokenColumn`/`TokenRow`/`TokenPadding` rather than the raw ones, which is what makes the push legal at any depth. §10.3(a)/(b) are rewritten to derive gap and padding from the row's resolved tokens, and §10.2 #23 asserts that a `TokenRow` at SMALL and at LARGE lays its children out with different gutters.

**Tooltips.** The toolkit paints hover tooltips with `Theme.current().label` and `radiusSmall` through a global `Supplier<TooltipStyle>` (`Scene.java:1703/1713`, installed at `Theme.java:213-220`), with no widget in scope. Widening it is **two call sites plus a null gate**, not "one place":

```java
public static void installTooltipStyle(Function<ControlSize, TooltipStyle> supplier);

// TooltipStyle gains the box, not just the ink:
public record TooltipStyle(Color fill, Color border, Color text, Font font,
                          float radius, float padH, float padV) { }

// at BOTH tooltipRect() (:1806-1814) and paintTooltip() (:1823-1840), plus the gate at :1773:
ControlSize step = tooltipTarget == null ? ControlSize.processDefault() : tooltipTarget.controlSize();
TooltipStyle style = supplier.apply(step);
```

`tooltipTarget` (`:1704`) can be null or detached between `showTooltip` and paint (`tooltipRect()` is reachable from `damageTooltip()` on the hide path), so the null fallback is `processDefault()`, stated rather than left to the reader. And `TOOLTIP_PAD_H = 8` / `TOOLTIP_PAD_V = 5` (`Scene.java:1699-1700`) move **into the record**: leaving them pinned would put an 11 pt label inside a MEDIUM box, i.e. padding at ~27 % of a 21 pt panel, which is the inverse of the whole design. Values in §7.31. The pointer offsets and screen margins stay locked (§4.3), as do `TOOLTIP_DWELL_MS` / `TOOLTIP_FADE_SECONDS`.

Do not build a general `limn.scene.SizeMetrics` SPI speculatively.

### 5.8 Performance on the 750-widget bench

`Bench.java` builds `Padding → Column(page) → { Row(top: 10 × Expanded → ProgressBar), Expanded → ScrollView → Column(list) → 150 × Row → {Label, Expanded → TextField, Checkbox, Expanded → ProgressBar, Button}, Label(status) }`. Deepest path is **depth 8**; ~1230 widgets total (the "750" counts interactive leaves).

| scenario | cost |
|---|---|
| **Steady state** (no epoch bump) | one long compare + one field read per resolution. At a pessimistic 6 resolutions/widget/frame: ~7400 × 2 ops ≈ **15 k ops/frame**, no branches taken, **zero allocation**. Below the `--bench` harness noise floor. |
| **After a bump** | all memos invalid; the next paint walks top-down, so each widget re-memoizes in **one chain link**. Total O(n) ≈ 1230 links ≈ 2.5 k ops, once, inside a frame that is already a full relayout plus a full repaint. |
| **Scrolling the bench list** | zero bumps: `ScrollView.scrollTo` uses `moveChild` (`Widget.java:416-419`), not add/remove. A `ListView`-style mount/unmount would bump a few times per frame → a few × O(n) ≈ 5–10 k ops ≈ single-digit µs. |
| **Memory** | 2 refs + 1 long + 1 enum ref per widget ≈ 24 B; 1230 widgets ≈ **30 KB**, against a measured 3.5× heap advantage over Swing. |

**Zero allocation in the hot path** is structural: `controlSize()` returns an enum constant, `tokensFor` returns a preexisting record from a `static final` array.

**But the `Font` story needs a fix, not a boast.** Revision 1 claimed "15 `Font` instances, built once" and that the `FontStore.resolved` hazard was "impossible here". Both are false *today*: `Label.effectiveFont()` (`Label.java:150-159`) returns `base.bold()` / `base.italic()`, `Font` is a record and `bold()`/`italic()` allocate a fresh instance (`Font.java:45-52`), and `FontStore.resolved` is an `IdentityHashMap<Font, StbFont>` (`FontStore.java:55`) that **stops caching entirely past 4096 entries** (`:298-299`, comment "bound: hostile churn of `Font` instances"). So every strong/emphasis Label already allocates a new identity and misses the memo on every measure, layout and paint, and five steps multiply the churn.

Two changes:

- `SizeTokens` **interns the style variants of each role**: 5 steps × 3 roles × 4 styles = **60 stable `Font` instances**, built at class init. `Label.effectiveFont()` selects from the record instead of deriving.
- `Label` **memoizes its own derived font** in a field invalidated by `setFont` / `setStrong` / `setEmphasis`, so the escape-hatch path (an app-supplied `Font` plus bold) is stable under `==` too.

§10.2 #14 asserts `assertSame` across two consecutive `effectiveFont()` calls **on a bold Label**: the actual churn path, which the `assertSame(tokens(LARGE).body())` assertion does not cover.

Discipline that goes with it: **resolve tokens once per public entry point and thread the record down**, never inside a per-row or per-glyph loop. Required for *correctness*, not just speed, in three places: `SegmentedControl.edges()` is called from both `onMeasure:111` and `onLayout:117` and its result caches into `bounds[]` for hit-testing; `PopupMenu.Column` measures with `Theme.current().body` at `:1065/:1075` while `paintColumn` re-reads at `:644`; `Spinner` reads its font and `spacingMedium` in both `paintValue:286-288` and `selectFieldAt:399-402`. Two resolutions that disagree route clicks to the wrong segment, row or numeric field.

### 5.9 Construction-time hazards: complete enumeration

There are **83** `Theme.current()` call sites in `limn-components` (not the 167 the earlier memo claimed). Classified by enclosing scope:

**(a) Field initializers: 26 sites, all `Transition` config, all SAFE.** `Button:37-40`, `Checkbox:43-47`, `RadioButton:37-41`, `ComboBox:52-54`, `Slider:46-48`, `TextField:67`, `TextArea:58`, `Spinner:69`, `ListView:83`, `SegmentedControl:41-43`, `ProgressBar:34`, `TabbedPane:84-86`. Every one reads only `animHover`/`animFocus`/`animFade`/`animTab`/`animEasing`, which are not in `SizeTokens` and are identical across all 13 palettes, so they stay exactly as they are. They matter only because they are the pattern a contributor will copy: a step captured this way is permanently wrong with no path to recovery. Fenced by the javadoc on `controlSize()`, by a comment at the `declaredControlSize` field, and mechanically by the monotonicity test.

**(b) `Dialog`'s constructor: 4 real breaks, and the worst case in the codebase.** `Dialog.java:118-131` is the *only* place that bakes size-dependent values at construction, and `Dialog` is precisely the container the maintainer named as the LARGE case:

| line | baked | why it breaks |
|---|---|---|
| `:121` | `content.gap(theme.spacingMedium)` | a raw `Flex` holds the number; nothing re-reads it |
| `:122` | `new Label(title).setFont(theme.title)` | `Label.effectiveFont()` (`:151`) is `font != null ? font : Theme.current().body`, so a non-null font **wins forever**: a LARGE dialog renders a MEDIUM title with no error and no obvious cause |
| `:127` | `buttonRow.gap(theme.spacingSmall)` | as `:121` |
| `:131` | `new Padding(Insets.all(theme.spacingLarge), …)` | `Padding.insets` is **`final`** with no setter at all |

`:602` reads `spacingLarge` *live*, so today's `Dialog` would produce a half-updated card. Three facts revision 1's remedy snippet got wrong, all verified: `DialogPanel extends Widget` (`:451`) and `Widget.onMeasure` is `protected abstract` (`:397`), so `return super.onMeasure(constraints)` **does not compile**; `content` is a constructor **local** (`:120`) and so is the `Padding` (`:131`), because only `buttonRow` is a field (`:88`), so neither has a receiver to name; and `Padding` is `public final class`.

The fix:

```java
// fields, promoted out of the constructor
private final TokenColumn content;
private final TokenPadding padding;
private final TokenRow buttonRow;

// Dialog.DialogPanel: the panel itself pushes nothing; its children are Token* containers
// that resolve their own spacing inside their own onMeasure, at any depth.
@Override
protected Size onMeasure(Constraints constraints) {
    return child.measure(constraints);       // exactly what Dialog.java:464 already does
}
```

`Label` gains a typographic **role** so `Dialog` can ask for a title without pinning one:

```java
public enum Role { BODY, LABEL, TITLE }        // maps to body() / label() / title()
public Label setRole(Role role);                // scales with the resolved step
```

`setFont()` remains the explicit escape hatch and still beats the step; when it wins, the icon box keeps using the font-derived formula so an explicitly-14 pt label inside an XLARGE subtree does not get a 24 pt icon. Because the role is resolved inside `Label.onMeasure`, `Dialog` pushes no font at all.

**(b′) Why the push must not be an unguarded setter call from inside `onMeasure`.** Revision 1 wrote "all three setters ignore an unchanged value, so this cannot re-dirty layout every frame". That understates the problem on the frame that matters. On the frame the step actually *changes*, `Flex.gap` / `Padding.setInsets` / `Label.setFont` call `markNeedsLayout()` from inside a measure pass; that sets `needsMeasure = true` on the widget being measured **and every ancestor** (`Widget.java:422-428`) and calls `Scene.markLayoutDirty()`. `Scene.layoutPass` then sets `layoutDirty = false` at `:2058` **after** the pass, swallowing the request and leaving the ancestor chain marked dirty with no scheduled pass. It also violates the two-phase contract the class doc states. Hence the `protected final` *silent* forms (assign the field, no `markNeedsLayout`), reachable only from a subclass's measure path, which is exactly what `TokenRow`/`TokenColumn`/`TokenPadding` are. The public guarded setters keep their `markNeedsLayout()` for app use.

**(c) Helper methods reached from measure, paint *and* events: 13 sites, all mechanical.** `Button.iconSize():68`, `ComboBox.widestItem():286/288`, `Label.effectiveFont():150`, `MenuBar.font():66`, `SegmentedControl.edges():86`, `Spinner.selectFieldAt():399/402`, `TabbedPane.stripHeight():254`, `TextArea.font():196`/`pad():200`, `TextField.font():186`/`padH():190`/`padV():194`. All are instance methods, so `Theme.current().tokensFor(this)` resolves correctly, including from event handlers, where the tree is complete. They take `SizeTokens` as a parameter after the migration so a paint resolves once.

**(d) 60 class-level `static final float` geometry constants.** A `static final` cannot vary per widget, so **every one is a latent half-migration**: a SMALL toolbar whose `ToolBar.PAD` stays 8 while its buttons shrink to 20 pt looks wrong with nothing in the diff to blame. The full disposition is §8.2. Review rule going forward: **a new `static final float` in `limn-components` that expresses a length is a defect** unless it sits in `Strokes` or is documented as input tuning.

---

## 6. Public API

### 6.1 `limn/scene/ControlSize.java` (new)

```java
package limn.scene;

import limn.concurrent.Ui;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The size step of a control: a deliberate, hand-tuned design axis, <b>not</b> a scale
 * factor and <b>not</b> a DPI setting (that is {@code Canvas.contentScale()}, applied
 * downstream in the backend; the two multiply, in that order, and must never be conflated).
 *
 * <p>Per step, type, control heights and spacing move on three <em>different</em> ramps,
 * while strokes (borders, focus rings and their gaps, hairline separators, check-mark
 * strokes, carets) keep the same thickness at every step.
 *
 * <p>This enum is the <em>axis</em> only. It carries no numbers, so the toolkit can own it
 * without knowing anything about a theme, which is what lets a raw
 * {@link limn.scene.layout.Row} act as a size scope for the components inside it. What a
 * step <em>means</em> lives with the design tokens in the components layer.
 *
 * <p>A widget's step is <b>inherited down the tree</b>: its own
 * {@linkplain Widget#declaredControlSize() declared} value, else the nearest ancestor's,
 * else its {@linkplain Scene#controlSize() scene's} default, else its
 * {@linkplain Widget#setControlSizeHost host}'s, else {@link #processDefault()}.
 * Several steps therefore coexist in one window, in one frame: a SMALL toolbar above a
 * MEDIUM form beside a LARGE dialog is three scopes in one tree.
 *
 * <p><b>Mixing steps inside one text row needs a baseline, not a box.</b>
 * {@code CrossAlignment.CENTER} aligns boxes, so two controls at adjacent steps sit with
 * their baselines up to 0.69&nbsp;pt apart (2.73&nbsp;pt across the whole ramp). Use
 * {@link limn.scene.layout.Flex.CrossAlignment#BASELINE} for a row that mixes steps and
 * carries text; use {@code CENTER} otherwise. {@code START} aligns box tops, which is
 * exact at every step, but leaves baselines up to 2.73pt apart across the full ramp
 * (0.69pt between adjacent steps) — text rows want BASELINE.
 *
 * <p><b>Never read a widget's step in a constructor or a field initializer</b> — a widget
 * has no parent while it is being constructed, and it can be reparented afterwards. Read it
 * inside {@code onMeasure}/{@code onPaint} or an event handler.
 *
 * <p>XSMALL is a pointer-first density: several controls paint below the 24&nbsp;pt WCAG
 * 2.5.8 target and rely on {@link Widget#hitOutset()} for conformance. Spinner steppers at
 * XSMALL are wheel/keyboard-primary.
 *
 * <p>Each step visible <em>at the same time</em> multiplies the glyph-atlas working set for
 * the faces it uses. Negligible for Latin; measurable for CJK/emoji-heavy UIs.
 */
public enum ControlSize {
    /** Dense: property inspectors, data-grid rows, packed toolbars. Pointer-first. */
    XSMALL,
    /** Compact forms and toolbars. */
    SMALL,
    /** The default. Every existing Limn UI renders here, unchanged. */
    MEDIUM,
    /** Primary actions, settings dialogs, low-density surfaces. */
    LARGE,
    /** Hero and onboarding surfaces. A component may clamp this to {@link #LARGE}. */
    XLARGE;

    /** @return the step used where nothing in the tree and no scene declares one */
    public static ControlSize processDefault();

    /**
     * Sets the process-wide fallback step: the root of the inheritance chain, and the
     * app-level "compact mode" switch. Every live scene re-measures, overlays included
     * and <em>unbound scenes included</em>; widgets and scenes that declare their own
     * step are unaffected. UI thread only.
     */
    public static void setProcessDefault(ControlSize size);
}
```

### 6.2 `limn/scene/Widget.java` additions

```java
    /**
     * @return the step this widget declares for itself <em>and its subtree</em>, or
     *         {@code null} when it inherits. This is the "is it set here" reader; use
     *         {@link #controlSize()} for the effective value (cf. {@link #isVisible()}
     *         vs {@link #isShowing()}).
     */
    public final ControlSize declaredControlSize();

    /**
     * @return the effective size step, never {@code null}: this widget's declared value,
     *         else the nearest declaring ancestor's, else its {@linkplain Scene#controlSize()
     *         scene default}, else its {@linkplain #setControlSizeHost host}'s,
     *         else {@link ControlSize#processDefault()}.
     *
     * <p><b>Read this inside {@link #onMeasure}, {@link #onPaint} or an event handler.</b>
     * Never in a constructor or a field initializer: a widget has no parent while it is
     * being constructed, so the answer there is the process default no matter what the
     * eventual parent declares, and a captured value can never be corrected.
     */
    public final ControlSize controlSize();

    /**
     * Sets the step for this widget and every descendant that does not declare its own;
     * it inherits down the tree like {@link #setCursor}. {@code null} restores inheritance.
     * Re-measures whatever actually changed and repaints. UI thread only.
     *
     * <p>Final by contract: {@link #measure} keys its cache on the resolved step, so a
     * subclass that computed a step on the fly would produce sizes the invalidation system
     * cannot see. A composite that owns widgets outside its own subtree links them with
     * {@link #setControlSizeHost} instead of overriding anything.
     */
    public final void setControlSize(ControlSize size);

    /**
     * Links this widget's size inheritance to {@code host} for the case the tree cannot
     * express: a widget that is the root of its own {@link Scene} (a popup or dialog
     * window) or a {@linkplain Scene#pushOverlay overlay}, both of which have no parent.
     * The chain then continues from {@code host}, live, so a later change on the host
     * reaches the popup while it is open. {@code null} unlinks. UI thread only.
     *
     * <p>Consulted <em>after</em> this widget's own scene default, so a popup scene that
     * declares a step keeps it and one that declares nothing falls through to its host.
     *
     * @throws IllegalArgumentException if {@code host} resolves through this widget
     */
    public final void setControlSizeHost(Widget host);

    /**
     * How far beyond its bounds this widget accepts pointer hits, in logical points — the
     * accessibility floor for a control painted smaller than {@code Strokes.MIN_HIT_TARGET}.
     * Consulted only after a tight pass over the whole subtree found nothing, so a widget
     * can never steal a point that lands inside a sibling. Default 0.
     *
     * <p>Typical implementation: {@code max(0, (MIN_HIT_TARGET - min(width(), height())) / 2)}.
     */
    protected float hitOutset();

    /**
     * Distance from the top edge to the first text baseline, for
     * {@link limn.scene.layout.Flex.CrossAlignment#BASELINE}. Default {@code height()}.
     */
    protected float baselineOffset();
```

`hitOutset()` and the **two-pass `hitTest`** (tight pass over the whole subtree first, outset pass only if it found nothing) ship as **phase-1 mechanism** with `hitOutset()` returning 0 everywhere, and gain consumers in phase 2. Revision 1 contradicted itself here (§9 phase 4 shipped it while §8.3 D13 deferred it to phase 5), and the height ramp is not sound without it.

### 6.3 `limn/scene/Scene.java` additions

```java
    /**
     * @return this scene's default step, or {@code null} to fall through to a hosted
     *         root's host link and then to {@link ControlSize#processDefault()}.
     *         Nullable by design: a popup's own scene declares nothing, which is what
     *         lets it inherit from the widget that opened it.
     */
    public ControlSize controlSize();

    /**
     * Sets this window's default step, the per-window root of the inheritance chain (a
     * compact tool window beside a regular main window). Widgets that declare their own
     * step, and their subtrees, are unaffected. UI thread only.
     */
    public void setControlSize(ControlSize size);

    /**
     * Re-measures and repaints this whole scene, root <b>and overlays</b>. Call it after
     * changing an input to measurement that is not a widget property: the active
     * {@code Theme}, the UI font family, this scene's default control size.
     *
     * <p>{@code root().markNeedsLayout()} is <b>not</b> enough: it dirties only the path to
     * the root, so children return cached sizes under unchanged constraints, and it misses
     * overlays entirely (open dialogs, open menus).
     */
    public void relayout();

    /** Tooltip style now depends on the anchor's resolved step (§5.7). */
    public static void installTooltipStyle(Function<ControlSize, TooltipStyle> supplier);
```

### 6.4 `limn/components` additions

```java
/** Metric design tokens for one ControlSize step, in logical points. */
public record SizeTokens(
        Font body, Font label, Font title,
        /* interned style variants — see §5.8; 5 steps × 3 roles × 4 styles = 60 Fonts */
        Font bodyBold, Font bodyItalic, Font bodyBoldItalic,
        Font labelBold, Font labelItalic, Font labelBoldItalic,
        Font titleBold, Font titleItalic, Font titleBoldItalic,
        float controlHeight, float padH, float padV,
        float gapIcon, float gapLabel, float gapButtonRow,
        float radiusSmall, float radiusMedium, float radiusLarge,
        float spacingSmall, float spacingMedium, float spacingLarge
        /* … the remaining tokens of §8.2, all float or Font; no double, ever … */) {

    /** The five rows, in ordinal order. Process-wide, immutable. */
    static final SizeTokens[] TABLE = { /* XSMALL, SMALL, MEDIUM, LARGE, XLARGE */ };

    /** The MEDIUM row — what {@code Theme}'s deprecated token fields read. */
    public static final SizeTokens MEDIUM = TABLE[ControlSize.MEDIUM.ordinal()];
}

// Theme
/**
 * Metric tokens for a size step. The returned record (and the {@link Font}s inside it)
 * are stable for the whole process, so {@code ==} holds and the backend's identity-keyed
 * font memo keeps hitting. Never build tokens per call, and never call
 * {@code Font.withSize} to derive a step's font.
 *
 * <p>{@code final}: the table is process-wide and palette-independent (§7.30).
 */
public final SizeTokens tokens(ControlSize size) { return SizeTokens.TABLE[size.ordinal()]; }

/** Tokens for the step resolved on {@code widget}, the one line components call. */
public final SizeTokens tokensFor(Widget widget) { return tokens(widget.controlSize()); }

// Strokes: pixel-locked by design; see §4. No member may be indexed by a ControlSize.
public static final float BORDER = 1, HALF_PIXEL_INSET = 0.5f, FOCUS_RING = 2,
        MIN_HIT_TARGET = 24, /* … */;
```

`setControlSize` returns `void` on `Widget`, matching `setCursor`/`setVisible`. It is also `final`, so **a covariant chaining override is impossible on two counts** (you cannot override `final`, and there is no covariant return from `void`); revision 1's §6.4 sentence did not compile. Components that chain add a *separate* helper:

```java
public Button withControlSize(ControlSize s) { setControlSize(s); return this; }
```

on `Button`, `Label`, `Checkbox`, `RadioButton`, `ProgressBar`, `TextField`. §10.3(a)'s capture scene needs none of them: it calls `row.setControlSize(step);` as a statement.

### 6.5 Usage

```java
// A dense toolbar, a default form and a roomy dialog, in one window and one frame.
ToolBar bar = new ToolBar();
bar.setControlSize(ControlSize.XSMALL);            // one call sizes the whole subtree
bar.addItem(new Button("New"))
   .addItem(new Button("Open").setSecondary(true));
bar.addSeparator();                                 // the Separator it builds inherits too
bar.addItem(new SegmentedControl(List.of("Edit", "Preview")));

TokenColumn form = new TokenColumn(Tokens.Role.MEDIUM);   // gap follows the step
form.crossAlignment(Flex.CrossAlignment.STRETCH);
form.add(new Label("Settings").setRole(Label.Role.TITLE));
form.add(new TextField().setText("value"));
form.add(new Button("Publish"));

// A LARGE primary action gets its OWN row rather than sitting beside MEDIUM fields:
// or share the row and align on baselines, never on boxes.
TokenRow actions = new TokenRow(Tokens.Role.SMALL);
actions.crossAlignment(Flex.CrossAlignment.BASELINE);
actions.add(new Label("Ready"));
actions.add(new Button("Publish").withControlSize(ControlSize.LARGE));
form.add(actions);

TokenColumn page = new TokenColumn(Tokens.Role.LARGE);
page.crossAlignment(Flex.CrossAlignment.STRETCH);
page.add(bar);
page.add(Expanded.of(form, 1));

Scene scene = new Scene(new TokenPadding(Tokens.Role.LARGE, page));
scene.setControlSize(ControlSize.MEDIUM);           // this window's chain root
```

A component reads it in exactly one place per pass:

```java
@Override
protected Size onMeasure(Constraints constraints) {
    SizeTokens t = Theme.current().tokensFor(this);          // resolve once, into a local
    TextMetrics metrics = textRuler().measure(text, t.body());
    float contentWidth = metrics.width();
    if (icon != null) {
        contentWidth += iconSize(t) + (text.isEmpty() ? 0 : t.gapIcon());
    }
    return constraints.constrain(contentWidth + 2 * t.padH(),
            Math.max(t.controlHeight(), metrics.lineHeight() + 2 * t.padV()));
}
```

---

## 7. Component-by-component

Every file in `limn-components` plus `text/TextEditModel`, `ComboBox.PopupPanel` and the toolkit's
tooltip path. Nothing is dropped, nothing invented.

> **Derived from the shipped code, not from the plan.** This section was rewritten against the
> implementation after phases 0-5 landed: every number below was read out of the component or out
> of `SizeTokens`, and where the original plan and the code disagreed, the code won. It had been
> through three adversarial reviews and 748 passing tests; the plan had been through a height-ramp
> change and a cancelled hit mechanism. Treat a disagreement between this section and a source
> file as a bug in this section.

Reading rule for every table below: **the MEDIUM cell equals today's literal**, except where a
convergence item is marked `(D<n>, landed)`; those are the enumerated, deliberate MEDIUM changes
this feature shipped.

### 7.1 Button: **participates. The archetype.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `padH` per side | 12 | 16 | **20** | 26 | 32 |
| `padV` per side (measure-only floor input) | 5 | 6 | **7** | 10 | 13 |
| `controlHeight` | 24 | 28 | **32** | 40 | 50 |
| `gapIcon` | 5 | 6 | **8** | 9 | 11 |
| `iconBox` | 14 | 16 | **18** | 20 | 24 |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 |
| measured height (derived) | 24 | 28 | **32** | 40 | 50 |
| focus-ring radius (derived, `radiusMedium + FOCUS_GAP_BUTTON`) | 7 | 8 | **10** | 12 | 14 |
| icon top (derived, `(height − iconBox)/2`) | 5 | 6 | **7** | 10 | 13 |

`onMeasure` is `metrics.width() + iconAdvance(t) + 2·padH` by `resolvedHeight(lineHeight)`. The box binds at every step (`lineHeight + 2·padV` is 22.89 / 26.06 / **30.41** / 38.75 / 48.27 against a 24 / 28 / 32 / 40 / 50 floor), so the measured height *is* `controlHeight`, an even integer at all five steps, and `padV` never reaches a paint or hit coordinate (`ButtonTest.heightIsTheControlHeightFloorAtEveryStep`, `.mediumHeightIsExactly32`). MEDIUM lost 0.40625 pt against the old `lineHeight + 2·spacingSmall + 4`; that is decision 3's whole cost on this control. Because both the height and `iconBox` are even at every step, the icon's top is an integer at every step: a D6 (landed) consequence, `iconBox` 18 at MEDIUM where it used to be 18.40625.

Locked: secondary outline `BORDER` 1 laid on `HALF_PIXEL_INSET` 0.5; focus ring `FOCUS_RING` 2 centred `FOCUS_GAP_BUTTON` 2 outside, so it spans −3…−1 and leaves exactly 1 pt of fill clear; `paintOutset()` returns `FOCUS_RING_OUTSET` 3. The ring's radius stays **derived** (`t.radiusMedium() + gapOut`) so it is concentric with the fill at every step with no row of its own.

Traps: (1) The undocumented `+ 4` the old height carried is gone, absorbed by decision 3's box; do not reintroduce it alongside `padV`. (2) `iconAdvance(SizeTokens)` is the single expression both `onMeasure` and `onPaint` call; the measure/paint duplication the stale text warned about was collapsed into it, and splitting it again silently mis-centres content by `gapIcon`. (3) Every method resolves `Theme.current().tokensFor(this)` **once** into a local and threads it down; two resolutions inside one pass is how measure and paint come to disagree about where the label sits. (4) `paintOutset()` landed; without it, ring fade-out on the toolkit's most-used widget sheds stale pixels under partial rendering, because `Scene` inflates damage by `1 + paintOutset()` and the ring reaches 3. (5) The ring is gated on `focus > 0.001f`, not on `isFocused()`, or the fade-out stops rendering on the first frame after blur. (6) `baselineOffset()` is `(height() − metrics.height())/2 + metrics.ascent()`, literally the expression `onPaint` draws with; they must be edited together. (7) Button's icon extent is now the tabled `iconBox`, while `Label` keeps the font-derived `measure("Hg", f).height() + ICON_OPTICAL_BUMP` (`Label.java:271`); the two agree to within 0.40625 pt at MEDIUM and are no longer the same formula. See 7.27 before "unifying" them. (8) `ButtonTest`'s padding assertion was rewritten against `padH` (the *value* was always 20; the token it named was `spacingLarge`). That is resolved, and it must not drift back to a `Theme` field.

### 7.2 ButtonGroup: **does not participate.**

Not a `Widget`: no bounds, no measure, no layout, no paint, and no numeric literal of any kind. The file is selection bookkeeping over a `List<RadioButton>` plus a `Consumer<Integer>`, and it reads neither `SizeTokens` nor `Strokes`. There is no table because there is nothing to table.

A `setControlSize` here would be a **false affordance**: callers would expect it to resize the members, but the group does not own their layout and cannot dirty them coherently, so it would be a second competing path to a property the radios already own, silently disagreeing whenever a radio was sized directly or added after the group. The radios are `Widget`s and inherit the step from their real parent, so bulk sizing already works through the container. The convention that a group is uniform is a caller's obligation: a mixed-step group is legal and looks broken.

Trap: the single-selection invariant in `add` calls `setSelectedSilently(false)` on the displaced member, which drives that radio's `progress` transition; a step change and a selection change can therefore be in flight on two different radios at once. Nothing in this file caches geometry, so that is safe by construction; keep it that way.

### 7.3 Checkbox: **participates, both variants.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `indicator` (BOX side) | 18 | 18 | **18** | 22 | 24 |
| `indicatorRadius` | 4 | 4 | **4** | 5 | 6 |
| `gapLabel` (indicator↔label) | 4 | 5 | **6** | 8 | 10 |
| `switchTrackW` | 40 | 40 | **40** | 47 | 55 |
| `switchTrackH` | 22 | 22 | **22** | 26 | 30 |
| `switchThumbInset` | 3 | 3 | **3** | 3.5 | 4 |
| BOX row `max(indicator, lineHeight)` (derived) | 18 | 18 | **18** | 22 | 24 |
| SWITCH row `max(switchTrackH, lineHeight)` (derived) | 22 | 22 | **22** | 26 | 30 |
| focus radius (derived, `indicatorRadius + 2`) | 6 | 6 | **6** | 7 | 8 |
| thumb radius (derived, `trackH/2 − inset`) | 8 | 8 | **8** | 9.5 | 11 |

**The indicator is floored at 18 for XSMALL, SMALL and MEDIUM**. The ramp is 18 / 18 / **18** / 22 / 24, not a five-value curve. The floor is deliberate: 18 is the extent this control has always painted, so no step regresses against today, and it keeps three consequences in hand at once.

| | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `INDICATOR_BORDER` ÷ `indicator` | 8.3 % | 8.3 % | **8.3 %** | 6.8 % | 6.3 % |
| `indicator` ÷ `lineHeight` | 1.396 | 1.280 | **1.097** | 1.173 | 1.078 |
| margin of `indicator` over `lineHeight` (pt) | 5.11 | 3.94 | **1.59** | 3.25 | 1.73 |
| check-mark clearance from the border's inner ink (pt) | 1.75 | 1.75 | **1.75** | 2.64 | 3.08 |

The `max(indicator, lineHeight)` that decides the row (and therefore every form row's height) is won by the indicator at all five steps with ≥ 1.59 pt to spare, so a font-family change or a CJK/emoji fallback cannot silently swap which term drives it. `switchTrackW` / `switchTrackH` hold the switch's **aspect ratio** at 1.82 / 1.82 / **1.82** / 1.81 / 1.83 (one shape at five sizes rather than the 1.65 → 1.84 drift a naive ramp gives), and thumb travel is `W − H` = 18 / 18 / **18** / 21 / 25.

**This row is under the 24 pt pointer target** (WCAG 2.2 SC 2.5.8 AA) at BOX's three floored steps and at LARGE, on the axis that decides it: a label widens a target, it never heightens it. The condition is **pre-existing** (18 pt is what this control has always measured), and decision 4 pays the accessibility floor *in paint*, so it is documented rather than closed: there is no `Widget.hitOutset()` and no two-pass hit test, and a toggle's pointer target is exactly the box it paints. The remedy is the standard's own *Spacing* exception, which needs a 24 pt **pitch**, not a 24 pt row: `Tokens.toggleColumnGap(widget)` is 6 / 6 / **6** / 8 / 10, so 18 + 6 = 24 exactly at MEDIUM and every step reaches the pitch (`CheckboxTest.stackedTogglesReachTheSpacingExceptionPitch`). It is a call an app makes on its column, not something a container can push, because a `TokenColumn` cannot know its children are toggles.

Locked: `INDICATOR_BORDER` 1.5, `CHECK_MARK` 2, `FOCUS_GAP_INDICATOR` **1.5** (D3, landed: was 1 here and 1.5 on RadioButton; unified upward), `FOCUS_RING_THIN` 1.5, the `+2` term of `indicatorFocusRadius()`, `HALF_PIXEL_INSET`, the switch outline `BORDER` 1, and the `CHECK_PATH_BOX` 18 the mark was authored against. The switch pill radius stays `trackH / 2`, which is a formula and correctly size-agnostic.

Traps: (1) The check-mark path is scaled by `indicator / CHECK_PATH_BOX`, which is exactly `1.0f` at the three floored steps, so `literal * 1.0f == literal` **bit-for-bit** and those steps reproduce the hand-tuned mark; this is the mechanism that makes §8 a proof rather than an approximation (`CheckboxTest.theCheckMarkPathIsBitIdenticalWhereverTheIndicatorSitsOnEighteen`). Never re-baseline `CHECK_PATH_BOX` to whatever `indicator` currently is; the constant is the *authoring* box, not the current one. (2) The thumb inset's three magic `3`s collapsed into `switchThumbInset` (resolved); likewise the fractional-box worry, since D6 left no fractional extent on this control. (3) `Checkbox` still has **no `setText`** and therefore no other re-layout entry point: `setControlSize` is the first, and it invalidates through the base. (4) `CheckboxTest` asserts slide progress against `animFade`; durations stay out of the table, structurally: `SizeTokens` declares no `double` of any kind. (5) `paintOutset()` is `FOCUS_GAP_INDICATOR + FOCUS_RING_THIN/2` = **2.25**, not the 2 the stale text predicted, because D3 widened the gap; it must stay the same *expression* as RadioButton's, since the two are in lockstep and a mixed form column has to damage the same rectangle (asserted in `CheckboxTest.paintOutsetCoversTheFocusRingReach`). (6) The 1.5 gap is measurable, not taste: the box border is a 1.5 pt pen centred on the 0.5 inset, so its outer ink sits 0.25 pt *outside* the nominal box and a 1 pt gap put the ring's inner ink on exactly that line, two strokes fusing into one seam. (7) The SWITCH focus ring uses `indicatorFocusRadius()` (6 at MEDIUM) around a pill whose radius is `trackH/2` (11), a **pre-existing** mismatch, unchanged here; if it is ever fixed, it must derive from `trackH/2`, not gain a second table row. (8) The local named `cy` in `onPaint` is a **top**, not a centre: `paintBox`/`paintSwitch` take it as `top`; reading it as a centre puts the mark half a box high. (9) `Strokes.CHECK_MARK`'s javadoc still argues its clearance from a 15 pt XSMALL indicator that the table does not ship; the conclusion holds (clearance is 1.75 pt at every floored step, identical to MEDIUM), but do not read a 15 out of it.

### 7.4 RadioButton: **participates, in lockstep with Checkbox BOX.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `indicator` (ring) | 18 | 18 | **18** | 22 | 24 |
| `indicatorInset` (dot gap) | 4 | 4 | **4** | 5 | 5.5 |
| `gapLabel` | 4 | 5 | **6** | 8 | 10 |
| row height `max(indicator, lineHeight)` (derived) | 18 | 18 | **18** | 22 | 24 |
| dot radius (derived, `indicator/2 − inset`) | 5 | 5 | **5** | 6 | 6.5 |
| dot diameter ÷ ring (derived) | 0.556 | 0.556 | **0.556** | 0.545 | 0.542 |
| annulus gap, ring inner ink → dot (derived) | 2.25 | 2.25 | **2.25** | 3.25 | 3.75 |

`indicator` is **the same token** as Checkbox BOX, a hard requirement, not a convenience: a settings form mixing checkboxes and radios must put every label on one optical column at every step, and two independently tuned rows drift apart the first time either is touched. It carries the same 18 pt floor at XSMALL/SMALL/MEDIUM and the same sub-24 pt row, with the same answer (`Tokens.toggleColumnGap`, 6 pt at MEDIUM → an 18 + 6 = 24 pitch; radios come in groups, so the *Spacing* exception is the normal case here rather than the fallback). `indicatorInset` holds dot ÷ ring inside a 1.4 % band across the whole ramp; pinning a single inset would swing it. The half-point at XLARGE is licensed for circle geometry (§3.6 item 1).

Locked: `INDICATOR_BORDER` 1.5 for the ring; `RING_ALIGN_INSET` 1, a private constant with no five-column row, which centres the ring stroke one point inside the box so its outer ink lands 0.25 pt *inside* the measured width; `FOCUS_GAP_INDICATOR` 1.5 (unchanged by D3: this side was already the wider gap and Checkbox came up to meet it); `FOCUS_RING_THIN` 1.5. Those two together are why the focus ink starts a full 1 pt clear of the ring.

Traps: (1) The dot radius `(indicator/2 − indicatorInset) × p` must stay **inside `onPaint`**, recomputed every frame, so a step change mid-transition retargets the dot instead of easing on toward the radius the old step wanted. (2) `paintOutset()` landed as `FOCUS_GAP_INDICATOR + FOCUS_RING_THIN/2` = 2.25: the focus circle's outer ink is 2.25 pt past an indicator that is flush with the widget's left edge and, since the row *is* the indicator, with its top and bottom; `Scene` assumes only 1 pt of AA feather. Keep it character-identical to Checkbox's. (3) `baselineOffset()`'s empty-text guard is load-bearing and is deliberately Checkbox's shape: an unlabelled radio must report the same reference as an unlabelled box, or a BASELINE row containing both drops one of them by several points, a real divergence, now pinned by `CheckboxTest.anUnlabelledRadioAndCheckboxReportTheSameBaselineReference`. (4) The stale text's "`ButtonGroupTest` is the only test and asserts no geometry" is resolved: the lockstep baseline, the focus-ring clearance and the `paintOutset` equality are all asserted in `CheckboxTest`, which is where a divergence between the two controls would actually show up.

### 7.5 SegmentedControl: **participates.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `controlHeight` | 24 | 28 | **32** | 40 | 50 |
| `padV` (measure-only floor input) | 5 | 6 | **7** | 10 | 13 |
| `segPadH` per side | 8 | 12 | **16** | 20 | 26 |
| `segInset` (track gutter) | 2 | 3 | **3** | 4 | 5 |
| `radiusMedium` (track) | 5 | 6 | **8** | 10 | 12 |
| `segPillRadius()` (derived, `max(0, radiusMedium − segInset)`) | 3 | 3 | **5** | 6 | 7 |
| pill height (derived, `height − 2·segInset`) | 20 | 22 | **26** | 32 | 40 |
| segment width | `max(MIN_HIT_TARGET, textW + 2·segPadH)` at every step |

Height is `resolvedHeight(lineHeight("Hg"))` and the box binds at every step, so the control is 24 / 28 / **32** / 40 / 50 verbatim (`SegmentedControlTest.everyStepTakesItsBoxFromItsRow`). `segPadH` is deliberately tighter than Button's `padH` at every step: segments are adjacent and share a track, so the separation is already carried by the pill, and a standalone button's gutter would make a four-segment control absurd.

**The pill radius is concentric at every step (D2, landed): MEDIUM went 7 → 5.** The old MEDIUM kept `radiusMedium − 1`, an eyeball fudge 2 pt off the concentric answer, which made the row non-monotone at exactly one cell: the cell compatibility pinned. `segPillRadius()` now owns the formula; the paint site calls it and must never re-derive it.

**The segment-width floor is one of the three surviving `Strokes.MIN_HIT_TARGET` clamps**, a plain `Math.max`, not a hit-test mechanism. It can only bind at XSMALL, where `2·segPadH` is 16: from SMALL up, `2·segPadH ≥ 24` on its own. At MEDIUM the narrowest possible segment is 32 pt before any glyph, so the clamp is a no-op.

Traps: (1) `edges(SizeTokens)` is called from **both** `onMeasure` and `onLayout`, and its result caches into `bounds[]`, which drives `segmentAt` hit-testing; it takes the row as a parameter precisely so one pass cannot resolve tokens twice and leave `bounds[]` describing a geometry different from the painted one. (2) Because the clamp lives *inside* `edges()`, it reaches hit-testing as well as measure; a clamp applied only on the measure side would make an XSMALL click at x = 47 select the third segment instead of the second (`SegmentedControlTest.theSegmentWidthFloorReachesHitTesting` is exactly that discrimination). (3) `setControlSize` must **not** clear `indicatorPlaced`, or the selection pill teleports instead of sliding. The flag exists only to snap the very first placement. (4) `bounds` is null until the first `onLayout`, so `segmentAt` returns −1 for one frame after a step change; pre-existing, unchanged. (5) The control calls `setFocusable(true)` and paints **no focus ring at all**, a pre-existing accessibility gap, out of scope here; when it is fixed it must use `FOCUS_RING` / `FOCUS_GAP_BUTTON` and land with a `paintOutset()` in the same commit, the way Button did. (6) The test's "a 1-char segment is 42 pt" javadoc was rewritten against `segPadH` rather than a `Theme` field (resolved); it stays true only because it names the token, not the number.

### 7.6 Slider: **participates. No font at all.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `sliderRail` | 4 | 4 | **6** | 7 | 8 |
| `sliderKnob` (rest radius) | 5 | 6.5 | **8** | 10 | 12 |
| `sliderKnobHover` | 6.5 | 8 | **10** | 12.5 | 15 |
| `sliderPad()` = `knobHover + FOCUS_GAP_SLIDER + BORDER` | 10.5 | 12 | **14** | 16.5 | 19 |
| `sliderHeight()` = `max(MIN_HIT_TARGET, 2·pad)` | 24 | 24 | **28** | 33 | 38 |
| `height − sliderRail` (parity: even at every step) | 20 | 20 | **22** | 26 | 30 |
| preferred length (free axis, not a token) | 220 | 220 | **220** | 220 | 220 |

`sliderPad()` and `sliderHeight()` are derived methods on the row (`SizeTokens:275-284`), not columns of their own, since a second five-column row for either would be a silently divergent path to the same pixels. The hover-grow ratio is 1.30 / 1.23 / **1.25** / 1.25 / 1.25: exactly +25 % at the three largest steps, and at XSMALL/SMALL the hover cell is the next step's rest knob (6.5, 8).

**D5, landed.** `sliderPad()` includes the ring's own stroke at *every* step, MEDIUM included: 13 → **14**, height 26 → **28**. The consequence is that the ring's outer edge is `knobHover + 3 + 1` = `sliderPad()` exactly, `trackLeft` is `sliderPad()`, and `height ≥ 2·sliderPad()` by construction, so a focused knob's ring is fully inside the box at both ends and on both edges at all five steps. The `paintOutset() → 1` the stale text proposed is therefore **not** in the file and must not be added; it would inflate every slider's damage rect to cover pixels the widget cannot reach.

Height is the one in the toolkit derived from a **focus-ring constant** rather than from a font or a design height, so nobody should round it onto the 24/28/32/40/50 ramp. It is also the only place `Strokes.MIN_HIT_TARGET` actually **binds**: at XSMALL the natural height is 21 and the floor lifts it to 24; at SMALL `2·pad` is already exactly 24 and the clamp is a no-op.

Locked: `FOCUS_GAP_SLIDER` 3, `FOCUS_RING` 2, knob rim `INDICATOR_BORDER` 1.5, `MIN_HIT_TARGET` 24, the `max(1, …)` track-width guard, `keyStep`/`pageStep` (value domain: a compact slider must not jump in coarser increments), and the 220 free axis, which must stay equal to `ProgressBar`'s.

Traps: (1) `Slider` calls `markNeedsLayout()` nowhere (`invalidate()` at `:158` is its only dirty call), so the base `Widget.setControlSize` supplying `markNeedsLayout()` (`Widget:526`) is load-bearing, because `sliderPad()` changes the measured height. (2) Both knob-radius interpolation endpoints (`sliderKnob`, `sliderKnobHover`) are read from the one row resolved inside `onPaint`, every frame; hoisting either into a field freezes it at the process default, since a widget has no parent at construction time. (3) `trackLeft` is `sliderPad()` and deliberately **not** `height()/2`: at XSMALL the hit floor makes the box 24 tall while the knob needs 21, and insetting by half the height would shorten the track by 3 pt for no reason. (4) Parity (§3.6): `trackTop = height()/2 − rail/2`, and fills are not pixel-snapped (only strokes are), so `(height − rail)` must stay **even** or the toolkit's longest straight run renders as two rows of ~50 % grey. The token row holds it at all five steps, MEDIUM now included; the stale "MEDIUM's pinned 21" is gone with D5. The knob is exempt by §3.6(1): a circle's perimeter is antialiased regardless. (5) `onMouseEvent` resolves the row **once** and threads it into `applyFromX`; a second resolution inside one component would map a click to a different value than the frame it was aimed at: invisible at MEDIUM, wrong everywhere else. (6) No `baselineOffset()` override, deliberately: a Slider carries no text, so the base class's bottom-edge reference is the correct `BASELINE` answer. (7) Resolved: `SliderTest` no longer bakes `pad() == 13`; it computes from `SizeTokens.MEDIUM.sliderPad()` (`:33-34`) and re-runs the geometry per step (`:184-196`), including the assertion that `2 · xs.sliderPad() < MIN_HIT_TARGET` so the one binding clamp stays covered. (8) Preferred width is 220 at every step, so `Slider` is exempt from strict width monotonicity (§10.2 #3).

### 7.7 ProgressBar: **participates. One number.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `progressThickness` | 4 | 6 | **8** | 10 | 12 |

That is the component's entire size axis. `onPaint` reads **no** token at all: the pill radius (`height()/2`), the minimum determinate fill (`max(height(), width()·shown)`), the indeterminate pill length (`width() × SWEEP_FRACTION`) and its travel are all functions of the laid-out box. **This is the model the whole feature imitates**, and adding a token read to `onPaint` would create the second path it exists to avoid. The long axis is free: 220 at every step, deliberately equal to `Slider`'s so a bar and a slider stacked in a form line up, so `ProgressBar` is exempt from strict width monotonicity (§10.2 #3).

Traps: (1) Resolved: the "no unset sentinel" defect is fixed by `public static final float UNSET = -1` (the `SizedBox.UNSET` idiom). Both `setThickness` and `setPreferredWidth` **latch**: any value `>= 0` is an author's pin that beats the step and survives every later `setControlSize`, and any negative value hands the dimension back to the step. `KitchenSinkScene`'s pinned `setThickness(6)` is the covered case (`ProgressBarAndImageTest:47-59`). (2) The indeterminate ticker calls `invalidate()` every frame, gated on `isShowing()`, and re-arms from `onAttached` and from `onPaint`; `sweepGeneration` (the blink-generation idiom) retires a ticker left over from a previous scene. A step change must not be reachable from inside that ticker. (3) `SWEEP_SECONDS` is width-coupled: a 360 pt bar sweeps faster in pt/s than a 220 pt one. Pre-existing; do not try to "fix" it through the table. (4) `withControlSize` exists only because `setControlSize` is `void`; it is a chaining alias, not a second mechanism. (5) If a bar is ever centred inside a taller row, §3.6's parity rule applies to it.

### 7.8 TextField: **participates. The reference for the text cluster.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `fieldPadH` per side | 8 | 10 | **12** | 15 | 18 |
| `fieldPadH ÷ body` (em) | 0.73 | 0.83 | **0.86** | 0.94 | 0.95 |
| `padV` per side (measure-only floor input) | 5 | 6 | **7** | 10 | 13 |
| `controlHeight` | 24 | 28 | **32** | 40 | 50 |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 |
| `gapIcon` | 5 | 6 | **8** | 9 | 11 |
| `fieldIcon` (in-field icon square) | 12 | 14 | **16** | 18 | 22 |
| `fieldDividerInset` (trailing divider, per end) | 6 | 7 | **8** | 10 | 13 |
| `fieldTrailing` | 24 | 28 | **32** | 40 | 50 |
| `fieldWidth` (preferred) | 172 | 204 | **240** | 300 | 360 |

Derived, not tabled; each is a function of the rows above and has no five-column row of its own:

| | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `lineHeight` (Roboto, 1.171875 em) | 12.891 | 14.063 | **16.406** | 18.750 | 22.266 |
| floor input `lineHeight + 2·padV` (**never binds**) | 22.891 | 26.063 | **30.406** | 38.750 | 48.266 |
| measured height `resolvedHeight(lineHeight)` | **24** | **28** | **32** | **40** | **50** |
| `textTop()` = `(h − metrics.height())/2` | 5.555 | 6.969 | **7.797** | 10.625 | 13.867 |
| divider length `h − 2·fieldDividerInset` | 12 | 14 | **16** | 20 | 24 |
| leading-icon centring `(h − fieldIcon)/2` | 6 | 7 | **8** | 11 | 14 |

**The box binds at every step (D1, landed).** `onMeasure` returns `constraints.constrain(fieldWidth, t.resolvedHeight(metrics.lineHeight()))` and the `max(controlHeight, lineHeight + 2·padV)` floor input is 1.1–1.7 pt short at all five steps, so the measured height *is* the declared height and every step is an even integer on the pixel grid. `padV` is therefore a measure-only input and must never appear as a paint or hit-test coordinate, which is why the trailing divider carries its own `fieldDividerInset` (8 at MEDIUM against `padV`'s 7) rather than borrowing it. The divider spans exactly half the control height at XS/SM/MD/LG and 0.48 h at XLARGE.

**MEDIUM moved 0.40625 pt, deliberately.** The old height 32.40625 made `textTop()` exactly 8, equal to the old `padV`; at 32 it is 7.796875 and the ink box sits 0.203 pt higher. That is D1's pixel-grid snap paid in one re-baseline, not a regression, and it is the one place in this component where MEDIUM is not bit-identical to the pre-feature tree.

**`fieldPadH` is tuned in ems, not against the radius.** It is the left margin of a left-aligned run the user reads and clicks into, so what stays near-constant is its size relative to the type it precedes: 0.73 – 0.95 em, a 1.30× swing. The review finding that asked for `fieldPadH ≥ radiusMedium + 3` ("the corner arc reaches 5 pt inboard while the first glyph starts at 6") does not survive the geometry: for radius *r* the left border's inboard offset at height *y* is `r − √(r² − (r−y)²)` for `y < r` and **zero** for `r ≤ y ≤ h − r`. At XSMALL the ink spans `y ∈ [5.555, 18.445]` against `r = 5`, so the arc intrudes nothing at all over the whole run.

**`fieldIcon` is even at every step, and that is its selection rule**, not `round(lineHeight)`, which is what an earlier revision used and which produced 13 and 19. The icon is centred twice, once in the field (`(height() − ico)/2`) and once inside the trailing region (`regionX + (regionW − ico)/2`, `regionW = fieldTrailing`), and `fieldTrailing ≡ controlHeight` at every step, so both quotients are the same integer 6 / 7 / 8 / 11 / 14. An odd `fieldIcon` puts both on a half-pixel. `fieldTrailing` stays a separate token from `controlHeight` because it will diverge the first time someone puts a two-glyph affordance there.

**Vertical anchoring: one derived anchor, resolved.** `textTop(TextMetrics)` (`TextField.java:203`) is the single vertical origin for the baseline, the caret, the selection band, both preedit underlines and `caretRect()`. Before it existed the baseline was centred while everything else was placed from `padV`, two expressions that agreed only at the one step where the font term bound, and at the others the locked 1 pt `INK_BLEED` silently became 0.03–1.97 pt and the preedit underline landed inside the descender ink.

Locked: `BORDER` 1 thickening to `FOCUS_RING` 2 through the single `BORDER + (FOCUS_RING − BORDER) * focus` stroke (**not** a ternary; that deletes the animation), `HALF_PIXEL_INSET` 0.5, `CARET` 1, `INK_BLEED` 1, `AA_BLEED` 2, `DAMAGE_MARGIN` 2, `CLIP_CLEARANCE` 1, the divider's `BORDER` 1, `IME_UNDERLINE` 1 and `IME_UNDERLINE_ACTIVE` 2. No `paintOutset()` override, unlike Button / Checkbox / RadioButton / Label: the border is drawn inset at 0.5 with a maximum 2 pt centred stroke, so it reaches 0.5 pt outside the box and `Scene`'s blanket `1 + paintOutset()` inflation (`Scene.java:526`, `:1673`) already covers it.

Traps: (1) The `MIN_HIT_TARGET` clamp that earlier revisions wanted on the trailing region was **removed and must not come back** (`trailingWidth`, `:216-225`): `fieldTrailing ≡ controlHeight` is already 24 at XSMALL, so the clamp could never bind, and §4's enforcement is structural; `Strokes.MIN_HIT_TARGET` and `ControlSize` both enumerate the three surviving clamp sites exhaustively and a fourth makes those statements false. (2) Resolved: the trailing hit test now bounds **both** axes (`:497-498`); a DRAG that left the field vertically used to keep reporting "over the trailing button" because Y was ignored. (3) One expression, `trailingWidth(t)`, serves the painted region and the hit region, so they cannot drift. (4) The two `CLIP_CLEARANCE` terms in `ensureCursorVisible` (`:307-308`) must stay identical or the caret oscillates one keystroke to the next. (5) `caretRect()` and the no-argument `ensureCursorVisible()` each resolve tokens themselves, on purpose: the scene calls `caretRect()` from the async blink chain where there is no enclosing measure/paint pass to thread a record down from. Everything else takes `SizeTokens t` as a parameter: measure, paint, the press mapping and the drag mapping must agree or a click lands on a different glyph from the one under the pointer. (6) `preferredWidth` uses a `< 0` sentinel (`:49-54`) rather than an eager default: a step cannot be read in a field initializer, where the widget has no parent and would latch the process default forever. (7) `indexAt`'s binary search assumes prefix widths are monotone in the index; `PasswordField` preserves that only because its substitution is count-preserving and its per-dot advance is constant (§7.9). (8) Pre-existing, unchanged: the measured height and `caretRect` both use the `"Hg"` probe, which has no CJK extent, so a taller fallback overflows, more visible at the larger steps. (9) A stroke-recording test over this component must paint at a **settled** transition state (`focus` exactly 0, then exactly 1) or it records frame-dependent fractional border widths and is flaky by construction. (10) `displayWidth(String, SizeTokens)` and `paintDisplayText(…)` are the **only** places the display form's extent and ink are decided, and they are a pair: every horizontal coordinate here (caret x, selection band, `indexAt`'s search, the scroll clamp) is a difference of two `displayWidth` calls, so a subclass that overrides one without the other makes measure and paint disagree about where the n-th mark sits. The preedit is deliberately **not** routed through them: a composition is never masked, and the one subclass that masks refuses text input.

### 7.9 PasswordField: **participates. No geometry of its own; the dot is drawn, not typeset (D12 landed, then superseded).**

It reads **no `SizeTokens` row and declares no extent, padding, radius or stroke**; heights 24 / 28 / 32 / 40 / 50 and fonts 11 / 12 / 14 / 16 / 19 all arrive through `TextField`'s resolve. Its two constants are the same at every step, which is the point:

| constant | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `DOT_DIAMETER` × body | 0.36 (3.96 pt) | 0.36 (4.32) | **0.36 (5.04)** | 0.36 (5.76) | 0.36 (6.84) |
| `DOT_ADVANCE` × body | 0.56 (6.16 pt) | 0.56 (6.72) | **0.56 (7.84)** | 0.56 (8.96) | 0.56 (10.64) |

**D12 is superseded.** D12 shipped a per-step glyph table (`·` U+00B7 / `·` / `•` U+2022 / `●` U+25CF / `●`) on the theory that a dot carries no optical size of its own and so needs a per-step ink ratio. The theory was fine; the available glyphs were not. Measured on the bundled faces, that ramp's ink was **0.11 / 0.11 / 0.20 / 0.90 / 0.90 em**: nothing sits between BULLET and BLACK CIRCLE, so the "ramp" was a 4.5× cliff in the middle of it. Against a type ramp that spans only 11 → 19 pt it painted **1.2 pt of ink at XSMALL and 17.1 pt at XLARGE**: the display steps' mask dwarfed the text it stood for, the dense steps' nearly vanished, and MEDIUM, the identity step, held the smallest solid mark in the toolkit. A drawn circle has no such quantisation, so the dot is now `fillCircle` at one ratio of the body font and the type ramp supplies the size, exactly as it does for every other extent in the toolkit. 0.36 em is where the faces actually used for password fields put it (Verdana's BULLET ink 0.365 em, Helvetica's and Arial's BLACK CIRCLE 0.430, the macOS system font's BULLET 0.191 and its BLACK CIRCLE 0.789); 0.56 em of pitch is Verdana's proportion for the same mark (0.365 in 0.545).

**Drawing it also closed D12's trap (4), which was not hypothetical.** U+25CF is absent from the bundled last-resort face, so the LARGE/XLARGE mask resolved through the per-code-point fallback chain, and that chain loads in the **background**, so the first frame of a display-step password field in a fresh process painted a row of `.notdef` tofu. `--scene forms --control-size XLARGE` captured exactly that. A circle needs no font, so the mask no longer depends on which faces happen to be resident.

The ratios live in the component and not in `SizeTokens`: `SizeTokens` rows are per-step by construction and these are deliberately step-independent; a row of five identical numbers would assert the opposite of the decision.

Traps: (1) **The substitution must emit exactly one mask code point per source code point.** `mask()` uses `text.codePoints().count()`, not `length()`: `TextEditModel` offsets are indices into the source and the only bridge to what is painted is `displayPrefix(charIndex)`, so the caret's x is the width of the mask of the prefix. One dot too many or too few and the caret drifts from the character it edits, a click lands on a neighbour, and selection highlights the wrong run; counting chars would additionally leak that a character was astral. (2) **`MASK` must stay a single BMP `char`.** The mask is built with `String.repeat`, so an astral one emits two chars per source code point and every char offset over `displayText()` doubles. It is now a *counter, not a mark* (nothing paints it while masked), which makes it easy to forget that the count still rides on it. (3) **`displayWidth` and `paintDisplayText` are overridden in pairs, and must stay that way.** Both read `DOT_ADVANCE`, so the n-th dot is painted at exactly the x the prefix of n dots measures to; override one alone and measure and paint disagree, which is the drift trap (1) describes, reintroduced from the other end. The step no longer has to be resolved inside the display hooks at all: both hooks now receive the pass's `SizeTokens`, so D12's once-per-pass hazard is gone with the glyph table. (4) `DOT_ADVANCE` must stay comfortably above `DOT_DIAMETER`: dots fusing into a dashed rule at the dense steps is the failure the glyph table was invented to dodge, and here it is a gap (0.20 em) rather than a glyph choice. (5) The dots are centred on the **ink box's** centre line (`baseline − ascent + height/2`), the band the selection fill and caret span; a dot has no baseline of its own to sit on, and every per-font vertical fudge died with the glyphs. (6) `acceptsTextInput()` returns false because secure entry keeps the platform IME off, so none of TextField's preedit geometry is live here and it needs no per-step validation. (7) Second-order, recorded once: `allowsWordJumps()` is false while masked so an observer cannot count the words and their lengths, and a larger step makes the dots countable from further away.

### 7.10 SearchField: **participates. Zero code changes; the stress case.**

It reads **no token of its own** (the whole file is a placeholder, two `SvgIcon.fromResource` calls and an Enter handler), but it is the only widget paying **both** insets simultaneously, which makes its inner width the canary for any regression in `fieldPadH`, `fieldIcon`, `gapIcon`, `fieldTrailing` or `fieldWidth`:

| | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| leading inset `fieldPadH + fieldIcon + gapIcon` | 25 | 30 | **36** | 42 | 51 |
| trailing inset `fieldTrailing` | 24 | 28 | **32** | 40 | 50 |
| total chrome | 49 | 58 | **68** | 82 | 101 |
| `fieldWidth` | 172 | 204 | **240** | 300 | 360 |
| chrome ÷ `fieldWidth` | 28.5 % | 28.4 % | **28.3 %** | 27.3 % | 28.1 % |
| inner text width | 123 | 146 | **172** | 218 | 259 |

That flat percentage (27.3 – 28.5 %, a 1.2-point band) is the whole reason `fieldWidth` scales 172 → 360: held at 240, XLARGE would lose 42 % of the field to chrome.

Traps: (1) Resolved: the clear button is not the toolkit's smallest target after all; it is `fieldTrailing ≡ controlHeight`, so 24 pt square at XSMALL, exactly `MIN_HIT_TARGET` with no clamp anywhere. That is the fact §7.8 trap 1 depends on; shrink `fieldTrailing` below `controlHeight` and both statements fail at once. (2) Both icons are built in the **constructor** via `SvgIcon.fromResource`, so a process using all five steps holds five rasterizations of each; the cache *key* is already size-correct and the LRU bound is the hazard. Also worth a look: the magnifier's own 1.5 pt strokes inside a 12 pt box at XSMALL. (3) Resolved: the worry that the `"Search…"` placeholder would clip mid-word at XSMALL was computed against a 168 pt `fieldWidth` and a heavier chrome estimate. The shipped XSMALL row leaves 123 pt of inner width for a seven-glyph run at 11 pt; it does not clip at any step. (4) Resolved: `SearchFieldTest` exists and covers Enter-to-submit and the trailing clear button. It builds at `scene.layoutPass(240, 32)` (a MEDIUM literal on both axes), so it must be rewritten against `SizeTokens.of(step).fieldWidth()` and the measured height before a per-step case is added. (5) `onKeyEvent` consumes ENTER **before** delegating to `super`; a subclass that wants both behaviours must call `super.onKeyEvent` first.

### 7.11 TextArea: **participates. Anisotropic content inset (D8, landed).**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `fieldPadH` (horizontal inset; D8, landed) | 8 | 10 | **12** | 15 | 18 |
| `areaPad` (vertical inset) | 6 | 7 | **8** | 10 | 12 |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 |
| `areaWidth` (preferred) | 252 | 276 | **320** | 368 | 436 |
| `areaHeight` (preferred) | 110 | 120 | **140** | 162 | 192 |
| `newlineHint` | 4 | 5 | **6** | 8 | 10 |

Derived:

| | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| visible lines `(areaHeight − 2·areaPad) ÷ lineHeight` | 7.60 | 7.54 | **7.56** | 7.57 | 7.55 |
| columns `(areaWidth − 2·fieldPadH) ÷ (0.5·body)` | 42.9 | 42.7 | **42.3** | 42.3 | 42.1 |
| v-bar over live text, `15 − fieldPadH` | 7 | 5 | **3** | 0 | −3 |
| h-bar over live text, `15 − areaPad` | 9 | 8 | **7** | 5 | 3 |

**Decisions.** (a) `areaWidth`/`areaHeight` are point-based with MEDIUM pinned at 320 × 140, and the four other cells are derived to hold the visible **content** constant rather than the box, because an editor is the component where character count matters most. The line count is flat to ±0.4 %. (b) The vertical pad is its own token `areaPad`, not `padV`: it is a text margin, not a vertical-centring pad, nothing outside this widget has to agree with it, and reusing `padV` would drive it to 5 pt at XSMALL under a 15 pt overlay bar. In em it runs 0.55 / 0.58 / 0.57 / 0.63 / 0.63. (c) **D8 landed and reversed the earlier decision (c):** the *horizontal* inset is now `fieldPadH`, the same token `TextField` insets its text by, MEDIUM 8 → 12, so a field and an area stacked in a form put their first character on the same x column; before D8 the area's text started 4 pt left of the field's at MEDIUM and the ragged left edge was visible in every form. `areaWidth` was deliberately **not** re-derived against the new inset: `fieldPadH` climbs faster than `areaPad` did, so the column count tilts from a flat ≈43.5 to 42.9 → 42.1 instead of staying flat. The residual is under 3 % and monotone; restoring a flat count would move the MEDIUM box, and D8 already spent this feature's MEDIUM budget on the text column itself.

**D10 is deferred past v1**: `ScrollBar` does not participate and `thickness()` stays a static 15 pt at every step, so `onLayout` reserves 15 everywhere. The cost is stated rather than absorbed: the vertical bar floats over `15 − fieldPadH` of live text (7 pt at XSMALL against 3 at MEDIUM) and the horizontal over `15 − areaPad` (9 and 7). At XSMALL, prefer a trailing right margin in the surrounding layout if the last column matters.

Locked: `WHEEL_STEP` 48 (a wheel detent is a device unit: the same physical flick must move the same physical distance in a dense editor and a roomy one), `BORDER` 1 → `FOCUS_RING` 2 through the one `1 + focus` stroke, `HALF_PIXEL_INSET` 0.5, `CARET` 1 with its `INK_BLEED` inset at both ends of the line box, `MIN_SELECTION_SLIVER` 2, `AA_BLEED` 2, `DAMAGE_MARGIN` 2, `CLIP_CLEARANCE` 1, both IME underlines, and `ScrollBar.thickness()` 15.

Traps: (1) **The `cachedContentWidth` metric-invalidation bug is fixed, but not the way the ADR proposed.** There is no `metricsChanged()` cross-file hook; the cache is keyed on the `Font` **and** the `"Hg"` probe `TextMetrics` that produced it and is validated at every read (`TextArea.java:291-305`). It has to be the *measurement* rather than the `Font`: a family re-bound underneath `Font.DEFAULT_FAMILY` leaves the record identical and only its metrics move. The dirty flag alone is cleared at exactly two sites, `setText` and `fireChange`, and neither is on the metrics path (`Scene.relayout` only marks measure caches dirty and never reaches a widget's private state), so the cache was already stale after a runtime `Fonts.setDefaultFamily` switch before this feature existed; the size axis merely adds a second such path. (2) **D16 landed, on one axis only.** The horizontal clip carries TextField's `−AA_BLEED / +2·AA_BLEED`; clipping tight to the pad cut the antialiasing fringe off the first and last glyph on every line, a hard vertical edge through the ink that read as a lighter stem and was the one place the area visibly differed from a field showing the same string. The **vertical** clip stays tight deliberately: it is a scroll boundary against the border, not an inset text run, and bleeding it 2 pt would let a half-scrolled line's ink cross the pad and sit on the rounded border. (3) Resolved: the preedit underline is `top + metrics.height()` taken from the line's own `lineTop` anchor, not `baseline + 2`. A fixed 2 pt drop is inside the descender at every step (the descender runs 3.418 pt below the baseline at MEDIUM and 4.639 at XLARGE), and cut it outright at 19 pt. `TextField` has always anchored at the ink-box bottom; this is now the same expression. (4) **The two pads must move together, per axis.** Every consumer of the horizontal inset reads `fieldPadH`: `viewWidth`, the paint clip and translate (`:492-494`), the press/drag mapping (`:606-607`), the `caretRect` clamp (`:850-858`). Every consumer of the vertical one reads `areaPad`, including `baselineOffset()`. Splitting the pad without moving all of them lands a click on a different character from the one under the pointer. (5) `tokens()` is an **entry-point** resolve, for the paths the toolkit enters from outside a pass: the two `ScrollBar.Model` callbacks (invoked from the bars' own event handling), the async blink chain and the public `scrollBy`. Everything reached from measure, paint or an event handler takes the record as a parameter; two resolutions that disagree inside one component put the click on a different line from the one that was drawn. (6) `baselineOffset()` is deliberately **not** scroll-dependent (a BASELINE row that re-aligned as the user scrolled would jitter), and deliberately has **no** empty-text guard: an editor always paints a line box, so falling back to the bottom edge would make typing the first character jump a whole ~140 pt row. (7) `lineTop` top-aligns the ink box in the line box rather than centring it; the two differ only by the face's line gap, and the painted baseline is byte-identical only because every face the toolkit ships has `lineGap == 0`. A face with a non-zero line gap moves every baseline in the editor. (8) `TextAreaTest` bakes `WHEEL_STEP == 48` (now a *guard* rather than a literal), the MEDIUM pad and the 15 pt bar strip; all stay green at MEDIUM.

### 7.12 TextEditModel (`text/`): **does not participate. Firm.**

Reads no `SizeTokens` row and no `Strokes` constant: a table here would be empty by construction. It is a pure headless model: a `StringBuilder`, a cursor, an anchor, a goal column, a lazily rebuilt line-start index and a bounded undo stack. Every quantity it exposes is a **char offset or a line number**; it imports nothing from `limn.graphics` and never sees a `Font`, a `TextRuler`, a `Canvas` or a point. Its three constants are a memory bound (`MAX_UNDO` 200), a performance bound (`GRAPHEME_SCAN_WINDOW` 64, explicitly documented as affecting only how long one backward cluster scan runs, never the answer) and a Unicode rule (`GRAPHEME` = `\X`). A size parameter here would be a category error and a second silent source of truth for geometry the widgets own.

The pixel↔index boundary is drawn exactly once and in the right place: both widgets do their own binary search over prefix widths and then hand the result to `model.alignToGrapheme(…)`, so the *measuring* lives in the widget and the *cluster rule* lives in the model. `moveWordRight` and `deleteWordForward` use `alignToGraphemeForward` instead, because forward motion that snapped a mid-cluster boundary down would land at or before the cursor and stall forever, and a char-class boundary genuinely can fall inside a cluster (NFD accents, keycaps).

The one temptation to resist, named explicitly because it will come up: making `goalColumn` pixel-based so Up/Down stops drifting through proportional text. If that is wanted, **`TextArea` owns it** and passes a resolved x into a `moveVertically(select, direction, goalX)` overload; the model stays ruler-free. `TextEditModelTest` contains no pixel assertion by construction: it is the one test file this feature cannot break, which is itself the evidence.

### 7.13 ComboBox (field): **participates, and forwards to its popup.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `fieldPadH` (left text inset) | 8 | 10 | **12** | 15 | 18 |
| `padV` (measure-only floor input) | 5 | 6 | **7** | 10 | 13 |
| `controlHeight` = the height | 24 | 28 | **32** | 40 | 50 |
| `comboCaretGutter` (measure) | 16 | 20 | **24** | 28 | 34 |
| `comboTextClip` (paint) | 18 | 22 | **26** | 30 | 36 |
| `comboCaretCenterX` (from the right edge) | 11 | 13 | **16** | 19 | 22 |
| `chevronHalfW` / half-height | 5 / 2.5 | 5 / 2.5 | **5 / 2.5** | 6 / 3 | 7 / 3.5 |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 |
| `popupGap` (anchor↔panel) | 2 | 3 | **4** | 5 | 6 |

`onMeasure` is `constrain(textWidth + 2·fieldPadH + comboCaretGutter, resolvedHeight(lineHeight))`. **The box binds at every step**: `lineHeight + 2·padV` is 22.89 / 26.06 / 30.41 / 38.75 / 48.27 against a ramp of 24 / 28 / 32 / 40 / 50, so the measured height *is* `controlHeight`, even at every step, and there is no resolved-height row distinct from the declared one (`ComboBoxTest.theBoxBindsTheHeightAtEveryStep`). MEDIUM lost exactly the 0.40625 that decision 3 removed: 32.40625 → **32** (D1, landed). Width under the 0.6 em test ruler for `"three"` is 65 / 76 / **90** / 106 / 127.

Two constraints the table honours and `ComboBoxTest.theCaretClearsTheBorderAndKeepsItsAngleAtEveryStep` asserts: `comboTextClip == comboCaretGutter + 2` at all five steps (today's deliberate 2 pt overhang, and today's already-inconsistent 24 < 26, unchanged), and `comboCaretCenterX − chevronHalfW ≥ 4` (6 / 8 / 11 / 13 / 15) so the chevron clears the border. The chevron's half-height is always `chevronHalfW / 2`, so the arrow *angle* is invariant (the same arrow, smaller) and the §4.4 floor at 5 keeps the pen at 0.32 / 0.32 / **0.32** / 0.27 / 0.23 of the glyph's vertical run.

Locked: `BORDER` 1 animating to `FOCUS_RING` 2 through the single `BORDER + (FOCUS_RING − BORDER)·focus` stroke, `HALF_PIXEL_INSET` 0.5, and the caret pen, which is now `ARROW_PEN` **1.6**; the private `COMBO_CARET_PEN` 1.8 is gone, because a chevron is an arrow (D4, landed). `everyStrokeTheFieldPaintsIsIdenticalAtEveryStep` records exactly `[BORDER, ARROW_PEN]` at all five steps. `EDGE_MARGIN` 8 (`ComboBox.java:48`) is a work-area clearance (a display fact, neither tabled nor in `Strokes`); it is the only such private literal left in the file.

`baselineOffset()` is the expression `onPaint` draws with, `(height() − metrics.height())/2 + metrics.ascent()`: 15.759766 / 18.101563 / **20.785156** / 25.468750 / 31.494141, which is what `Flex.CrossAlignment.BASELINE` aligns a mixed-step row on.

Traps: (1) The three uncommented `POPUP_GAP` copies are gone: `createPopupWindow` resolves `t.popupGap()` once and spends it on the anchor, the above/below space test and the flipped `screenY`. (2) **A step change while the popup is open closes it.** The window is sized once, from `popupItemHeight`/`popupPadV`; re-measuring rows inside a stale window clips the last one. `popupStep` records the step the window was sized at and `onMeasure` posts `close()` when it no longer matches (*posted*, because a measure pass must not mutate the tree it is running over). It reopens at the new step. (3) `popupPanel.setControlSizeHost(this)` must run **before** `new Scene(popupPanel)`, not after: binding measures the panel, and a panel that resolved the process default there would lay rows out at one step inside a window sized at another. The ordering at `ComboBox.java:259-262` is load-bearing and reads as harmless. (4) The field, not the panel, computes the popup's window height, so it reads two of the panel's tokens (`popupItemHeight`, `popupPadV`) directly: `contentH = n·popupItemHeight + 2·popupPadV`, floored at a two-row `minH` of 54 / 60 / **72** / 88 / 104. Those two rows therefore have two consumers in two classes and cannot be re-derived on one side only. (5) `ComboBoxTest` lays out tight at `(200, 32)` so the geometry-coupled assertions read the box layout actually gives the field.

### 7.14 ComboBox.PopupPanel: **participates by inheritance only. No public setter.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `popupItemHeight` | 24 | 26 | **30** | 36 | 42 |
| `popupPadV` | 3 | 4 | **6** | 8 | 10 |
| `popupRowInsetX` (row band inset) | 3 | 4 | **6** | 8 | 10 |
| `popupBarInsetX` (bar's right inset) | 2 | 2 | **3** | 4 | 5 |
| `popupDotCol` (dot centre, from the row inset) | 5 | 6 | **7** | 8.5 | 10 |
| `popupDotRadius` | 1.75 | 2 | **2.5** | 3 | 3.5 |
| `popupMarkerCol` (text x, from the row inset) | 11 | 13 | **16** | 19 | 22 |
| `radiusLarge` (panel) | 9 | 11 | **14** | 17 | 20 |
| `radiusSmall` (row highlight) | 3 | 3 | **4** | 5 | 6 |
| `body` | 11 | 12 | **14** | 16 | 19 |

A dropdown at a different density from the field that opened it is simply a bug, so the step is pinned from the owner through `setControlSizeHost` (the panel is the root of its own window-bound `Scene` and inherits through the host link, not the tree), and there is no setter.

**`popupItemHeight` starts at 24, not 22.** A dropdown row is a pointer target with no keyboard-only fallback for a mouse user, and the rows are not `Widget`s, so nothing can extend them: the row height *is* the target. Constraints the table honours: `radiusSmall ≤ (popupItemHeight − 2·ROW_GUTTER)/2` (3 ≤ 11 … 6 ≤ 20), `radiusLarge ≤ popupItemHeight/2 + popupPadV` (9 ≤ 15 … 20 ≤ 31), and `popupDotCol + popupDotRadius < popupMarkerCol` (6.75 < 11, 8 < 13, 9.5 < 16, 11.5 < 19, 13.5 < 22).

`popupDotCol` and `popupMarkerCol` are both measured **from `popupRowInsetX`**, not from `popupPadV`; the panel's vertical padding and its row inset are separate tokens that happen to carry the same five numbers (3 / 4 / **6** / 8 / 10). Absolute text x is 14 / 17 / **22** / 27 / 32; the dot centre is 8 / 10 / **13** / 16.5 / 20. The pre-conversion `POPUP_PADDING` carried all four roles and one of them, the dot's literal 7, did not move at all: at XSMALL the marker column shrank to 11 while the dot stayed at 7, so the mark left its gutter at four of five steps.

`popupDotRadius` **scales** even though it is a filled mark (the one sanctioned departure from "marks are pixel-locked"): it has no stroke, it is pure area, and 2.5 pt vanishes in a 42 pt row.

Locked: panel `BORDER` 1 with `HALF_PIXEL_INSET`, `ROW_CLIP` 1 (a clip inset whose value *is* the border width), `ROW_GUTTER` 1 (so the highlight band is `popupItemHeight − 2` = 22 / 24 / **28** / 34 / 40), and `WHEEL_STEP` 48.

**`ScrollBar.thickness()` does not participate** (D10, deferred): 15 pt at every step. The bar is laid out `popupBarInsetX` in from the right edge, so bar-plus-inset occupies 17 / 17 / **18** / 19 / 20 pt of the panel's width: 9.9 % of a popup over an XSMALL field at `fieldWidth` against 7.5 % at MEDIUM, and the 15 pt thumb track covers 62.5 % of a 24 pt XSMALL row against 50 % of a 30 pt MEDIUM one. Note that nothing *shortens* the row band for it: the highlight spans `width() − 2·popupRowInsetX` and the row's own text clip ends at `width() − popupRowInsetX`, so a long label runs under the thumb, more of it at a compact step. Accepted cost of one scrollbar geometry process-wide, stated rather than absorbed.

Traps: (1) **The six copies of the row geometry are gone.** Window sizing, `contentHeight`, `revealRow`, `damageRow`, the paint loop and the hit test now all route through `rowTop(int, SizeTokens)` and its inverse `rowAt(float, SizeTokens)`. The tokens are a *parameter* on both for the same reason the copies were collapsed: two resolutions inside one gesture put the copies back, and `setHighlight` damages two rows, reveals a third and repaints from the same row; one resolution or the highlight scrolls to one row and repaints another. (2) `rowAt` **truncates rather than floors**, deliberately, preserving what the four copies did: a click in the panel's *top* padding truncates to 0 and hits row 0, extending its target upward, while the bottom padding yields `items.size()` and is inert. The asymmetry is real and is a behaviour question, not a conversion question. (3) `damageRow` uses exactly `popupItemHeight` with no outset, so any change to the highlight geometry must be mirrored there or partial rendering trails. (4) The headless test gap is *narrowed, not closed*: `PopupPanelTest` pins `rowAt(rowTop(i)) == i` exhaustively and the half-open `[top, top + itemH)` boundary, but against a **local mirror** of the formula at MEDIUM literals (`PADDING = 6`, `ITEM_HEIGHT = 30`), because `createPopupWindow` short-circuits on `scene.window() == null` and a headless test cannot reach the private panel. It pins the arithmetic; it does not pin that production reads the tokens. That weakness is stated in the test's own header and should stay stated.

### 7.15 Spinner: **participates.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `controlHeight` / `padV` (measure floor only) | 24/5 | 28/6 | **32/7** | 40/10 | 50/13 |
| height = `resolvedHeight(lineHeight)` | 24 | 28 | **32** | 40 | 50 |
| `spinnerButtonW` | 18 | 22 | **26** | 30 | 36 |
| `spinnerWidth` (preferred, a floor) | 96 | 116 | **140** | 168 | 200 |
| `spacingMedium` (the value's pad, both sides) | 6 | 9 | **12** | 16 | 20 |
| `spinnerFieldInset` (divider + TIME highlight, vertical) | 2 | 3 | **4** | 5 | 6 |
| `spinnerFieldPadX` (TIME highlight, horizontal) | 2 | 2 | **3** | 4 | 5 |
| `arrowHalf` (§4.4 floored glyph) | 4 | 4 | **4** | 5 | 6 |
| `radiusMedium` / `radiusSmall` | 5/3 | 6/3 | **8/4** | 10/5 | 12/6 |

**D1, landed:** the `HEIGHT = 34` wart is gone. `onMeasure` returns `t.resolvedHeight(atMax.lineHeight())`, the same one height formula every text-bearing control uses, so a Spinner is exactly as tall as a Button / TextField / ComboBox / SegmentedControl at **all five** steps: MEDIUM 34 → **32**. The pinned-fudge cell and the `controlHeight + (step == MEDIUM ? 2 : 0)` special case the stale text carried do not exist; the box binds at every step, so there is no "resolved height" distinct from the declared one.

**D14, landed:** `onMeasure` consults `textRuler()`. It measures `format(min)` and `format(max)` (the widest rendering is always at a bound for a fixed decimal count) and returns `max(spinnerWidth, spacingMedium + valueWidth + spacingMedium + spinnerButtonW)`. The box therefore does not resize as the user steps, and a long value can no longer slide under the stepper column and be bisected by the divider. Note the value pad is `spacingMedium`, **not** `fieldPadH`: Spinner never reads `fieldPadH` at any site.

**Decision on XSMALL, unchanged and now documented in the class javadoc:** keep the stepper buttons. Each half is `spinnerButtonW × height/2` = 18 × 12 at XSMALL (22 × 14, 26 × 16, 30 × 20, 36 × 25 above it), below fine-motor comfort, so a dense form should treat the keyboard as the primary way to adjust the value there. A per-step *layout* variant that dropped the steppers is the kind of special case that makes a size axis unmaintainable.

Locked: `BORDER` 1 → `FOCUS_RING` 2 as the continuous expression `BORDER + (FOCUS_RING − BORDER)·focus` (branching on `focus > 0` deletes the animation; see the `Strokes` class note), both dividers `BORDER`, `HALF_PIXEL_INSET`, `SPINNER_HOVER_INSET` 1 (whose value *is* the divider width, so the hover fill can never cover a divider), `ARROW_PEN` 1.6, and `HOLD_INITIAL_DELAY_MS` 350 / `HOLD_REPEAT_INTERVAL_MS` 55 (motor-control cadence, not geometry).

Traps: (1) Resolved: `radiusMedium` is now resolved **once** per paint into a local that the fill, the `clipRoundRect` and the border all share, so the hover fill cannot spill past a corner the clip was cut at a different radius. (2) `paintValue` and `selectFieldAt` take the same resolved row, but they derive the HH/MM boundary two different ways: paint places `mm` at `pad + w(hh) + w(":")` while the hit test compares against `pad + w(hh + ":")`. Kerning between the digits and the colon is the only thing keeping those equal; if a font ever kerns there, the click boundary drifts off the rendered colon. (3) There is **no `WHEEL` case, deliberately.** A spinner in a scrolling panel used to swallow the wheel whenever the pointer crossed it, stopping the scroll *and* editing a field the user was only scrolling past; mouse events bubble until consumed, so not handling the wheel is exactly what lets it reach the `ScrollView` ancestor. Focus-gating (Qt's approach) was considered and rejected: in a property grid you click a field and then scroll with the pointer still over it. Do not "fix" this. (4) `spinnerFieldInset` is the one optical margin shared by the divider and the TIME highlight's vertical inset; `spinnerFieldPadX` is a separate, slower row for the highlight's horizontal pad, so the highlight does not grow sideways as fast as it grows tall. Two independent `4`s at MEDIUM before the conversion, so do not re-merge them. (5) `onMouseEvent` resolves the row once and threads it into `regionAt`/`selectFieldAt`: a second resolution could classify a click against a different step than the one that painted the stepper column. (6) `baselineFor` is the single text anchor for both `paintValue` and `baselineOffset()`; they must never disagree. (7) Implicit test contract, still worth recording: the tight-layout tests hold only while `spinnerButtonW ∈ [13, 80]`; the row's 18…36 is inside it. `SpinnerTest` now derives its box from `MEDIUM.spinnerWidth()`/`spinnerButtonW()` (`:27-29`) and runs the ramp per step (`:245-258`).

### 7.16 ToolBar: **participates, and propagation is the whole point.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `toolBarPad` (all four edges) | 4 | 6 | **8** | 10 | 12 |
| `toolBarGap` (default only) | 4 | 6 | **8** | 10 | 12 |
| `toolBarSepInset` | 2 | 3 | **4** | 5 | 6 |
| `radiusMedium` | 5 | 6 | **8** | 10 | 12 |
| height with same-step Buttons (`tallestChild + 2·pad`) | 32 | 40 | **48** | 60 | 74 |

The height row is arithmetic, not a table: the box binds on the control-height ramp at every step, so a bar of same-step Buttons is `24/28/32/40/50 + 2·toolBarPad`. The bar has **no height table of its own** and must never get one: a bar full of MEDIUM buttons would then disagree with itself about how tall those buttons are, depending only on the bar's own step. Items added through `addItem` are children and inherit the bar's step (`ToolBarTest:147-153` asserts the inherited value with `declaredControlSize() == null`: propagation is inheritance, not assignment); this is the case where a size property *without* propagation would be actively harmful.

Traps: (1) `gap(float)` **latches**, and the flag, not the value, records it. Inferring "explicit" from `gap != toolBarGap()` would silently un-latch a caller who happened to pass this step's default. (2) `gapOf(t)` is the one gap expression; `onMeasure` and `onLayout` each resolve their own row (they run at different times) but share that expression and the `toolBarPad` read. (3) Resolved, and in the opposite direction from what the stale text prescribed: measure hands each item `Constraints.loose(maxWidth, UNBOUNDED_LIMIT)` **on purpose**, because the bar's height derives from the tallest natural child. Passing the incoming bound down instead lets a stretchy item (`Separator.vertical()` fills its constraint) claim the whole window and drag the bar with it. Stretch still happens, in `onLayout`, against the inner band measure produced (`ToolBarTest:173-180`). Do not "unify" the two passes. (4) `addSeparator()` cannot apply the inset where it creates the separator: a widget has no parent until `add()` returns, so its step would resolve to the process default. It clears the memo and lets the next `onMeasure` push `toolBarSepInset`, early enough that the new inset paints in the same frame. `syncSeparatorInsets` prunes **every** pass, not only when the value moves: gating the prune on a value change leaked separators the app had removed for as long as the step held still. (5) The idempotence guard is `Separator.setInsetInternal`'s equality check, not `ToolBar`'s `appliedSepInset` field; `setInset` marks layout dirty and cannot know the inset only moves ink, so deleting that check makes every ToolBar measure request a render forever. (6) Resolved: `ToolBarTest` exists; height, width, centring, the zero clamp, the empty bar, per-step pad/gap, inheritance, the gap latch across a step change, the stretchy-item case and the bar-built separator's inset are all covered. `NewControlsScene` remains the visual-verification scene.

### 7.17 Separator: **participates by inheritance only. No public setter.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `separatorBox` (thin axis) | 5 | 7 | **9** | 13 | 17 |

The only token the component reads, in `onMeasure`. The drawn line is `Strokes.HAIRLINE` 1 at every step and has no row: the only thing a reader perceives is a 1 pt rule, so a "large separator" is either the same line with more air (the **container's** job, and containers have per-step spacing) or a thicker rule, which is a different visual element. Only the box moves, and only by inheritance: a separator declared XLARGE inside an XSMALL list is nonsense, so no chaining size setter exists to express it. `setInset` is the one dimension a caller controls, and `ToolBar` feeds it `toolBarSepInset` from its own resolved step.

**D15, landed.** `lineCenter(box) = (int)(box / 2) + HALF_PIXEL_INSET`: floor, never `Math.round`. `Math.round` is `floor(x + 0.5)`, so on an odd box it returned `box/2 + 1` and put the ink a full point *below* centre; at box 5 the split was 3 : 1 and the divider visually belonged to the item beneath it. With floor, the odd box centres the hairline **exactly** at every step:

| box | y | 1 pt line covers | air above / below |
|---|---|---|---|
| 5 | 2.5 | 2.0 – 3.0 | 2 / 2 |
| 7 | 3.5 | 3.0 – 4.0 | 3 / 3 |
| **9** | **4.5** | **4.0 – 5.0** | **4 / 4** |
| 13 | 6.5 | 6.0 – 7.0 | 6 / 6 |
| 17 | 8.5 | 8.0 – 9.0 | 8 / 8 |

The parity rule is what makes this work and is now a correctness constraint rather than a consistency one: a 1 pt (odd) line can only sit exactly centred inside an odd box, and the stroke still lands on a whole device pixel. An even box would put it 0.5 pt below centre. `MiscComponentsTest` asserts the box is a whole odd point at every step, that `y == box/2`, that the air above equals the air below, and that the recorded stroke is `HAIRLINE`; the parity is executable now, not editorial.

`FALLBACK_LENGTH` 24 is a "do not collapse to zero" guard on the free axis, identical at every step and **not** a size token, so `Separator`'s long axis is exempt from strict monotonicity (§10.2 #3).

Traps: (1) `onPaint` reads **no** `SizeTokens`: the weight is locked and the position derives from the laid-out box, so paint cannot disagree with measure about which step it is on. Use it as the reference pattern. (2) The public `setInset` keeps `Ui.checkUiThread()`; the package-private `setInsetInternal` deliberately drops it, because `Widget.measure` has never been thread- or runtime-checked and `Ui.checkUiThread()` throws when no runtime is installed at all; routing a container's measure through the public setter would make a ToolBar holding a bar-built divider throw from `measure(...)` off the UI thread, or in an embedder that measures before a backend exists. Do not "fix" the missing check by promoting the internal form. (3) Resolved: `MiscComponentsTest` no longer asserts a bare `9f`; it reads `SizeTokens.of(step).separatorBox()` and runs all five steps.

### 7.18 ScrollBar: **does not participate. D10 deferred past v1.**

Reads no `SizeTokens` row and no `Strokes` constant. Rather than an empty table: `thickness()` stays `static` and returns **15** at every step (`WIDE` 11 + 2 × `MARGIN` 2), and every other number in the file (`THIN` 5, `WIDE` 11, `MARGIN` 2, `MIN_THUMB` 28, the four durations (0.10 / 0.09 / 0.28 / 1.1 s), the three thumb alphas (0.9 dragging / 0.75 hovered / 0.5 resting), the three epsilons (0.5 overflow, 0.02 paint, 0.05 hit) and the `min(w, h)/2` pill radius) is a private literal that does not move.

Three reasons, unchanged by the implementation: **OS precedent** (macOS and Windows scrollbars are one size regardless of the density beside them, since a scrollbar is window chrome, not type); **it is a pointer target with a `MIN_THUMB` 28 grab floor**, and shrinking it at XSMALL makes a 10 000-row list ungrabbable; and **it never reserves layout space** (it is an overlay at every host, so its thickness reflows nothing, and shrinking it only reduces occlusion). D10's proposed 11 / 13 / **15** / 17 / 19 is deferred past v1 because it would change a `static` method's signature and force a coordinated commit across all five call sites for a benefit no host can measure.

That staticness is the payoff. `thickness()` is called from exactly six sites: `ScrollView:196`, `ListView:310`, `ListView:311` (twice on that line), `ComboBox:590`, `TextArea:353`, and `ScrollBarTest:46`, whose whole premise is `scene.layoutPass(ScrollBar.thickness(), 200)`. Not participating meant no signature change and no coordinated commit, which is why `ScrollView`, `ListView`, `TextArea` and the ComboBox popup converted independently.

Traps: (1) **`Strokes.SCROLLBAR_MARGIN` is declared and consumed by nobody.** `ScrollBar` keeps its own `private static final float MARGIN = 2`. The two agree today by coincidence, and the live one is the private field; grep before changing either. The `Strokes` entry is correct about *why* the value must be single-sourced (`trackLength`, `thumbRect`, `thumbStart` and the drag ratio must move together or dragging desynchronises from painting), it is just not the source. (2) When D10 lands it must also rewrite `ScrollBarTest:46`, which sizes the whole scene from the static call, to take an instance-resolved thickness or a fixed literal. (3) The pill radius is `min(thumbW, thumbH)/2`, a formula and deliberately **not** `radiusSmall`: a theme radius would square off the thumb ends as the bar widens 5 → 11 on approach.

### 7.19 ScrollView: **does not participate. Pure propagator.**

**Note added with `ScrollGutters.Layout.RESERVED`.** A reserved strip turns D10 (§7.18,
`ScrollBar.thickness()` fixed at 15 pt) from an overlay cost into a *layout* cost:
under `OVERLAY` a bar that is too wide for a dense step merely covers more content,
but under `RESERVED` it takes 15 pt of width at XSMALL exactly as at XLARGE: 6.3 %
of a 240 pt panel against 4.2 % of a 360 pt one. The mode does not create the debt
and does not make it worse per point; it makes it visible in the layout, which is
one more argument for closing D10. It applies to all three scrollers: `ScrollView`,
`ListView` and `TextArea` share one `ScrollGutters`, so closing D10 fixes them
together.

Reads no `SizeTokens` row and no `Strokes` constant: zero fonts, zero padding, zero radii, zero strokes, zero text. Its entire visual contribution is a clip rect (`clipsChildren()` plus the explicit `clipRect` in `paintChildren`, so scrolled-out damage clamps to the viewport) and two overlay children. The ambient step flows through it to its content by inheritance, for free; `setControlSize` on a ScrollView needs no override.

Both bars are overlays: `vBar.layoutBox(width() − t, 0, t, vLen)` and `hBar.layoutBox(0, height() − t, hLen, t)` are positioned *over* the content, and the child is measured and laid out at `max(content, viewport)` without subtracting either. `maxOffsetX/Y` therefore cannot move when `ScrollBar.thickness()` moves, which makes `ScrollViewTest:42`'s `maxOffsetY() == 200` provably immune to D10; state that so nobody "fixes" it. The one place thickness does show is the corner: with both bars present each track is shortened by `t` (`vLen = height() − t`, `hLen = width() − t`) so the two thumbs never overlap and each travels to its own end.

Trap: **`ScrollView.java:24` holds the last unmerged copy of the wheel detent**, `private static final float WHEEL_STEP = 48`, used at `:244-245`. The other five hosts (`ListView`, `TextArea`, `PopupMenu` ×2, `TabbedPane`, `ComboBox.PopupPanel`) all read `Strokes.WHEEL_STEP`. The values agree, so nothing is broken and nothing will fail; but §4's enforcement is structural precisely because there is nowhere to put five columns, and a private copy is a place. `ScrollViewTest:62`'s `assertEquals(48, offsetY())` guards the literal, not the lock.

### 7.20 ListView: **participates, almost entirely as a propagator.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `listRowSeed` (frame-0 row estimate) | 30 | 38 | **48** | 58 | 70 |
| `listWidth` (unbounded-width intrinsic) | 160 | 200 | **240** | 288 | 340 |
| `radiusMedium` (selection-ring corner) | 5 | 6 | **8** | 10 | 12 |

Three tokens, and that is the whole list. Rows are adapter-supplied widgets that live in the list's subtree (they are `add`ed inside `measuredHeight` *before* they are measured), so they resolve the step themselves and `list.setControlSize(SMALL)` shortens them because **they** re-measure. The list never imposes a row height by fiat. `ListViewTest.rowsResolveTheListsStepThemselves` pins exactly that with a row whose `onMeasure` returns its own `controlHeight`. **That is the entire answer to "what does a SMALL ListView mean".**

`listRowSeed` feeds `avgRowHeight(t)`, which is the measured mean once layout has produced one and the seed until then; every scroll number is built from it (`estimatedContentHeight`, `estimatedOffset`, `scrollToOffset`, `rowsPerPage`) with no exception, because a raw 0 there makes an empty list's intrinsic height 0 and its page size 1. `listWidth` and `VISIBLE_ROWS_HINT · avgRowHeight` are free-axis fallbacks that bind only under an unbounded constraint (6 × 30 / 38 / **48** / 58 / 70), and the 6 is a row **count**, not a length, so it must not move with the step.

The selection ring is the textbook locked case: weight `FOCUS_RING_THIN + (FOCUS_RING − FOCUS_RING_THIN)·focusFade`, i.e. 1.5 pt resting animating to 2.0 pt focused, with the inset locked at the resting weight (1.5) and the shrink at twice it (3) as half-stroke consequences. **Only the corner radius moves**, and `theSelectionRingKeepsLockedWeightsAndTakesOnlyItsRadiusFromTheStep` asserts all four facts at all five steps. Because the ring is drawn *inside* the row rather than around it, no `paintOutset()` override is needed here; unlike Button, Checkbox, RadioButton and Label, ListView paints nothing outside its own bounds.

**The scroll bar does not take part** (D10, deferred): `ScrollBar.thickness()` is 15 pt at every step and the bar is laid out flush at `w − thickness`, overlaying the rows. At a compact step it covers a larger fraction of a shorter row than at MEDIUM. `hitTest` gives the bar priority over the rows when it is shown, so that occlusion is an input fact as well as a visual one. Accepted cost of one scrollbar geometry process-wide.

Traps: (1) **`measuredRowHeight` must not be seeded at construction.** It starts at 0 and the seed is resolved lazily in `avgRowHeight(t)`, because a widget has no parent while it is being constructed: a constructor-time seed would be the process default's forever, with no path to recovery. The `focusFade` `Transition` in the same constructor *does* read `Theme.current()`; those are **animation durations**, palette- and step-independent, and the pattern must not be copied for a metric. (2) Everything reachable from more than one entry point takes the token row as a **parameter** (`avgRowHeight`, `estimatedContentHeight`, `estimatedOffset`, `scrollToOffset`, `rowsPerPage`); only `tokens()` resolves, once per pass or per gesture, and from the `ScrollBar.Model` callbacks, which are entered from the bar's own pass and cannot be handed a row. Two resolutions inside one gesture let the scroll estimate and its clamp disagree, which is a scroll that sticks near the ends. (3) `scrollBy` moves the mounted rows immediately rather than waiting for layout, because `revealInView` re-reads coordinates between nested scrollables within one pass (the `Scrollable` contract). (4) `vBar.refresh()` at the tail of `onLayout` is what re-estimates the thumb after a step change; a step change mid-life visibly re-estimates thumb size until the first `updateAverageHeight`. (5) `ListViewTest:175-176` bounds `children().size() <= 9` and `created <= 9` against a 200 pt viewport; any step or prefetch change that mounts extra rows trips it, and it is a mount-count guard, not a size assertion. (6) `WHEEL_STEP` is read from `Strokes`, and `oneWheelNotchTravelsTheSameDistanceAtEveryStep` asserts the same 48 pt of travel at all five steps: a dense list and a roomy one must move the same physical distance per flick.

### 7.21 MenuBar: **participates, and forwards to its dropdown.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| strip height ≡ `controlHeight` | 24 | 28 | **32** | 40 | 50 |
| `menuBarPadH` | 6 | 9 | **12** | 15 | 18 |
| `menuBarChipInset` | 2 | 3 | **3** | 4 | 5 |
| `radiusSmall` (chip) | 3 | 3 | **4** | 5 | 6 |
| `body` | 11 | 12 | **14** | 16 | 19 |

`onMeasure` returns `constrain(Σ titleWidth, t.controlHeight())`: the strip takes the step's control height **outright**, not `resolvedHeight(lineHeight)`. It is chrome, not a text-fit box, and the text-fit floor would be slack at every step anyway (11 pt body, `lineHeight` 12.89, in a 24 pt strip at XSMALL). D1 landed here as a *sharing* rather than a re-value: the private `STRIP_H = 32` became `controlHeight`, MEDIUM unchanged, and the two dense steps moved with the ramp (20 → 24, 26 → 28), so a bar and the buttons beside it agree at every step. `menuBarPadH` gets its own row instead of piggybacking `spacingMedium`: the two coincide through MEDIUM and diverge above it (15/18 against 16/20).

The bottom rule is `HAIRLINE` 1 drawn at `height() − HALF_PIXEL_INSET`, one locked weight at all five steps, on the most visible hairline in the toolkit. The active chip is `fillRoundRect(x + chip, chip, w − 2·chip, height() − 2·chip, radiusSmall)`, wholly inside the box, and the rule is inside it too, so **MenuBar declares no `paintOutset()`** and none is owed.

**`titleWidth` is the width-axis half of the accessibility answer**, and one of the exactly three surviving `Strokes.MIN_HIT_TARGET` sites: `max(MIN_HIT_TARGET, textWidth + 2·menuBarPadH)`, a plain `Math.max` on the painted box. There is no `Widget.hitOutset()` and no two-pass hit test; `titleAt` has zero slop, so a one-glyph title at XSMALL would measure `2×6 + ~6 = 18` pt and be an under-sized target. Widening the box is the right fix rather than an invisible outset because adjacent titles must not overlap. It binds only where `2·menuBarPadH + text < 24`, i.e. at XSMALL and (for very short titles) SMALL; at MEDIUM the padding alone is already `2×12 = 24`, so it is a no-op at MEDIUM and above.

Traps: (1) **Five walks, one row.** `titleWidth`, `titleLeft`, `titleAt`, `onMeasure`/`onPaint` and `openMenu` all take the resolved `SizeTokens` as a *parameter*; two resolutions inside one pass route a click to the neighbouring menu. (2) Titles are drawn left-aligned on `menuBarPadH`, deliberately **not** centred in the box, because the floor only ever widens the last few points on the right, and centring would drift a floored title's text off its pad. (3) The rev-1 trap "`openMenu` must construct the popup at the bar's step" is **resolved**: the call is `popup.showAnchored(this, sceneX, sceneY, titleWidth(index, t), height())`, and since a `PopupMenu`'s surface is parentless the anchor link is the only path the step can travel: a SMALL bar drops a SMALL menu. (4) `pointOverStrip` is derived from `width()`/`height()` and follows the strip for free; it is passed to `popup.inScenePassThrough` for the fullscreen fallback, one of the host-link sites. (5) The rev-1 trap "no `MenuBarTest` exists" is **resolved**: `MenuBarTest` re-derives `titleWidth` from the row, drives all five steps, and pins the floor with a two-title XSMALL bar measuring exactly `2 × MIN_HIT_TARGET`.

### 7.22 PopupMenu (+ MenuSurface + Column): **participates by forwarding. One step for the whole cascade, fixed at open.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `menuRowHeight` | 24 | 26 | **28** | 34 | 40 |
| `separatorBox` (separator row) | 5 | 7 | **9** | 13 | 17 |
| `menuPadV` | 4 | 5 | **6** | 7 | 8 |
| `menuMinWidth` | 112 | 140 | **168** | 196 | 224 |
| `menuCheckGutter` | 18 | 22 | **26** | 30 | 34 |
| `menuArrowGutter` | 15 | 18 | **22** | 26 | 30 |
| `menuCheckInset` | 5 | 7 | **8** | 10 | 12 |
| `menuArrowNudge` | 4 | 5 | **6** | 7 | 8 |
| `menuSepInsetX` | 8 | 9 | **10** | 12 | 14 |
| `menuHiliteInsetX` / `Y` | 3/2 | 3/2 | **4/2** | 5/3 | 6/4 |
| `checkGlyphW` (§4.4 floor) | 9 | 9 | **9** | 10.5 | 12 |
| `menuArrowW` × `H` (§4.4 floor) | 5×8 | 5×8 | **5×8** | 6×9.5 | 7×11 |
| `scrollChevronHalf` (§4.4 floor) | 4 | 4 | **4** | 4.5 | 5 |
| `radiusMedium` / `radiusSmall` | 5/3 | 6/3 | **8/4** | 10/5 | 12/6 |
| `body` | 11 | 12 | **14** | 16 | 19 |

**`menuRowHeight` starts at exactly 24.** Menu rows are not `Widget`s (`Column` builds `top[]`/`hgt[]` and `MenuSurface.hit(lx, ly)` dispatches against them), so nothing outside this class can enlarge a row, and no clamp is applicable: the row height *is* the target height, and 24 at XSMALL makes every step of the cascade a conformant pointer target by construction. A row is at least `menuMinWidth` = 112 pt wide, so the other axis was never in question.

The separator row is `separatorBox`, the **same token `Separator` uses** (§7.17), odd at every step so a 1 pt rule centres in it. Its MEDIUM cell, 9, is what the old menu-local band already drew; the ramp below and above it now comes from one row rather than two. A column's geometry: `h = 2·menuPadV + Σ hgt[i]`, `contentW = max(menuMinWidth, menuCheckGutter + widestLabel + menuArrowGutter)`. The submenu's y-alignment follows `menuPadV` (`y = parentItemTop − menuPadV`) and has no token of its own. Glyph paths are the MEDIUM literals scaled: the tick by `checkGlyphW / 9`, the chevron by `menuArrowW / 5` and `menuArrowH / 2` (exact at MEDIUM, extent-only, pens locked).

Invariants the table honours, checked at all five steps: `menuCheckInset + checkGlyphW < menuCheckGutter` (14 < 18, 16 < 22, **17 < 26**, 20.5 < 30, 24 < 34); `menuRowHeight > lineHeight(body)` (24 > 12.89 … 40 > 22.27); and the corner-arc pair, which is `menuPadV + menuRowHeight/2 ≥ radiusMedium` (16 ≥ 5, 18 ≥ 6, **20 ≥ 8**, 24 ≥ 10, 28 ≥ 12) and, for a leading separator, `menuPadV + separatorBox/2 ≥ radiusMedium` (6.5 ≥ 5, 8.5 ≥ 6, **10.5 ≥ 8**, 13.5 ≥ 10, 16.5 ≥ 12).

Locked: panel `BORDER` 1 at `HALF_PIXEL_INSET`, `ROW_CLIP` 1 (the row clip and both hint-band insets; the value *is* the border width), separator `HAIRLINE` 1, `MENU_CHECK_PEN` 1.8, `ARROW_PEN` 1.6, `SUBMENU_OVERLAP` 2 (exactly twice `BORDER`, because it exists to hide the seam between two columns' borders), `MENU_SCROLL_HINT_H` 12 (a *control* band, not a veneer: it intercepts clicks before item activation), `WHEEL_STEP` 48, and the file-local `SCROLL_EPSILON` 0.5 (a threshold, not a length, and the reason the hint band's paint condition, its hit region and the scroll clamp agree). Documented consequence of the locked detent: one notch covers ~2.0 rows at XSMALL, ~1.7 at MEDIUM, ~1.2 at XLARGE.

Traps: (1) The rev-1 dual-theme read is **resolved by construction**: `beginOpen` builds the surface, installs `setControlSize`/`setControlSizeHost`, resolves `tokens` **once**, and only then calls `pushRoot()`, in that order, because building the root column measures labels against `tokens.body()`. `Column`'s constructor and `MenuSurface.paintColumn` both read that one field; `paintColumn` re-resolving would let a column measure at one step and paint at another. (2) The step is fixed at open: `setControlSize` on an open menu is ignored, since the columns' snapshots were built from the captured row. **Never bump `Menu.modCount` for a restyle**: it means "the model mutated" and drops all deeper columns. This is what deletes the hardest item in the audit (no size generation joining the staleness check, no live `reposition()` + `refitNativeWindow()`, no interaction with the `appliedW/appliedH` HiDPI-flash suppression). (3) **D17 landed**: `Column.fit` narrows *both* axes to the bounds, so `MenuSurface.clamp`'s `hi < lo` branch is now a pure backstop for degenerate bounds instead of the path that let a column overflow. Reachable on a real screen at XLARGE (`menuMinWidth` 224); only a work area under 168 pt reaches it at MEDIUM. (4) **New trap the shrink path revealed:** in `fit`, a non-positive bound means "not measured yet", not "no room"; the in-scene overlay is configured with `(0, 0)` because only `MenuSurface.onLayout` knows the scene size, and any `reposition()` before that first layout would otherwise collapse the column to 0×0 for every reader of `w`/`visibleH` (`boundingBox`, `hit`, the paint clip). Stated exactly once, in `fit`. (5) **The separator's half-pixel snap was refused, deliberately.** It would be a no-op on the term it targets (`separatorBox` is odd at every step and `menuPadV`/`menuRowHeight` are integral, so `top[i]` is whole and `hgt[i]/2` contributes exactly the .5 a centred 1 pt rule wants), and it would be wrong anyway: `col.y` derives from a fractional pointer anchor minus the render offset, and at contentScale 1.25/1.5/2 a half *point* is not a half *pixel*, so a point-space snap pushes the rule off the device grid precisely where crispness matters. The one point-space correction in the file is `HALF_PIXEL_INSET` on the border, measured from the column's own box. (6) `damageRow` and the scroll damage rect are computed from `col.top/hgt/w`; stale extents under-damage, i.e. visible tearing, which is what `MenuDamageTest` exists to catch, and it now reads `popup.tokensForTest().menuRowHeight()` instead of keeping a private `ITEM_H = 28`. (7) `MenuTest` derives its scroll ceiling as `2·menuPadV + items·menuRowHeight − visibleH` from the same row and asserts `tokensForTest()` against `SizeTokens.of(step)` for both the inherited and the pinned case; the rev-1 baked `> 600` was false at the two dense steps.

### 7.23 Menu (model): **does not participate.**

Zero size-affecting quantities: an ordered `List<MenuItem>` plus `modCount`. The **same `Menu` instance is legitimately presented at two steps at once** (a MenuBar drops it at the bar's step while the app opens the identical `Menu` as a compact context menu), so a field here would be a second source of truth every presenter has to reconcile. The participating surfaces are `PopupMenu` (`setControlSize`, the anchor host link) and `MenuBar`. `modCount` is the only staleness signal a presenter has: `Column` snapshots `builtModCount` at construction and `rebuildStaleColumns` re-measures the first column whose model moved, dropping every deeper one, which is exactly why a restyle must never travel through it.

### 7.24 MenuItem (model): **does not participate.**

Zero quantities: kind, label, action, onToggle, submenu, enabled, checked. A per-item step would let one column mix 24 pt and 40 pt rows against a single captured row, breaking the geometry snapshot (`Column` fills `top[]`/`hgt[]` in one pass from `tokens`) and making `reveal`'s `menuPadV`-based math meaningless. The only size-relevant model concept is `Kind.SEPARATOR`, which selects `separatorBox` over `menuRowHeight` for that row; the arrays are per-item, but the *kind* is the only input that varies them.

Forward-looking note, unchanged and still cheap: per-item icons or accelerator text land in the `menuCheckGutter` / `menuArrowGutter` columns and would become the first model-driven size input; both gutters are already tokens, so that addition needs no second pass.

### 7.25 TabbedPane: **participates. The best showcase.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` | 11 | 12 | **14** | 16 | 19 |
| `tabPadV` | 6 | 7 | **9** | 12 | 15 |
| strip height = `lineHeight + 2·tabPadV` (Roboto) | 24.89 | 28.06 | **34.41** | 42.75 | 52.27 |
| `tabPadH` | 8 | 12 | **16** | 20 | 24 |
| `tabIconSize` (D6, landed) | 13 | 14 | **16** | 19 | 22 |
| `tabIconGap` | 4 | 5 | **6** | 8 | 10 |
| `tabRevealMargin` | 12 | 18 | **24** | 30 | 36 |
| `tabHoverInset` (header pill) | 3 | 3 | **4** | 5 | 6 |
| `stripBtnHoverInset` | 2 | 2 | **3** | 5 | 6 |
| `tabChevron` half-size (§4.4 floor) | 5 | 5 | **5** | 6 | 7.5 |
| `radiusSmall` (pill + focus ring) | 3 | 3 | **4** | 5 | 6 |

Two **independent** ramps (`body` and `tabPadV`) compose into a non-linear height ladder (the requirement made mechanical), and the result is deliberately fractional at all five steps (MEDIUM is exactly 16.40625 + 18 = 34.40625; the class javadoc rounds it to 34.41). §3.6 allowlists it: do not round it. `tabPadV` at XSMALL is **6, not 4**, precisely so `12.89 + 12 = 24.89` clears 24: the three square strip controls are `stripHeight` on a side, so the ramp pays the accessibility floor by construction and **no `MIN_HIT_TARGET` clamp appears anywhere in this file**. `tabHoverInset` at XSMALL is **3, not 2**, because `tabHoverInset > FOCUS_GAP_TAB` must hold at every step or the hover pill swallows the focus ring drawn inside it. **D6 landed**: `tabIconSize` is 16 at MEDIUM (was 16.40625, the old `measure("Hg", body).height()`), which bounds the icon rasterization cache's key count; `iconAdvance` is one expression (`tabIconSize + tabIconGap`) so the measure and paint sides cannot drift and mis-centre the content.

Locked: strip separator `HAIRLINE` 1, `TAB_INDICATOR` 2.5, `FOCUS_RING_THIN` 1.5 at `FOCUS_GAP_TAB` 2, `ARROW_PEN` 1.6 on all three chevrons, `WHEEL_STEP` 48, `SCROLL_STEP_FRACTION` 0.75, and the two 0.5 epsilons (the overflow test `headersTotal > width() + 0.5` and the chevron enable states), which are numerical slop guarding the oscillation the class doc is about, so scaling them would change *when* overflow engages. The indicator is bottom-anchored (`y = height() − TAB_INDICATOR`), which keeps it inside the box for free; together with the focus ring drawn *inward* at gap 2, that is why **TabbedPane declares no `paintOutset()`** while Button, Checkbox, RadioButton and Label do. The indicator's own inset scales: its edges are `header.x() + tabPadH/2` and `header.x() + header.width() − tabPadH/2`, i.e. 4 / 6 / **8** / 10 / 12 pt in from each side.

**D11 is closed as not-applicable, not implemented.** `hoverPillRadius(t)` returns `radiusSmall`. The concentricity rule `inner = outer − (innerInset − outerInset)` carries no information here because `radiusSmall` (3/3/4/5/6) and `tabHoverInset` (3/3/4/5/6) are the *same ramp*: the expression reduces algebraically to the constant `FOCUS_GAP_TAB` = 2 and, with MEDIUM pinned, would ship a non-monotone 2/2/**4**/2/2: a 52 pt XLARGE tab with a 2 pt pill beside a 34 pt MEDIUM tab with a 4 pt one.

**Overflow policy: shrink the controls, never drop them.** With overflow, `button = min(stripHeight, width()/6)` and `viewWidth = max(0, width() − 3·button)`, with prev at `0`, next at `width − 2·button`, list at `width − button`, and the strip viewport at `viewLeft = button`. That keeps `viewWidth ≥ width()/2` at every width: monotone, never zero, and all three affordances stay reachable. The rev-1 rule (drop the two chevrons below `4 × stripHeight`) is **cancelled**, because it made the viewport non-monotone in the pane's width: at MEDIUM a 137 pt pane got a 102 pt viewport and a 138 pt pane got 34 pt, since full mode costs `2 × stripHeight` more than compact mode at *every* width, so no threshold can remove the discontinuity. Three squares still cost `3 × stripHeight` = 74.7 / 84.2 / **103.2** / 128.3 / 156.8 pt when the pane is wide enough to afford them. The reveal margin is likewise capped against the viewport: `min(tabRevealMargin, max(0, (viewWidth − headerWidth)/2))`.

Traps: (1) **Three sites must produce the same number**: `TabbedPane.stripHeight(t)` (layout and the separator), `StripButton.onMeasure` (which calls `stripHeight()` rather than re-deriving), and `TabHeader.onMeasure` (`lineHeight + 2·tabPadV`, the same two tokens in the same expression). A second, disagreeing resolve lays the headers out at one step and paints them at another, and the strip clips its own tabs. `TabStrip.onMeasure` also reports the **pane's** resolve on purpose: one number for the container and its viewport, always. (2) `headerWidths[]` and `overflowing` are computed in `TabbedPane.onLayout` and consumed by `TabStrip.onLayout`, so a step change **must** re-measure the *pane*; `invalidate()` alone leaves stale widths (the base `setControlSize` does the right thing). (3) `openTabList` anchors the popup on `listButton`, **not** on `scene()`, because the `Widget` overload hosts the cascade inside this pane, so the menu resolves the pane's step; anchoring on the scene routes through `scene.root()` and opens a MEDIUM menu out of an XSMALL pane. (4) The `10_000` measure sentinel in `preferredStripWidth` and `onLayout` is a latent clip for long titles and gets closer as fonts grow. Pre-existing, unchanged. (5) Under the degenerate test ruler `lineHeight` is flat 12, so strip heights are 24 / 26 / **30** / 36 / 42 rather than 24.89 … 52.27 (§8.4); `TabbedPaneTest` derives every coordinate from `SizeTokens.of(step)` through its own `stripH`/`headerW` helpers, and its one literal pair (`content.y() == 30`, `height() == 270`) is the MEDIUM instance of that derivation. `TabScrollOverflowTest` pins the shrink policy directly, including `list.width() ≤ paneWidth/6` on a 160 pt XLARGE pane. (6) The block comment above the overflow branch still narrates the cancelled drop rule; the code beneath it, and the comment inside the branch, are the shipped policy.

### 7.26 Dialog: **participates by inheritance. No independent knob.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| page padding ≡ `spacingLarge` (`TokenPadding(LARGE, …)`) | 12 | 16 | **20** | 26 | 32 |
| content gutter ≡ `spacingMedium` (`TokenColumn(MEDIUM)`) | 6 | 9 | **12** | 16 | 20 |
| action-row gutter ≡ `gapButtonRow` (`ActionRow`) | 4 | 5 | **6** | 8 | 10 |
| `title` (the title `Label`, via `Role.TITLE`) | 15 | 17 | **20** | 24 | 29 |
| `body` (the muted, wrapped message `Label`) | 11 | 12 | **14** | 16 | 19 |
| `radiusLarge` (card, both fill and outline) | 9 | 11 | **14** | 17 | 20 |
| `dialogMaxWidth` (measure cap) | 320 | 380 | **440** | 520 | 600 |
| *derived:* measure in ch at `body × 0.5`, net of padding | 54 | 58 | **57** | 59 | 56 |

A Dialog is a **container of sized components**, not a sized component: it has no size knob of its own, it *is* a `ControlSize` scope its content inherits. `Dialog` is not a `Widget`, so `setControlSize` is a **forwarder** onto the card (`DialogPanel`), and `controlSize()` reads it back; that is the only knob. Everything else follows the resolved step, so a LARGE dialog is genuinely roomier rather than a MEDIUM card with bigger buttons in it.

**The inheritance rule, stated because the `Scene` overloads cannot express anything else.** `show(Scene)` / `showToolkitModal(Scene)` / `showNonModal(Scene)` take a **`Scene`**, so the step comes from the **owner scene's root**, not from the control that opened the dialog. A dialog raised from a SMALL toolbar inside a MEDIUM scene renders MEDIUM, with nothing in the code to point at. The three `show(Widget)` overloads landed for exactly this: `adoptSizeHost(owner)` records the opening widget as `sizeHost` and returns `owner.scene()`, and `sizeHostFor(owner)` falls back to `owner.root()`.

`dialogMaxWidth` scales to hold a roughly **constant character count**, which is the entire purpose of a measure cap. Held at 440 while `body` grew to 19, XLARGE would hold ~46 ch and wrap badly. It is consumed twice, at two different anchors: native mode caps the *window* at `min(dialogMaxWidth, window.logicalWidth())`, in-scene mode caps the *card* at `min(dialogMaxWidth, width() − 2 × spacingLarge)` so the scrim always reads as a margin (`DialogTest.inSceneCardIsCappedAtDialogMaxWidth`, `…KeepsAScenePaddingMarginWhenTheWindowIsNarrow`).

The action row's gutter is `gapButtonRow`, deliberately **not** `spacingSmall` (3/4/6/8/10): the two agree from MEDIUM up and part ways below it, where 3 pt between two adjacent activatable rects would defeat the WCAG 2.2 SC 2.5.8 *Spacing* exception the dense steps lean on. No `Token*` container carries that token, so the push lives in `ActionRow.onMeasure`.

Locked: card `BORDER` 1 with `HALF_PIXEL_INSET` 0.5 (both the glassy and `UNDECORATED_OPAQUE` paths), `SLIDE_DISTANCE` 14 (motion choreography: 14 pt of travel reads the same regardless of card size; scaling it would make an XLARGE dialog feel sluggish for the identical duration), `FADE_SECONDS` 0.16, `Theme.animWindow`, the scrim alpha 0.55, the card alpha 0.98.

Traps: (1) **The blocking constructor defect is fixed, and the fix is the shape to copy.** Nothing in the constructor may capture a size: a widget has no parent while it is being built, so a step read there resolves to the process default whatever the eventual owner declares; and a `Dialog` is single-use, so no re-show can ever correct it. Hence three self-resolving containers (`TokenPadding`, `TokenColumn`, `ActionRow`) instead of three theme literals, and `Label.setRole(TITLE)` instead of `setFont(theme.title)`: an explicit font beats the step forever, which is how a LARGE dialog used to render a MEDIUM title with no error and no obvious cause (`DialogTest.titleAsksForTheRoleInsteadOfPinningAFont`). (2) **Ordering, native mode:** the window is sized from `dialogMaxWidth` and `panel.measure(...)` *before* `bind()`, so `panel.setControlSizeHost(sizeHostFor(owner))` must run before either; it does, immediately before `new Scene(panel)`. Install it later and the window is sized at the process default while the content re-measures at the owner's step inside it, so the card clips or floats. (3) **In-scene, the host link goes on the `SceneOverlay`, not on the panel.** The panel *has* a parent there (the overlay), and a host link on a parented widget is ignored because the tree wins; the overlay is the parentless one, since `pushOverlay` sets the scene but never a parent. Two display modes, two different widgets, same one-line mistake available in each. (4) `SceneOverlay.paintChildren` calls `onLayout()` during **paint** (to re-place the card for the fade slide), so resolution runs on the paint hot path (safe precisely because it is a memo read plus an array index, never a re-measure), and that `onLayout` resolves the row on the **card**, not on the overlay, so an explicitly-stepped Dialog keeps its own width budget inside a differently-stepped host scene. (5) `ActionRow` (like `TokenColumn`/`TokenPadding`) pushes with the **silent** setter: a `markNeedsLayout()` from inside `onMeasure` dirties the whole ancestor chain with no pass scheduled to clear it. (6) Native mode rounds the panel to integer window pixels (`Math.round` on both axes), so a fractional card height gives a sub-point window/card mismatch at every step. Pre-existing. (7) Single-use is enforced by `checkNotPresented()`, so there is no re-show path where a stale step could be corrected, which is the reason trap 1 was blocking rather than cosmetic. (8) Resolved: `DialogTest`'s old fixed `(30, 30)` press and `(x0+10, y0+10)` grab landed on the title `Label` at the dense steps; both are now `spacingLarge / 2` in from the card corner, i.e. bare card at every step.

### 7.27 Label: **participates. The purest case: 100 % typography, 0 % chrome.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `body` (`Role.BODY`) | 11 | 12 | **14** | 16 | 19 |
| `label` (`Role.LABEL`) | 10 | 11 | **13** | 14 | 17 |
| `title` (`Role.TITLE`) | 15 | 17 | **20** | 24 | 29 |
| `gapLabel` (icon ↔ text) | 4 | 5 | **6** | 8 | 10 |
| `iconBox` (D6, landed: MEDIUM 18.40625 → **18**) | 14 | 16 | **18** | 20 | 24 |

Zero strokes, zero padding, zero radius, zero focus ring; the only locked quantity in the file is `ICON_OPTICAL_BUMP` 2, and it appears only in the *fallback* icon formula.

**Content-tight at every step, icon included (D9, landed).** `onMeasure` returns the line box (`lineHeight` unwrapped, `lines × lineHeight` wrapped) with **no** `max(lineHeight, iconBox)` term; a Label's job is to let the *parent's* alignment decide the row height, and a per-step floor would double-count against the container's control height. The icon square is deliberately `ICON_OPTICAL_BUMP` larger than the line box (that is the formula the tabled row was derived from), so taking the max let the icon win unconditionally and left an icon Label ~1.6 pt taller than a bare one at MEDIUM; a form column mixing the two went visibly ragged. The difference is now paid as an **overhang** of `max(0, (iconBox − height()) / 2)` per side, ~0.8 pt at MEDIUM, declared to partial rendering by a new `paintOutset()` override. Shrinking the icon to the line box instead would have undone the optical correction rather than applied it. The width axis still counts the full square, because there it displaces text rather than the row above.

`Role { BODY, LABEL, TITLE }` is orthogonal to the step: a 3 × 5 grid, not a `setFont`-vs-step precedence fight. `setFont` remains the escape hatch and **beats** both; while it is set (or for any role with no box of its own), `iconBox` falls back to `measure("Hg", f).height() + ICON_OPTICAL_BUMP`, so an explicitly-14 pt label inside an XLARGE subtree does not get the 24 pt box, and a 20 pt title does not get the 18 pt body square.

**Recorded non-decision: no per-step leading multiplier.** Line spacing stays whatever the font reports. Introducing one is the most visible difference between a real type scale and a font-size swap, and it belongs to a typography feature, not this one.

Traps: (1) `paintOutset()` runs on the **damage path** (every `invalidate()` and every partial-repaint cull test), so it must not resolve tokens or measure text; `iconOverhang` is therefore computed once in `onLayout` and merely read back. That is sound only because `height()` can change solely through `layoutBox`, which always calls `onLayout`. (2) The icon is painted **before** the text clip is installed, under a clip of its own (`0, iconTop, width(), iconBox`): nesting it inside the text clip would shave off exactly the overhang `paintOutset()` promises to repaint, while the icon clip still stops at `width()` horizontally so a squeezed Label cannot smear the glyph onto its neighbour. (3) `setText`'s fast path takes `invalidate()` instead of `markNeedsLayout()` when the measured width is unchanged. It must resolve the font through `effectiveFont(tokensFor(this))`, the same expression a measure uses, or a step change slips through the local-repaint branch; the guard is additionally restricted to `!wrap && scene() != null`. (4) The derived-font memo is keyed on the **base** `Font` instance and is invalidated by `setFont`/`setRole`/`setStrong`/`setItalic` but *not* by a step change; that is correct only because each step's token is a distinct, process-stable instance, so `base == memoBase` simply misses. It exists because `Font.bold()`/`italic()` allocate a fresh record per call against an `IdentityHashMap` glyph memo. (5) `baselineOffset()` is `textTop(lineHeight) + ascent`, **not** bare `ascent`: the ink starts at y = 0 only under `VAlign.TOP`, and the default is CENTER; the paint path shares the same `textTop`, which is what keeps them from drifting. (6) Resolved: the `Objects.equals` guard on `setFont` and the derived-font memo both landed; `LabelTest`'s formerly nonexistent icon coverage is now three tests (`theIconTakesTheTabledBoxAndTheLabelGap`, `theIconBoxAndTheGapFollowTheStep`, `theIconOverhangsTheRowInsteadOfGrowingIt`), the last of which asserts the exact `paintOutset` value and that a bare and an icon Label measure the same height.

### 7.28 ImageView: **does not participate.**

Reads no `SizeTokens` at all: there is not one metric token in the file, and the only `Theme` read is a *colour* (`disabledText`, for the tinted/disabled path). No table.

Every quantity is an **external fact**: the app's explicit `setPreferredSize` in logical points, or the asset's own pixel dimensions. Scaling either input is actively harmful:

- `Fit.NONE` documents "1 px = 1 logical point", the mode an app picks precisely to defeat scaling.
- Bitmaps go through `Canvas.drawImage` **texture sampling**, unlike `Icon.paint`'s re-rasterization at `round(size × contentScale)`. "Scale the icon" and "scale the image" are fundamentally different operations, and every step but MEDIUM would ship a visibly softer picture.
- The `-1` sentinel on `preferredWidth`/`preferredHeight` is a live hazard for any multiply-all-extents pass (−1 × 0.6 = −0.6 silently breaks the natural-size branch).

`ProgressBarAndImageTest.imageViewMeasuresToNaturalSizeOrPreferred` (natural 32 × 16, preferred 64 × 64) is the guardrail proving this decision. Two narrow, opt-in concessions remain **deferred past v1**: a convenience for tinted icon-mode adopting `iconBox`, and a per-step placeholder extent for the null-image async case (`onMeasure` returns 0 × 0 with no image, so `Images.loadAsync` pops layout); the latter applies **only** when no preferred size was given.

### 7.29 Viewport3D: **does not participate; its no-GPU placeholder does.**

| token (placeholder branch only) | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `radiusMedium` (placeholder frame) | 5 | 6 | **8** | 10 | 12 |
| `body` (the "3D unavailable" message) | 11 | 12 | **14** | 16 | 19 |

A GPU surface, not a control: no padding, border, focus ring, radius or typography on the normal paint path. Its extent is `setPreferredSize` or the bare 360 × 260 default, and making that step-dependent would change the offscreen `RenderTarget`'s pixel count **quadratically, times 4 MSAA samples**, turning a design token into a VRAM and fill-rate knob, the exact wrong coupling. `SAMPLES = 4` likewise must never become a step, and nothing may enter `onMeasure`.

The verdict moved on one point since revision 1: the no-GPU placeholder is **not** pinned to MEDIUM. It is real UI and the only token-sized chrome the widget owns, so it resolves the row like every other component and a viewport dropped into an XSMALL panel does not fall back with a MEDIUM corner radius and MEDIUM body type. The lookup lives **inside the placeholder branch**, deliberately: the 3D path sizes nothing from the step and would otherwise pay a resolution per frame at 60 fps for a value it never reads.

`DRAG_SLOP` 2 is a motor constant, locked in `Strokes`; the literal that used to sit in `onMouseEvent` was the last copy of the value, and growing it at XLARGE would make deliberate small drags register as clicks.

Traps: (1) **The placeholder still has no clip and no ellipsis.** The message is centred at `(width() − metrics.width()) / 2`, which goes negative on a narrow viewport and bleeds onto neighbours. The step now *moves the threshold* as well, since the message is drawn at `body`, so a viewport wide enough at XSMALL can bleed at XLARGE. `Label.ellipsize` is static and package-visible; this is a fix independent of the size axis. (2) **The click-vs-drag slop is still tested against the per-event delta** while `lastX`/`lastY` are reassigned on every DRAG, so a slow drag never sets `dragged` and spuriously fires `onClick` on RELEASE; accumulate against the press origin. Filed separately; locking `DRAG_SLOP` neither causes nor fixes it. (3) `Viewport3DInputTest` has no pixel assertions on the widget (it drives `layoutPass(200, 200)` and a 30 pt drag), so nothing here breaks; the corollary is that the 360 × 260 default is asserted **nowhere** in the repo. Add a measure test before touching it.

### 7.30 Theme: **not reshaped. The three Font tokens delegate to MEDIUM, the six floats stay literals, and the table is static.**

| Theme field | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `radiusSmall` (literal 4) | 3 | 3 | **4** | 5 | 6 |
| `radiusMedium` (literal 8) | 5 | 6 | **8** | 10 | 12 |
| `radiusLarge` (literal 14) | 9 | 11 | **14** | 17 | 20 |
| `spacingSmall` (literal 6) | 3 | 4 | **6** | 8 | 10 |
| `spacingMedium` (literal 12) | 6 | 9 | **12** | 16 | 20 |
| `spacingLarge` (literal 20) | 12 | 16 | **20** | 26 | 32 |
| `body` = `SizeTokens.MEDIUM.body()` | 11 | 12 | **14** | 16 | 19 |
| `label` = `SizeTokens.MEDIUM.label()` | 10 | 11 | **13** | 14 | 17 |
| `title` = `SizeTokens.MEDIUM.title()` | 15 | 17 | **20** | 24 | 29 |

The nine tokens are `public final` **instance** fields, identical in all 13 palettes and absent from both the private constructor and `make()`. Revision 1 proposed rebasing all nine onto `SizeTokens.MEDIUM` and deprecating them; what shipped is a **split**, and the split is the whole point:

> **The six float tokens ARE inlined at every call site, and always have been.** They are *constant variables* (JLS 4.12.4: `final`, primitive, constant initializer) even as instance fields, and JLS 13.1 resolves every reference to one at compile time, as verified with `javap`: `theme.spacingLarge` compiles to `ldc 20.0f` with the receiver null-checked and popped. Rebasing them onto `SizeTokens.MEDIUM` would turn every read in every consumer (this repo and every application compiled against it) into a `getfield`. **So they keep their literals on purpose**, and `Theme`'s static initializer compares all six against `SizeTokens.MEDIUM` and throws `AssertionError` at class init on any divergence. Drift is impossible rather than unlikely.

The three `Font` tokens are the opposite case: `Font.of(...)` is a method call, so they were never constant variables, nothing was ever inlined from them, and rebasing costs no compatibility. It buys object identity (`assertSame(theme.body, SizeTokens.MEDIUM.body())`, asserted by `ControlSizeTest.mediumIsTheDefaultRowAndThemeSharesItsFontInstances`) and collapses 13 palettes' 13 distinct `Font` instances for one logical face into one entry in the backend's identity-keyed memo. Nothing is marked `@Deprecated`: these fields are the correct thing to write when you mean MEDIUM.

**The token table is one process-wide `static final SizeTokens[]` (`SizeTokens.TABLE`), and `tokens(ControlSize)` / `tokensFor(Widget)` are `final` instance methods over it.** Metrics are palette-independent **by construction**, and an overridable per-palette variant would be a trap on three counts, all recorded in `tokens`' javadoc: the six float fields are inlined at their call sites, so an override could not reach a field read at all; the nine fields read the *static* MEDIUM row, so an instance override would be invisible to every unmigrated call site and would make `assertSame(theme.body, theme.tokens(MEDIUM).body())` false; and `Theme.setCurrent` only assigns a volatile field. There is **no theme-change listener anywhere in the repo** (`ControlSize.addChangeListener` and `Fonts` have one; `Theme` does not), so a palette that could change metrics would change every measurement in every window with zero relayout. If per-palette metrics are ever wanted, **both** prerequisites are required and neither may be taken alone: route `setCurrent` through the same per-scene registry as `Fonts`/`ControlSize`, **and** initialize the nine fields from `this.tokens(MEDIUM)` in the constructor.

`Theme`'s six animation tokens (`animEasing`, `animHover`, `animFocus`, `animFade`, `animTab`, `animWindow`) stay exactly where they are: durations are not extents, and `SizeTokens` declares no `double` of any kind so one cannot be smuggled into a row. Its static initializer also installs the tooltip style (§7.31).

Naming hazard to note: `Theme` already spends the words Small/Medium/Large three times (`radius*`, `spacing*`). `ControlSize`'s `XSMALL…XLARGE` deliberately does not reuse them, so `t.radiusMedium()` at step LARGE is unambiguous.

### 7.31 Tooltips (toolkit path): **participate via the anchor's step, box included.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `label` (the tooltip's font) | 10 | 11 | **13** | 14 | 17 |
| `radiusSmall` | 3 | 3 | **4** | 5 | 6 |
| `tooltipPadH` | 5 | 6 | **8** | 10 | 12 |
| `tooltipPadV` | 3 | 4 | **5** | 6 | 8 |

`Theme`'s static block installs a `Function<ControlSize, TooltipStyle>` into `limn.scene.Scene`, building `TooltipStyle(surfaceRaised, outline, text, label, radiusSmall, tooltipPadH, tooltipPadV)` from `SizeTokens.of(step)` and the *current* theme, so one installed lambda follows both runtime theme switches and per-anchor size changes. It is applied at **two** call sites, `tooltipRect()` and `paintTooltip()`, plus the null-supplier gate in `updateTooltipFade()`; a widening that reaches only one of the two gives a panel painted at one step and damaged at another.

The step is the hovered anchor's, resolved live by `Scene.tooltipStep()`. The anchor can be null or detached between `showTooltip` and paint (`tooltipRect()` is reachable from `damageTooltip()` on the hide path), so the fallback is stated rather than left to the reader: `ControlSize.processDefault()`.

The two previously pinned pad constants are **gone from `Scene`**, which is the point of the exercise: leaving them locked would put a 10 pt label inside a MEDIUM box (padding at ~27 % of the panel) at XSMALL and a cramped one at XLARGE. That is the type ramp moving while the box does not, the inverse of the whole design.

Locked, and deliberately not tabled: `TOOLTIP_DWELL_MS` 600 and `TOOLTIP_FADE_SECONDS` 0.10 (time, not extent); the pointer offsets `+12` / `+20` / `−8` and the 4 pt screen margins in `tooltipRect()` (screen furniture, not component density); the ±1 pt damage bleed in `damageTooltip()`; and the panel outline's weight.

Traps: (1) **The tooltip outline is a literal `1` in `paintTooltip`, not `Strokes.BORDER`.** `Strokes` lives in `limn-components` and `Scene` in `limn-toolkit`, so the dependency runs the wrong way and the constant cannot be referenced. It is `BORDER` by value and must stay so; `TooltipStyle` carries no stroke field, which is what keeps a future contributor from making it per-step. (2) `tooltipPadV` **must stay integral** at every step: the panel height is `lineHeight + 2 × padV` and a half-point pad puts the box on a fractional device row at scale 1.0. (3) `tooltipRect()` is called from the damage path *and* from paint, and both re-`apply` the supplier (cheap only because the supplier is a table index plus a record allocation; anything heavier there runs per damage event). (4) `TooltipDamageTest` hovers (30, 15) inside a Button and asserts the damage rect is `< 150 × 60`; that ceiling is fed by `label`, `tooltipPadH` and `tooltipPadV`, so it holds at MEDIUM and must be parameterized before tooltips are asserted per step; there is no per-step tooltip test in the repo today.

---

### 7.32 ColorPicker: **participates. Six rows of its own, MEDIUM unmoved.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `colorFieldH` (saturation/value canvas) | 100 | 122 | **148** | 180 | 216 |
| `colorRampW` (hue ramp) | 12 | 14 | **18** | 22 | 26 |
| `colorRailH` (channel rail band; EVEN: centred in its box) | 6 | 8 | **10** | 12 | 14 |
| `colorThumbH` | 12 | 15 | **18** | 22 | 26 |
| `colorThumbW` (also the rail's travel inset, halved) | 8 | 9 | **10** | 12 | 14 |
| `colorGap` (block stack and channel line) | 5 | 6 | **8** | 10 | 13 |

Reused rather than tabled again: `fieldIcon` for the channel letter column (a fixed
column inside a field row, and 16 at MEDIUM already), `spacingSmall` for the gap
between channel lines and for the checkerboard square, `controlHeight` for the
before/after swatch (one control tall and two wide, so it lines up with the hex
field beside it). The rail box is `max(MIN_HIT_TARGET, colorThumbH + 2·FOCUS_GAP_SLIDER
+ FOCUS_RING)`: the ring around the thumb is the same absolute affordance a Slider's
knob gets, so the box has to clear it at every step, and the 24 pt floor still binds
at XSMALL and SMALL.

**MEDIUM holds the shipped geometry exactly**, so adopting the axis moved no pixel at
the default step; the picker was fixed-size before this and the migration is a pure
widening. The one construction-time hazard (§5.9) is that the picker builds its tree
once: nothing reads a token in the constructor, and `applyStep` pushes the row into
the gaps and the tab padding from `onMeasure`/`onLayout`, relying on the equality
guards in `Flex.gap` and `Padding.setInsets`. Fixed boxes are a private `TokenBox`
rather than `SizedBox`, for the same reason.

### 7.33 SplitPane: **participates. One extent, and it is the gutter.**

| token | XS | SM | **MD** | LG | XL |
|---|---|---|---|---|---|
| `separatorBox` (the gutter between the panes) | 5 | 7 | **9** | 13 | 17 |

Nothing else about a split scales, and that is the finding rather than an omission.
The gutter *is* a `Separator`'s box (a centred hairline's worth of room), so it
reuses that row, odd at every step, and the parity rule carries over unchanged. The
grab band is `MIN_HIT_TARGET` at every step (§4.1: a hit target is a motor constant,
and a divider is the one thing in the control a user chases), the resting line and its
hover thickening are `HAIRLINE → FOCUS_RING` (locked weights, one animated stroke),
and the arrow steps are value-domain increments (§4.3): a splitter in a dense panel
must not move the boundary in coarser jumps than a roomy one.

## 8. Compatibility

**Claim: with nothing declaring a step, every existing Limn UI renders byte-identically and every existing test stays green, with exactly three documented exceptions: D15 (§7.17, one hairline moves 1 pt, its own commit), the `paintOutset()` commit (§9, damage rects only), and the degenerate-ruler cases of §8.4.**

The rule is **per cell, not per row.** Revision 1 stated it per row and therefore propagated five known MEDIUM warts into four steps that have no screenshot, no test, no baseline and no user:

| wart | revision 1 (all five steps) | revision 2 (MEDIUM cell only) |
|---|---|---|
| Spinner height `controlHeight + 2` | 26 / 30 / **34** / 42 / 52 (2 pt taller than every row-mate at every step) | 20 / 26 / **34** / 40 / 50 (aligned at four of five) |
| SegmentedControl pill radius `radiusMedium − 1` | 4 / 5 / **7** / 9 / 11 | 3 / 4 / **7** / 6 / 7 (concentric everywhere but MEDIUM) |
| TabbedPane hover/focus radii | non-concentric at all five insets | concentric at four; MEDIUM keeps `radiusSmall` |
| `Slider.pad()` omits the ring stroke | 13 → clipped ring at every step | `+ BORDER` at four; MEDIUM keeps 13 |
| fractional icon boxes | 14.89 / 16.06 / **18.41** / 20.75 / 24.27 | 15 / 16 / **18.40625** / 21 / 24 |

Second reason, purely practical: phase 5 would otherwise need **five** re-baselines per item instead of one, which makes the D-list strictly more expensive the longer it waits; that is the opposite of the stated rationale for deferring.

### 8.1 The proof, in five parts

**(a) Typography is identical by object identity.** `Theme.body/label/title` are initialized *from* `SizeTokens.MEDIUM`, so `assertSame(theme.body, theme.tokens(MEDIUM).body())` holds. Nothing can drift because there is one object. And because the table is `static final` and palette-independent (§7.30), it holds for every palette.

**(b) Every MEDIUM numeric *cell* equals the literal it replaces.** §8.2 lists all of them with source file and line. Float equality is exact: both sides are the same decimal literal. Spot-checked cell by cell against the sources during this revision.

**(c) The composite height formulas are algebraic identities, for *any* ruler.**

| component | today | becomes | identity at MEDIUM |
|---|---|---|---|
| Button height (`:108`) | `lineHeight + 2·spacingSmall + 4` | `max(controlHeight, lineHeight + 2·padV)` | `2·6 + 4 = 16 = 2·8`; and with Roboto `32.40625 > 32` so the floor does not bind |
| TextField height (`:216`) | `lineHeight + 2·(spacingSmall + 2)` | same | `2·(6+2) = 16 = 2·8` |
| ComboBox height (`:279`) | `lineHeight + 2·(spacingSmall + 2)` | same | idem |
| Button width (`:107`) | `contentW + 2·spacingLarge` | `contentW + 2·padH` | `20 = 20` |
| TextField padH (`:190`) | `spacingMedium` | `fieldPadH` | `12 = 12` |
| TextField ink anchor (`:353` vs `:362`) | baseline centred, bands at `padV` | both from `textTop()` | `textTop() = (32.40625 − 16.40625)/2 = 8 = padV` |
| TextArea pad (`:200`) | `spacingSmall + 2` | `areaPad` | `8 = 8` |
| Checkbox / Radio gap | `spacingSmall` | `gapLabel` | `6 = 6` |
| Label icon gap (`:34`) | `ICON_TEXT_GAP = 6` | `gapLabel` | `6 = 6` |
| TabbedPane strip (`:254`) | `lineHeight + 2·HEADER_PAD_V` | `lineHeight + 2·tabPadV` | `9 = 9` |
| Slider height (`:163`) | `2·(THUMB_HOVER_RADIUS + FOCUS_RING)` | `max(MIN_HIT_TARGET, 2·pad)` | `max(24, 26) = 26` |
| Spinner height (`:257`) | `HEIGHT = 34` | `controlHeight + (MEDIUM ? 2 : 0)` | `32 + 2 = 34` |
| MenuBar title width (`:81-91`) | `2·H_PAD + textW` | `max(MIN_HIT_TARGET, 2·menuBarPadH + textW)` | `24 ≤ 24 + textW` → no-op |
| SegmentedControl segment | `textW + 2·PAD_H` | `max(MIN_HIT_TARGET, textW + 2·segPadH)` | `24 ≤ 32 + textW` → no-op |
| ScrollBar `thickness()` | `WIDE + 2·MARGIN` | unchanged | `15` |

The `+4` (Button) and `+2` (TextField, TextArea, ComboBox) magic addends are **absorbed exactly**, not approximated: they disappear from the source without moving a pixel.

**(d) Hand-tuned glyph geometry is bit-exact at MEDIUM.** Every scaled path is `literal × (resolved / mediumConstant)`. At MEDIUM `resolved == mediumConstant`, so the ratio is `1.0f` exactly (IEEE-754 division of a finite non-zero float by itself) and `literal * 1.0f == literal` bit-for-bit. Covers `Checkbox`'s check path, `RadioButton`'s dot inset, and `PopupMenu`'s check and arrow paths. The §4.4 floors do not disturb this: they set the XSMALL/SMALL *cells* equal to the MEDIUM cell, so those steps also get ratio `1.0f`.

**(e) Nothing outside the tables changes.** `ScrollBar`, `ScrollView`, `ImageView`, `Viewport3D`, `Menu`, `MenuItem`, `TextEditModel`, `ButtonGroup`, `PasswordField` and `SearchField` have no per-step edits at all. `WHEEL_STEP` is 48 in all six components. Every animation duration, every epsilon, every free-axis preference is untouched. Every value in §4.2 keeps its literal; extracting `CARET = 1` from five copies changes no value.

Consequently the audit's long list of "tests that will break" **does not apply at MEDIUM**. The complete inventory, which revision 1 left six files short:

`MenuTest:80-81/251/267`, `MenuDamageTest:18/45/70`, `TabbedPaneTest:31/72-97/126/136-139`, **`TabScrollOverflowTest`**, **`TabbedPaneFocusTest`**, `SegmentedControlTest:14-17/35-36`, `TextFieldTest:181-188`, **`TextFieldImeTest`**, `TextAreaTest:79/90/102-108`, **`TextAreaImeTest`**, `SpinnerTest:26/42-50`, `SpinnerHoldTest:47/65-109`, `SliderTest:26/44/53`, `MiscComponentsTest:16/19`, `ScrollViewTest:62`, **`ScrollBarTest:46`**, `LabelTest:78/94/96`, `ProgressBarAndImageTest:45/65/79/104-116`, `CaretDamageTest:48/132/195`, `CheckboxTest:41-48`, `ButtonTest:105-111`, `FocusRevealTest:32-41`, `TooltipDamageTest:96-124`, **`TransitionIntegrationTest`**. All stay green, unmodified, and become the MEDIUM regression net. Several (`ScrollViewTest:62`, `TextAreaTest:79`, `MiscComponentsTest:16/19`) are promoted to *guards* for locked values. The two IME files matter specifically: they are the only coverage of the preedit path, which is where §7.8's anchoring defect and §7.11 trap 3's `baseline + 2` bug live, so §10.2 #21 adds the per-step assertion that makes those deferrals executable rather than rhetorical.

### 8.2 Hardcoded constants that become tokens

`SizeTokens` fields, with the MEDIUM value each must equal and its source. Every row below was re-read from the file during this revision.

| token | MEDIUM | today's source |
|---|---|---|
| `body` / `label` / `title` | 14 / 13 / 20 | `Theme.java:59/60/61` |
| `spacingSmall` / `Medium` / `Large` | 6 / 12 / 20 | `Theme.java:64/65/66` |
| `radiusSmall` / `Medium` / `Large` | 4 / 8 / 14 | `Theme.java:54/55/56` |
| `controlHeight` | 32 | `SegmentedControl.HEIGHT:31` |
| `padH` | 20 | `Button.java:107` (`spacingLarge`) |
| `padV` | 8 | `TextField.padV():194` (`spacingSmall + 2`) |
| `gapIcon` | 8 | `Button.ICON_TEXT_GAP:29`, `TextField.ICON_GAP:39` |
| `gapLabel` | 6 | `Checkbox:119`, `RadioButton:108/131`, `Label.ICON_TEXT_GAP:34` |
| `gapButtonRow` | 6 | `Dialog.java:127` (`spacingSmall`) |
| `indicator` | 18 | `Checkbox.BOX_SIZE:32` = `RadioButton.RING:28` |
| `indicatorInset` | 4 | `RadioButton.java:126` (`RING/2 − 4`) |
| `switchTrackW` / `H` / `ThumbInset` | 40 / 22 / 3 | `Checkbox:33/34/155-157` |
| `segHeight` / `segPadH` / `segInset` | 32 / 16 / 3 | `SegmentedControl:31/30/32` |
| `fieldPadH` | 12 | `TextField.padH():190` (`spacingMedium`) |
| `fieldDividerInset` | 8 | `TextField.java:337` (`padV()`) |
| `fieldTrailing` | 32 | `TextField.TRAILING_WIDTH:40` |
| `fieldWidth` | 240 | `TextField.preferredWidth:46` |
| `areaPad` | 8 | `TextArea.pad():200` (`spacingSmall + 2`) |
| `areaWidth` / `areaHeight` | 320 / 140 | `TextArea:42/43` |
| `newlineHint` | 6 | `TextArea.java:386` |
| `comboCaretGutter` / `TextClip` / `CaretCenterX` | 24 / 26 / 16 | `ComboBox:278/311/317` |
| `chevronHalfW` | 5 | `ComboBox:321/323` |
| `popupGap` | 4 | `ComboBox:219/229/237` (**three copies**) |
| `popupItemHeight` / `popupPadV` | 30 / 6 | `ComboBox.ITEM_HEIGHT:41`, `POPUP_PADDING:42` |
| `popupMarkerCol` / `popupDotCol` / `popupDotRadius` | 16 / 7 / 2.5 | `ComboBox:537-538` / `:533` / `:533` |
| `spinnerHeight` / `ButtonW` / `Width` / `FieldInset` | 34 / 26 / 140 / 4 | `Spinner:50/48/49/305+319` |
| `arrowHalf` | 4 | `Spinner.java:335` (`float s = 4`) |
| `toolBarPad` / `Gap` / `SepInset` | 8 / 8 / 4 | `ToolBar:17/19/31` |
| `separatorBox` | 9 | `Separator.THICKNESS:20` |
| `menuBarStrip` / `menuBarPadH` / `ChipInset` | 32 / 12 / 3 | `MenuBar:36/35/107` |
| `menuRowHeight` / `SepBand` / `PadV` | 28 / 9 / 6 | `PopupMenu:56/57/58` |
| `menuCheckGutter` / `ArrowGutter` / `MinWidth` | 26 / 22 / 168 | `PopupMenu:59/60/61` |
| `menuSepInsetX` / `CheckInset` / `ArrowNudge` | 10 / 8 / 6 | `PopupMenu:659` / `:669` / `:674` |
| `menuHiliteInsetX` / `Y` | 4 / 2 | `PopupMenu:664` |
| `checkGlyphW` / `menuArrowW` × `H` / `scrollChevronHalf` | 9 / 5 × 8 / 4 | `PopupMenu:704-705` / `:711` / `:694` |
| `tabPadH` / `tabPadV` / `tabIconGap` / `tabRevealMargin` | 16 / 9 / 6 / 24 | `TabbedPane:48/49/50/53` |
| `tabHoverInset` / `stripBtnHoverInset` / `tabChevron` | 4 / 3 / 5 | `TabbedPane:581` / `:487` / `:495` |
| `sliderRail` / `sliderKnob` / `sliderKnobHover` | 5 / 8 / 10 | `Slider:32/33/34` |
| `progressThickness` | 8 | `ProgressBar.DEFAULT_HEIGHT:19` |
| `listRowSeed` / `listWidth` | 48 / 240 | `ListView:66` / `:238` |
| `dialogMaxWidth` | 440 | `Dialog.MAX_PANEL_WIDTH:84` |
| `tooltipPadH` / `tooltipPadV` | 8 / 5 | `Scene.java:1699/1700` |

Constants that move to `Strokes` instead (unchanged values): 17 literal `1`s; `1.5` (`RadioButton:123/136`, `Checkbox:125/137/138`, `ListView:428`, `Slider:192`); `1.6` (`Spinner:343`, `PopupMenu:695-699/711`, `TabbedPane:496`); `1.8` (`ComboBox:325`, `PopupMenu:704-705`); `2` (`Checkbox:143`, `Slider:188`, `TextField:426`, `TextArea:430`, `Button:139`); `2.5` (`TabbedPane.INDICATOR_THICKNESS:51`); `3` (`Slider.FOCUS_RING:35`); the twelve `0.5` half-pixel insets; `48` (`WHEEL_STEP`, six files); `2` (`ScrollBar.MARGIN:68`); `12` (`PopupMenu.SCROLL_HINT_H:63`); `2` (`Viewport3D:162`); and the new `MIN_HIT_TARGET = 24`.

Constants that stay exactly as they are, documented as *not* size: `Slider.PREFERRED_WIDTH:36` 220, `ProgressBar.DEFAULT_WIDTH:20` 220, `Separator.FALLBACK_LENGTH:22` 24, `ScrollBar.THIN/WIDE/MIN_THUMB:66/67/69` 5/11/28, `Viewport3D` 360/260/4, `Dialog.SLIDE_DISTANCE:83` 14, `ComboBox.EDGE_MARGIN:45` 8, `ProgressBar.SWEEP_FRACTION/SECONDS:21/22`, `TabbedPane.SCROLL_STEP_FRACTION:57`, `TextField/TextArea.BLINK_SECONDS`, `Spinner.HOLD_*_MS:52/53`, `TextEditModel.MAX_UNDO/GRAPHEME_SCAN_WINDOW`, the tooltip pointer offsets and screen margins (`Scene.java:1815-1819`), all six `Theme` animation tokens.

### 8.3 Deferred convergence items (each needs a screenshot re-baseline)

Every genuine improvement that would move a **MEDIUM cell** is deferred and individually opt-in. Five items that revision 1 deferred *entirely* now land at their four non-MEDIUM cells inside their cluster's phase (marked ◐), leaving only the MEDIUM cell behind:

| # | item | today → then | phase | why deferred |
|---|---|---|---|---|
| D1 | Unify Button / SegmentedControl / Spinner / ComboBox on one height at MEDIUM | 32.40625 / 32 / 34 → 32 | 5 | ◐ already unified at the four other steps; MEDIUM changes every screenshot containing a Spinner or a SegmentedControl |
| D2 | SegmentedControl pill radius at MEDIUM → `radiusMedium − segInset` | 7 → 5 | 5 | ◐ concentric at the four other steps; the `−1` is an eyeball fudge and MEDIUM is a visible 2 pt change |
| D3 | Reconcile focus gaps | Checkbox 1 vs RadioButton 1.5 → one value | 5 | locking without reconciling enshrines an inconsistency at all five steps; the fix is a visible 0.5 pt change on one of them |
| D4 | Reconcile arrow strokes | ComboBox 1.8 vs Spinner/PopupMenu/TabbedPane 1.6 → one value | 5 | same argument |
| D5 | `Slider.pad()` includes the ring's stroke at MEDIUM | 13 → 14 (height 26 → 28) | 5 | ◐ included at the four other steps; `paintOutset()` fixes the damage half at MEDIUM for free, so only the clipping remains |
| D6 | Integer icon box at MEDIUM | 18.40625 → 18 | 5 | ◐ integers at the four other steps already (§4.5), so the raster-cache win is banked; only the MEDIUM key remains fractional |
| D7 | `TextArea` measure in lines and columns | 140 → 149 | 5 | now a **no-op in effect** (§7.11 holds lines/columns flat to ±0.4 %) and an integral line *count* is unreachable under §3.6 anyway |
| D8 | `TextArea` horizontal pad = `fieldPadH` | 8 → 12 | 5 | aligns TextField and TextArea text on one x column |
| D9 | `Label` icon box ≤ lineHeight | icon Labels are ~2 pt taller than plain ones | 5 | a real row-raggedness bug, independent of this feature |
| D10 | `ScrollBar` participates | 15 → 11/13/**15**/17/19 | 5 | the only structural blocker (`static thickness()`, **six** call sites incl. `ScrollBarTest:46`); Open Question #3 |
| D11 | `TabbedPane` concentric hover/focus radii at MEDIUM | `radiusSmall` at two insets → `outer − insetDelta` | 5 | ◐ concentric at the four other steps |
| D12 | Per-step `PasswordField` mask glyph | `•` everywhere → `·` / `•` / `●` | 5 | must preserve one glyph per code point; **superseded** (§7.9): the glyphs' ink quantised to 0.11 / 0.20 / 0.90 em, so the dot is now drawn at one ratio of the body font |
| D13 | *(promoted out of the D-list)* | n/a | **1 + 2** | `Widget.hitOutset()` + the two-pass `hitTest` is a **prerequisite** of the height ramp, not a convergence item |
| D14 | `Spinner.onMeasure` consults the value text | clips at MEDIUM already | 5 | pre-existing; widening-only, so safe, but out of scope |
| **D15** | `Separator` crisp-line snap `Math.round` → floor | MEDIUM's hairline moves 1 pt (now exactly centred) | **3, own commit** | the whole ramp depends on it (§7.17); deferring means five re-baselines instead of one |
| **D16** | `TextArea.clipRect` adopts TextField's −2/+4 AA bleed | `(pad, pad, viewW, viewH)` → bled | 5 | moves MEDIUM pixels; revision 1 had this as a phase-3 aside inside a "zero MEDIUM deviation" claim |
| **D17** | `PopupMenu.clamp()` shrink path | overflows instead of shrinking when `hi < lo` | 5 | unreachable at MEDIUM on any work area ≥ 168 pt, so it is diff-neutral there, but it *is* reachable at XLARGE (`menuMinWidth` 224) |

### 8.4 The documented ruler exception

`ComponentTestBase.RULER` (`:28-29`) returns `lineHeight = 12` **regardless of the font** (10 pt per code point, ascent 8, descent 2). Under that degenerate ruler, a MEDIUM Button's natural height today is `12 + 2·6 + 4 = 28`, and with the new `max(controlHeight, …)` floor it becomes **32**. Real rendering is unaffected (Roboto gives 32.40625 either way and the floor never binds at MEDIUM), but three sites read the natural height under the degenerate ruler and must be re-verified:

- `ComboBoxTest:58` and `:143`: the combo takes its natural height inside a `Column`, so siblings shift by 4 pt. `:149`'s press at (20, 70) moves from empty root space into the filler; the assertion (a non-focusable filler does not take focus) still holds, but the scenario changes. **Rewrite the press y as `combo.height() + filler.height()/2`**, since the audit already flagged this test as passing for the wrong reason.
- `FocusRevealTest:37-41`: 20 buttons in a 150 pt viewport; `maxOffsetY() > 0` holds more strongly.
- `TooltipDamageTest:96-124`: the hover at (30, 15) stays inside a *taller* button; the `< 150 × 60` damage assertion is fed by `label` and the two tooltip pads and is unaffected.

Empirically validated during review: applying the `max(32, …)` floor to Button/TextField/ComboBox and running the full component suite leaves it green.

The deeper point: **MEDIUM-under-the-degenerate-ruler was never the shipped MEDIUM.** The real fix is `SCALED_RULER` (§10.1), which makes the two agree, and which is why it ships in **phase 0**, not phase 1 (§9).

---

## 9. Rollout plan

Each phase is independently shippable, independently testable, and gated on an **empty** screenshot diff at MEDIUM, except the two commits that are explicitly a rendering-output change, which carry their own re-baseline.

### Phase 0: mechanism only. No component consumes tokens.

`limn/scene/ControlSize.java`; the `Widget` fields and methods plus the epoch bump in `setSceneRecursively`; the resolved step in `measure()`'s cache key; `Scene.controlSize()/setControlSize()/relayout()` (renamed from `relayoutForFontChange`) and the `GlobalMetricsListener` **subscribed in the `Scene` constructor** rather than in `bind` (§5.4), serving both `Fonts` and `ControlSize`; `Widget.hitOutset()` + the two-pass `hitTest`, returning 0 everywhere; `Widget.baselineOffset()` + `Flex.CrossAlignment.BASELINE`; `Flex.gap` idempotence guard and its `protected` silent form; `Padding` un-`final`ed with a guarded `setInsets` and its silent form; `Label.setFont` guard; `limn/components/SizeTokens.java` with **all five rows authored** plus `TABLE`/`MEDIUM`; `Strokes` (incl. `MIN_HIT_TARGET`); `Tokens` + `TokenRow`/`TokenColumn`/`TokenPadding`; `Theme.tokens`/`tokensFor` (`final`, over the static table) and the nine fields rebased on `SizeTokens.MEDIUM`; the `Theme` javadoc fix; the widened tooltip supplier and `TooltipStyle`'s two new components. **Plus `SCALED_RULER` and `StrokeRecordingCanvas`**: they touch no production code, and §10.2 #1's baselines must be captured *under* `SCALED_RULER` before any conversion, which revision 1 made impossible by introducing the ruler in phase 1 (§8.4).

Nothing can regress because nothing consumes it. This is the commit where the API is reviewed on its own merits, before any pixels move.

### Phase 0b: `paintOutset()` overrides. **Own commit, explicit re-baseline.**

`Button` (3), `Checkbox` (2), `RadioButton` (2), `TextField` (1), `TextArea` (1), `Slider` (1), `SegmentedControl` (3). These are pre-existing bugs the axis would amplify, but their **entire purpose is to change which pixels are repainted** (`Scene.damageWidget` inflates by `1 + paintOutset()` = 1 today while Button's ring reaches −3), so a phase-0 "empty screenshot diff" gate cannot hold for any partial-rendering capture in the same commit. Re-baseline `MenuDamageTest`, `CaretDamageTest`, `TooltipDamageTest` and the damage-debug fade captures here.

### Phase 1: test and demo infrastructure.

The `ControlSizeTest` resolution/invalidation suite; the `control-sizes`, `control-sizes-coexistence` and `control-sizes-focus` capture scenes; the `--control-size` demo flag; the §10.3(f) HiDPI cross-check script. Plus the four **missing** test files, written before their components are touched: `ToolBarTest`, `MenuBarTest`, `SearchFieldTest`, `PopupPanelTest` (headless, hit-index == paint-index == reveal target). Raise `SvgIcon.MAX_CACHED_SIZES` to 16 (§4.5).

### Phase 2: the form-row cluster. **Phased by cluster, not by step.**

`Button`, `Label` (+ `Role`, + the derived-font memo), `Checkbox`, `RadioButton`, `TextField` (with the `textTop()` anchor, `PasswordField`/`SearchField` free), `ComboBox` field + `PopupPanel` (after the **six-way** geometry restatement is collapsed), `Spinner`, `SegmentedControl`. `hitOutset()` gains its consumers here, together with the two width-axis floors (`MenuBar.titleWidth`, `SegmentedControl` segment).

**Why cluster and not step.** If phase 2 sized `Button` but not `Spinner`, a SMALL form row would show a 26 pt button beside a 34 pt spinner, which is visibly broken. Sizing all five *steps* of a coherent cluster at once is safe; sizing all clusters of one *step* is not. The cluster boundary is "things that appear in the same row".

### Phase 3: containers and chrome, one cluster per commit.

`ToolBar` (with `gap` latching) → `TabbedPane` (`stripHeight` and `TabHeader.onMeasure` **must** move in the same commit; plus the overflow policy) → `TextArea` (with the `metricsChanged()` fix) → `Slider` → `ProgressBar` (with the `UNSET` sentinel) → `ListView` → `MenuBar` + `PopupMenu` (with the dual-theme-read fix and `PopupMenu`'s new `Widget`-anchored `showAt`/`showAnchored` overloads) → `Dialog` (with the constructor de-baking onto `Token*` containers) → **D15** → `Separator`.

Ordering rules: **never migrate a child's metrics without its container's in the same commit when the container derives its extent from the child's tokens** (`TabbedPane` is the case that bites; `ToolBar` is safe alone because its height is `tallestChild + 2·pad`), and **D15 lands immediately before `Separator`** so the parity story is correct on first sight.

### Phase 4: policy and documentation.

XSMALL documented as pointer-first in `ControlSize`'s javadoc and in the component javadocs that need it (Checkbox, RadioButton, Spinner, MenuBar); the `control-sizes-audit` debug scene with 24 pt target-circle overlays over both the paint box and the hit box.

### Phase 5: convergence, opt-in.

D1–D12, D14, D16, D17 from §8.3, each its own commit with its own re-recorded PNGs, so a screenshot diff attributes correctly and none can hide an unintended third change.

### Lint gate for phase 2/3 completion: a build failure, not a checklist

Because the six float tokens are inlined at every call site (§7.30), a stale `theme.spacingMedium` read is invisible to any runtime check; this grep is the **only** thing that closes the gap, so it is a Gradle `check` task that fails the build, not a reviewer's habit:

```bash
grep -rn 'theme\.\(body\|label\|title\|radiusSmall\|radiusMedium\|radiusLarge\|spacingSmall\|spacingMedium\|spacingLarge\)' \
  limn-components/src/main --include='*.java' | grep -v '/Theme\.java:'
# must be empty for every migrated component
```

---

## 10. Verification

### 10.1 Test infrastructure

**Do not change `RULER`**, because ~30 test classes depend on its exact-integer arithmetic. Add a sibling calibrated to Roboto's real vertical metrics, so a height computed in a test equals the height the backend produces:

```java
/**
 * Ruler that scales with the font, for control-size tests. Uses the embedded Roboto's real
 * vertical ratios (em 2048, ascent 1900, descent −500, lineGap 0): ascent 0.927734em,
 * descent 0.244141em, lineHeight 1.171875em. Advance is a flat 0.6em per code point (real
 * advances vary; only the vertical metrics need to be faithful for size assertions).
 * {@link #RULER} stays font-blind so the existing exact-integer assertions hold.
 */
static final TextRuler SCALED_RULER = (text, font) -> {
    float s = font.size();
    return new TextMetrics(0.6f * s * (int) text.codePoints().count(),
            0.927734375f * s, 0.244140625f * s, 1.171875f * s);
};
```

Also: `ComponentTestBase` resets `Theme.setCurrent(Theme.dark())` in `@BeforeEach` (`:40`); add `ControlSize.setProcessDefault(MEDIUM)` beside it, **and in `SpinnerHoldTest:43`**, which sets the theme directly without extending the base. This reset only works once the metrics listener is registered in `Scene`'s constructor (§5.4); before that fix it is a silent no-op on every headless scene. And fix the four duplicated rulers (`RecordingTestCanvas:71`, `CaretDamageTest:36`, `SpinnerHoldTest:29`, `TooltipDamageTest:34`) to delegate rather than re-declare.

`RecordingTestCanvas` discards stroke widths, so the pixel-locked contract needs a new helper that captures them:

```java
/** Captures every stroke width the frame produced, for the pixel-locked contract. */
final class StrokeRecordingCanvas implements Canvas {
    final List<Float> strokes = new ArrayList<>();
    @Override public void drawRoundRect(RoundRect r, float sw, Paint p) { strokes.add(sw); }
    @Override public void drawRect(float x, float y, float w, float h, float sw, Paint p) { strokes.add(sw); }
    @Override public void drawCircle(float cx, float cy, float r, float sw, Paint p) { strokes.add(sw); }
    @Override public void drawLine(float a, float b, float c, float d, float sw, Paint p) { strokes.add(sw); }
    @Override public void drawPath(Path2D path, float sw, Paint p) { strokes.add(sw); }
    // remaining Canvas methods no-op, as in RecordingTestCanvas
}
```

### 10.2 Unit tests to add (`ControlSizeTest`, plus a token-consistency test)

| # | test | what it protects |
|---|---|---|
| 1 | **MEDIUM is the identity**: every participating component's `onMeasure` under `SCALED_RULER` equals a baseline hard-coded from a pre-feature run (20/26/**32.40625**/40/50 for Button, TextField, ComboBox; 32 for SegmentedControl at MEDIUM; 34 for Spinner at MEDIUM; 18 for Checkbox/RadioButton; 26 for Slider) | §8. Baselines captured **in phase 0, under `SCALED_RULER`**, before any conversion |
| 2 | **Golden tokens**: all ~66 MEDIUM values asserted against literals copied from the pre-feature source, each with its `file:line` in a comment | the cheapest tripwire for a single wrong table cell |
| 3 | **Monotonicity, correctly scoped**: measured width and height are **non-decreasing** XSMALL → XLARGE for every participating component, and **strictly increasing on the axis its own tokens drive**. Exempt by name: `Slider`/`ProgressBar` preferred width (220, a free axis), `Separator`'s long axis (`FALLBACK_LENGTH` 24), and the `Slider` XSMALL == SMALL height tie (both 24 by the hit floor) | a transposed table row. Revision 1's "strictly increase … for every participating component" was **red on first run** against three components the spec itself declares participating |
| 3b | **The ink fraction falls**: `lineHeight ÷ resolvedHeight` is strictly **decreasing** and `effectivePadV` strictly **increasing** across the five steps | §3.1. Revision 1's own table failed this at SMALL, which is what made SMALL do no densification |
| 4 | **The height ladder is shared**: Button, TextField and ComboBox measure the *same* height at every step; SegmentedControl and Spinner join them at all steps but MEDIUM | form-row alignment, the point of `controlHeight`, and the per-cell compatibility rule |
| 5 | **Inheritance precedence**: `Column(SMALL){Button}` → SMALL; `button.setControlSize(LARGE)` wins; `setControlSize(null)` restores; nothing declared → scene default; **a hosted root with a non-null scene default takes the scene's, not the host's**; no scene → `processDefault()` | §5.2, including the ordering fix |
| 6 | **Scene-widget transparency**: `Column(LARGE){Padding{Column{Button}}}` resolves LARGE through two non-declaring `limn.scene.layout` intermediates | the executable proof that the enum belongs in the toolkit |
| 7 | **Reparenting**: memoize SMALL under one parent, move to a LARGE parent, assert LARGE | the epoch bump in `setSceneRecursively` |
| 7b | **Overlay reparenting**: memoize a **parentless** widget, `pushOverlay` it into a scene declaring LARGE, assert LARGE; `removeOverlay` and assert it reverts | §5.3. Fails if the epoch is bumped in `add`/`remove` instead of in `setSceneRecursively` |
| 8 | **Host link**: a `Scene`-root widget resolves `processDefault()`, then `setControlSizeHost(owner)` gives the owner's step, then a change on the owner reaches it **live**; and a cycle throws | §5.5, all five out-of-tree sites |
| 9 | **Deep re-measure under identical constraints**: attach a Button under a Column, `layoutPass(400,300)`, change the Column's step, `layoutPass(400,300)` again, assert the Button's width changed | the measure-cache-key decision (§5.4). Fails if the step is not in the key |
| 9b | **Unbound scenes hear global changes**: construct a `Scene` with **no window**, `ControlSize.setProcessDefault(LARGE)`, `layoutPass(400,300)`, assert the Button re-measured | §5.4. Fails against today's code, where the listener is registered only in `bind` |
| 10 | **Overriding subtrees keep their cache**: a Button declaring MEDIUM under a Column switched to XLARGE does not re-measure | the other half of the same decision |
| 11 | **Coexistence in one frame**: one scene, `Row{Column(XSMALL){Button}, Column(LARGE){Button}}`, one `layoutPass`, assert 20 and 40 | the maintainer's mandate, as a test |
| 12 | **Full damage on change**: `setPartialRendering(true)`, change a step, assert the next frame is full (`RecordingTestCanvas.cleared == true`, `firstClip == null`) | §5.4 |
| 13 | **Pixel-locked strokes**: with `StrokeRecordingCanvas`, paint each component at all five steps **at a settled focus state (`focus` exactly 0, then exactly 1)** and assert the **multiset of stroke widths is identical** across steps | *the whole feature's contract*, currently untested for anything. The settled-state requirement is mandatory: four components stroke at `1 + focus` (§4.2) and would otherwise record frame-dependent fractions |
| 14 | **Interning / allocation / palette independence**: `assertSame` on `tokens(LARGE)` across calls and on `tokens(LARGE).body()`; `assertSame(theme.body, tokens(MEDIUM).body())`; **`assertSame` across two consecutive `effectiveFont()` calls on a *bold* Label**; `assertSame(Theme.light().tokens(MEDIUM), Theme.dark().tokens(MEDIUM))` | the `FontStore.resolved` identity-memo hazard (§5.8) on its **actual** churn path, plus §7.30's static-table decision |
| 15 | **Shape-ratio bands**: `radiusMedium ÷ resolvedHeight ∈ [0.22, 0.26]` and `padH ÷ resolvedHeight ∈ [0.58, 0.67]` at all five steps | §3.5's design invariant, against a careless retune |
| 16 | **Separator centring**: `separatorBox` is odd at all five steps, and the painted line is **centred in its box to within 0.5 pt** at every step (exactly centred once D15 lands) | §7.17. Revision 1 pinned the formula `round(box/2) + 0.5`, promoting a 1 pt off-centre defect to an asserted invariant |
| 17 | **Token consistency**, a loop over all five steps asserting: `comboTextClip == comboCaretGutter + 2`; `comboCaretCenterX − chevronHalfW ≥ 4`; `radiusLarge ≤ popupItemHeight/2 + popupPadV`; `radiusSmall ≤ (popupItemHeight − 2)/2`; `popupDotCol + popupDotRadius < popupMarkerCol`; `menuCheckInset + checkGlyphW < menuCheckGutter`; `menuPadV + menuRowHeight/2 ≥ radiusMedium`; `menuPadV + menuSepBand/2 ≥ radiusMedium`; `2·arrowHalf ≤ spinnerButtonW − 4`; `menuRowHeight > lineHeight(body)`; `fieldPadH ≥ 0.7 × body`; `(sliderHeight − sliderRail) % 2 == 0` **except at MEDIUM**; `tabHoverInset > FOCUS_GAP_TAB`; `switchTrackW ÷ switchTrackH ∈ [1.78, 1.86]`; `indicator − lineHeight ≥ 1.5`; **and for every locked pen, `pen ÷ glyphMinExtent ≤ 0.45`** | ~25 relationships that are real but unwritten. **A hand-authored table can violate any of them with one careless edit and nothing will crash.** `popupItemHeight` and `menuRowHeight` are in scope for `≥ MIN_HIT_TARGET`; painted boxes that rely on `hitOutset()` (Checkbox, RadioButton, indicator rows) are explicitly **out** of scope and covered by #20 instead |
| 18 | **Paint-vs-hit consistency per step**: for `SegmentedControl`, `ComboBox.PopupPanel` (paint, hit **and `revealRow`**), `PopupMenu.Column` and `Spinner`, the index the hit test returns equals the index paint drew at that y, at all five steps | the class of bug a step change causes, which no existing test catches |
| 19 | **Latching setters**: `ProgressBar.setThickness(6)` then a step change → still 6; `ToolBar.gap(16)` then a step change → still 16; `Label.setFont(...)` beats the role and the step | §7.7, §7.16, §7.27 |
| 20 | **Hit targets reach 24 at every step**: for every control whose painted box falls below `MIN_HIT_TARGET` on either axis, a point 1 pt inside the *outset* box hits it, and a point inside a **sibling** does not | §3.3 point 2. This, not the height ramp, is what makes the claim true |
| 21 | **Per-step IME preedit geometry**: in `TextFieldImeTest` and `TextAreaImeTest`, at all five steps the preedit underline y lies inside the field box and **at or below the ink box bottom** | §7.8's anchor fix and §7.11 trap 3. Fails today for `TextArea` (`baseline + 2`), which is the executable form of that deferral |
| 22 | **Cross-step baseline alignment**: for all ten step pairs, a `Row` with `CrossAlignment.BASELINE` holding one control of each puts their baselines within 0.01 pt; with `CENTER`, within 2.8 pt and within 0.7 pt for adjacent steps | §3.7. Revision 1 had no test relating two coexisting subtrees at all |
| 23 | **The spacing ramp reaches containers**: a `TokenRow` at SMALL and the same at LARGE lay identical children out with **different gutters**; a raw `Row.gap(10)` is unchanged by a step change | §5.7. The widest of the three ramps was untested and unreachable |
| 24 | **TabbedPane overflow at XLARGE**: an XLARGE pane laid out at 160 pt asserts `viewWidth ≥ stripHeight`, that the tab-list chevron is hit-testable, and that a second `layoutPass` at the same width produces an identical `scrollOffset` | §7.25's overflow policy and the oscillation guard |
| 25 | **Non-participants are inert**: `ImageView` and `Viewport3D` measure identically at every step, with and without a preferred size; the `-1` sentinel survives | §7.28, §7.29 |
| 26 | **`SizeTokens` declares no `double`**: reflection assertion | the most likely mistake in a token refactor: an animation duration slipping into the table |

Tests to **rewrite against computed geometry** before their component's phase (all currently bake a MEDIUM literal into a synthesized coordinate; all stay green at MEDIUM): `SliderTest:26/44/53/56`, `SpinnerTest:26/42-61`, `SpinnerHoldTest:47/65-109`, `TabbedPaneTest:31/72-97/126/136-139`, `TabScrollOverflowTest`, `TabbedPaneFocusTest`, `TextFieldTest:181-188`, `TextFieldImeTest`, `TextAreaTest:74-108`, `TextAreaImeTest`, `MenuTest:80-81/198/212-227/251/267-271`, `MenuDamageTest:18/45/70`, `SegmentedControlTest:14-17/35-36`, `MiscComponentsTest:16/19`, `ProgressBarAndImageTest:45/65/79`, `ComboBoxTest:149`, `CaretDamageTest:48/132`, `ScrollBarTest:46` (only if D10 lands).

### 10.3 Screenshot scenes to add

**(a) `control-sizes`**: the matrix, in `CaptureScenes.java` beside `tabsAlignment:176-184`. Deliberately written so that **not one component calls `setControlSize`**; only the five rows do, and a row is a `limn.scene.layout` widget, so the scene rendering correctly *is* the proof of the propagation model. The row and the page **derive their gutters and padding from the resolved step** (a `TokenRow`, a `TokenColumn`, a `TokenPadding`); with a literal `gap(10)` inside `CaptureScenes.column()`'s `gap 10` and `scene()`'s `Insets.all(22)`, all five rows would show identical gutters and the capture would prove nothing about the widest of the three ramps:

```java
static Built controlSizes(boolean light) {
    Theme.setCurrent(light ? Theme.light() : Theme.dark());
    TokenColumn col = new TokenColumn(Tokens.Role.LARGE);
    col.add(new Label("ControlSize — type and metrics scale, strokes do not")
            .setRole(Label.Role.TITLE));
    for (ControlSize step : ControlSize.values()) {
        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);       // gutters follow the step
        row.crossAlignment(Flex.CrossAlignment.BASELINE);
        row.setControlSize(step);                              // inheritance does the rest
        row.add(new Label(step.name()).setMuted(true));
        row.add(new Button("Save"));
        row.add(new Button("Cancel").setSecondary(true));       // 1pt outline: must not thicken
        row.add(new Checkbox("On").setChecked(true));
        row.add(new Checkbox("Sw").setStyle(Checkbox.Style.SWITCH).setChecked(true));
        row.add(new RadioButton("Pick"));
        row.add(new TextField().setText("text"));
        row.add(new ComboBox(List.of("One", "Two")));
        row.add(new Spinner());
        row.add(new SegmentedControl(List.of("A", "B")));
        row.add(new Slider());
        row.add(Separator.vertical());
        col.add(row);
    }
    return new Built(scene(new TokenPadding(Tokens.Role.LARGE, col)));
}
```

**(b) `control-sizes-coexistence`**, a SMALL `ToolBar` above a MEDIUM form beside a **LARGE `Dialog`**, in one window, one frame, captured **twice**: once with the dialog in-scene and once as a native window. The in-scene run is the one whose panel is a parentless overlay, so the capture **fails visibly** (a MEDIUM dialog) if the `setControlSizeHost` link is missing; the native run is the only one that exercises the §7.26 trap 2 ordering constraint, where a late host link yields a correctly-stepped card inside a wrongly-sized window. Include one mixed-step row under `CrossAlignment.BASELINE`; that is the §3.7 mechanism on film.

**(c) `control-sizes-focus`**: focus rings are the most important pixel-locked quantity and are invisible in a static capture. Reuse the deferred-capture machinery verbatim (`controlsCapture`, `Main.java:252/338-351`): `focusTraverse` into one control per row, wait ~450 ms for `focusFade` to settle (`animFocus` 0.14, so the `1 + focus` border is at exactly 2.0), then `captureNextFrame`. **This is the capture that would have caught the `paintOutset` bug.**

**(d) `control-sizes-audit`** (phase 4, dev-only): every component at XSMALL beside the same at MEDIUM, with 24 pt target circles overlaid on **both** the paint box and the `hitOutset()` box. A non-participating (half-migrated) component is instantly obvious because the two instances are identical; a missing `hitOutset()` is obvious because only one circle is satisfied.

**(e) A `--control-size` flag** (`DemoOptions`, applied before scene construction via `ControlSize.setProcessDefault`) buys all 27 components at all 5 steps for free: `--scene kitchen --control-size SMALL --screenshot out.png`. Per the project's headless rule, always `--screenshot` (invisible window), never a live window.

**(f) The HiDPI cross-check: mechanical, not an eyeball test.** The claim "borders keep the same pixel thickness across steps" is only meaningful per content scale:

```bash
for step in XSMALL SMALL MEDIUM LARGE XLARGE; do
  for s in 1.0 1.25 1.5 2.0; do
    limn-demo --scene control-sizes --control-size $step --scale $s \
              --screenshot shots/cs-$step-$s.png
  done
done
```

From `Snapping.strokeWidthDev(w, scale) = max(1, round(w × scale))`, every `Strokes.BORDER` must be exactly `max(1, round(1 × s))` device px: **1, 1, 2, 2, identical across all five steps at a given `s`**. Sample the secondary Button's border row in each PNG and assert the run length. That is a script and it belongs in CI.

### 10.4 What a reviewer looks at

1. **Is the MEDIUM screenshot diff empty?** Not "close": empty. A non-empty MEDIUM diff blocks the phase, full stop. The two exceptions are pre-declared and carry their own re-baseline: the `paintOutset()` commit (phase 0b) and D15 (phase 3).
2. **In the `control-sizes` PNG, is every border and every focus ring the same thickness across the five rows?** Zoom to the secondary Button's outline and to the focus row. This is the feature.
3. **Do the gutters differ between the five rows?** They must. Identical gutters mean the spacing ramp, the widest of the three, never reached the container (§5.7).
4. **Do the ramps look like one design?** Compare the XSMALL and XLARGE rows: the silhouettes should be recognizably the same controls. If XLARGE looks like a zoomed screenshot, the type ramp has been made proportional.
5. **In the coexistence capture, do the mixed-step controls sit on one baseline?** Under `BASELINE`, exactly. Under `CENTER`, within the §3.7 tolerance. Under `START`, they will not, and `START` is wrong for a mixed row.
6. **Grep the diff for `Theme.current()` inside a field initializer or a constructor.** Any new one is a defect (§5.9).
7. **Grep the diff for a new `static final float` expressing a length in `limn-components`.** Any one not in `Strokes` and not documented as input tuning is a defect (§5.9d).
8. **Does every private paint/layout helper take `SizeTokens` as a parameter** rather than calling `tokensFor(this)` itself? Required for correctness in `SegmentedControl`, `PopupMenu` and `Spinner` (§5.8), and for cost everywhere else. And is `tokensFor` absent from every per-row and per-glyph loop?
9. **Does `padV()` appear as a paint or hit-test coordinate anywhere in the text cluster?** It must not; that is §7.8's defect. Every vertical position comes from `textTop()`, `fieldDividerInset` or `areaPad`.
10. **For each out-of-tree owner** (`ComboBox`, `PopupMenu` × 2 presentation paths, `Dialog` × 2 modes), is `setControlSizeHost` installed **before** the sizing `measure()`/`reposition()`/`pushOverlay` call, and is the ComboBox popup **closed** on a step change?

---

## 11. Decisions taken

All eight questions were resolved by the maintainer on 2026-07-29; §0 is the summary table.
This section records each answer, the reasoning offered with it, and what it obliges.
Five answers match the recommendation and are recorded briefly. Three diverge (2, 3 and 4),
and those carry consequences that must be propagated before implementation.

**11.1 Nomenclature: `ControlSize { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }`, as recommended.**
`XSMALL` pairs symmetrically with `XLARGE`; `MEDIUM` is the default name in MUI, Ant, Fluent and
Carbon; the type name avoids the collision with `limn.scene.Size`, which already exists as a
measurement result. The rejected alternatives (AppKit's `MINI…`, the original
`TINY…EXTRALARGE`) are in §2.3.

**11.2 Five steps, no per-component clamping (DIVERGES).** §11 wanted the five steps with
`XLARGE → LARGE` clampable where a component has an irreducible glyph, as macOS does. The
decision is that every component honours every step, with no exceptions.

*Obligation this creates:* **D17 moves out of the opt-in phase 5 and into the required rollout.**
`PopupMenu.clamp()` (`:591-593`) returns `lo` when `hi < lo`, so a column wider than the work area
overflows instead of shrinking. With `menuMinWidth = 224` at XLARGE that is reachable on narrow
work areas *and in tests*; it was the sole clamp candidate, and removing clamping removes the
mitigation. D17 must land before XLARGE is enabled for menus, and its shrink path moves MEDIUM
pixels on any work area below 168 pt, so it needs its own commit and re-baseline.

**11.3 MEDIUM control height is exactly 32; D1 in phase 1 (DIVERGES).** §11 wanted 32.40625
preserved through phase 3 with D1 as the first phase-5 commit. The decision is that the pixel grid
outranks byte-identical compatibility, since §3.6's own analysis put the cost of the fractional
extent squarely in the mixed-size rows this feature exists to enable.

*Obligations:* §3.1, §3.3 and §3.6 are updated above. `padV` at MEDIUM becomes 7 so the floor
binds. §8 changes from a proof of zero pixel change to **one enumerated change**: Button,
TextField and ComboBox each lose 0.40625 pt of height, Spinner loses 2 (34 → 32), and
SegmentedControl is already 32 (the D1 unification). One deliberate screenshot re-baseline in
phase 1. The `+ 4` literal at `Button.java:108` is deleted rather than absorbed.

**11.4 Height floor 24, no `hitOutset()` and no two-pass hit test (DIVERGES).** §11 wanted a 20 pt
floor with a hit region wider than the paint box, on the correct reading that WCAG 2.5.8 measures
the target rather than the painted box. The decision trades 4 pt of XSMALL density for leaving
input dispatch untouched (the only behavioural change the feature would have made, and the item
§11 itself called "the one thing that cannot slip").

*What this settles:* the spec's largest risk is gone. No `Widget.hitOutset()`, no two-pass hit
test, no phase-2 outset consumers. `MIN_HIT_TARGET` survives only as a `Math.max` clamp on two
width-axis floors (`MenuBar.titleWidth`, `SegmentedControl` segment width). Menu and dropdown rows
were already raised to 24 at XSMALL in revision 2, for the independent reason that they are not
`Widget`s and `hitOutset()` could never have reached them.

*The one loose end: Checkbox and RadioButton.* Their row is `max(indicator, lineHeight)`, which
is **18 pt at MEDIUM today** and would be 15 pt at XSMALL under §7.3's table. They were the main
consumers of `hitOutset()`. Dropping the mechanism does not create a violation at MEDIUM (the
18 pt target is pre-existing and unchanged), but it does make the dense steps worse than today for
toggles specifically. Three ways to close it, needing one more call:
  1. Put the toggle *row* on the control-height ramp (24/28/32/40/50) with the indicator glyph
     centred inside it. Fixes today's 18 pt as a side effect, but takes MEDIUM form rows from 18 pt
     to 32 pt, which is a large layout change, and the indicator would have to become even for parity.
  2. Floor the indicator at today's 18 pt for XSMALL and SMALL, so no step is worse than today.
     Cheapest, and costs the dense steps their density for toggles only.
  3. Accept 15/16 and document that toggles keep sub-24 targets at the dense steps, as they do
     today at MEDIUM.
Recommendation: **(2)**, because it guarantees no step regresses against today while leaving the
MEDIUM layout untouched.

**11.5 `ScrollBar` does not participate in v1, as recommended.** Removes the audit's only
structural blocker (`static thickness()`, six call sites including `ScrollBarTest:46`) and matches
OS precedent, where scrollbars do not track font size. Accepted cost, to be documented in
`TextArea`'s javadoc: at XSMALL a 15 pt overlay bar sits over a 6 pt `areaPad`, covering ~9 pt of
live text against 7 of 8 today. §7.18 stands.

**11.6 `Label.Role { BODY, LABEL, TITLE }` ships, as recommended.** Without it
`Dialog.java:122`'s `setFont(theme.title)` pins a MEDIUM title inside a LARGE dialog with no error
and no obvious cause, and every app copying that idiom inherits the trap. Cost: one public enum and
one setter on the toolkit's most-used component, and a 3 × 5 grid instead of a
`setFont`-versus-step precedence rule.

**11.7 The ComboBox popup dot scales (1.75 / 2 / 2.5 / 3 / 3.5), as recommended.** The one
deliberate departure from "marks are pixel-locked", now signed off and therefore citable as
precedent. The distinguishing argument to record for whoever cites it: the dot has **no stroke**;
it is pure area, so there is no pen weight to keep constant, and 2.5 pt of ink in a 42 pt XLARGE
row disappears. A mark *with* a stroke stays locked. Ships with `popupDotCol` so the dot stays
inside its gutter.

**11.8 `Tokens` + `TokenRow` / `TokenColumn` / `TokenPadding` ship; `Padding` loses `final`, as
recommended.** Un-`final`ing `Padding` is source- and binary-compatible. Without this the widest of
the three ramps (3.33×) is unreachable from the containers that own spacing, and §10.3(a)'s
flagship capture would show all five rows with identical gutters, so the proof would refute itself.

**11.9 Closed earlier, recorded for the trail.** The `Checkbox` check-mark pen at 2 pt was
resolved by arithmetic in revision 2 (raising `indicator` 13 → 15 at XSMALL restores 1.83 pt of
clearance against MEDIUM's 1.75, with the pen unchanged), not by taste.

**11.10 What landed, and what is still open.** The propagation this section used to demand is
done: §7 was rewritten from the shipped code (see its note), and the items below were resolved in
the phase named.

  - §7 per-component tables, §7.3/§7.4 toggle rows: rewritten from the implementation, phase 5.
  - §9 phase 0b's `hitOutset` mechanism and phase 2's outset consumers: deleted; decision 4
    cancelled that mechanism. `MIN_HIT_TARGET` survives as a plain `Math.max` at three sites,
    enumerated in `Strokes` and `ControlSize`.
  - §10.2 test #3b (ink monotonicity): dropped; §3.1 proves it unsatisfiable alongside all-even
    heights and a 24 pt floor. #20 (outset coverage): dropped with the mechanism. #17's
    `(sliderHeight − sliderRail) % 2 == 0` now holds at **every** step including MEDIUM, and
    `SliderTest.everyStepCentresTheRailOnWholePoints` asserts it against painted geometry.
  - `MenuTest` and `MenuDamageTest`: parameterized against the token row, phase 3.
  - D1, D2, D3, D4, D5, D6, D8, D9, D12, D14, D15, D16, D17: landed. D11 is closed as
    **not applicable**: the TabbedPane hover-pill concentricity rule reduces algebraically to a
    constant, because `radiusSmall` and `tabHoverInset` are the same ramp.

**Still open, and deliberately so:**

  - **D10: `ScrollBar` does not participate** (decision 5). It stays 15 pt at every step, so a
    dense `TextArea`, `ListView` or ComboBox popup gives proportionally more of its width to
    chrome. Each of the three states the cost in its own javadoc rather than absorbing it.
  - **D7**, `TextArea` measure in lines and columns: a no-op in effect, recorded rather than
    implemented.
  - **`Tokens.toggleColumnGap` has no automatic consumer.** A `TokenColumn` cannot know its
    children are toggles, so an app stacking them applies the gap itself. Without it, stacked
    toggles keep a 21/22 pt pitch at the dense steps and miss WCAG 2.5.8's *Spacing* exception.
  - **Checkbox and RadioButton rows stay under the 24 pt target**, at MEDIUM as they already did
    before this feature. The `control-sizes-audit` demo scene exists to keep that visible: it
    marks every element whose painted box cannot contain the target.
  - **`AREA_WIDTH` was not re-derived after D8**, so the visible column count drifts from a flat
    ~43.5 to 42.9…42.1 across the ramp. Under 3 %, monotone, and its own item if ever wanted.

## 12. Changes from review

Three adversarial reviews (visual, engineering, completeness) were applied. What changed, grouped by what drove it. Every factual claim about today's code below was re-verified against the files during this revision.

### Numbers that moved

| what | revision 1 | revision 2 | driver |
|---|---|---|---|
| control height | 24 / 28 / **32.4** / 40 / 50 | **20 / 26 / 32.4 / 40 / 50** | ink fraction was non-monotone (SMALL airier than MEDIUM), XSMALL ÷ MEDIUM was 0.74 (a compact step, not a dense one), contradicting §2.2's stated purpose. The 24 pt floor rested on a misreading of WCAG 2.5.8, which measures the pointer target, not the painted box |
| `padV` | 5 / 6 / **8** / 10 / 13 | **3 / 5 / 8 / 10 / 13**, with the *effective* column printed beside it | the declared row was unreachable at four of five steps, so §3.4's optical argument was made about numbers that never render |
| `label` | `body − 1` → 10 / 11 / **13** / 15 / 18 | **10 / 11 / 13 / 14 / 17** | a constant −1 makes contrast shrink to 5.3 % at XLARGE, below the 7 % JND the same section invokes to reject a 15 pt body |
| `indicator` (Checkbox/Radio) | 13 / 15 / **18** / 22 / 26 | **15 / 16 / 18 / 21 / 24** | 1.5 ÷ 13 = 11.5 % border ink (reads as a filled square); the radio annulus gap collapsed to the ring stroke; 13 exceeded `lineHeight` by 0.11 pt, so the form row height was decided by a 0.9 % margin |
| `indicatorInset` | 3 / 3.5 / **4** / 5 / 6 | **3.5 / 3.5 / 4 / 5 / 5.5** | holds dot ÷ ring in a ±4 % band on the new indicator row |
| `switchTrackW` | 28 / 33 / **40** / 48 / 57 | **31 / 35 / 40 / 47 / 56** | switch aspect ratio drifted 1.65 → 1.84; it is one shape at five sizes |
| glyph half-extents | shrank 22–30 % below MEDIUM | **floored at the MEDIUM cell for XS/SM** (§4.4) | locked pens hit 51 % of a chevron's vertical run and 53 % of a Spinner arrow's: a filled wedge, not a chevron |
| `fieldPadH` | 6 / 9 / **12** / 16 / 20 | **8 / 10 / 12 / 15 / 18** | 0.55 → 1.05 em is a 1.93× swing in a left-aligned run's margin; now 1.30× |
| `gapLabel` / `gapIcon` / Dialog button gap | all on `spacingSmall` (3 at XS) | **4/5/6/8/10**, **5/6/8/9/11**, **`gapButtonRow` 4/5/6/8/10** | 0.27 em glues a checkbox to its label; 3 pt between two activatable rects also defeats the 2.5.8 Spacing exception the spec leans on; §7.3's own trap 6 asked for ≥ 11 pt |
| `sliderRail` / height | 3/4/**5**/6/8 vs 24/24/26/31/36 | **4/4/5/7/8** vs **24/24/26/33/38** | `(height − rail)` was odd at three steps, putting a 220 pt rail's edges on half-pixels; two of those steps were free choices |
| `spinnerHeight` | `controlHeight + 2` everywhere | **`+ 2` at MEDIUM only** → 20/26/**34**/40/50 | 2 pt taller than every row-mate at all five steps, relatively worst (8.3 %) at the step created to be dense |
| SegmentedControl pill radius | `radiusMedium − 1` everywhere | concentric at four steps; **MEDIUM keeps 7** | the `−1` is an eyeball fudge with nothing to preserve outside MEDIUM |
| `Slider.pad()` | omits the ring stroke everywhere | **`+ BORDER` at four steps**; MEDIUM keeps 13 | same |
| icon boxes | fractional at all five steps | **15 / 16 / 18.40625 / 21 / 24** | five fractional raster keys per content scale against an LRU bound of 8 |
| `tabHoverInset` | 2 / 3 / **4** / 5 / 6 | **3 / 3 / 4 / 5 / 6** | `2 > 2` fails at XSMALL, so the hover pill swallows the focus ring; revision 1 flagged this and shipped it anyway |
| `popupItemHeight` / `menuRowHeight` | 22 / 26 / **30** / 36 / 42 and 22 / 24 / **28** / 34 / 40 | **24 / 26 / 30 / 36 / 42** and **24 / 26 / 28 / 34 / 40** | menu and dropdown rows are not `Widget`s, so `hitOutset()` cannot reach them; 2 pt is cheaper than a documented AA violation |
| `areaWidth` / `areaHeight` (+ new `areaPad`) | 240…480 × 100…212 | **252/276/320/368/436 × 110/120/140/162/192**, `areaPad` 6/7/**8**/10/12 | the default TextArea held 8 % *less* document at XSMALL and 10 % more at XLARGE, which is the one thing the spec rejects for `fieldWidth` and `dialogMaxWidth` |
| `fieldWidth` | 180 / 210 / **240** / 300 / 360 | **168 / 200 / 240 / 300 / 360** | re-derived so SearchField chrome stays flat at 28 ± 0.5 % under the new `fieldPadH`/`gapIcon` |
| `menuSepInsetX` / `menuCheckInset` / `tabIconGap` / `menuBarPadH` | n/a | retuned, see §7.22 / §7.25 / §7.21 | optical tracking with the row, not with `spacingSmall` |
| new tokens | n/a | `gapButtonRow`, `fieldDividerInset`, `areaPad`, `popupDotCol`, `tooltipPadH`, `tooltipPadV` | each is a distinct optical duty that was borrowing another token, or a literal the ramp left behind |

### Compile errors and provably false claims, corrected

- **§7.30(b) was backwards.** "JLS 4.12.4/15.29 inline only constant variables reached by a simple or `TypeName.Identifier` name, so `theme.radiusSmall` was never inlined" (verified false with `javap`: reading `t.radiusSmall + 1` compiles to `ldc 4.0f`). The six float tokens **are** inlined at every call site (JLS 13.1 applies to instance fields too). Consequences now stated: every downstream module must be recompiled in the same commit; no consumer built against an older jar sees a changed MEDIUM row; and the promised per-palette override path is impossible, so the table is `static final` and `tokens()` is `final` (§7.30, Open Question closed inside it). The lint gate becomes a Gradle build failure because it is now the only thing that closes the gap.
- **§6.4's chaining setters did not compile.** You cannot override a `final` method, and there is no covariant return from `void`. Replaced by `withControlSize(...)` helpers.
- **§5.9b's Dialog remedy did not compile and could not reach its objects.** `DialogPanel extends Widget` and `Widget.onMeasure` is `protected abstract`, so `super.onMeasure` is an error; `content` and the `Padding` are constructor **locals** (`Dialog.java:120/131`), only `buttonRow` is a field; `Padding` is `public final class`. Replaced by promoting them to `Token*` fields.
- **§5.4's global path could not reach an unbound `Scene`.** The listener is registered only in `bind` (`Scene.java:222-223`), and `layoutPass` early-returns on `!layoutDirty`, so tests #5, #11, #12 and the `@BeforeEach` reset would silently do nothing. Subscription moved to the constructor; test #9b added.
- **§4.2's `FOCUS_RING` inventory invented a constant and double-counted four statements.** `TextField:386`, `TextArea:351`, `ComboBox:306`, `Spinner:282` draw **one** rect at `1 + focus`, not a 2 pt ring; extracting `focus > 0 ? FOCUS_RING : BORDER` would delete the animation on four components. The only true separate-ring sites are `Button:139` and `Slider:188`. Test #13 now requires a settled focus state.
- **§7.17's Separator arithmetic was wrong in both direction and magnitude.** `Math.round` is `floor(x + 0.5)`: an odd box puts the line **1.0 pt below** centre (not half a point past), an even box 0.5 pt below (not before), and even boxes centre it better. The parity rule as written selected the worse case. Rewritten, with the real justification (constant offset across the ramp) and the real fix (floor instead of round) filed as **D15** landing before Separator's phase-3 commit, because at box 5 the split is 3 : 1.
- **§3.3's "WCAG 2.5.8 satisfied by construction" was false inside its own document** (15 pt indicator rows, 22 pt menu rows, `hitOutset` deferred to phase 5, while §9 phase 4 shipped it, a second internal contradiction). Scoped to the vertical axis, then replaced by the real mechanism.
- **§3.3's parity sentence was false.** A 1 pt stroke centred in an even box lands on a half-pixel (which is why `HALF_PIXEL_INSET` exists), and baselines are rounded by `GlCanvas.drawText` regardless of box parity. Replaced with the real reason (`height/2` drives pill radii and rail centring) and a real defence of 50 over 48.
- **§3.5 claimed `radiusMedium ÷ height` was flat while its own table dipped 20 %.** Now 0.231–0.250, stated honestly, band asserted at `[0.22, 0.26]`.
- **§3.6's integer-extent rule was violated by three of the spec's own tables.** Restated as a rule about *tokens*, with an explicit allowlist of font-derived resolved extents, and the wobble argument moved to where it is true (D1, and the mixed-step row).
- **§5.8's "15 `Font` instances, zero allocation, hazard impossible" was already false.** `Label.effectiveFont()` allocates via `Font.bold()`/`italic()` (a record), and `FontStore.resolved` stops caching past 4096. Now 60 interned Fonts plus a per-Label derived-font memo; test #14 asserts `assertSame` on the bold path.
- **§7.22 trap 6's `MenuTest:251` number was wrong** (408; the correct figure for 22 pt rows is 468, and under the revised 24 pt rows it is 528 at XSMALL and 590 at SMALL, against 652 at MEDIUM, which matches `MenuTest:267`'s own comment).
- **§7.11 trap 1 mis-attributed a pre-existing bug.** `cachedContentWidth` is cleared only at `TextArea:126` and `:697`, neither on the metrics path, so it is already stale after a font-family switch. Fixed once via `metricsChanged()` rather than by routing a step change through the edit path (which would reset scroll and fire `onChange`).
- **§7.14's "four restatements" of the popup row geometry were six**, and the missing one was `revealRow` (`:469-471`): the whole `Scrollable`/keyboard path.
- **§7.18's "five call sites" for `ScrollBar.thickness()` were six**; the sixth is `ScrollBarTest:46`, whose premise is a bar laid out at exactly its static thickness.
- **§5.7's "two-line change" for the tooltip SPI was two call sites plus a gate**, needed a null/detached guard on `tooltipTarget`, and left the tooltip *box* pinned at MEDIUM padding.
- **§10.2 test #3 was red on first run** against `Slider` (XS == SM height, 220 pt free width), `ProgressBar` (220) and `Separator` (`FALLBACK_LENGTH`).
- **§7.8's `fieldPadH ≥ radiusMedium + 3` finding is rebutted, not applied.** A rounded rect's left border intrudes `r − √(r² − (r−y)²)` at height *y*, which is 0.21 pt at the ink's topmost row at XSMALL and zero over the rest of the run, so there is no glyph/corner collision at any step. The em-ratio defect was real and is fixed; the same rebuttal applies to `menuSepInsetX`/`menuCheckInset`, where the real invariant (`menuPadV + menuRowHeight/2 ≥ radiusMedium`) is asserted instead.

### Structural additions

- **§3.7 Cross-step composition**: the section the visual review identified as missing outright. `Flex.CrossAlignment.BASELINE` + `Widget.baselineOffset()`, plus a measured tolerance table for `CENTER` (≤ 0.69 pt adjacent, 2.73 pt across the ramp) and a prohibition on `START` for mixed rows. Test #22, and it is on film in §10.3(b).
- **§5.7 spacing reaches containers**: `Tokens.applyGap`/`applyInsets` + `TokenRow`/`TokenColumn`/`TokenPadding`, and the explicit rule that a raw `Flex.gap`/`Padding`/`SizedBox` extent is a literal. Without it the 3.33× spacing ramp was unreachable and §10.3(a)'s capture would have shown five rows with identical gutters. Test #23; reviewer checklist item 3.
- **§5.5 is a five-row table** and `PopupMenu` gains `setControlSize` plus `Widget`-anchored `showAt`/`showAnchored` overloads; without them §7.21's MenuBar requirement and every app context menu could not reach a step at all, and the in-scene overlay path (`PopupMenu.java:270`) was missing from the site list entirely.
- **§5.2's resolution order puts the scene default before the host link**, which is what makes `Scene.setControlSize` reachable for popups, menus and dialog windows, i.e. for exactly the "compact tool window" §6.3 advertises.
- **§5.3's epoch bump moves to `setSceneRecursively`**, the single funnel for `add`/`remove`/`pushOverlay`/`removeOverlay`, so a parentless subtree pushed into a scene with a different default cannot keep a stale memo. Test #7b.
- **§5.9b's token push is not a setter call from inside `onMeasure`.** On the frame the step changes, `markNeedsLayout()` from inside a measure pass is swallowed by `layoutPass`'s trailing `layoutDirty = false` (`Scene.java:2058`), leaving the ancestor chain dirty with no scheduled pass. `protected final` silent forms, reachable only from the `Token*` subclasses' measure path.
- **§4.5 icon raster budget**: the arithmetic the atlas got and icons did not, plus `MAX_CACHED_SIZES` 8 → 16.
- **§7.25 overflow policy**, a number instead of "validate against the oscillation guard": below `4 × stripHeight`, drop prev/next, keep the list chevron, `viewWidth ≥ stripHeight`. Test #24.
- **§7.26 Dialog inheritance rule**: a Dialog inherits the *owner scene root's* step, not the opening control's, plus `show(Widget)` overloads and the `:305-306` ordering constraint.
- **§9 gains phase 0b**: the seven `paintOutset()` overrides are a deliberate rendering-output change and cannot ride a commit gated on an empty diff. `SCALED_RULER` and `StrokeRecordingCanvas` move into phase 0 so test #1's baselines can exist at all.
- **§8's compatibility rule is per cell**, with a table of the five warts that no longer propagate, and three declared exceptions instead of an absolute claim.
- **§8.1(e)'s test inventory gains the six missing files** (`ScrollBarTest`, `TabScrollOverflowTest`, `TabbedPaneFocusTest`, `TextFieldImeTest`, `TextAreaImeTest`, `TransitionIntegrationTest`), and §10.2 gains **#21**, the per-step preedit assertion that makes §7.11 trap 3's deferral executable; it fails today.
- **§10.2 grows from 21 to 26 tests**, including the ink-fraction monotonicity (#3b) that revision 1's own table would have failed, the hit-target reachability (#20) the height ramp now depends on, and the locked-pen ratio bound inside #17.
- **Two open questions closed by arithmetic** (the check-mark pen, the XLARGE Checkbox clamp) and **two new ones opened** by the fixes: the phase-1 `hitOutset()` scope, and un-`final`ing `Padding` for the `Token*` containers.
