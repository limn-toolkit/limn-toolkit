# ADR 034. Order and case are facts about a language, and the language is the text's

**Status: accepted.** Closes the "locale-aware collation and case mapping" line that ADR 006 §4
opened, ADR 031 §7.1 carried, and ADR 032 §8 recorded as deliberately unscheduled.

---

## 0. What was measured before anything was decided

**Every `toUpperCase`/`toLowerCase` in main sources is a machine format, deliberately pinned.**
`SyntheticSpec` and `Y4mSource` parse file syntax, `ThemeStrings` slugs a theme name,
`ImageFormat` folds a media type, `Accelerator` sniffs `os.name` — all `Locale.ROOT`, all
correct, none changed. The `Character.toUpperCase` calls in mnemonic matching (`MenuInk`,
`MenuItem`, `MenuBar`, `Accelerator`) are keyboard facts, not text, and are also untouched.

**The toolkit sorts no user-visible strings.** `ListView`, `ComboBox`, `TokenBox` and the menus
show items in the order the application gave them: order is application content, so there was no
sort site to fix — there was a seam to offer. The one collation-adjacent structure in-repo is the
backend `FontStore`'s family `TreeSet` under `CASE_INSENSITIVE_ORDER` (ADR 006 §4 noticed it),
a registry order built once and exposed through `Fonts.families()`; a registry cannot re-sort on
a locale change, so it keeps its order and a picker that wants display order sorts at display time.

**The one user-visible case operation was `ComboBox` type-ahead.** `regionMatches(true, …)` is a
per-`char` fold: it can never match "stras" to "Straße", because one ß upper-cases to two letters
and a per-char comparison cannot, and it matched a Turkish dotted İ only by accident of the
accentless per-char tables.

## 1. Decision

**1. The text locale is process-wide, UI-locale-derived, with a process override** — ADR 033
Decision 1's shape, taken for its reasons. `I18n.textLocale()` answers `I18n.locale()` until
`I18n.setTextLocale(…)` declares the content is in another language; `null` returns to
following. The two locales are different facts — an English interface listing Swedish names must
still put ä after z — but almost every application never declares, and the default keeps order
and case in the language the user is reading. A declaration is a text change: the epoch bumps and
listeners run, because every order an application built through the collator is now stale and the
application's own change listener is where it re-sorts. Not a per-subtree axis, for the price
ADR 032 measured and the buyer this ADR does not have.

**2. Collation is `I18n.collator()`: one fresh platform `Collator` per sort.** `java.text`, no
new dependency, default strength. Fresh per call because a `Collator` carries mutable
per-comparison state and must not be shared across threads; the platform caches the tables per
locale and hands out clones, so freshness costs a clone, not a table build. The toolkit itself
still sorts nothing — the seam is for applications today and for the first widget that ever
sorts (a table column header) tomorrow, so that day already has its one answer.

**3. Case mapping is `I18n.toUpperCase`/`toLowerCase`, applied at the point of use.** Whole
strings in the text locale, never a rewrite of what an application authored — ADR 033 Decision
3's line, unmoved. Type-ahead routes through it: both sides upper-cased whole, with a
lower-cased retry for the scripts where only one direction folds, which is what makes "stras"
find "Straße" and makes Turkish i find İstanbul on purpose rather than by accident.

## 2. What this deliberately is not

- **Not ICU.** The platform's collation tables, which the tests pin for German, Swedish and
  Turkish; the day a language needs tailoring they do not carry, that is a dependency decision
  to take then, not now.
- **Not normalization, not locale-aware equality.** Keys, slugs and formats keep `equals` and
  `compareTo`; a theme key must not change identity with the UI language.
- **Not localized mnemonics, not the `FontStore` order, not any `ROOT` pin above** — each
  measured, each left, each for the reason given.
- **Not a per-subtree language.** ADR 006 §4's per-subtree locale stays open; the
  inheritance-chain precedent remains the recorded route if a real interface ever needs it.

## 3. Cost

Two methods that delegate to `String`, one that clones a cached table, and one changed behaviour:
type-ahead now folds whole strings, two small allocations per candidate per keystroke in an open
popup — a list the user is reading, not a data set. A text-locale declaration re-lays-out every
scene exactly as a language switch does, and is exactly as rare.
