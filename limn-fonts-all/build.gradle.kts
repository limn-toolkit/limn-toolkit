plugins {
    // Not `java-library`: that plugin publishes a jar, and this module has nothing to put in
    // one. A platform publishes `<packaging>pom</packaging>` and no jar at all — the same
    // lesson limn-video-ffmpeg-natives-all already paid for.
    `java-platform`
}

/*
 * Every fallback face at the versions this toolkit release was tested with, and nothing else.
 *
 * The faces live in the limn-fonts repository and version with the FONT (ADR 036): the pan-CJK
 * jar keeps one version for as long as its bytes do, and an application's cache keeps it across
 * toolkit upgrades. What that split takes away is one line naming a known-good set — three
 * artifacts, three version numbers, none of them this repository's — and this module is that
 * line, put back. It is versioned WITH the toolkit precisely because "which font versions was
 * this Limn tested against" is a fact about the toolkit; the catalog entries it reads are the
 * same ones the backend's own dependencies and VendoredFontsTest verify.
 *
 * A POM carries no bytes, so re-publishing it with every toolkit release costs nothing — the
 * heavy jars it names are downloaded once per FONT version, not once per Limn version.
 *
 * Roboto is absent on purpose: it is not an option to aggregate, it is a hard runtime
 * dependency of limn-backend-lwjgl and arrives with it.
 */
val fontArtifacts = mapOf(
    "limn-fonts-noto-cjk" to libs.versions.limn.fonts.noto.cjk,
    "limn-fonts-noto-emoji" to libs.versions.limn.fonts.noto.emoji,
    "limn-fonts-noto-scripts" to libs.versions.limn.fonts.noto.scripts,
)

// The three are WRITTEN INTO the POM rather than declared as Gradle dependencies, mirroring
// limn-video-ffmpeg-natives-all and for the same class of reason: declared the obvious way they
// also land on this module's own resolvable configurations, and module metadata would offer a
// second, subtly different answer beside the POM's. Without metadata, Gradle and Maven read the
// same POM and get the same three runtime dependencies — which is all this module is.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom.withXml {
                val dependencies = asNode().appendNode("dependencies")
                fontArtifacts.forEach { (artifact, version) ->
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", project.group)
                        appendNode("artifactId", artifact)
                        appendNode("version", version.get())
                        appendNode("scope", "runtime")
                    }
                }
            }
        }
    }
}
