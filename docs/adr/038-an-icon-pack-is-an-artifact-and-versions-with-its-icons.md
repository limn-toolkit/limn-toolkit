# ADR 038: An icon pack is an artifact, and it versions with its icons

- **Status:** Accepted, 2026-09-02. Implemented as the
  [limn-icons-tabler](https://github.com/limn-toolkit/limn-icons-tabler) repository and
  artifact (`3.46.0.0` first); the module, its generator and the backend's rasterize-everything
  test are gone from here.
- **Date:** 2026-09-02

## Context

The third and last of the payloads ADR 036 and ADR 037 moved out, and the smallest: four
megabytes of generated Java and a resource blob that change only when Tabler releases, re-published
under every toolkit version. The same diagnosis — a byte on upstream's schedule riding an artifact
on the toolkit's — and the same remedy were on the table, with one thing the fonts and the FFmpeg
payload did not have: the pack **depends on the toolkit**. `Tabler.icon(...)` returns
`limn.graphics.Icon` and calls `SvgIcon.of`; a pack repository would compile against a *published*
toolkit, while this repository's backend tests consumed the *published* pack. Two repositories,
two arrows, one cycle: the pack's POM would name a toolkit version that this tree had already
left behind, and a toolkit change to those two types could not be released with green tests
without a pack release that could not be built without the toolkit release.

## Decision

**The pack lives in its own repository, one artifact, versioned after Tabler** — `3.46.0.x` is
Tabler 3.46.0, the fourth component is the packaging's — released from `versions.properties`
through the same tag-releases / publish / consumer-rehearsal machinery as the other two.

**The toolkit is a `compileOnly` dependency of the pack.** Its POM names no toolkit version at
all: every application already has the toolkit through `limn-backend-lwjgl`, and a transitive
pin would only ever be stale. The pack's catalog line means "the oldest toolkit API this was
compiled against" (two types, one call), and moving it spends the fourth component.

**Nothing in this repository depends on the pack except `limn-demo`**, which is an application
and takes it as one would. The backend's test that rasterized all six thousand icons is
**removed, not moved**: it was a prototype-era proof that NanoSVG draws Tabler's SVG dialect, and
with the generation stabilised it had become a five-second tax on every backend run that proved
the same thing every time. What it also guarded — a future Tabler release using an SVG feature
NanoSVG cannot draw — is now guarded nowhere, and that is the trade, made knowingly. With that
arrow gone the cycle is gone in fact, not just in the POM.

**The enums are committed; the drawings are not.** The enums are the pack's public API — text,
reviewable, a Tabler bump is a diff of added and moved names — and `check` regenerates them from
the pinned tarball and refuses a difference. The blob, its index and Tabler's licence are four
megabytes of upstream's drawings reproducible byte for byte from a tarball pinned by version and
SHA-256, so the build generates them into `build/` (cached after the first download) exactly as
the FFmpeg payload is built rather than stored. `TablerPackTest` asserts the committed enums and
the generated blob describe the same set.

## Consequences

- With this, no artifact published from this repository re-ships anything that does not change
  with the toolkit: fonts, natives and icons each version with their upstream, and a toolkit
  release is a Java build.
- The pack disappears from the site's aggregate `/api/`; its enums are generated and its one
  hand-written class is documented in its own artifact's javadoc.
- An application upgrading changes the pack's coordinate from the toolkit's version to Tabler's
  (`limn-icons-tabler:3.46.0.0`). Its API is unchanged.
- A Tabler release that NanoSVG cannot draw would reach an application as a blank button rather
  than a failed build. The pack's release notes ask for a visual check of the demo after a bump.
- The documentation's version check (`scripts/check-versions.sh`) learned a third family of
  pinned coordinates, required to agree with the catalog.

## Alternatives considered

**Moving the rasterize-everything test to the pack repository** (against the published
backend, under xvfb). Rejected by the owner: a test worth running once, not on every release of
either side.

**Keeping the test in the backend, reading only the pack's data** (`testRuntimeOnly` on the
pack, slicing the blob by its index and drawing through `SvgIcon.of`). It would have removed
the compile-time arrow and kept the guard; rejected together with the test itself, for the same
reason, and recorded here because it is the shape to reach for if the guard is ever wanted back.

**A pack with no toolkit dependency at all** (data plus a pack-local interface, the adapter in
the application or the toolkit). Rejected: it breaks `Tabler.icon(...)` for everyone on 0.5.0
to remove an arrow that `compileOnly` already removes from every consumer's POM.
