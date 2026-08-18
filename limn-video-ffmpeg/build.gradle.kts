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

// The shim's file-name stem, and the two licence files, named once: the publish guard, the
// main jar's filter and the classifier jars all have to agree on which file is which.
val shimName = "liblimnffmpeg."
val ffmpegLicences = listOf("LICENSE-ffmpeg.txt", "NOTICE-ffmpeg.txt")

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
        // A platform counts as carried only if its CLASSIFIER would have something in it. Since
        // the split, a directory holding nothing but the shim is the new way to publish a decoder
        // that cannot decode: the main jar would look complete, and the natives-<platform> jar
        // beside it would be an empty archive. So the manifest and at least one library that is
        // not the shim are both required, which is exactly what that jar is made of.
        val carried = (root.listFiles() ?: emptyArray())
            .filter { platform ->
                if (!platform.isDirectory) return@filter false
                val files = platform.listFiles() ?: emptyArray()
                files.any { it.name == "libraries.txt" }
                        && files.any { it.isFile && !it.name.startsWith(shimName) && it.name != "libraries.txt" }
            }
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

// ------------------------------------------------------------------ how the payload is split
//
// The main jar carries the JNI shim for every platform; the FFmpeg libraries ride in one
// classifier artifact per platform, `natives-<os>-<arch>`. An application therefore downloads
// about two megabytes for the machine it runs on instead of all six.
//
// The split is invisible to the loader, and that is not luck: FfmpegLibrary resolves everything
// under `limn/video/ffmpeg/native/<platform>/` as a CLASSPATH resource, and a classpath spans
// jars. The shim comes out of this jar and the libraries out of the classifier's, and neither
// end knows the difference.
//
// libraries.txt goes with the FFmpeg libraries rather than with the shim, and that placement is
// the whole diagnostic. It is the file FfmpegLibrary looks for first, so an application that
// forgot its classifier gets "this build carries no FFmpeg native for <platform>" — the sentence
// that was already written for a platform nobody built — instead of a link error halfway through
// extraction naming a file it has never heard of.
//
// LICENSE-ffmpeg.txt and NOTICE-ffmpeg.txt go in BOTH. They are cheap, and a reader who has only
// one of the two jars in front of them should still find the licence that jar answers to.

tasks.named<ProcessResources>("processResources") {
    exclude {
        it.path.startsWith("limn/video/ffmpeg/native/")
                && !it.isDirectory
                && !it.name.startsWith(shimName)
                && it.name !in ffmpegLicences
    }
    doFirst {
        if (ffmpegNatives.isDirectory) {
            logger.lifecycle("limn-video-ffmpeg: bundling the '$ffmpegProfile' shim from $ffmpegNatives")
        } else {
            logger.lifecycle("limn-video-ffmpeg: no native built (run scripts/build-ffmpeg.sh); " +
                    "the decoder will report itself unavailable and its tests will skip")
        }
    }
}

/**
 * One jar per platform, carrying that platform's FFmpeg libraries and its manifest.
 *
 * Registered for all six whether or not a payload exists: a developer who built only their own
 * slice gets five empty jars nobody publishes, and the guard above is what stops an empty one
 * from ever reaching Central.
 */
val nativeJars = requiredNativePlatforms.map { platform ->
    val suffix = platform.split("-").joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
    tasks.register<Jar>("nativesJar$suffix") {
        description = "The FFmpeg libraries for $platform, published as natives-$platform."
        group = "build"
        archiveClassifier.set("natives-$platform")
        from(ffmpegNatives) {
            include("limn/video/ffmpeg/native/$platform/**")
            exclude("**/$shimName*")
        }
        from(ffmpegNatives) {
            ffmpegLicences.forEach { include("limn/video/ffmpeg/native/$it") }
        }
    }
}

/**
 * Every platform at once, for a distribution that is not built per machine.
 *
 * An application shipped as one cross-platform bundle cannot name the classifier of the machine
 * it will land on, and asking it to list all six is a footgun: the list goes stale the day a
 * seventh target exists. `natives-all` is that list, kept here instead of in every build file
 * that consumes this module. It is what the single published jar used to be, minus the shim,
 * which now travels in the main artifact and must not be duplicated here.
 */
val nativesAllJar = tasks.register<Jar>("nativesJarAll") {
    description = "Every platform's FFmpeg libraries in one artifact, published as natives-all."
    group = "build"
    archiveClassifier.set("natives-all")
    from(ffmpegNatives) {
        include("limn/video/ffmpeg/native/**")
        exclude("**/$shimName*")
    }
}

plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            nativeJars.forEach { artifact(it) }
            artifact(nativesAllJar)
        }
    }
}

// The native payload reaches the main source set through resources.srcDir above, which is what a
// sources jar copies as well: without this the -sources artifact carried every binary a release
// builds, all six platforms of it, and was the largest thing this module published. A sources jar
// answers "what was this compiled from", and no part of that answer is a .dylib.
tasks.named<Jar>("sourcesJar") {
    exclude("limn/video/ffmpeg/native/**")
}
