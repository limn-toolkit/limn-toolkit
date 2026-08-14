# ADR 015: Which codecs are worth their megabyte, and what evidence there is for one this project cannot encode

- **Status:** Accepted, 2026-08-04. Implemented as phase 8a of the video player: the configure line
  in `scripts/build-ffmpeg.sh`, `FfmpegMedia.components()`, `FfmpegVideoDecoder.supports` and
  `CodecBreadthTest`.
- **Date:** 2026-08-04
- **Scope:** which decoders and demuxers the shipped library holds, what each one costs, and what a
  test can honestly say about a codec that has no encoder here.
- **Audience:** whoever adds or removes a codec, or is asked why AV1 is not in the list. Extends
  ADR 011, which decided what may be inside this module and left the codec set to this phase.

---

## 0. The two findings that decide the shape of this work

**Finding 1: of the codecs anyone wants, this project can encode none of them.** FFmpeg's H.264
encoder is x264, which is GPL and therefore refused by ADR 011. FFmpeg has **no native encoder at
all** for HEVC, VP9 or AV1: the encoders for those are x265 (GPL), libvpx and libaom/SVT-AV1/rav1e
(external libraries). So the round trip that proves the MPEG-4 path (write a clip, read it back)
cannot be written for a single codec this phase adds. What a test can say about them is therefore a
real design question and §3 is the answer.

**Finding 2: FFmpeg's AV1 decoder does not decode.** `libavcodec/av1dec.c` in ffmpeg-7.1.5 refuses
outright when no hardware accelerator is attached:

```c
if (!avctx->hwaccel) {
    av_log(avctx, AV_LOG_ERROR, "Your platform doesn't support"
           " hardware accelerated AV1 decoding.\n");
```

and `--disable-everything` switches every hardware accelerator off. So `--enable-decoder=av1` builds
cleanly, links, reports itself present, and fails on the first frame of the first file. This was
checked in the source tree the build script pins, not recalled.

---

## 1. Decision

1. **Video: h264, hevc, vp9, vp8. Audio: aac, opus, vorbis. Containers: mov and matroska.** Each
   measured on its own before it was added (§2).
2. **AV1 is out**, and the reason is finding 2 rather than licence or size. Software AV1 means
   libdav1d, which is a second external native dependency with its own build system: a phase, not a
   flag (§4).
3. **libswresample is linked in**, at 0.18 MB, because FFmpeg's Opus decoder declares a dependency on
   it and configure otherwise switches Opus off with nothing but a warning line in a log.
4. **Evidence comes in three tiers and each states what it does not cover** (§3). No media file and
   no bitstream is committed.
5. **What the module advertises is asserted against the linked library**, read out of libavcodec and
   libavformat rather than out of the configure line.

## 2. What each addition costs, measured

macOS arm64, the `player` profile, shared libraries only, one build per row on 2026-08-04. Each row
is that one addition on top of the base, so the reader can refuse any of them individually, which is
the point of measuring them separately rather than as a set.

| | uncompressed | delta | share |
|---|---|---|---|
| base: h264 + aac, mov demuxer | 2.79 MB | n/a | n/a |
| + hevc decoder and parser | 3.61 MB | **+0.82 MB** | +30% |
| + vp9 decoder | 3.51 MB | **+0.73 MB** | +26% |
| + vp8 decoder | 2.90 MB | +0.11 MB | +4% |
| + matroska demuxer | 2.82 MB | +0.03 MB | +1% |
| + vorbis decoder | 2.86 MB | +0.08 MB | +3% |
| + opus decoder, with libswresample | n/a | +0.18 MB | +6% |
| **all of them together** | **4.72 MB** | +1.93 MB | +69% |

The whole set costs less than the sum of its rows because the video decoders share DSP code.

**What actually ships, the `player` profile end to end, before and after this phase:**

| | uncompressed | in a jar |
|---|---|---|
| macOS arm64, before | 2.84 MB | n/a |
| macOS arm64, after | **4.77 MB** | **1.97 MB** |
| macOS x86_64, before (ADR 011 §4's table) | 3.29 MB | 1.22 MB |
| macOS x86_64, after | **6.60 MB** | **2.41 MB** |

The two architectures did not grow by the same amount (arm64 by 1.93 MB and x86_64 by 3.31 MB)
because the HEVC and VP9 decoders carry far more hand-written x86 assembly than NEON. That is worth
knowing before anyone reads one number as the payload.

**Hardware decode, measured the same way on 2026-08-05 (ADR 014, phase 8c).** The `player` profile
gained `--enable-videotoolbox --enable-hwaccel=h264_videotoolbox --enable-hwaccel=hevc_videotoolbox`:

| | before | after | delta |
|---|---|---|---|
| macOS arm64 | 4.77 MB | **4.80 MB** | **+0.03 MB** (+0.6%) |
| macOS x86_64 | 6.60 MB | **6.63 MB** | **+0.03 MB** (+0.5%) |

**Subtitle decoders, measured the same way on 2026-08-05 (ADR 017, phase 9).** Each row is that one
decoder on top of the `player` line as this phase left it, and the base here is the FFmpeg shared
libraries alone, 4 976 256 bytes, so the deltas are comparable with the table above and the
absolute is smaller than the rows that include the shim:

| | delta |
|---|---|
| + movtext: tx3g, what an MP4 carries | **+304 B** |
| + subrip | **+672 B** |
| + ass | **+240 B** |
| + webvtt | **+160 B** |
| **all four together** | **+896 B** |

**These are three orders of magnitude below every other row in this document**: 0.018% of the
payload against HEVC's +30%. A text subtitle decoder is string manipulation over ASS helpers the
four of them share, and most of what they add fits inside the alignment slack the existing segments
already carry. A number that small is exactly the shape of a result that means configure quietly
dropped the flags, so it was checked against `config_components.h` and the linked library the way
§3's second tier requires; all four are really there. No bitmap subtitle decoder is in either
profile, and ADR 017 §4 says why that is a refusal rather than a saving.

**Hardware decode is the cheapest row among the codecs**, and the reason is that **VideoToolbox is a
system framework**:
what is linked is Apple's, what is added to the payload is two hwaccel wrappers, and the decoder
itself was already there. Nothing in §1 moves for a third of a percent.

**`--enable-videotoolbox` alone would have been free and useless, which is finding 2's trap for the
second time.** It switches on the framework and not one accelerator: `--disable-everything` turned
every hwaccel off, and only `--enable-hwaccel=<name>` turns one back on. Such a build attaches a
device, decodes in software, and reports itself as hardware decoding. So the accelerators are
asserted out of the linked library exactly as the decoders are (`hwaccel:h264:videotoolbox` is a
fact and the configure line is a claim), and the check that nothing *else* was accelerated is what
keeps `--disable-autodetect` honest.

**One accelerator exists only in the `full` profile and ships nowhere: `mpeg4_videotoolbox`.** It is
there for §3's reason. Finding 1 says this repository can encode neither H.264 nor HEVC, so the two
accelerators that ship can never be pointed at a clip it produced, but VideoToolbox also decodes
MPEG-4 Part 2, which `full` *can* encode, so the round trip that proves the software seam can prove
the hardware one. Its own limit is worth writing down beside it: **Apple Silicon's VideoToolbox has
no MPEG-4 Part 2 decoder**, so on those machines that round trip skips and the tier-2 and tier-3
evidence is all there is.

**How each was decided.**

- **HEVC, at nearly a third of the payload, is the most expensive line here and the easiest.** It is
  what every recent phone records in by default. A player for "the user's own media files" that
  cannot open what their phone produced is not a player for that use case, which is the constraint
  the whole subsystem was scoped against.
- **VP9 is the same argument for what the web serves**, and it is what a WebM holds.
- **VP8 at 0.11 MB is cheap enough that refusing it is not worth the sentence explaining it.** It is
  the other half of WebM, and a demuxer that opens a file it then cannot decode is a worse failure
  than one that does not open it.
- **The matroska demuxer at 0.03 MB is the cheapest thing in the table** and is the container VP8,
  VP9, Opus and Vorbis actually arrive in. Enabling those decoders without it would be four codecs
  with nowhere to come from.
- **Opus and Vorbis are what a WebM's soundtrack is.** Without them such a file opens and plays
  silent, which is the worst of the three possible outcomes. Opus additionally drags in
  libswresample; §5 records why that is not the shim starting to use it.

**What was left out and is not in the table:** every other decoder FFmpeg has. The build is still
`--disable-everything` plus a list, and `CodecBreadthTest` bounds the decoder count so that losing
that line fails rather than merely growing the payload.

## 3. The three tiers of evidence, and the one that was rejected

Finding 1 means there is no single test that covers a new codec end to end. So there are three, in
decreasing strength and increasing coverage, and each is written down with what it misses.

### Tier 1: a real round trip, over the path this repository owns

`RoundTripTest` encodes an MPEG-4 Part 2 clip into a real MP4 and reads it back. That exercises
every line of the decode path this project wrote: the demultiplexer loop, the packet-to-picture
step, the planar handoff, the slot pool, the binding epoch, the release discipline, the rescale of
timestamps. **That path is byte-identical C for every codec**: the codec is a pointer inside
libavcodec and nothing above it varies.

*What it does not cover:* libavcodec's own HEVC and VP9 decoders. Those are FFmpeg's to test, they
are tested by FFmpeg's own FATE suite against conformance bitstreams this project has no business
duplicating, and a passing test here would not add to that.

### Tier 2: linkage, read out of the library

`FfmpegMedia.components()` enumerates what libavcodec and libavformat actually hold, and
`CodecBreadthTest` asserts the advertised set against it. **A configure flag is a claim; a linked
symbol is a fact.**

This is not theoretical and it caught something the day it was written: `--enable-decoder=opus` was
in the configure line and Opus was **not** in the library, because FFmpeg had disabled it for a
missing dependency and said so only in a warning line inside a log file nobody reads. Without this
tier that ships as a WebM whose sound is silent.

*What it does not cover:* whether the linked decoder produces a correct picture.

### Tier 3: real files, when the machine has some

`-Dlimn.video.test.clips=<directory>` opens and decodes every file in a directory, checking the
geometry, the layout and the stride of the first picture of each. Skipped, loudly, when the property
is not set, which is every machine that has not been given clips.

*What it does not cover:* anything, on a machine with no clips. It is opt-in precisely because the
alternative is committing media, and this repository does not.

### The tier that was rejected: hand-authoring a bitstream

Writing a conformant one-frame bitstream per codec was weighed and refused. A VP9 keyframe needs its
boolean arithmetic coder and its uncompressed header; an HEVC one needs CABAC and a VPS/SPS/PPS
triple. Each is days of work, and (the part that decides it) **the only reference available to
check the result against is the decoder being tested**, which makes the exercise circular. A
bitstream that both the author and the decoder agree is valid, and that no other decoder has ever
read, proves that two pieces of the same misunderstanding agree.

## 4. AV1, and what it would actually take

Finding 2 is the whole of it: there is no software AV1 decoder in FFmpeg. What exists is
**libdav1d** (BSD-2-Clause, so the licence is not the obstacle), and adding it is:

- a second pinned tarball with its own checksum and its own provenance question;
- a **meson + ninja** toolchain the build script does not use and this project does not require;
- a cross build per architecture, doubled again on macOS, plus `pkg-config` wiring so FFmpeg's
  configure can find it;
- roughly a megabyte more payload, unmeasured here because nothing was built;
- and one more shared library in the manifest and the loader.

None of that is hard. All of it is a phase with its own verification, and pretending otherwise by
adding a flag that produces a decoder which fails on every file is the outcome this ADR exists to
prevent.

**libvpx was not reconsidered.** The research note already refused it as a primary decoder and
nothing here changes that; VP8 and VP9 now decode through FFmpeg, which is the thing libvpx was
being considered for.

## 5. libswresample is linked and unused, and that is deliberate

The shim converts planar float audio to interleaved 16-bit itself, in about ten lines, and ADR 011's
build refused libswresample on exactly that ground. It is now linked anyway, because FFmpeg's Opus
decoder declares it as a dependency, not because anything here started calling it.

The temptation this creates is worth naming: with libswresample present it becomes easy to delete
the shim's own conversion and call it instead. Do not. The shim's version is the one the audio path
is tested against, it allocates nothing per buffer, and the downmix coefficients are stated where
they can be read. What is linked here is a dependency of a decoder, and the manifest lists it so the
loader extracts it, nothing more.

## 6. What this costs, stated rather than discovered

- **The shipped payload grows by about two thirds.** That is the price of the use case, and it is
  still an order of magnitude below the prebuilt JavaCPP natives the research note measured at
  18.5 MB. A distributor who wants the small build removes flags from one function in one script.
- **The tests and the demo still run against the `full` profile**, which is not what ships; ADR 011
  §4 already states that trade and this phase does not change it.
- **A fifth shared library** to extract and load per platform.
- **Windows and Linux are still phase 6b**, so every number here is macOS's.

## 7. What this deliberately is not

- **Not hardware decode.** ADR 014, which was `Proposed` when this was written, is now `Accepted`,
  and added §2's cheapest row and one `full`-only accelerator to §3's tiers.
- **Not 10-bit.** ADR 016, and it is a different question: a codec is what a file is compressed
  with, a depth is what its samples are.
- **Not a change to the install order.** ADR 011 §5 decided that the operating systems' decoders
  should lead once they exist, and none of them exists yet.
- **Not a promise about AV1.** §4 says what it would take, not when.

## Sources

- `libavcodec/av1dec.c` in the ffmpeg-7.1.5 source tree the build script pins: the refusal quoted
  in finding 2 is from that file
- `configure` in the same tree: `opus_decoder_deps="swresample"`, which is §5's whole reason
- The payload table in §2 was produced by configuring and building each row separately; the method
  is the same one `scripts/build-ffmpeg.sh` uses, with one flag added per build
