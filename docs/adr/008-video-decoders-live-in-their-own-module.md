# ADR 008: Decoders live in their own module, the application installs them, and a stream may be described rather than located

- **Status:** Accepted, 2026-08-03. Implemented as phase 3 of the video player: the `limn-video`
  module, its `limn.video.decode` package, and the demo's Video tab.
- **Date:** 2026-08-03
- **Scope:** where a decoder lives, who decides which ones exist, and how a stream that has no file
  behind it is opened through a facade whose entry point takes a path.
- **Audience:** whoever adds a decoder, including the one with natives, which is what this ordering
  exists to keep out of the base modules until the seam is proven. Extends ADR 007, which decided
  what a picture looks like when it crosses the SPI; this decides where the thing producing it sits.

---

## 1. Decision

1. **A new module, `limn-video`, depending on `limn-toolkit` and nothing else.** Every decoder that
   is not the backend's lives there. `limn-backend-lwjgl` does not depend on it, and neither does
   `limn-components`.
2. **The application installs decoders.** `limn-demo` calls `Videos.installDecoder` at startup. No
   module installs one on anybody's behalf.
3. **A decoder claims a path by its file name, and a path need not name a file.** The synthetic
   generator claims `*.synth` and reads the rest of the name as its whole configuration.
4. **A Y4M stream reports `VideoColor.unspecified()`**, with FFmpeg's `XCOLORRANGE` honoured where a
   writer emitted it, and an override available on the decoder for a caller that knows better.
5. **No media file is committed.** A test that needs one writes it first, which is why the reader
   ships with a writer.

---

## 2. Why a module rather than a package in the toolkit

The toolkit depends on nothing, and that is load-bearing rather than tidy: it is what lets the
architecture check be a grep for imports, and what keeps an application that draws rectangles from
carrying a codec. Every decoder that is coming (a trimmed FFmpeg with a hand-written JNI shim, the
operating systems' own) arrives with a native payload, a licence and a platform matrix. Put the
first two decoders in the toolkit because they happen to be pure Java, and the module boundary that
should have stopped the third one from following them does not exist by the time it matters.

The cost is a module for about a thousand lines. That is the correct price: the boundary is worth
more than the file count, and it is far cheaper to draw now than after something depends across it.

## 3. Why the application installs decoders, and not the backend

`Videos` is an ordered list and the order is the probe order: the first installed is asked first.
A backend that installed its own would therefore be ahead of everything the application chose,
silently, and the application's only way to take priority back would be to uninstall something it
did not install.

It is also the wrong question to ask a backend. Which decoders exist is a function of what an
application ships and what it is licensed to ship, and those are the same answer for every window
system it runs on. `VideoSurfaces` goes the other way for the opposite reason: a surface belongs to
the GL context of the window rendering it, so only the backend can supply one, and there is nothing
for a second provider to claim.

## 4. Why a path may be a description

The generator has no file. It could have been reached through a factory of its own (and it is, for
the caller that already holds a spec), but if that were the *only* way in, then the demo, the tests
and any future harness would exercise a code path that no real decoder uses: not the facade, not the
install order, not `canOpen`, not the failure a caller sees when nothing claims the input. The
thing that most wants proving would be the thing least covered.

So the spec has a text form and the text form is a file name:

```
pattern=counter,size=640x360,format=I420,color=BT709_LIMITED,rate=30-1,frames=0,slots=3.synth
```

Every field appears, so it round-trips exactly and two specs that print the same are the same. The
decoder never opens, reads or creates anything; it claims the extension and parses the name. A
misspelled key is a failure rather than something skipped, because a key silently ignored leaves a
stream running at a size nobody asked for.

**The rate is separated by a dash and not a slash.** A slash is a directory separator, and a spec
carrying one still produces a path that resolves perfectly well and names somewhere that is not
there, a bug that is found by a user rather than by a build. That the whole spec is one path
element is asserted rather than remembered.

## 5. Why an unsignalled colour, and an override

YUV4MPEG2 carries width, height, frame rate, interlacing, pixel aspect ratio and a chroma tag. It
does not carry the luma/chroma matrix, and it does not carry the code range except through an
extension FFmpeg invented. Three answers were available:

- **Guess BT.709 limited and report it as signalled.** Rejected: it is right most of the time and
  unfalsifiable the rest, and a caller that could do better is told nothing is missing.
- **Refuse to open the stream.** Rejected: the container is readable and the picture is correct;
  only its interpretation is unstated, and every tool that writes these files leaves it unstated.
- **Report `unspecified()`.** Taken. It decodes as BT.709 limited (so a caller that ignores the
  distinction gets the common case) and answers `false` to `isSpecified()`, so a caller that can
  ask the user, read a sidecar or know from context may.

The override exists because the common failure is not ambiguity, it is standard-definition content:
a Y4M of BT.601 material says nothing, and decoding it as BT.709 shifts every colour that is not
grey. That is not a preference the decoder can infer, so it is a constructor argument, and it wins
over anything in the header.

## 6. What no media in the repository costs, and what it buys

It costs a writer. A reader with no file to read is unverifiable, and the honest way to get a file
without committing one is to produce it: a source whose every sample is arithmetic is written to a
temporary YUV4MPEG2 file and read back, and the assertion is that every sample survived.

That round trip also pins two limits of the container as facts rather than as comments: the range
survives through `XCOLORRANGE` and **the matrix does not**, and a two-plane layout has no tag at all
and is refused rather than de-interleaved into samples that are no longer the source's.

What it buys is a repository whose size does not grow with its test coverage, a test suite with no
binary fixture anybody has to trust, and (the reason it is a rule rather than a habit) no path by
which a licensed clip ends up in an open-core project's history, where deleting it later does not
remove it.

## 7. What this deliberately is not

- **Not a player.** No clock, no thread, no audio. A source is pulled by whoever wants a picture,
  and the demo pulls one per painted frame.
- **Not a widget.** The demo draws its pictures with a demo-local pane, the way the cube gadget is
  demo-local. Measuring, letterboxing, clipping, damage and disposal belong to the widget phase.
- **Not decode off the UI thread.** The contract is written (every metadata accessor is final state
  readable from any thread, the recycler is lock-free and never waits, and a picture may be released
  on a thread other than the one it was delivered to), and a producer-consumer stress exercises it.
  Moving decoding off the paint path is a later phase's, and it changes no signature here.
