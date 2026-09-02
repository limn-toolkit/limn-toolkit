# Releasing

A release of Limn is three artefacts that have to agree: the jars on Maven Central, the tag
in this repository, and the site that tells a reader which coordinates to copy. This file is
how they are kept in agreement, and why the automation stops where it stops.

## The model

**The tag is the decision.** `v0.1.0` means "this commit is what I want 0.1.0 to be". Nothing in
the tree carries a release version: `build.gradle.kts` reads `-PlimnVersion` and otherwise says
`0.1.0-SNAPSHOT`, so a release edits no file and leaves nothing behind saying the wrong thing.

**Nothing publishes itself.** Pushing the tag uploads and stops, twice over: the Central
deployment is staged until somebody presses Publish, and the GitHub release is created as a
draft. That is not caution for its own sake — Central keeps what it accepts. A wrong artifact
cannot be replaced, only superseded by a version number nobody wanted to spend, so the last
moment at which a release can still be dropped is worth keeping.

**The site follows the release, not the branch.** `site-deploy` runs on `release: published` and
on demand, not on every push to main. The page documents coordinates a reader is meant to copy,
and those coordinates resolve to nothing until the deployment is published. This is why the
documentation for a version goes IN the commit that gets tagged rather than in a follow-up: the
page goes live at the moment what it describes does.

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

1. **Land everything, including the documentation for this version.** `site/src/guides/install.md`
   and the README name coordinates; they are part of the release, not a follow-up.
2. **Rehearse locally** (optional, and free):
   ```
   ./gradlew publishAllPublicationsToBuildDirRepository -PlimnVersion=0.1.0
   ```
   Every artifact under `build/repo` should have a `.asc` beside it. No signature means no key
   reached Gradle, and the release would be rejected after uploading.
3. **Tag, annotated**, and push it:
   ```
   git tag -a v0.1.0 -m "v0.1.0" && git push origin v0.1.0
   ```
   Annotated rather than lightweight: a tag is a claim about a commit and deserves an author, a
   date and a message. `gh release` falls back to the commit message for a lightweight one.
4. **Watch `publish`.** It runs the full `check` under xvfb before anything is uploaded, verifies
   that the tag exists and points at the commit being built, uploads the signed bundle, and
   drafts the GitHub release.
5. **Inspect the deployment** on <https://central.sonatype.com/publishing/deployments>. This is
   the last reversible moment: **Drop** discards it and costs nothing.
6. **Publish it**, then **publish the draft release** on GitHub after editing its notes. The
   second one deploys the site.

## When something goes wrong

**The build failed after the tag was pushed.** Fix it on main, then re-run `publish` from the
Actions tab with the version typed in. It verifies that the tag exists and points at the commit
it is building, so a fix has to be tagged too — move the tag with `git tag -f` and
`git push --force origin v0.1.0` if nothing has been published under it yet. Once a version is
published on Central, the tag that produced it is frozen: publish the fix as a new version.

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
