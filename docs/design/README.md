# Design notes

Background that does not belong in the source. One file per subsystem, named after
what it explains rather than after a class, because a note tied to a file name goes
stale the moment the code is split or renamed.

**These files are not shipped.** A published Javadoc jar contains only the sources'
own documentation, so nothing in the code may point here: a reader with the artifact
and no repository would follow a dead reference. Javadoc has to stand on its own; this
directory holds what a *contributor* wants and a *user* of the toolkit does not.

## What goes where

| | Lives in | Why |
| --- | --- | --- |
| Contract: units, ranges, nullability, threading, what a method promises | Javadoc, on the member | It is what a caller needs, and it ships with the artifact |
| A trap that prevents a specific wrong edit | Javadoc or a one-line comment, **at the code it guards** | Whoever is about to make that edit is reading that line, not an index |
| Why a design is shaped this way; alternatives weighed; consequences accepted | An ADR under `docs/adr/` | Dated, decided once, read once |
| Subsystem background a contributor needs before changing it | Here | Too long for a member, too small for an ADR |
| What was tried before, when a value changed, how many call sites exist | Nowhere | It rots, and git already has it |

## The rot rule

Nothing in this directory or in a Javadoc may state a fact that the code can change
without anyone noticing: no call-site counts, no `File:123` citations, no "N sites
today". This repository has already proved the point twice in one file: `Strokes`
documented that its line citations had been dropped because 70 of 71 pointed at the
wrong construct, then acquired three more, two of them wrong; and every one of its five
usage counts was wrong when checked (17→23, 12→31, 6→7, 5→8, 2→15).

If a claim must stay true, assert it in a test. That is the difference between
documentation and a guarantee.

## Notes

| File | Covers |
| --- | --- |
| [background-work.md](background-work.md) | `Ui.post`, `Ui.async`, `Ui.work`: which to reach for, what is ordered and what is not, the discard trap, the rule a loader obeys |
| [size-axis.md](size-axis.md) | ControlSize, SizeTokens, Strokes: locked vs tabled, the density floor, the constructor trap |
| [text-shaping.md](text-shaping.md) | ShapedText, TextRuler and the HarfBuzz backend: the one text path and what holding a value still buys, the visual and logical axes, itemization, the two caches and what sizes them, what a missing native or a missing face costs |
| [text-and-input.md](text-and-input.md) | TextField, TextArea, PasswordField, Spinner: the held shaped line, the caret's side, visual versus logical movement, the display seam, the drawn password dot, insets, scrollbar geometry |
| [popups-and-dialogs.md](popups-and-dialogs.md) | Dialog, PopupMenu, ComboBox: window vs in-scene, stacking, the native refit |
| [theming.md](theming.md) | Theme and its builder, the token enum, the 8-bit grid, the audit's bars, ColorPickerButton and the editor module's traps |
| [video.md](video.md) | limn.video, VideoView, the decoders and the shim: the three lifetimes, the threads, what a seek touches, rotation, bit depth and colour, what is in the native |
| [charts.md](charts.md) | limn.components.chart: why geometry is resolved twice, what the animation interpolates, how hiding re-flows a stack, the donut's hole, why the palette order is load-bearing |
| [i18n.md](i18n.md) | limn.i18n: how a bundle family resolves and what stays resident, what the toolkit itself ships translated and why some of it deliberately is not, which scripts render today |
| [website.md](website.md) | `site/`: the generators and what feeds them, the invariants, the build gates and what each one caught, the traps in the capture harness and in the Javadoc theming |
