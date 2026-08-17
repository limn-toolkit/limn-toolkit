// limn-video-ffmpeg: the H.264/AAC-in-MP4 decoder, and the only module in this repository with a
// native payload.
//
// It is a module of its own rather than a package in limn-video for the reason ADR 008 gave when
// it drew that boundary: limn-video is pure Java and carries no native and no third-party
// dependency at all, and that sentence had to survive the arrival of the decoder that has both.
// An application that plays a Y4M pulls no FFmpeg; an application that plays neither pulls
// nothing at all.
//
// NOTHING HERE IS BUILT BY GRADLE. The library this module loads comes from
// scripts/build-ffmpeg.sh; Gradle never invokes a C compiler, because a toolkit build must not
// need one. What the build does is package it.
//
// NO native is committed, and none ever will be. .gitignore excludes this module's whole native/
// tree, because nothing this project did not write lives in git, and because a binary in a source
// repository is a binary nobody can review. The published jar is the only place these libraries
// are meant to exist.
//
// WHERE A PAYLOAD COMES FROM, then:
//
//   a release      .github/workflows/natives.yml builds all six desktop slices, on five runners,
//                  and hands them to the publish job as artifacts. They live for the length of
//                  that run, go into the jar, and are gone. Nothing is pinned, downloaded or
//                  trusted: the release builds what it ships.
//   a developer    ./scripts/build-ffmpeg.sh — about a minute per platform, and it needs a C
//                  compiler and nothing else. `--profile full` adds the encoders and the mov
//                  muxer the writer tests need.
//   a developer    ./scripts/fetch-ffmpeg.sh — no toolchain at all: it takes the natives out of
//   in a hurry     the published jar on Maven Central, which is the same signed artifact an
//                  application gets, and unpacks them where Gradle looks.
//
// A clone with none of the above is a supported state, not a broken one: the module compiles, its
// tests skip the way the GL-backed ones do, and the demo runs with the decoder reporting itself
// unavailable.

plugins {
    `java-library`
}

dependencies {
    // limn-toolkit and nothing else. Not limn-video: this needs the SPI (limn.video), not the
    // pure-Java decoders, and depending on them would tie a native module to a codec-free one for
    // no gain.
    api(project(":limn-toolkit"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Which build of the native to put on the classpath.
//
//   player  what ships: H.264 and AAC out of MP4, and nothing else. No encoder, no muxer.
//   full    player plus the mpeg4/aac encoders and the mov muxer, so a test and the demo can
//           write a real MP4 and read it back; no generated media is committed, so producing one
//           is the only honest way to have one. For the codecs nothing here can encode, the
//           committed corpus in media/ is what the breadth tier decodes (docs/adr/027).
//
// A developer who has built `full` gets it, because that is the build whose tests can run. A
// release picks `player` explicitly with -PlimnFfmpegProfile=player, and packaging (phase 6c) is
// where that becomes a rule rather than a default.
val ffmpegProfile: String = (findProperty("limnFfmpegProfile") as String?)
    ?: if (file("native/dist/full").isDirectory) "full" else "player"

val ffmpegNatives = file("native/dist/$ffmpegProfile")

sourceSets {
    named("main") {
        // Absent on a machine that never ran the build script, which Gradle accepts: a resource
        // directory that does not exist contributes nothing rather than failing.
        resources.srcDir(ffmpegNatives)
    }
}

/**
 * The desktop targets a published decoder must cover.
 *
 * Written out here rather than discovered from the directory, and that is the whole point: a
 * check that trusts what it finds calls an empty directory complete, and a slice whose build
 * failed then publishes as a jar that plays no video on that platform and says nothing.
 */
val requiredNativePlatforms = listOf(
    "linux-aarch64", "linux-x86_64",
    "macos-aarch64", "macos-x86_64",
    "windows-aarch64", "windows-x86_64",
)

// A release ships `player`, and this is what turns that from a sentence into a rule. The default
// above prefers `full` when a developer has built it, which is right for running the tests and
// wrong for an artifact, because `full` carries encoders and a muxer that nothing published uses.
// Publishing from such a machine without saying so would put them in the jar silently.
//
// The payload is checked as well as the profile's name, and the two failures are different. The
// name being wrong puts the wrong binaries in the jar; the payload being absent puts NO binaries
// in it, and that one used to pass. A resource directory that does not exist contributes nothing
// rather than failing (see sourceSets above), so a publish from a tree where the native was never
// built produced a decoder module with no decoder in it, announced by one line in the log.
tasks.withType<AbstractPublishToMaven>().configureEach {
    doFirst {
        if (ffmpegProfile != "player") {
            throw GradleException(
                "refusing to publish limn-video-ffmpeg with the '$ffmpegProfile' native: a " +
                        "published artifact carries the 'player' build. Re-run with " +
                        "-PlimnFfmpegProfile=player."
            )
        }
        val root = file("native/dist/player/limn/video/ffmpeg/native")
        val carried = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.listFiles()?.isNotEmpty() ?: false) }
            .map { it.name }
            .toSet()
        val missing = requiredNativePlatforms.filterNot { carried.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "refusing to publish limn-video-ffmpeg without its native payload: " +
                        "${missing.joinToString(", ")} " +
                        (if (missing.size == 1) "is" else "are") + " missing from " +
                        "${root.relativeTo(rootDir)}.\n" +
                        "The binaries are not in this repository and are not meant to be: a " +
                        "release builds all six on its own runners (.github/workflows/" +
                        "natives.yml) and merges them in before this task runs. Reaching this " +
                        "message means either that job did not, or that this is a local publish " +
                        "from a machine that has only its own platform — which is what " +
                        "scripts/build-ffmpeg.sh produces, and is not enough to publish."
            )
        }
    }
}

tasks.named("processResources") {
    doFirst {
        if (ffmpegNatives.isDirectory) {
            logger.lifecycle("limn-video-ffmpeg: bundling the '$ffmpegProfile' native from $ffmpegNatives")
        } else {
            logger.lifecycle("limn-video-ffmpeg: no native built (run scripts/build-ffmpeg.sh); " +
                    "the decoder will report itself unavailable and its tests will skip")
        }
    }
}
