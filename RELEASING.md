# Releasing

A release of Limn is three artefacts that have to agree: the jars on Maven Central, the tag
in this repository, and the site that tells a reader which coordinates to copy. This file is
how they are kept in agreement, and why the automation stops where it stops.

## The model

**`versions.properties` is the decision, and the only place the number is written.** Landing a
bumped entry on `main` is what releases it: the `tag-releases` workflow tags that commit
`v<version>` and starts `publish`. Nothing else in the tree carries the version:
`build.gradle.kts` reads `-PlimnVersion` from the workflow and otherwise says the `-SNAPSHOT` of
that same file, so a working clone is always one step ahead of the last release; the
documentation writes **placeholders** — `x.y.z` in the READMEs, which GitHub renders raw and
whose Maven Central badge says the current number, and `{{version}}` in the guides, which the
site fills in at deploy time from the release it documents — and `scripts/check-versions.sh`
refuses a literal, in `tag-releases` and again in `publish`. A release commits nothing. Nobody
types a tag or pushes one — the same arrangement as `limn-fonts`, `limn-ffmpeg-natives` and
`limn-icons-tabler`.

**Nothing publishes itself.** `publish` runs the full `check` under xvfb, uploads, and stops,
twice over: the Central deployment is staged until somebody presses Publish, and the GitHub
release is created as a draft. That is not caution for its own sake — Central keeps what it
accepts. A wrong artifact cannot be replaced, only superseded by a version number nobody wanted
to spend, so the last moment at which a release can still be dropped is worth keeping.

**The site follows the release, not the branch.** `site-deploy` runs on `release: published` and
on demand, not on every push to main. The page documents coordinates a reader is meant to copy,
and those coordinates resolve to nothing until the deployment is published. This is why the
number a page shows is the release the deploy documents, filled in at that moment: the page
goes live saying what it describes, and never said it before.

**What versions with the toolkit, and what does not.** Every module published from here carries
the toolkit's version, including the two aggregator POMs (`limn-fonts-all`,
`limn-video-ffmpeg-natives-all`), because "the set this Limn was tested with" is a fact about
the toolkit. The fonts, the FFmpeg payload and the icon pack version on their own cadence, in
repositories of their own (ADRs 036–038); this repository pins them in
`gradle/libs.versions.toml`; their coordinates in the documentation ARE literals, by design —
they are pins — and `check-versions.sh` fails when one disagrees with the catalog.

## Before the first release on a new machine

- The namespace `io.github.limn-toolkit` is registered and verified on the Central Portal, and
  has SNAPSHOTs enabled if you want them (a per-namespace switch, off by default; publishing a
  snapshot without it fails with 403, not 401).
- The four repository secrets exist: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a
  Portal **user token**, not the login), `SIGNING_KEY` (the armored private key, `BEGIN`/`END`
  lines included) and `SIGNING_PASSWORD`.
- The public half of the signing key is on a public keyserver (`keyserver.ubuntu.com`,
  `keys.openpgp.org`). Central verifies against one, and a key it cannot find fails validation
  *after* the upload.

Credentials for publishing from a workstation live in a file **outside this repository**, sourced
only for the command that needs them, and never in `~/.gradle/gradle.properties`: that file is
global, and other projects on the same machine would silently pick up these credentials and sign
their releases with this key.

## Releasing

1. **Land everything.** The code, the ADRs, the guides that describe the version's shape — never
   the number: the documentation carries placeholders, and the check refuses anything else.
2. **Bump `versions.properties`** (say `0.6.0` → `0.7.0`), commit, and push `main`.
3. **Watch `tag-releases`, then `publish`.** The first checks the documentation, tags `v0.7.0`
   and dispatches the second, which runs the full `check` under xvfb before anything is
   uploaded, verifies the tag, uploads the signed bundle, builds the demo jar and drafts the
   GitHub release. Nothing is committed by either.
4. **Inspect the deployment** on <https://central.sonatype.com/publishing/deployments>. This is
   the last reversible moment: **Drop** discards it and costs nothing.
5. **Publish it**, then **publish the draft release** on GitHub after editing its notes. The
   second one deploys the site.

Rehearsing locally is still free and still worth it before a bump:
```
./gradlew publishAllPublicationsToBuildDirRepository -PlimnVersion=0.7.0
```
Every artifact under `build/repo` should have a `.asc` beside it when a key is configured.

## When something goes wrong

**The build failed after the tag was made.** Nothing was uploaded. Fix it on main, delete the
tag on the web UI (repository → Tags → the tag's ⋯ menu), and push: `tag-releases` sees the
version untagged again and redoes the tag and the dispatch on the fixed commit. If only the upload hiccuped and the tag itself is fine, re-run `publish` from the
Actions tab instead (version blank takes `versions.properties`). Once a version is published on
Central, its tag is frozen: publish the fix as the next number.

**The deployment is wrong.** Drop it on the Portal, delete the draft release, and start over with
the same version — nothing was consumed.

**A published version is wrong.** It stays published. Release the next patch version; there is no
other move, which is what steps 5 and 6 are for.

## Snapshots

Any commit can be published to Central's snapshot repository, which is replaceable and needs no
signature:

```
./gradlew publishToMavenCentral
```

Consumers add `https://central.sonatype.com/repository/maven-snapshots/` as a repository. This is
also the honest dress rehearsal for the credentials and the upload path, because it exercises
both without spending a version number.

## The native payload, and where it comes from

Nowhere in this repository (ADR 037). `limn-video-ffmpeg` is Java; its FFmpeg libraries and the
JNI shim in front of them are the `limn-ffmpeg-natives` artifact, built and released from
<https://github.com/limn-toolkit/limn-ffmpeg-natives> on FFmpeg's schedule and versioned after
it (`7.1.5.0` is FFmpeg 7.1.5). This repository pins the version it was tested with in
`gradle/libs.versions.toml`, and three things follow from that one line: `limn-video-ffmpeg`'s
runtime dependency on the shim jar, the six classifiers `limn-video-ffmpeg-natives-all` names,
and the payload the tests run against. A release of Limn builds no native and uploads none.

**Bumping the payload** is a dependency review like any other: change the catalog line, run
`check` (the codec-breadth and licence tests re-verify what the new build links and reports),
and land it. A shim ABI change is the one case with an order: the natives repository releases
first with a new `LIMN_FFMPEG_ABI`, then `FfmpegLibrary.EXPECTED_ABI` moves here with the
catalog line. An application that pins the two apart is told so at load time, by number.

### Working on it locally

Nothing to do for playback: the tests and the demo resolve the published `player` payload from
Maven Central like any other dependency, and CI does the same. The writer tests need an
encoder nothing published carries: build the `full` profile in a sibling clone of the natives
repository and both this module's tests and the demo pick it up by convention —

```
git clone https://github.com/limn-toolkit/limn-ffmpeg-natives ../limn-ffmpeg-natives
(cd ../limn-ffmpeg-natives && ./scripts/build-ffmpeg.sh --profile full)
```

— or point `-PlimnFfmpegNatives=<dir>` at one built elsewhere. Without it the writer tests skip
and everything else runs.

## What is not published

`limn-demo`: it is the kitchen sink and the verification scenes, not a library, and publishing it
would invite an application to depend on it.
