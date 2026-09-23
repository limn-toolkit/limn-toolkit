# Contributing

Thank you for looking. This file says how the repository is organized, what a change is expected
to carry, and how to check it before you open a pull request. Contributions are accepted under
the [Apache-2.0](LICENSE) licence, as the rest of the code is.

## Before you start

Open an issue first for anything that changes an API, adds a component, or changes what a widget
publishes to a screen reader. Those are decisions, and a decision is written down before it is
built: in an architecture decision record under [`docs/adr/`](docs/adr/README.md), numbered in
the order decisions are taken and never renumbered. A bug fix or a documentation correction
needs no issue.

Until 1.0 the API may still break. A breaking change is welcome when it makes the API right, and
it pays its own migration: every call site in the repository, and a row in the changelog saying
what to write instead.

## Building and testing

[`README.md`](README.md#building-from-source) has the build: a JDK 21 runs it, the artifacts
target 17, and `./gradlew check` compiles, tests and builds the Javadoc of every module. On macOS
the demo needs `-XstartOnFirstThread`, which `./gradlew :limn-demo:run` already passes.

A pull request passes `./gradlew check`. CI runs it on Linux under `xvfb`, and then builds the
website from the same commit, so run `scripts/check-versions.sh` and the site build too if you
touched documentation.

What a change carries in tests:

- **A behaviour change comes with a test that fails without it.** Tests run headless, on the
  test's own thread as the UI thread; `limn-test`'s `SceneDriver` clicks, types and presses keys
  the way a window would.
- **A component is held to its shape's contract.** `limn-test` has one per accessibility shape
  (`ToggleContract`, `ValueContract`, `TextContract`, `RowsContract`, `GridContract` and the
  rest); a new component of a known shape runs its contract, and is added to
  `DamageContractTest` and `NotificationContractTest`, which hold every component to what its
  gestures repaint and announce.
- **Some rules are enforced by a test that reads the sources**: every public mutator of a widget
  checks the UI thread, and every setter of a self-typed base class returns the widget's own
  type. If one of those fails, the rule is the thing to follow, not the test to change.

## Where an explanation goes

[`docs/design/README.md`](docs/design/README.md) has the table: a contract in the Javadoc, a trap
at the line it guards, a decision in an ADR, the background of a subsystem in a design note. Its
rot rule applies everywhere: no fact the code can change without anyone noticing, such as a call
count or a `File:123` citation. A claim that must stay true is asserted in a test.

## The website and the guides

The site lives in [`site/`](site/) and needs Node 22 or later and pnpm;
[`docs/design/website.md`](docs/design/website.md) describes its build. From `site/`:

```bash
pnpm install
pnpm build         # needs ./gradlew aggregateJavadoc :limn-demo:exportThemeTokens first
pnpm check:links
```

A guide's code sample is compiled wherever it can be. Put it in
`limn-demo/src/main/java/limn/demo/site/` between `// #region guide:<name>` and
`// #endregion`, give it a test beside it, and write `{% snippet guide:<name> %}` in the page.
A hand-typed block is read against the source by nobody but you.

`site/site.config.json` records the commit the pages were last read against. The build lists every
toolkit commit since then; when a change alters what a guide says, update the guide in the same
pull request.

## Versions and releases

The documentation never names the toolkit's version. A README writes `x.y.z` and a guide writes
`{{version}}`, and `scripts/check-versions.sh` refuses a literal. A release is a bump of
`versions.properties` and nothing else: no commit is made by the release, and none is needed.
[`RELEASING.md`](RELEASING.md) has the steps.

A change a user would notice gets a line in the top section of [`CHANGELOG.md`](CHANGELOG.md),
and a change a user's code must follow gets a row in its migration table.

## Commit messages

One sentence that says what changed and why, with the measurement when there is one:

> Land a far scrollBy by estimate in Table and ListView, because moving the anchor that far left
> the next layout to walk and measure every row on the way, 1.35–1.95 s for 100,000 rows

A body is for what the sentence cannot hold: the approach, what was tried and rejected, what a
reviewer should look at first.

## Accessibility

What a widget publishes is checked in three places: the per-shape contracts in `limn-test`, the
accessibility gallery's golden transcripts in `limn-demo`, and live runs with NVDA, VoiceOver and
Orca. Green tests prove the model, not that a reader speaks, so a change to a bridge or to what a
component publishes is also heard under a screen reader before it is called done;
[`docs/design/accessibility.md`](docs/design/accessibility.md) says how the gallery drives one,
and the component's ADR records what was heard.
