# ADR 035. The locale is a property of the subtree, and the pass carries it

**Status: accepted.** Closes ADR 006 §4's "per-subtree locale", re-recorded open by ADR 032 §8.
The escape hatch ADR 006 named and ADR 032 shaped — the `ControlSize` inheritance chain — is
delivered for language: a widget subtree can hold a locale different from `I18n`'s process-wide
one, and its string lookups, its `MessageFormat` arguments, its digits (ADR 033) and its line
breaking all resolve through it. The recorded motivation, verbatim from the direction axis: a
Hebrew UI holding an LTR code pane, where reading everything off the process locale is the
shortcut that breaks it, discovered late.

---

## 0. What was measured before anything was decided

**The axis itself is the settled part.** `ControlSize` and `LayoutDirection` already resolve
declared → parent → scene default → host link → process default, memoized per widget against a
global epoch, keyed into `measure`'s cache, forbidden in constructors. Direction's own doc
(ADR 032 §5) reserved the move for a third axis wanting into the measure key: one resolved-axes
value, not a fourth field. Nothing here needed inventing; it needed copying faithfully.

**The delivery question was the real one: who consults the axis?** The sweep found roughly forty
`I18nString.get()` call sites across the components, plus the readers that are not on a widget at
all: `ChartFormats` reads `I18n.locale()` inside `DoubleFunction<String>` lambdas the application
may also write; `Label.wrapText` is static and hands `BreakIterator.getLineInstance(I18n.locale())`
its language; `I18nString.format` runs `MessageFormat` under `I18n.locale()`;
`I18n.numberingSystem()` derives digits from it. A widget-level seam (`resolveText(I18nString)`)
could reach the forty call sites by editing all forty, and could not reach the statics at all
without changing the chart-format contract that "a plain `DoubleFunction<String>` is equally
acceptable".

**ADR 006 already ruled on this shape of choice.** Its finding 5 replaced revision 1's `tr()`
convention ("never store the result") with a type, because a rule that every component must
remember, that fails silently, and that `checkArchitecture` cannot see, is not a rule. A
`resolveText` seam would have re-created exactly that: `text.get()` — the default spelling, in
every existing component, every future component, and every application widget — would have been
the quiet wrong one inside a locale-declaring subtree.

**Two memos cache resolved text against the i18n epoch alone.** `Spinner`'s rendered value and
`CartesianChart`'s two label scans (both from ADR 033) re-resolve when the epoch moves. A
*declared* subtree locale moves resolution without moving the epoch: both needed the effective
locale in their keys, and they are the complete list — every other widget re-reads its
`I18nString` per pass and re-shapes when the string changes.

**`PropertyBundle` held exactly one table, and per-subtree lookups would have thrashed it.** Its
`lookup` for an unprepared locale called `prepare`, which *replaced* the resident table. Two
languages alternating in one frame — precisely what a Hebrew UI with an English pane does — would
have re-read both files per frame, forever, silently.

## 1. Decision

**1. A locale axis on `Widget`, the third of its kind.** `setLocale(Locale)` /
`declaredLocale()` / `locale()`, `Scene.setLocale` beside `Scene.setLayoutDirection`, resolved
through the identical chain with the identical constructor prohibition, bottoming out in
`I18n.processLocale()`. Its epoch is its own, with one honest asymmetry: the chain's bottom lives
in `limn.i18n`, which cannot bump a package-private counter in `limn.scene`, so the memo is
validated against two stamps — the locale epoch and `I18n.epoch()`, which every process-locale
switch already moves. A bundle registration therefore re-resolves every locale memo spuriously
(one link per widget, per rare event): the same cost ADR 032 priced when it argued for separate
counters, paid in the other direction to avoid a cross-package hook. The measure key takes the
axis as the reserved move: `measuredControlSize` and `measuredLayoutDirection` collapsed into one
`MeasuredAxes` record, allocated only when a measure actually runs.

**2. The pass carries the locale; nothing else changes spelling.** `Widget.measure`, `layoutBox`,
`paintWidget`, the six event-dispatch bridges and `tooltip()` hold the widget's effective locale
in a thread-local **scope** (`I18n.pushScope`/`popScope`, nesting, always in `finally`), and
`I18n.locale()` answers the innermost scope before the process locale. That single redefinition
is the delivery: `I18nString.get()` and `format()`, `I18n.localizeDigits`, `ChartFormats`' and an
application's own format lambdas, and `Label`'s `BreakIterator` all read the language at the
moment they resolve, so they follow the subtree they are working inside without knowing subtrees
exist. The default spelling is the correct one — ADR 006 finding 5's argument, applied to the
axis it predicted. Off the UI thread and outside any pass, `I18n.locale()` is the process locale,
exactly as before; the scope is resolution context, never state.

**3. Digits ride the effective locale; there is still no numbering-system axis.** ADR 033's
Decision 1 is amended in one word and otherwise stands: `I18n.numberingSystem()` derives from the
*effective* locale, so an Arabic-locale spinner inside a Latin interface writes `٤٢` through the
same format-time seam, with no second mechanism. The declared override remains process-wide and
wins inside every scope, because it is a statement about the deployment, not about a subtree. ADR 034's text locale —
the same-day sibling — rides the same way: `textLocale()` derives from the effective locale, so
a type-ahead fold or an in-pass sort answers for its subtree, and its process-wide override
keeps winning too.
`I18nString`'s memo takes the locale as a second key in its one slot — a static string genuinely
read in two languages every frame re-resolves per alternation, a bundle walk of hash gets, paid
only where two languages share a screen. The two epoch-keyed widget memos (§0) take it too.

**4. Residency is a ledger, not a guess.** `I18n.retainLocale`/`releaseLocale` count who declares
a language; `Widget.setLocale` and `Scene.setLocale` retain the new and release the old.
`StringBundle` gains a default-no-op `release(Locale)`; `PropertyBundle` holds one table per
prepared locale and drops on release; `I18n.setLocale` releases the outgoing process locale
unless retained, and `addBundle` prepares a late bundle for every retained locale. The old
promise — a process visiting ten languages holds one table — becomes: it holds the tables of the
languages *on screen*, which is what the promise always meant. Retaining prepares immediately,
so a subtree's first frame reads no file; `lookup` for an unprepared locale keeps its lazy load
instead of swapping the resident table, which is what retires the §0 thrash.

## 2. What this deliberately is not

- **Not a change to what an accessor answers outside a pass.** `label.text()` called from
  application code resolves under the locale in effect *there* — the process one, unless the
  caller opened a scope. The painted text and an off-pass read of a locale-declaring subtree can
  therefore differ; the widget knows its answer (`widget.locale()`), and `pushScope` is public
  precisely so a caller who cares can say so. Named here so it reads as a decision.
- **Not a direction derived from the locale.** ADR 032 §1.2 is untouched: a Hebrew-locale
  subtree still lays out by its declared `LayoutDirection`, and the two axes are declared
  separately because a Hebrew pane of LTR code is one axis of each.
- **Not per-subtree bundles.** Bundles stay one process-wide registry; a subtree changes which
  *language* answers, not which tables exist. Newest-wins already gives an application override
  scoped by key.
- **Not Spinner's separators, not plural rules, not collation.** ADR 006 §2.4 and §4 stand where
  they stood.
- **Not automatic release on GC.** A widget discarded while still declaring a locale keeps its
  retain — widgets have no disposal hook, and inventing one for a string table would be the tail
  wagging the dog. Bounded by the languages ever declared (usually one), and
  `setLocale(null)` before dropping a subtree returns the memory. Recorded in the setter's doc.

## 3. Cost

Steady state: one memoized `Locale` read and two thread-local writes per widget per pass and per
dispatched event; two `long` compares (plus a reference-equality `Locale.equals`) per memo read.
A declared-locale change costs one relayout of the subtrees whose resolved locale actually moved,
which is the axis working as the other two do. With nothing declared anywhere, every scope pushes
the process locale, every memo compares equal, and behaviour is byte-for-byte what it was — the
suite that predates this ADR passes untouched, which is the claim's proof.
