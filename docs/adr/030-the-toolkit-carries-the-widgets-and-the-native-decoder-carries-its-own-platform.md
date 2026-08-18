# ADR 030: The toolkit carries the widgets, and the native decoder carries one platform at a time

- **Status:** Accepted, 2026-08-18. Supersedes the module boundary half of
  [ADR 008](008-video-decoders-live-in-their-own-module.md); leaves the rest of it standing.
- **Date:** 2026-08-18
- **Scope:** which modules are published, what a developer types to get a window, and how the
  FFmpeg payload reaches the machine that runs it.
- **Audience:** whoever wonders why `limn-components` and `limn-video` are gone from a repository
  whose ADRs argue for them, and whoever adds the seventh native target.

---

## 1. Context

Two things were true at once, and only one of them was written down.

The written one is ADR 008: decoders live in their own module, because the decoder that was coming
had a native payload, a licence and a platform matrix, and a boundary drawn after something
depends across it is a boundary that never gets drawn. That reasoning was correct and it still is.

The unwritten one is what a developer met. `limn-toolkit` held `Widget`, the layout widgets and
the backend SPIs — and not one control. `Button`, `Label`, `Dialog` and sixty-one others lived in
`limn-components`, and `limn-video` held two pure-Java decoders behind an SPI that was already in
the toolkit. The name promised a toolkit and delivered an engine, and the install line was two
coordinates because of a split nobody consuming it had a use for.

Measured rather than assumed: four of the five satellite modules import nothing from
`limn.components` at all. `limn-icons-tabler` imports `limn.graphics`. `limn-video` imported
`limn.video`. The backend imports eight core packages and no control. So the boundary was real —
but it was load-bearing for people **extending** Limn, and free for people **using** it, and the
second group is much larger.

At the same time the FFmpeg module shipped all six desktop payloads in one jar. Every application
that wanted H.264 downloaded four megabytes to use one sixth of them.

## 2. Decision

1. **`limn-components` and `limn-video` move into `limn-toolkit`.** Seven published modules become
   five. The toolkit now holds the widget set, layout, the scene graph, the backend SPIs and the
   pure-Java decoders, and still depends on nothing.
2. **The install is one coordinate.** `limn-backend-lwjgl` already exported the toolkit through
   `api`, so depending on the backend is depending on the whole toolkit. It was true before the
   merge and useless before it, because the widget set was somewhere else.
3. **The boundaries that carry weight stay.** The backend, the FFmpeg decoder, the icon pack and
   the theme editor remain separate modules, for the reasons each of them already had.
4. **The FFmpeg libraries ship one classifier per target**, `natives-<os>-<arch>`, with the JNI
   shim staying in the main artifact and `natives-all` published beside the six for a bundle that
   cannot know its machine.

## 3. What of ADR 008 survives

Its section 2 asks why a module rather than a package in the toolkit, and answers: because the
decoders that are coming arrive with a native payload, a licence and a platform matrix, and the
boundary has to exist before they do.

That decoder arrived. It is `limn-video-ffmpeg`, and **it is still a module of its own.** The
sentence ADR 008 was protecting — an application that draws rectangles does not carry a codec —
is protected by the boundary around the native decoder, not by the one around the pure-Java ones.
What moved into the toolkit is 30K of Java beside a backend that is 24M, with no native, no
third-party dependency and no licence of its own.

So ADR 008 is superseded on where `limn-video` lives, and stands on everything else: the
application still installs decoders, a decoder still claims a path by its file name, and no media
file is committed.

## 4. Why the shim stays in the main jar

The split had to fall somewhere, and the natural line is ownership: the shim is this project's own
C, one file, tens of kilobytes; the FFmpeg libraries are two megabytes and somebody else's
copyright. Six shims in the main jar cost almost nothing and mean the Java and the thing it binds
to are never out of step.

It works because `FfmpegLibrary` resolves everything under `limn/video/ffmpeg/native/<platform>/`
as a **classpath resource**, and a classpath spans jars. Neither end can tell that the shim came
out of one archive and the libraries out of another.

`libraries.txt` goes with the libraries rather than with the shim, and that placement is the whole
diagnostic. It is the first file the loader looks for, so an application that forgot its classifier
gets "this build carries no FFmpeg native for `<platform>`" — a sentence already written for a
platform nobody built — instead of a link failure partway through extraction naming a file it has
never heard of.

## 5. Consequences

- A second backend (the ANGLE route of ADR 003) now compiles against a core that includes the
  widget set. That is a compile-time cost only: any application using that backend has the widgets
  anyway.
- `checkArchitecture` is untouched. It forbids AWT/Swing/SWT everywhere and LWJGL outside the
  backend, and the backend is still a module, so the one rule the build actually enforces is
  unaffected. The component/decoder boundary was never enforced by it — it was two sentences in
  `settings.gradle.kts`, which now name the boundary that replaced them.
- An application that plays no video carries the two pure-Java decoders. Thirty kilobytes.
- The sources jar stops carrying binaries, which the split made obvious: the payload reaches the
  main source set through `resources.srcDir`, so `-sources` had been shipping every native a
  release built.
- This is decided **before** the first publication. Nothing is on Maven Central yet, so no
  coordinate is being orphaned and no consumer is being broken. After a release the same change
  would mean artifacts that exist for ever and resolve to nothing.
