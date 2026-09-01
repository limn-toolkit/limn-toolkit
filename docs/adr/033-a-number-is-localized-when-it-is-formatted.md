# ADR 033. A number is localized when it is formatted, and nowhere else

**Status: accepted.** Closes ADR 006 §4's "non-ASCII digits", re-recorded by ADR 032 §8: an
Arabic interface showed `42` in Latin digits in its spinners and clocks, and Devanagari digits
were never producible at all. (The charts, it turned out, were localizing already — see below —
which is half the reason this needed measuring before deciding.)

---

## 0. What was measured before anything was decided

**The toolkit renders numbers it owns in exactly three places.** `Spinner` formats its value
with `String.format(Locale.US, …)`, memoized on the value alone; `ChartFormats` builds axis and
tooltip formats that read `I18n.locale()` at the moment they format; `MediaControls` writes an
`m:ss` clock by hand. Every other digit the toolkit prints is a machine format — a hex color, a
Y4M header, theme serialization — or an application's own string, which the toolkit must never
rewrite.

**One of the three already localized, by accident of the platform.** Java's own locale data
writes Arabic-Indic digits: `String.format` under `ar` renders `1234` as `١٬٢٣٤`, separators
included, so charts under `ar` already showed Arabic digits while the spinner beside them —
pinned to `Locale.US` for its input story — showed `42`. Two consequences shape Decision 3. A
format that rides the platform's locale data must **fold to ASCII before localizing**, or a
declared override could never win over what the platform already wrote. And the measurement
found a defect riding along: `trimZeros` compared code points against ASCII `'0'`, so a chart
under `ar` never trimmed `٣٫٥٠`; the trim now asks the digit's *value*, which is the same fix.

**One memo is already right and one is already wrong.** `CartesianChart` keys its label memos on
`I18n.epoch()`, so a locale change reaches charts on screen. `Spinner`'s rendered-value memo does
not: a locale change today re-lays the widget out and repaints the memoized string. Latent while
every locale formats the same ASCII digits; a defect the moment they do not. ADR 006 §5 predicted
exactly this shape ("a component that resolves into a private cache; the epoch is the tool").

**The editor is seeded from the display string, and the display string decides the input story.**
`Spinner`'s inline editor begins as `edit.setText(text())`, so whatever the display shows must
parse back. And `acceptsChar` admits `'0'`–`'9'` only: an Arabic keyboard, which types U+0660–0669,
cannot type a digit into a `Spinner` at all today. Localizing the display without touching input
would break commit; touching input was owed anyway.

**A numbering system is a fact about the locale, with regional exceptions.** CLDR: `ar` defaults
to `arab`, the Maghreb (`ar-MA`, `ar-DZ`, `ar-TN`, `ar-LY`, `ar-EH`) to `latn`; `fa` and `ps` to
`arabext`; `he` to `latn`; `hi` to `latn` with `deva` available. It is not a fact about the
script's direction — Hebrew is right-to-left and uses Latin digits — which is why the direction
axis is the wrong vehicle for it.

**The vendored faces cover what is promised.** `DigitCoverageTest`, beside the other real-face
tests, asserts every digit of U+0660–0669, U+06F0–06F9 and U+0966–096F resolves to a real glyph
in the vendored script faces, and that a localized clock face keeps its left-to-right order under
a right-to-left base: an Arabic-Indic digit is bidi class AN and resolves to an even level under
either paragraph, so no new ordering mechanism is needed anywhere.

## 1. Decision

**1. The numbering system is process-wide, locale-derived, with a process override.**
`I18n.numberingSystem()` answers from a small CLDR table over `I18n.locale()`;
`I18n.setNumberingSystem(system)` overrides it and `null` returns to following the locale; both
routes bump the i18n epoch that formatting memos already key on. It is deliberately **not** a
per-subtree axis: an axis costs an epoch, a memo and a measure-key entry on every widget in the
process (ADR 032 priced it), and buys nothing here, because substitution happens at format time
inside the three widgets that render numbers they own — an application's strings are never
rewritten, so the failure that forced direction off the locale (a Hebrew UI holding a
left-to-right code pane) cannot recur for digits. If a real interface ever needs two numbering
systems at once, the inheritance-chain precedent is the recorded route; it is not taken on
speculation.

**2. Display is localized, input is tolerant, and input is not otherwise localized.** The
display string carries the locale's digits. Because the editor is seeded from that string, parse
first folds every known digit set back to ASCII and then runs the path that already exists, and
`acceptsChar` admits every known set's digits — which also ends the Arabic-keyboard lockout. The
Arabic decimal separator (U+066B), grouping beyond ADR 006, and every other input-format
question stay out of scope, recorded below.

**3. Substitution happens at format time, through one helper pair.**
`I18n.localizeDigits(String)` on the way out, `I18n.toAsciiDigits(String)` on the way in. Not at
shaping time: ADR 032 §3 pins `TextRuler`'s and `Canvas`'s signatures, a shaping-time rewrite
would touch application content, and the string a widget holds must remain the string
`ShapedText.matches` keys on. Once the string itself carries the digits, shaping, bidi, caret
geometry and width all follow with zero new mechanism. A format that rides the platform's
locale data (the chart formats) folds to ASCII first and then localizes, so the declared system
is authoritative over the platform's own substitution and not only over ASCII.

**4. Four systems, one character each of state.** `latn`, `arab`, `arabext`, `deva` — each is a
zero code point and nine successors. `deva` is never chosen automatically (CLDR defaults `hi` to
`latn`) and is reachable through the override, which is why it exists in the enum and in the
coverage test rather than in the locale table. Thai or Bengali digits are a constant and a table
row away the day a locale asks, behind the same coverage test.

## 2. What this deliberately is not

- **Not localized separators or grouping.** ADR 006's decimal-separator story is unchanged, and
  one divergence is accepted and named rather than papered over: under `ar` the charts write the
  platform's separators (`٫`, `٬`), because Java does, while the spinner's editable value keeps
  the ASCII separators its input path is built on. Parsing U+066B stays open.
- **Not plural rules, not 12-hour time, not a per-subtree numbering system** — each still out,
  for ADR 006 §4's reasons and Decision 1's.
- **Not a rewrite of application text.** `Label`, `TextArea`, chart series names and everything
  else an application authors keep their characters exactly.

## 3. Cost

One enum, two linear string scans that return their argument untouched under `latn`, and the
epoch key `Spinner`'s memo already owed ADR 006. The width story is unchanged: a spinner's width
bound is measured from its extremes through the same formatter that paints, so a wider digit set
widens both together.
