// limn-video-ffmpeg: the H.264/HEVC/VP9/VP8 and AAC/Opus/Vorbis decoder, in Java, over a native
// payload that lives somewhere else.
//
// It is a module of its own rather than a package in limn-toolkit for the reason ADR 008 gave
// when it drew that boundary, and ADR 030 kept: the toolkit is pure Java and carries no native
// and no third-party dependency at all, and that sentence had to survive the arrival of the
// decoder that has both. An application that plays a Y4M pulls no FFmpeg; an application that
// plays neither pulls nothing at all.
//
// NOTHING NATIVE IS IN THIS REPOSITORY ANY MORE, and nothing native is built by it (ADR 037). The
// payload — the JNI shim and the FFmpeg libraries for six desktop targets — is the
// limn-ffmpeg-natives artifact, from a repository of its own, versioned with FFMPEG rather than
// with this toolkit: a release of Limn re-uploads no native byte, and an application's cache keeps
// the ~2 MB slice for its machine across every toolkit upgrade that names the same payload.
//
//   the main jar of that artifact   the shim for every platform. This module depends on it, so it
//                                   arrives with this module and an application adds nothing.
//   natives-<os>-<arch>             that platform's FFmpeg libraries. The APPLICATION names the
//                                   one for its machine, or limn-video-ffmpeg-natives-all (a POM
//                                   published from here, versioned with the toolkit) for all six.
//
// The split is invisible to the loader: FfmpegLibrary resolves everything under
// limn/video/ffmpeg/native/<platform>/ as a CLASSPATH resource, and a classpath spans jars. What
// keeps the two halves honest is the ABI handshake — the shim exports a number and FfmpegLibrary
// checks it before anything else — and the catalog, which pins the exact payload version this
// module's POM names and its tests were run against.

plugins {
    `java-library`
}

// A developer's `full` payload, for the writer tests: encoders and the mov muxer that nothing
// published carries (a player does not encode, and an encoder is patent surface — see the build
// script). It is built in a sibling clone of limn-ffmpeg-natives with
// `scripts/build-ffmpeg.sh --profile full`, and picked up from there by convention, or from
// wherever -PlimnFfmpegNatives points. It joins the TEST source set only: test resources come
// before dependency jars on the test runtime classpath, so the full build shadows the published
// player payload's manifests and libraries at the same resource paths, and it can never reach a
// published jar of this module — there is no path from here to the main jar at all.
val devNatives: File = (findProperty("limnFfmpegNatives") as String?)?.let { file(it) }
    ?: rootDir.resolve("../limn-ffmpeg-natives/native/dist/full")

sourceSets {
    named("test") {
        // Absent on a machine that never built one, which Gradle accepts: a resource directory
        // that does not exist contributes nothing rather than failing.
        resources.srcDir(devNatives)
    }
}

dependencies {
    // limn-toolkit, for the SPI (limn.video). The pure-Java decoders live there too now, and
    // this module uses none of them: what it implements is the same interface they do.
    api(project(":limn-toolkit"))

    // The shim, for every platform, from the payload's main jar. runtimeOnly, because no Java
    // here compiles against a .so; it is what makes "add limn-video-ffmpeg" bring the native
    // half of itself along.
    runtimeOnly(libs.limn.ffmpeg.natives)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The FFmpeg libraries, for the tests: all six classifiers, because this is a library and the
    // suite should run wherever a developer or a runner happens to be, and because six on one
    // classpath is exactly what an application using natives-all has. The loader picks the
    // directory named by os.name/os.arch and the others sit inert. ~13 MB, downloaded once.
    val payload = libs.limn.ffmpeg.natives.get()
    listOf("linux-aarch64", "linux-x86_64", "macos-aarch64", "macos-x86_64",
            "windows-aarch64", "windows-x86_64").forEach { platform ->
        testRuntimeOnly("${payload.module}:${payload.versionConstraint.requiredVersion}:natives-$platform")
    }
}

tasks.named<Test>("test") {
    doFirst {
        if (devNatives.isDirectory) {
            logger.lifecycle("limn-video-ffmpeg: tests run against the 'full' payload at $devNatives")
        } else {
            logger.lifecycle("limn-video-ffmpeg: tests run against the published 'player' payload; " +
                    "the writer tests skip (a 'full' build from limn-ffmpeg-natives beside this " +
                    "repository enables them)")
        }
    }
}
