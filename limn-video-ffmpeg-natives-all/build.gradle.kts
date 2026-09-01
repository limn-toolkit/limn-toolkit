plugins {
    // Not `java-library`: that plugin publishes a jar, and 0.5.0 shipped exactly that — a
    // 261-byte decoy whose emptiness reads as a broken release to anyone who opens the
    // directory on Central. A platform publishes `<packaging>pom</packaging>` and no jar at
    // all, which is what the paragraph below has claimed all along.
    `java-platform`
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

// The same six the decoder publishes, and spelled out for the same reason they are spelled out
// there: a list discovered from what happens to exist calls a missing platform complete. See
// requiredNativePlatforms in limn-video-ffmpeg/build.gradle.kts.
val nativePlatforms = listOf(
    "linux-aarch64", "linux-x86_64",
    "macos-aarch64", "macos-x86_64",
    "windows-aarch64", "windows-x86_64",
)

// ------------------------------------------------------------------ why the POM is written here
//
// The six are WRITTEN INTO the POM rather than declared as Gradle dependencies (the platform's
// `allowDependencies()` route), and that is a correction rather than a preference.
//
// Declared the obvious way — `runtimeOnly(project(":limn-video-ffmpeg")) { artifact { classifier
// = ... } }` — they are correct in both the POM and the module metadata, and they also land on
// this module's own testRuntimeClasspath. `check` then tries to RESOLVE them, and cannot: a
// project dependency that selects a classifier needs the target project to expose that artifact
// as a consumable variant, and limn-video-ffmpeg's natives-<os>-<arch> jars are publication
// artifacts, not variants. It failed in the publish workflow's `check` step, which is the gate in
// front of the upload, and nowhere before it: generating the POM, generating the metadata and
// publishing to mavenLocal never resolve a test classpath, so every check made while writing this
// module passed.
//
// Gradle module metadata is switched off here for the same reason it would otherwise matter. A
// consumer that finds a .module prefers it over the POM, and there is no way to put these six
// into one without also putting them on a configuration that resolves in this project. Without
// it, Gradle and Maven read the same POM and get the same six — which is all this module is.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom.withXml {
                val dependencies = asNode().appendNode("dependencies")
                nativePlatforms.forEach { platform ->
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", project.group)
                        appendNode("artifactId", "limn-video-ffmpeg")
                        appendNode("version", project.version)
                        appendNode("classifier", "natives-$platform")
                        appendNode("scope", "runtime")
                    }
                }
            }
        }
    }
}
