plugins {
    // Not `java-library`: that plugin publishes a jar, and 0.5.0 shipped exactly that — a
    // 261-byte decoy whose emptiness reads as a broken release to anyone who opens the
    // directory on Central. A platform publishes `<packaging>pom</packaging>` and no jar at
    // all, which is what the paragraph below has claimed all along.
    `java-platform`
}

/*
 * Every platform's FFmpeg libraries at once, and nothing else.
 *
 * Nothing is compiled here and nothing is meant to be: this module's entire content is its
 * dependency list. A distribution shipped as one cross-platform bundle cannot name the classifier
 * of the machine it will land on, and asking it to write all six is a list that goes stale the
 * day a seventh target exists. This is that list, kept here rather than in every build file that
 * consumes the decoder.
 *
 * What it names changed with ADR 037 and what it IS did not. The payload now lives in the
 * limn-ffmpeg-natives repository and versions with FFmpeg, so the six classifiers below belong to
 * `limn-ffmpeg-natives` at the version the catalog pins — not to limn-video-ffmpeg at this
 * module's own version, which is how it started. This POM stays HERE, versioned with the
 * toolkit, for the same reason limn-fonts-all does: "which payload this Limn was tested with" is
 * a fact about the toolkit. It carries no bytes, so re-publishing it every release costs nothing,
 * and the ~2 MB slices it names are downloaded once per PAYLOAD version, not once per Limn.
 *
 * It replaced a `natives-all` classifier that CARRIED the six payloads instead of naming them —
 * a byte-for-byte duplicate of the six beside it — and a classifier could not do this: classifiers
 * share the module's single POM, so dependencies declared for one apply to the shim as well.
 */

// The same six the payload publishes, spelled out for the same reason they are spelled out
// there: a list discovered from what happens to exist calls a missing platform complete.
val nativePlatforms = listOf(
    "linux-aarch64", "linux-x86_64",
    "macos-aarch64", "macos-x86_64",
    "windows-aarch64", "windows-x86_64",
)

// ------------------------------------------------------------------ why the POM is written here
//
// The six are WRITTEN INTO the POM rather than declared as Gradle dependencies (the platform's
// `allowDependencies()` route), and that is a correction rather than a preference: declared the
// obvious way they also land on this module's own resolvable configurations, and module metadata
// would offer a second, subtly different answer beside the POM's. Without metadata, Gradle and
// Maven read the same POM and get the same six — which is all this module is.
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
                        appendNode("artifactId", "limn-ffmpeg-natives")
                        appendNode("version", libs.versions.limn.ffmpeg.natives.get())
                        appendNode("classifier", "natives-$platform")
                        appendNode("scope", "runtime")
                    }
                }
            }
        }
    }
}
