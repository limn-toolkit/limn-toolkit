# Releasing

A release of Limn is three artefacts that have to agree: the jars on Maven Central, the tag
in this repository, and the site that tells a reader which coordinates to copy. This file is
how they are kept in agreement, and why the automation stops where it stops.

## The model

**`versions.properties` is the decision, and the only place the number is written.** Landing a
bumped entry on `main` is what releases it: the `tag-releases` workflow tags that commit
`v<version>` and starts `publish`. Nothing else in the tree carries the version:
`build.gradle.kts` reads `-PlimnVersion` from the workflow and otherwise says the `-SNAPSHOT` of
that same file. Because the bump IS the release, that snapshot carries the number of the last
release (or of the one about to land), not of the next one — and Maven orders `0.6.0-SNAPSHOT`
*below* `0.6.0`, so a consumer must pin an exact version: a range or `latest` resolves to the
release, never to the snapshot that is newer in time. The
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
5. **Publish it** on the Portal. From here on the version exists and cannot be taken back.
6. **Publish the draft release** on GitHub after editing its notes. This is what deploys the
   site.

Rehearsing locally is still free and still worth it before a bump:
```
./gradlew publishAllPublicationsToBuildDirRepository -PlimnVersion=0.7.0
```
Every artifact under `build/repo` should have a `.asc` beside it when a key is configured.

## When something goes wrong

**The build failed after the tag was made.** Nothing was uploaded. Fix it on main, delete the
tag on the web UI (repository → Tags → the tag's ⋯ menu), and push: `tag-releases` sees the
version untagged again and redoes the tag and the dispatch on the fixed commit. If only the
upload hiccuped and the tag itself is fine, re-run `publish` from the Actions tab instead, with
the **tag selected as the ref** in the dispatch dialog (version blank takes
`versions.properties`): the `verify` job refuses a run whose checked-out commit is not the one
the tag points at, so a dispatch from `main` fails by design rather than publishing whatever
`main` has become. Once a version is published on Central, its tag is frozen: publish the fix
as the next number.

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

## What the build is allowed to download

Two files say it, and a release runs on a machine that trusts nothing else.
`gradle/wrapper/gradle-wrapper.properties` carries `distributionSha256Sum`, so the wrapper
refuses a Gradle distribution whose bytes are not the published ones, and
`gradle/verification-metadata.xml` carries a SHA-256 for every artifact any task here resolves
— the libraries, their natives for all six targets, the fonts, the payload, the icon pack, the
test framework and the publishing plugin with everything it pulls. A jar that does not match is
a failed build, not a warning: a compromised mirror or a re-uploaded artifact stops in front of
`check`, not after a signed bundle has left for Central. The same reason every `uses:` in the
workflows names a commit rather than a tag.

**Changing a dependency** therefore has a second step, and the failure names it: bump the
catalog (or the wrapper), then regenerate the entry —

```
./gradlew --write-verification-metadata sha256 check :limn-demo:fatJar aggregateJavadoc \
    :limn-demo:exportThemeTokens publishAllPublicationsToBuildDirRepository
```

— and read the diff before committing it. The task list is every entry point the workflows run,
because Gradle records what the build resolved and nothing more: a task left out is a
configuration whose artifacts are not in the file, and CI finds that out the day it runs one.
Existing entries are kept, so a regeneration only ever adds; remove the stale component by hand
when a version is retired. A Dependabot pull request that bumps the catalog needs this run on
top before it is green.

For a new **Gradle version**, the wrapper's checksum is the one published at
<https://gradle.org/release-checksums/> for the `-bin.zip`, copied by hand, never by a script
that fetches it from the same place it fetches the distribution.

## The two artifacts that are run rather than depended on

`limn-demo` and `limn-theme-editor` are published to be **started from their coordinate**:
each jar names its main class in the manifest, and each POM says one platform at a time — LWJGL
is excluded from every path to the backend and declared again plain, and every platform's
natives (LWJGL's, and for the demo the FFmpeg payload) sit in a Maven `<profile>` keyed on the
JVM's `os.name` and `os.arch`. Maven and jbang activate those profiles when they resolve a
dependency, Gradle cannot and reads the module metadata beside the POM instead, which still
names every platform, as a library should. So `jbang io.github.limn-toolkit:limn-demo:x.y.z`
downloads the toolkit, the fonts and the natives of the machine it runs on, and nothing else;
`jbang demo@limn-toolkit/limn-toolkit` does the same through the aliases in
`jbang-catalog.json` at the root, which name `RELEASE` rather than a number so a release never
has to touch them (the newest published version, as Central's metadata says; `--fresh` if a
cached answer is a day old). The demo is still an application and not a library — nothing
should depend on it — and the `limn-demo-all.jar` attached to every release stays for a reader
with no network or no cache to fill. `check` refuses a POM of either module that names a native
outside its profile (`checkHostNativesPom`), and `publishAllPublicationsToBuildDirRepository`
is how to read what would ship.
