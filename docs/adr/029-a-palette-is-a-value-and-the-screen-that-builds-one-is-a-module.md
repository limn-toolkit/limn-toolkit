# ADR 029: A palette is a value, and the screen that builds one is a module

- **Status:** Accepted, 2026-08-10. Implemented as `Theme.Builder`, `Theme.Token` and the
  `limn-theme-editor` module.
- **Date:** 2026-08-10

## Context

`Theme` shipped fifteen palettes and no way to make a sixteenth. Its constructor and its
`make` helper were both private, so an application whose brand was not one of the fifteen
had exactly two options: fork the file, or accept somebody else's blue. That is the wrong
answer for a toolkit: a palette is the most application-specific thing in a design
system, and it was the one part a user could not supply.

Opening it up raises three questions that have to be answered together, because answering
any one of them alone produces something that does not work:

1. **What is a palette allowed to carry?** If it can carry metrics, a theme switch has to
   re-measure every window, and the six spacing and radius fields that are inlined into
   every consumer at compile time (JLS 13.1; see the note in `Theme`) become unreachable
   through an override. If it carries only colour, a switch is a repaint.
2. **How precise is a tone?** A palette that can hold a colour no hex value can express is
   a palette that comes back different from the file it was saved to, and two palettes that
   render identically compare unequal.
3. **Where does the screen that edits one live?** The editing UI is large, it depends on
   the whole widget set, and it is of no use whatsoever to an application that merely wears
   a theme.

## Decision

**A palette is a value carrying colours, a name, a mode and one metric.**
`Theme.builder(name, dark)` seeds from the built-in light or dark palette so that every
tone is already a working one; `theme.toBuilder()` starts from an existing palette. The
constructor stays private and `make` is now written in terms of the builder, so the
derivations an application reaches for (`deriveAccentStates`, `deriveDisabled`,
`deriveSemanticStates`) are the same expressions the eleven derived built-ins were built
from (asserted, palette by palette, in `ThemeBuilderTest`).

**One metric is allowed in, and the rule that lets it in is the interesting part.**
`Theme.setCurrent` assigns a volatile field and notifies nobody, so a palette may only carry
a metric that *nothing measures from*; otherwise a theme switch would change every
measurement in every window with no relayout. Exactly one metric qualifies: a corner radius.
No `onMeasure`, `onLayout`, `baselineOffset` or `paintOutset` in this toolkit reads one, so
shape is a repaint exactly like colour. `Theme.cornerScale` is therefore a multiplier over
every radius in the size table, and `ThemeShapeTest` lays a sample tree out at scale 0 and
scale 3 at all five size steps and demands identical geometry: the premise, asserted, not a
regression test.

Spacing and typography stay out, and the prerequisites for ever letting them in are written
down on `Theme.tokens`: route `setCurrent` through the same weak per-scene registry as
`Fonts`/`ControlSize`, and initialize the nine token-backed fields from
`this.tokens(MEDIUM)`. Until both exist, they cannot follow a palette.

A palette with the default shape returns the **process-wide table itself**, so identity
(and the backend's font memo) are what they were before shape existed. Only a palette that
asks for a shape builds five rows, once, for itself.

**`Theme.Token` enumerates the tones**, with a stable `key()` and read/write
access to a theme and a builder. Three consumers iterate it instead of naming the tones
(the serializer, the editor and the audit), and a reflection test asserts the enum names
every public `Color` field on `Theme` and only those. Without it, a tone added later would
silently be missing from three places, none of which would fail to compile.

**Every tone is snapped to eight bits per channel** on its way into the builder. That is
the precision a hex value, a colour field and a monitor share. It moves the eleven derived
built-ins by at most 1/255 (invisible), and in exchange `ThemeFormat` round-trips exactly,
`Theme.equals` survives a save and a reload, and an editor can ask "has this been changed"
and get an answer that means something.

**`ThemeFormat` owns the whole round trip, and it lives in `limn-components`** (beside
`Theme`, not in the editor), so an application can load a palette its designer saved
**without** the editor anywhere in its build. That is the point of having a format at all:
what crosses between authoring a theme and wearing one is a value.

`parse`/`write` are the pure pair; `load(Path)`/`load(InputStream)` are the two lines above
them that an application actually calls. The loaders are worth their place because the
resource case (a theme shipped inside the jar, which is the distribution story the README
tells) is otherwise a try-with-resources, a null check, `readAllBytes` and a charset in
every application. There is no `save` counterpart: writing is one obvious line, and nobody
writes back into a jar. No asynchronous form is owed and the Javadoc says why: a kilobyte
of configuration, read once, normally before there is a window; an application loading one
with a window up still goes through `Ui.work`, which is what `ThemeEditorFiles` does.

**The editor is its own module, `limn-theme-editor`, and nothing depends on it.** It sits
exactly where `limn-icons-tabler` sits. Authoring a palette and wearing one are different
jobs; the screen that builds a `Theme` has no business on the classpath of every
application that draws a button. What crosses the boundary is a value.

**The editor does not decide where a palette lives.** Copy and Paste move one through the
clipboard and need nothing from the platform. `ThemeEditorFiles` is a separate, optional
class that opens the platform chooser and reads or writes through `Ui.work`; an
application that keeps its themes in a preferences store, a document bundle or on a server
simply does not call it.

## Consequences

- An application can ship its own palette, and a designer can hand one over as a text file.
- `Theme` gained `equals`/`hashCode`/`toString`. Existing identity comparisons
  (`Theme.current() == Theme.dark()`) are unaffected.
- The eleven derived built-in palettes moved by at most one 8-bit step. Nothing asserts
  their exact hex values, and the two palettes that *are* solved for exact contrast targets
  were already on the grid.
- `Theme.radiusSmall/Medium/Large` are **gone**. They were JLS 4.12.4 constants inlined at
  every call site, so they could not follow `cornerScale` and were silently the wrong number on
  any palette that has a shape; `tokens(step).radiusMedium()` is the palette-aware read, and now
  the only one. All 28 reads in this repository were migrated first, and the fields were removed
  rather than left deprecated because nothing has been released against them. The spacing three
  keep their meaning and stay: spacing is not scaled by anything, so a constant is still correct.
- `Color` gained `relativeLuminance()`, `contrastRatio(a, b)` and `lightness()`. The audit
  needs them, `ThemeContrastTest` had carried private copies, and they are ordinary colour
  maths that belongs on the colour.
- A user can now build an illegible palette. `ThemeAudit` is the answer: it measures every
  ink against every surface it can land on, both elevation steps, and the two distinctness
  rules a contrast ratio cannot see, including the focus ring that matches the accent,
  which eight of the shipped palettes get wrong.
- `TokenBox` was promoted out of `ColorPicker` into the public `Token*` family, because two
  widgets and the editor now need an extent that follows the size step.

## Alternatives considered

**Subclassing `Theme`.** Rejected outright: the six spacing and radius fields are constant
variables inlined at every call site in this repository and in every application compiled
against it, so an override could not reach a field read at all. A subclass would appear to
work and change nothing.

**A palette that carries metrics too.** Rejected for this change, and the prerequisites are
written down on `Theme.tokens`: `setCurrent` would have to route through the same weak
per-scene registry as `Fonts` and `ControlSize`, and the nine token-backed fields would
have to be initialized from `this.tokens(MEDIUM)`. Until both exist, a palette that could
change metrics would change every measurement in every window with no relayout at all.

**Floating-point tones.** Rejected: nothing can display, type or save the extra precision,
and keeping it costs an exact round trip and a meaningful `equals`.

**The editor inside `limn-components`.** Rejected on the same grounds as the icon pack. It
would put a settings screen (with file dialogs, an audit and a preview) on the classpath
of every application that draws a button.
