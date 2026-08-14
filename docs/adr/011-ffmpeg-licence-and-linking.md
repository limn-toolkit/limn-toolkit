# ADR 011: LGPL-2.1, linked dynamically, and what a distributed decoder owes that an operating system's does not

- **Status:** Accepted, 2026-08-04. Implemented as phase 6a of the video player: the configure line
  in `scripts/build-ffmpeg.sh`, the shared libraries it produces, and `LicenceTest`.
- **Date:** 2026-08-04
- **Scope:** which FFmpeg this project builds, how the shim links to it, what that obliges Limn and
  anything commercial downstream of it, and where the patent position leaves the operating systems'
  own decoders.
- **Audience:** whoever changes the configure line, packages a release, or decides the decoder
  install order in phase 8. Extends ADR 008, which decided that a codec dependency lands in a module
  of its own; this decides what may be in it.
- **Not legal advice.** Everything below is what the licences and the public record say, with the
  sources. §6 lists what only counsel can settle, and none of it is treated as settled here.

---

## 1. Decision

1. **LGPL-2.1-or-later. `--enable-gpl` is never passed**, and neither is `--enable-nonfree`.
2. **The FFmpeg libraries are built shared and the shim links them dynamically.** Not static.
3. **The shipped library decodes and does not encode.** The build that encodes exists, is called
   `full`, and is for the tests and the demo.
4. **The licence is asserted from the built library, not from the script**, by reading
   `avutil_license()` and the configure line back out of what was actually linked.
5. **The operating systems' decoders lead the install order in phase 8**, and the reason has a date
   on it rather than being a permanent principle (see §5).

## 2. Which components force which licence, checked rather than recalled

FFmpeg is LGPL-2.1-or-later, and the parts that are not are enumerated in the source tree's own
`LICENSE.md` rather than being a matter of interpretation. Checked against ffmpeg-7.1.5:

| | Licence | In this build |
|---|---|---|
| libavcodec, libavformat, libavutil | LGPL-2.1+ | yes |
| libpostproc | **GPL-2+** | no: `--disable-postproc` |
| `libavcodec/x86/flac_dsp_gpl.asm`, `x86/idct_mmx.c`, `libavfilter/x86/vf_removegrain.asm` | **GPL-2+** | no: reachable only with `--enable-gpl` |
| ~30 filters (`vf_blackframe.c`, `vf_boxblur.c`, `signature_lookup.c`, …) | **GPL-2+** | no: `--disable-avfilter` |
| libx264, libx265, libxvid, avisynth, frei0r, libcdio | **GPL-2+**, external | no |
| Fraunhofer FDK AAC, OpenSSL | **nonfree**, external | no |

`--enable-gpl` is the switch that reaches any of them, and passing it changes FFmpeg's licence to
GPL-2+, and with it this module's, and with it anything that ships this module. That is the single
most likely wrong edit in the whole subsystem, because it is what the internet tells you to do when
a codec will not build. It is therefore not left to a comment: `LicenceTest` reads
`avutil_license()` and `avutil_configuration()` out of the linked library and fails the build if
either has moved.

**The encoders this project uses are LGPL.** `mpeg4`, `mjpeg` and `aac` are FFmpeg's own native
encoders and `movenc.c` is its own muxer; none of the four appears anywhere in the GPL list above.
The confusion worth naming is that the *H.264* encoder is not FFmpeg's: it is x264, it is a
separate GPL library, and it is why a round trip in the tests encodes MPEG-4 Part 2 rather than the
codec this decoder is actually for. (The `aac` encoder here is FFmpeg's native one. `libfdk_aac` is
a different thing and is nonfree.)

## 3. Why dynamic linking, and what static would have cost

LGPL-2.1 §6 lets a work be linked with the library either way, but static linking carries an
obligation dynamic linking does not: the distributor must **also** supply what is needed to relink
the work against a modified library: object files, or the work's source. FFmpeg's own compliance
guidance asks for dynamic linking for exactly this reason.

For Limn alone the obligation would be free: Limn is Apache-2.0, the shim's source is public and
`scripts/build-ffmpeg.sh` is checked in, so anyone can already relink. **The obligation is not
Limn's problem, it is the downstream's.** This is an open-core project with a commercial product
below it, and a statically linked shim would put every distributor of that product in the position
of having to publish object files or source to satisfy §6, a question they would have to answer
for every release, about a decision made here.

Dynamic linking removes the question instead of answering it. What ships is four files that sit
beside each other: `libavutil`, `libavcodec`, `libavformat` and the shim. A user who wants a
different FFmpeg replaces three of them, which is the freedom the licence exists to protect, and it
works without anything being rebuilt.

**What it costs**, so it is not discovered later:

- Four files per platform instead of one, and a manifest naming them in load order, because a jar
  cannot hold a symlink and the FFmpeg libraries carry their version in their file names.
- A run-time search path per platform: `@rpath` install names with `-rpath @loader_path` on macOS,
  `$ORIGIN` on Linux, and the executable's own directory on Windows (phase 6b). FFmpeg's
  `--install-name-dir=@rpath` does the macOS half; without it every install name carries the build
  machine's `--prefix`, which exists nowhere else.
- Slightly more than static would be: a shared library keeps what a static link would drop.
  Measured at 3.29 MB uncompressed for the whole set, against 18.5 MB for the prebuilt JavaCPP
  natives the note measured, so the difference is not where the payload budget is decided.

## 4. Why the shipped library does not encode

The two builds differ by which codecs are linked in and by nothing else: the same shim source, the
same entry points, one of which asks libavcodec at run time whether an encoder exists and reports
that it does not.

**Measured, macOS x86_64, uncompressed / deflated in a jar:**

| profile | libavcodec | libavformat | libavutil | shim | total | in a jar |
|---|---|---|---|---|---|---|
| `player`: h264 + aac decode, mov demux | 2.11 MB | 0.37 MB | 0.78 MB | 0.03 MB | **3.29 MB** | **1.22 MB** |
| `full`: plus mpeg4/mjpeg/aac encode, mp4+mov mux, mpeg4/mjpeg decode | 3.02 MB | 0.54 MB | 0.78 MB | 0.03 MB | **4.36 MB** | **1.65 MB** |

So encoding costs **1.07 MB uncompressed, 0.43 MB in a jar**, about a third more, on a payload
whose whole point is to be small. That is the reason, and it is the only reason that survived
scrutiny: the patent argument for keeping an encoder out, which is what this decision originally
rested on, **is much weaker than it was three weeks ago** (§5).

What it costs, stated plainly rather than buried: **the tests and the demo run against a build that
is not the one that ships.** The decode path (demux, packet to picture, the planar handoff, the
pool, the release discipline, the rescale, the audio conversion) is byte-identical C in both, and
the difference is a configure line. It is not nothing, and it is the reason the profile is named in
the build output every time Gradle assembles this module.

## 5. Patents: what a decoder you distribute owes that one you call does not

This is the part that decides phase 8's install order, and it is the part with a clock on it.

**The distinction is real.** An operating system's decoder (VideoToolbox, Media Foundation,
VA-API) is licensed by the operating system's vendor, and an application that calls it is using a
licensed implementation it did not distribute. An application that ships its own decoder is
distributing an implementation of the standard, and whatever the standard's patents require of a
distributor, they require of it. FFmpeg's own position is that they have never read a patent to
implement anything and therefore do not know what reads on their code, and they say plainly that
commercial use of the MPEG codecs may attract a demand from a pool.

**But the dates have moved, and they are close.**

- **MPEG-4 Part 2 (Visual)**: reported as having reached the expiry of its last patent on
  **2026-07-19**, roughly a fortnight before this decision. If that holds, the encoder this project
  builds for its tests carries no pool exposure at all, and the patent half of §4's argument is
  gone. That is why §4 rests on the measured megabyte instead.
- **H.264 / AVC**: the last significant US patent in the pool is reported as expiring around
  **2027-11-29**. So AVC is still encumbered today, for something over a year, and then largely is
  not. Other jurisdictions trail rather than matching that date.

**The consequence for phase 8, with the reason attached to the date:** while AVC is encumbered, the
operating systems' decoders should **lead** the install order, not because they are faster, though
they are, but because using one puts the licensed implementation on the vendor's side of the line.
The FFmpeg decoder then serves the files and the platforms the OS route does not. Once AVC's pool
has run out, that reason evaporates and the ordering becomes an ordinary engineering question about
speed, power and codec coverage, decided per platform.

`Videos` is an ordered list and the order is the probe order, so this is an install order and never
a rewrite. Nothing in phase 6a forecloses it and nothing in it has to change when the date passes.

## 6. What this ADR cannot settle, and what would

Written down rather than glossed, because a confident sentence here would be worth less than an
honest gap:

- **Whether any live patent reads on this build**, in any jurisdiction. Needs counsel with the pool
  membership lists, not a configure line. The expiry dates in §5 are from public reporting and one
  of them is a fortnight old; treat both as a prompt to re-check rather than as a finding.
- **Whether the LGPL §6 reading in §3 is the one a court would take.** Dynamic linking is chosen
  precisely so the question does not have to be answered, which is the value of choosing it.
- **What the commercial downstream must include in its own notices.** Limn's `NOTICE` gains the
  FFmpeg entry here; the product below it has its own distribution and its own obligations, and
  those are a decision for whoever ships it.
- **Whether shipping a decode-only library is worth tests running against a different build.** §4
  states the trade rather than hiding it. If the answer changes, the change is one flag in one
  script and the tests stop needing a skip.

## 7. What this deliberately is not

- **Not a commitment to FFmpeg.** ADR 008's boundary means a second decoder (an operating
  system's, or libvpx behind a BSD build) arrives as another module and another entry in an
  ordered list.
- **Not a packaging decision.** Which classifier ships, how it is signed and how it is notarised is
  phase 6c, and it needs credentials this phase does not have.
- **Not hardware decode.** Everything here is software, `--disable-everything` disables the
  hardware accelerators along with the rest, and phase 8 is where that is reopened.

## Sources

- FFmpeg, *License and Legal Considerations*: <https://www.ffmpeg.org/legal.html>
- `LICENSE.md` in the ffmpeg-7.1.5 source tree: the enumeration in §2 is from this file
- Via Licensing Alliance, AVC/H.264 programme: <https://www.via-la.com/licensing-programs/avc-h-264/>
- *Last MPEG-4 Visual Patent Expires Today*, 2026-07-19:
  <https://www.techtimes.com/articles/320983/20260719/last-mpeg-4-visual-patent-expires-today-freeing-divx-xvid-globally.htm>
- *Decoding the Landscape: Recent Developments in Video Codec Licensing*:
  <https://www.streamingmediaglobal.com/Articles/Editorial/Featured-Articles/Decoding-the-Landscape-Recent-Developments-in-Video-Codec-Licensing-164043.aspx>
