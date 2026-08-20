plugins {
    `java-library`
}

/*
 * The list of every platform's FFmpeg libraries, and nothing else.
 *
 * Nothing is compiled here and nothing is meant to be: this module's entire content is its
 * dependency list. A distribution shipped as one cross-platform bundle cannot name the classifier
 * of the machine it will land on, and asking it to write all six is a list that goes stale the
 * day a seventh target exists. This is that list, kept here rather than in every build file that
 * consumes the decoder.
 *
 * It replaces a `natives-all` classifier that CARRIED the six payloads instead of naming them.
 * That artifact was a byte-for-byte duplicate of the six beside it — about 13.7 MB of the roughly
 * 27 MB of native code a release uploaded — and the Central Portal's monthly allowance is the
 * thing that made a duplicate stop being free. Naming them costs a POM.
 *
 * A classifier could not do this. Classifiers share the module's single POM, so dependencies
 * declared for `natives-all` would apply to `limn-video-ffmpeg` itself and every consumer of the
 * shim would drag all six platforms back in — which is the split this replaced. A sibling module
 * has its own POM, and that is the whole reason it is one.
 */
dependencies {
    // The same six the decoder publishes, and they are spelled out for the same reason they are
    // spelled out there: a list discovered from what happens to exist calls a missing platform
    // complete. See requiredNativePlatforms in limn-video-ffmpeg/build.gradle.kts.
    listOf(
        "linux-aarch64", "linux-x86_64",
        "macos-aarch64", "macos-x86_64",
        "windows-aarch64", "windows-x86_64",
    ).forEach { platform ->
        runtimeOnly(project(":limn-video-ffmpeg")) {
            artifact {
                name = "limn-video-ffmpeg"
                type = "jar"
                classifier = "natives-$platform"
            }
        }
    }
}
