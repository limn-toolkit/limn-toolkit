# ADR 037: The native payload is an artifact, and it versions with FFmpeg

- **Status:** Accepted, 2026-09-02. Implemented as the
  [limn-ffmpeg-natives](https://github.com/limn-toolkit/limn-ffmpeg-natives) repository and
  artifact (`7.1.5.0` first), `FfmpegLibrary.EXPECTED_ABI` / `FfmpegNative.abi()`, and the
  rewritten `limn-video-ffmpeg-natives-all` POM here.
- **Date:** 2026-09-02

## Context

ADR 030 settled *where the decoder lives*: in a module of its own, so a codec with a licence and
a platform matrix never reaches a base module. It said nothing about *when its payload
versions*, and the answer that fell out was the wrong one. Every release of the toolkit ran
`natives.yml` — five runners, six FFmpeg builds, twenty to sixty minutes — to produce bytes
identical to the previous release's, because the FFmpeg pin (7.1.5) had not moved, then
uploaded them under a new toolkit version, and every consumer re-downloaded them. A payload
that changes on FFmpeg's schedule was riding an artifact that versions on the toolkit's.

ADR 036 had just done this exercise for fonts, and two of its findings carried over intact.
`FfmpegLibrary` already resolves the shim and the libraries as classpath resources under
`limn/video/ffmpeg/native/<platform>/`, so which jar carries them is invisible to the loader;
and the split into a shim jar plus one classifier per platform already existed, so nothing
about the *shape* had to change — only whose version it wears.

One thing was genuinely new. The fonts are inert data; the payload has a **JNI shim** whose
entry points are bound by name to `limn.video.ffmpeg.FfmpegNative`. Splitting the repositories
splits one interface into two halves that release apart.

## Decision

**The payload lives in its own repository, as one artifact, `limn-ffmpeg-natives`, versioned
after the FFmpeg it carries.** The first three components *are* the FFmpeg version and the
build refuses any other (`7.1.5.0` is FFmpeg 7.1.5 as first packaged; `7.1.5.1` the same FFmpeg
with a newer shim; `7.1.6.0` the next FFmpeg). Its main jar carries the shim for every platform
and the FFmpeg licence and notice; six `natives-<os>-<arch>` classifiers carry each platform's
libraries and the manifest the loader reads — exactly what `limn-video-ffmpeg` published before,
under a version that now means something. The build script, the six-slice workflow and the C
moved there whole.

**`limn-video-ffmpeg` is pure Java and depends on the shim jar.** `runtimeOnly`, so "add
limn-video-ffmpeg" still brings the native half of itself along and an application adds nothing
for the shim. The libraries stay the application's choice, as they were: one classifier for its
machine, or `limn-video-ffmpeg-natives-all` for all six.

**`limn-video-ffmpeg-natives-all` stays here, versioned with the toolkit**, and names the six
classifiers of the payload at the version the catalog pins — the same reasoning as
`limn-fonts-all`: "which payload this Limn was tested with" is a fact about the toolkit, a POM
carries no bytes, and the ~2 MB slices it names are downloaded once per *payload* version.

**The two halves of the JNI interface shake hands at load time.** The shim exports an ABI
number (`LIMN_FFMPEG_ABI`) and `FfmpegLibrary` asks for it before anything else, refusing a
mismatch with a sentence that names both numbers and the artifact — instead of an
`UnsatisfiedLinkError` from whichever entry point differed, in the middle of a decode. A shim
predating the handshake is refused the same way. The number moves only when a native signature
does, in both repositories, natives first.

**A release of Limn builds no native.** `natives.yml`, `build-ffmpeg.sh` and `fetch-ffmpeg.sh`
are gone from here; `publish.yml` lost its five-runner job; the catalog line is the whole
coupling. Bumping the payload is a dependency review — the codec-breadth and licence tests
re-verify what the new build links and reports — not a binary diff.

**Tests and the demo resolve the published `player` payload like any dependency**, all six
classifiers on the test classpath so the suite runs wherever it lands. CI therefore *gains*
coverage: until now it had no payload at all and every decode test skipped. The `full` profile
(encoders and a muxer, for the writer tests — deliberately never published) is built in a
sibling clone of the natives repository and picked up by convention from
`../limn-ffmpeg-natives/native/dist/full`, on the **test** classpath here and the demo's own,
where project resources precede dependency jars and therefore shadow the player payload at the
same paths. There is no route from that directory to a published jar.

**Before its first release the payload was consumed, not just published.** The natives
repository's CI and its publish job both rehearse a target-17 consumer against the packaged
jars — metadata, manifests, shim placement — before anything is uploaded, which is the half of
a rehearsal ADR 036's first release skipped and paid for.

## Consequences

- A toolkit release takes the time of a Java build. The six-slice matrix runs only when FFmpeg
  or the shim changes, in the repository that owns them.
- **An application upgrading changes one coordinate:** the classifier line moves from
  `limn-video-ffmpeg:<toolkit>:natives-…` to `limn-ffmpeg-natives:<payload>:natives-…`, or to
  the unchanged `limn-video-ffmpeg-natives-all`. An older `limn-video-ffmpeg` never reads the
  new artifact and a newer one never reads its own old classifiers; the release notes on both
  sides say so.
- The ABI number is a new thing to move, in two places, in order. It is also the first time a
  mismatch says what it is.
- The documentation's version check (`scripts/check-versions.sh`) learned a second family of
  coordinates that are pins, not the toolkit's version: literal by design, and required to agree
  with the catalog.
- Two repositories must agree on the JNI surface and on the codec list. The handshake enforces
  the first; `CodecBreadthTest` and `LicenceTest` here, run against the pinned payload,
  enforce the second.

## Alternatives considered

**Building only the shim per toolkit release** (FFmpeg from the new artifact, the C still here,
compiled against published headers). Rejected: it keeps the six-machine matrix in every release
for a file that changes as rarely as the payload does, and it needs a `-dev` artifact of
headers and import libraries that exists for no other reason.

**Each platform as an artifact of its own** rather than classifiers of one. Considered for the
"main jar with nothing in it" hazard, rejected because the main jar here is not empty — it is
the shim for six platforms — and classifiers keep the coordinates one word away from what
every document already said.

**The aggregator POM in the natives repository.** Rejected as in ADR 036: with the payload
versioning apart, "the set this Limn was tested with" is a claim only this repository can make.

**No handshake, trusting the POM's pinned version.** Rejected: the POM says what this module
*wants*; an application's dependency management decides what it *gets*, and the failure mode of
a drift — a link error naming a mangled symbol during playback — was the worst available.
