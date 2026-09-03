// Limn: multi-module build root.
//
// Layers (conceptual, top to bottom):
//   limn-demo → limn-toolkit ← limn-backend-lwjgl
//   limn-toolkit carries the widget set and the pure-Java decoders; the decoder over a
//   native payload is limn-video-ffmpeg, which nothing depends on, so a codec with a
//   licence and a platform matrix can never reach a base module (ADR 030). The payload
//   itself is not here at all: it is limn-ffmpeg-natives, versioned with FFmpeg (ADR 037).
//
// The backend module IMPLEMENTS the SPI (limn.backend.*) defined in limn-toolkit
// (dependency inversion): no module above it sees LWJGL/OpenGL.

import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    base
    // Applied to each published module below and never to the root, which builds no artifact.
    alias(libs.plugins.central.publish) apply false
}

// The version the next release carries, from versions.properties — the one file that names it.
// The release itself arrives as -PlimnVersion from the publish workflow (which took it from the
// tag, which tag-releases made from that same file); a working clone reads the -SNAPSHOT of it,
// which is also what publishToMavenLocal wants, and is ahead of the last release rather than
// behind it.
val nextVersion = java.util.Properties().apply {
    file("versions.properties").inputStream().use { load(it) }
}.getProperty("limn-toolkit") ?: throw GradleException("versions.properties names no limn-toolkit version")

allprojects {
    group = "io.github.limn-toolkit"
    version = (findProperty("limnVersion") as String?) ?: "$nextVersion-SNAPSHOT"
}

// What is published, and what a one-line description of it says in the POM.
//
// limn-demo is absent on purpose: it is the kitchen sink and the verification scenes, not a
// library, and publishing it would invite an application to depend on it. Everything else is
// something an application legitimately puts on its classpath, including the two it OPTS IN to
// (the icon pack and the theme editor), which are modules precisely so that choice exists.
val publishedModules = mapOf(
    "limn-toolkit" to
            "The widget set, layout, the scene graph, the backend SPIs and the pure-Java video " +
            "decoders; depends on nothing.",
    "limn-theme-editor" to "The screen that authors a Theme; an application opts in.",
    "limn-video-ffmpeg" to
            "H.264/HEVC/VP9/VP8 and AAC/Opus/Vorbis out of MP4 and Matroska, via FFmpeg. The " +
            "native payload is the limn-ffmpeg-natives artifact, versioned with FFmpeg: this " +
            "module brings its JNI shim along, and an application adds one natives-<os>-<arch> " +
            "classifier of it (macOS, Linux, Windows; x86_64, aarch64; LGPL-2.1-or-later, see " +
            "NOTICE-ffmpeg.txt in those jars) or limn-video-ffmpeg-natives-all. Without one the " +
            "decoder reports itself unavailable and the rest of the toolkit is unaffected.",
    "limn-backend-lwjgl" to "The LWJGL backend: GLFW, OpenGL and stb behind the toolkit's SPIs.",
    "limn-video-ffmpeg-natives-all" to
            "Every desktop platform's FFmpeg libraries at once, for a distribution shipped as " +
            "one cross-platform bundle. It carries no code and no binaries: it names the six " +
            "natives-<os>-<arch> classifiers of limn-ffmpeg-natives, at the version this " +
            "toolkit release was tested with, so a build that cannot know its machine does not " +
            "have to name them itself.",
    "limn-fonts-all" to
            "Every fallback face at once — the limn-fonts artifacts (pan-CJK, colour emoji, " +
            "the complex scripts) at the versions this toolkit release was tested with. It " +
            "carries no bytes: a POM naming three runtime dependencies, versioned with the " +
            "toolkit because which font versions it was tested against is a fact about the " +
            "toolkit.",
)

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            // The JDK this build runs on, pinned.
            //
            // Without it Gradle uses whichever JDK launched it, and the two ends of this
            // project disagreed silently: a developer on 21 and a CI runner on 17 compiled
            // the same bytecode (see options.release below) but generated DIFFERENT Javadoc.
            // The 21 doclet declares its palette as CSS custom properties and the 17 one
            // writes literal colours, so `site/src/styles/javadoc-theme.css`, which works by
            // redefining those properties, had nothing to redefine on CI: /api/ published
            // with the stock Javadoc colours and no 404 anywhere to say so.
            //
            // release stays 17: what the artifacts target is a promise to whoever consumes
            // them, and it is not the same decision as which compiler builds them.
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
            // No withJavadocJar() here, and the published modules still get one: the publishing
            // plugin builds it (`plainJavadocJar`), because Maven Central requires a javadoc jar
            // in every deployment. Asking for both produces two tasks writing one file, which
            // Gradle rejects outright rather than picking a winner.
            //
            // What that jar carries is not a formality: this repository splits its documentation
            // on the premise that a member's contract stands alone because a user with the
            // artifact has no repository. The `check` → `javadoc` wiring below is what keeps
            // that promise buildable; the plugin only packages it.
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:all,-processing,-serial,-requires-automatic")
        }

        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                encoding = "UTF-8"
                charSet = "UTF-8"
                addBooleanOption("html5", true)
                // doclint minus `missing`, which is this repository's documentation rule expressed
                // as a build flag. The four groups kept (accessibility, html, reference, syntax)
                // all ask "is the documentation that EXISTS correct and resolvable", and every one
                // of them has already caught a real defect here: an @throws on a record type, a
                // @link to a package the module cannot see, a bare & , a heading two levels off.
                // `missing` asks a different question ("is every element documented"), and the
                // only way to satisfy it on a record like Vec3 is `@param x the x`, which the
                // documentation rules forbid outright. Left on, it buries the four that matter
                // under hundreds it does not.
                addBooleanOption("Xdoclint:all,-missing", true)
            }
        }

        // A published Javadoc jar is the whole premise of how this repository splits its
        // documentation: a member's contract must stand alone because a user with the artifact has
        // no repository. That premise was untrue for as long as `javadoc` sat outside `check`:
        // the jar could not be built at all, and nothing said so.
        tasks.named("check") {
            dependsOn(tasks.named("javadoc"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // A failing test has to name itself in the CONSOLE, because on CI the console is the
            // only artefact that survives. Gradle's default points at an HTML report under
            // build/, which on a runner is a directory that ceases to exist with the machine:
            // the first red build this repository ever had on Linux said "4 failed" and not one
            // word about which four, and diagnosing it started by reproducing the whole runner.
            testLogging {
                // `skipped` beside `failed`, because a test this project skips is a claim it
                // stopped checking, and the reason is environmental every time: no GL context, no
                // FFmpeg native, no device precise enough for ten bits. Silent on a runner, that
                // reads as coverage nobody has.
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
            }
            // The audio tests open a REAL OpenAL device and play real tones (that is the point:
            // they cover the device path, not a mock). Audible from a build, they are a bug in
            // someone's afternoon, so the bundled OpenAL Soft is pointed at its null backend.
            // Everything still runs: the device opens, buffers upload, sources play and the
            // availability-gated tests keep running; only the speakers are spared.
            // Override with -DlimnAudibleTests=true when listening IS the verification.
            if (System.getProperty("limnAudibleTests") != "true") {
                environment("ALSOFT_DRIVERS", "null")
            }
            // Opt-in properties reach the DAEMON when a developer passes -D on the command line,
            // and the tests run in a JVM of their own, so a documented escape hatch that nobody
            // forwarded is a test that can never run, which is what limn.video.test.clips was.
            // Forwarded only when set, so an absent one stays absent rather than becoming "".
            for (property in listOf("limn.video.test.clips")) {
                System.getProperty(property)?.let { systemProperty(property, it) }
            }
            // …and that one has a default now: the committed corpus in media/. Decoding a real
            // H.264 file is the only end-to-end evidence for the codec this repository ships and
            // cannot encode (ADR 027), so it runs by default rather than waiting for somebody to
            // remember a flag. A -D above still wins, for a developer with a larger corpus.
            val clips = rootProject.layout.projectDirectory.dir("media").asFile
            if (System.getProperty("limn.video.test.clips") == null && clips.isDirectory) {
                systemProperty("limn.video.test.clips", clips.absolutePath)
            }
        }

        // ---------------------------------------------------------------------
        // Architecture check (fails the build on a violation):
        //  1. java.awt.* / javax.swing.* / org.eclipse.swt.* forbidden in ALL
        //     modules (main and test);
        //  2. org.lwjgl.* forbidden outside :limn-backend-lwjgl: all access to
        //     GLFW/OpenGL/stb stays confined to the backend, behind the
        //     limn.backend SPI (a requirement for the ANGLE/Vulkan migration route).
        // ---------------------------------------------------------------------
        val moduleName = name
        val javaSources = fileTree(projectDir) {
            include("src/*/java/**/*.java")
        }
        val checkArchitecture = tasks.register("checkArchitecture") {
            description = "Fails if forbidden imports are found (AWT/Swing/SWT anywhere; LWJGL outside the backend)."
            group = "verification"
            inputs.files(javaSources)
            doLast {
                val forbiddenEverywhere = listOf("java.awt.", "javax.swing.", "org.eclipse.swt.")
                val lwjglAllowed = moduleName == "limn-backend-lwjgl"
                val importPattern = Regex("""^\s*import\s+(?:static\s+)?([A-Za-z_][A-Za-z0-9_.]*)""")
                val violations = mutableListOf<String>()
                javaSources.forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val match = importPattern.find(line) ?: return@forEachIndexed
                        val imported = match.groupValues[1]
                        val badCore = forbiddenEverywhere.any { imported.startsWith(it) }
                        val badLwjgl = !lwjglAllowed && imported.startsWith("org.lwjgl.")
                        if (badCore || badLwjgl) {
                            violations += "${file.path}:${index + 1}: forbidden import '$imported'"
                        }
                    }
                }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "Architecture violations in :$moduleName\n" + violations.joinToString("\n")
                    )
                }
            }
        }
        tasks.named("check") {
            dependsOn(checkArchitecture)
        }
    }
}

// ---------------------------------------------------------------------------- publishing
//
// Three destinations, and which one a task means:
//
//   publishToMavenLocal        the ~/.m2 that another build on this machine resolves from. It is
//                              how a consuming project (a composite build, an experiment) gets
//                              Limn as a real dependency rather than as a project reference.
//   publishAllPublicationsToBuildDirRepository
//                              a plain file repository under build/repo, for looking at what
//                              would ship without sending it anywhere.
//   publishToMavenCentral      the real one: uploads a signed bundle to the Central Portal, where
//                              it sits as a deployment until somebody presses Publish. The
//                              publish workflow runs this, off a tag, with -PlimnVersion.
//
// Central is not a repository you can point `maven { url = ... }` at: its API takes a bundle of
// the whole publication, signed, which is what the plugin applied here builds. A release it
// accepts is permanent, and it checks what a file repository never would: every artifact carries
// a signature, by a key it can find on a public keyserver. A -SNAPSHOT version goes to a separate
// repository under the same task, where neither of those holds and nothing is permanent.
subprojects {
    val moduleDescription = publishedModules[name] ?: return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")

    // Both ids, because natives-all is a `java-platform` — a POM and nothing else — and a gate
    // that only knew "java" left it publishing with none of the metadata below.
    listOf("java", "java-platform").forEach { pluginId ->
    plugins.withId(pluginId) {
        extensions.configure<MavenPublishBaseExtension> {
            // Uploads and stops. The last step stays a human pressing Publish on the Portal,
            // because that is the last moment at which a release can still be dropped: what
            // Central accepts, it keeps, and a wrong artifact cannot be replaced, only
            // superseded by a version number that nobody wanted to spend.
            publishToMavenCentral()

            // Signing is conditional on a key being configured, and the guard below is the other
            // half of that sentence. Unconditional, it would break the one workflow this
            // repository documents for everybody else: install.md tells a reader with no GPG key
            // to run publishToMavenLocal, and that reader is not releasing anything.
            if (providers.gradleProperty("signingInMemoryKey").isPresent ||
                    providers.gradleProperty("signing.keyId").isPresent) {
                signAllPublications()
            }

            pom {
                name.set(this@subprojects.name)
                description.set(moduleDescription)
                url.set("https://limn-toolkit.github.io/limn-toolkit")
                scm {
                    url.set("https://github.com/limn-toolkit/limn-toolkit")
                    connection.set(
                        "scm:git:https://github.com/limn-toolkit/limn-toolkit.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/limn-toolkit/limn-toolkit.git")
                }
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("dyorgio")
                        name.set("Dyorgio Nascimento")
                        url.set("https://github.com/dyorgio")
                    }
                }
            }
        }

        // The plugin configures the publication; this adds the local file repository back, which
        // is a different question from where a release goes and is worth keeping either way.
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "buildDir"
                    url = uri(rootProject.layout.buildDirectory.dir("repo"))
                }
            }
        }

        // LICENSE and NOTICE in every jar that leaves here — classes, sources and javadoc alike.
        // The POM names the licence, but a POM is not what a redistributor has in hand: NOTICE is
        // where the third-party terms are, and the Apache licence asks that it travel with the
        // work. A jar's reader has no repository to look in, which is the same reason the fonts
        // and the FFmpeg payload carry theirs.
        //
        // The jvm.tasks base type rather than the bundling one the DSL spells `Jar`: the
        // publishing plugin's javadoc jar extends the base directly, and the narrower type
        // matched the classes and sources jars while the javadoc jar shipped without.
        tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
            metaInf {
                from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
            }
        }
    }
    }
}

// What the conditional signing above must never become: a release that Central rejects on
// validation, or one it accepts unsigned. A missing key is a missing secret in the workflow, and
// the moment to say so is before anything leaves the machine.
//
// The check hangs off the task graph rather than off the tasks themselves, and that is the whole
// reason it is here rather than in the block above: `publishToMavenCentral` aggregates, so a
// doFirst on it runs AFTER the upload it depends on has already happened. whenReady is the last
// point that is still before execution, and it sees the graph for every module at once.
gradle.taskGraph.whenReady {
    val releasing = allTasks.any {
        it.name == "publishToMavenCentral" || it.name == "publishAndReleaseToMavenCentral" ||
                it.name.startsWith("publishAllPublicationsToMavenCentral")
    }
    if (!releasing) return@whenReady

    // A snapshot is not a release and is not held to a release's rules: the Portal routes it to
    // the snapshot repository, where it is replaceable and where a signature is optional. Said
    // out loud, because the task that sends it there is spelled exactly like the one that
    // releases, and the difference is a suffix in a version somebody passed on the command line.
    if (project.version.toString().endsWith("-SNAPSHOT")) {
        logger.lifecycle(
            "publishing ${project.version} to Central's SNAPSHOT repository. This is not a " +
                    "release: pass -PlimnVersion=<version> for one."
        )
        return@whenReady
    }

    if (!providers.gradleProperty("signingInMemoryKey").isPresent &&
            !providers.gradleProperty("signing.keyId").isPresent) {
        throw GradleException(
            "refusing to publish ${project.version} to Maven Central unsigned: no signing key " +
                    "is configured, and Central requires a signature on every artifact of a " +
                    "release. Set signingInMemoryKey and signingInMemoryKeyPassword as " +
                    "ORG_GRADLE_PROJECT_ environment variables (which is what " +
                    ".github/workflows/publish.yml does) — from a workstation, in an " +
                    "environment file outside the repository, sourced for the one command; " +
                    "never in ~/.gradle/gradle.properties, which every project on the machine " +
                    "reads (see RELEASING.md)."
        )
    }
}

// One Javadoc across every module, for the site's /api/. Aggregate rather than seven
// separate trees because the modules link to each other constantly: a @link from
// limn-video-ffmpeg to limn.scene.Widget resolves here and would be a dead word in a
// per-module build. Deliberately NOT wired into `check`: each module's own `javadoc`
// already is, and that is what guards the published jars.
val aggregateJavadoc = tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Javadoc for every module in one tree, for the public site's /api/."
    // Java projects that have Java, which is not the same set. limn-video-ffmpeg-natives-all
    // applies `java-library` to get a jar, a sources jar and a javadoc jar that Central asks for,
    // and has no source at all: its whole content is its POM. It has nothing to document, and
    // including it broke this task rather than adding an empty page — a module with no source
    // never compiles, so its compileClasspath is still unresolved when the line below reaches for
    // it, and Gradle refuses to resolve another project's configuration from a task that is
    // already running ("without an exclusive lock"). The six that do compile were resolved by
    // their own compileJava long before this.
    val documented = subprojects.filter { sub ->
        sub.plugins.hasPlugin("java") &&
                !sub.extensions.getByType<SourceSetContainer>()["main"].allJava.isEmpty
    }
    dependsOn(documented.map { "${it.path}:classes" })
    setDestinationDir(layout.buildDirectory.dir("docs/aggregate-javadoc").get().asFile)
    title = "Limn UI ${project.version}"
    source(documented.map { it.extensions.getByType<SourceSetContainer>()["main"].allJava })
    classpath = files(documented.map {
        it.extensions.getByType<SourceSetContainer>()["main"].compileClasspath
    })
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        // Explicit, because javadoc otherwise inherits the BUILD MACHINE's default locale
        // into every page's <html lang>: 404 English pages shipped declaring lang="pt"
        // from a pt-BR machine, and screen readers picked a Portuguese voice for all of
        // them. The site's generator asserts lang="en" and fails loudly on a regression.
        locale = "en"
        addBooleanOption("html5", true)
        // Same doclint set as the per-module task, and for the same reason: the four
        // groups kept ask whether the documentation that EXISTS is correct and resolvable.
        addBooleanOption("Xdoclint:all,-missing", true)
        links("https://docs.oracle.com/en/java/javase/17/docs/api/")
    }
}

// The root's `check` aggregates the modules'. Without this it is the base plugin's
// empty task: `./gradlew check` at the root passes having run nothing, and (worse)
// an including build that wires itself to `:check` (a composite build can only
// depend on named tasks, not on "everything") inherits that same green nothing.
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
