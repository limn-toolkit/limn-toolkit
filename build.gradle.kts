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

// The version a release carries, from versions.properties — the one file that names it. The
// release itself arrives as -PlimnVersion from the publish workflow (which took it from the tag,
// which tag-releases made from that same file); a working clone reads the -SNAPSHOT of it, which
// is also what publishToMavenLocal wants. Because landing the bump IS the release, that snapshot
// carries the LAST release's number (or the one about to land), not the next one's, and Maven
// orders 0.6.0-SNAPSHOT below 0.6.0: a consumer that wants the snapshot has to pin it exactly,
// and one that resolves a range or `latest` gets the release, never the snapshot.
val nextVersion = java.util.Properties().apply {
    file("versions.properties").inputStream().use { load(it) }
}.getProperty("limn-toolkit") ?: throw GradleException("versions.properties names no limn-toolkit version")

allprojects {
    group = "io.github.limn-toolkit"
    version = (findProperty("limnVersion") as String?) ?: "$nextVersion-SNAPSHOT"
}

// What is published, and what a one-line description of it says in the POM.
//
// Everything here is something an application legitimately puts on its classpath — including
// the theme editor, which it OPTS IN to, a module precisely so that choice exists — with one
// exception that is published for the opposite reason. limn-demo is not a library and no
// application should depend on it; it is published so that a stranger can RUN it, from its
// coordinate, with nothing cloned: `jbang io.github.limn-toolkit:limn-demo:<version>`. Its POM
// is shaped for that reader (see hostNativesModules below) and its description says so.
val publishedModules = mapOf(
    "limn-toolkit" to
            "The widget set, layout, the scene graph, the backend SPIs and the pure-Java video " +
            "decoders; depends on nothing.",
    "limn-theme-editor" to
            "The screen that authors a Theme; an application opts in. Also a program: the jar " +
            "names ThemeEditorApp as its Main-Class and the POM brings a backend and every " +
            "fallback face, so `jbang io.github.limn-toolkit:limn-theme-editor:<version>` opens it.",
    "limn-demo" to
            "The kitchen sink: every widget, the charts, the media player and the 3D viewport in " +
            "one window. An application, not a library — nothing should depend on it. Published " +
            "so that `jbang io.github.limn-toolkit:limn-demo:<version>` runs it from Maven " +
            "Central with nothing cloned; its POM selects the native libraries of the machine " +
            "it resolves on (Maven <os> profiles), so only that platform's are downloaded.",
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
        // The check has no product, only a verdict, and a task with inputs and no outputs is
        // one Gradle has to run every time: nothing on disk can say the verdict is still
        // current. The marker is that something. It is a file so the task can be up-to-date,
        // and cacheable, and it names the count so a reader of build/reports can see what
        // was looked at, which is all a passing check has to say.
        val marker = layout.buildDirectory.file("reports/architecture/ok.txt")
        val checkArchitecture = tasks.register("checkArchitecture") {
            description = "Fails if forbidden imports are found (AWT/Swing/SWT anywhere; LWJGL outside the backend)."
            group = "verification"
            // Relative, so a cache entry written on one machine is a hit on another: the
            // verdict depends on what the files say, not on where the checkout sits.
            inputs.files(javaSources)
                    .withPropertyName("javaSources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(marker).withPropertyName("marker")
            outputs.cacheIf { true }
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
                val checked = javaSources.files.size
                marker.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(":$moduleName: $checked source file(s), no forbidden import\n")
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

// ------------------------------------------------------- the natives a runnable artifact names
//
// limn-backend-lwjgl declares LWJGL's natives for every desktop target, and rightly so: it is a
// library, an application adds it once and deploys wherever it likes. A jar somebody RUNS from
// its coordinate is the opposite case. The machine resolving it is the machine running it, and
// the other five platforms' twelve megabytes are pure cost, downloaded to sit inert. So the two
// runnable modules publish a POM that says one platform at a time: LWJGL is excluded from every
// dependency that could carry it, the LWJGL modules are declared again plain, and each platform's
// natives — LWJGL's, and whatever natives-<os>-<arch> classifier the module names itself, which
// for the demo is the FFmpeg payload — sit in a <profile> keyed on the JVM's os.name and os.arch.
//
// Maven activates such profiles in a DEPENDENCY's POM, not only in the project's, and so does
// jbang: verified against 0.141 with an artifact of this shape installed to ~/.m2, `jbang info
// tools` resolved natives-macos-arm64 and nothing else, with the exclusion honoured, and jbang
// took the classifier of a GAV, a RELEASE metaversion, and -XstartOnFirstThread through either
// spelling of --java-options.
//
// Gradle cannot read a profile, and Gradle is not the reader this is for. The module metadata
// stays published beside the POM and still says what it always did — the backend, with every
// platform — so an application that takes limn-theme-editor from a Gradle build gets the
// classpath it got before. Two answers, one per reader, each the right one for what that reader
// can do with it; the sibling aggregator POMs switch their metadata off for the opposite reason,
// because there the two readers can do the same thing and must be told the same list.
val hostNativesModules = setOf("limn-demo", "limn-theme-editor")

// One row per desktop target, and how Maven tells it apart. The family names are Maven's, and
// Linux goes by os.name rather than by family because Maven's "unix" family includes macOS. The
// arch is what the JVM reports: every 64-bit ARM JVM says "aarch64", while 64-bit Intel is
// "amd64" on Linux and Windows and "x86_64" on macOS — and one profile cannot name two values,
// so the Intel rows say "not ARM", which is the only other 64-bit answer LWJGL ships for.
class HostPlatform(
    val id: String, val lwjglClassifier: String, val osKey: String, val osValue: String, val arch: String)
val hostPlatforms = listOf(
    HostPlatform("linux-aarch64", "natives-linux-arm64", "name", "linux", "aarch64"),
    HostPlatform("linux-x86_64", "natives-linux", "name", "linux", "!aarch64"),
    HostPlatform("macos-aarch64", "natives-macos-arm64", "family", "mac", "aarch64"),
    HostPlatform("macos-x86_64", "natives-macos", "family", "mac", "!aarch64"),
    HostPlatform("windows-aarch64", "natives-windows-arm64", "family", "windows", "aarch64"),
    HostPlatform("windows-x86_64", "natives-windows", "family", "windows", "!aarch64"),
)

fun groovy.util.Node.child(name: String): groovy.util.Node? =
    (get(name) as List<*>).firstOrNull() as groovy.util.Node?
fun groovy.util.Node.childText(name: String): String? = child(name)?.text()

// The LWJGL modules the backend uses and the classifiers it declares them under, READ from its
// build rather than copied here: the backend is where a ninth LWJGL module or a seventh target
// would be added, and this only redistributes what it says. The classifier set is checked
// against the table above so that a new target fails this build rather than resolving nothing.
class LwjglDeclaration(val modules: List<String>, val version: String)
fun lwjglAsDeclaredByTheBackend(): LwjglDeclaration {
    val backend = project(":limn-backend-lwjgl")
    val modules = backend.configurations.getByName("implementation").dependencies
        .filter { it.group == "org.lwjgl" && it.name != "lwjgl-bom" }
        .map { it.name }.distinct().sorted()
    val classifiers = backend.configurations.getByName("runtimeOnly").dependencies
        .filter { it.group == "org.lwjgl" }
        .flatMap { (it as ExternalModuleDependency).artifacts.mapNotNull { artifact -> artifact.classifier } }
        .toSet()
    val expected = hostPlatforms.map { it.lwjglClassifier }.toSet()
    if (modules.isEmpty() || classifiers != expected) {
        throw GradleException(
            "limn-backend-lwjgl declares LWJGL modules $modules under classifiers $classifiers, " +
                    "but the host-natives table in the root build knows $expected: update the table.")
    }
    return LwjglDeclaration(modules, libs.versions.lwjgl.get())
}

fun groovy.util.Node.appendDependency(
        group: String, artifact: String, version: String, classifier: String? = null) {
    appendNode("dependency").apply {
        appendNode("groupId", group)
        appendNode("artifactId", artifact)
        appendNode("version", version)
        classifier?.let { appendNode("classifier", it) }
        appendNode("scope", "runtime")
    }
}

/** Reshapes a generated POM so that it names one platform's natives at a time. */
fun shapeForHost(pom: groovy.util.Node, group: String) {
    val lwjgl = lwjglAsDeclaredByTheBackend()
    val dependencies = pom.child("dependencies") ?: pom.appendNode("dependencies")
    val perPlatform = hostPlatforms.associate { it.id to mutableListOf<groovy.util.Node>() }
    for (dependency in dependencies.children().filterIsInstance<groovy.util.Node>().toList()) {
        val classifier = dependency.childText("classifier")
        if (classifier != null && classifier.startsWith("natives-")) {
            // The module's own per-platform payload, declared six times so that the fat jar
            // carries all of them: in the POM each goes into its platform's profile instead.
            val platform = perPlatform[classifier.removePrefix("natives-")]
                ?: throw GradleException("$classifier names a platform the host-natives table does not know")
            dependencies.remove(dependency)
            platform += dependency
        } else if (dependency.childText("groupId") == group &&
                dependency.childText("artifactId") in publishedModules) {
            // Every path that could reach the backend's all-platform LWJGL is cut, and only a
            // module of this build can be such a path: the fonts and the icon pack, published
            // from their own repositories, name no backend. An exclusion that matches nothing
            // would be harmless, but a reader of the POM would still have to work that out.
            dependency.appendNode("exclusions").appendNode("exclusion").apply {
                appendNode("groupId", "org.lwjgl")
                appendNode("artifactId", "*")
            }
        }
    }
    lwjgl.modules.forEach { module -> dependencies.appendDependency("org.lwjgl", module, lwjgl.version) }
    val profiles = pom.appendNode("profiles")
    hostPlatforms.forEach { platform ->
        val profile = profiles.appendNode("profile")
        profile.appendNode("id", "natives-${platform.id}")
        profile.appendNode("activation").appendNode("os").apply {
            appendNode(platform.osKey, platform.osValue)
            appendNode("arch", platform.arch)
        }
        val natives = profile.appendNode("dependencies")
        lwjgl.modules.forEach { module ->
            natives.appendDependency("org.lwjgl", module, lwjgl.version, platform.lwjglClassifier)
        }
        perPlatform.getValue(platform.id).forEach { natives.append(it) }
    }
}

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
            if (this@subprojects.name in hostNativesModules) {
                publications.withType<MavenPublication>().configureEach {
                    pom.withXml { shapeForHost(asNode(), this@subprojects.group.toString()) }
                }
            }
        }

        // The shape above is a promise to a reader who is not here — a POM is read on a machine
        // this build never sees — so `check` reads the POM this build would ship and refuses one
        // that names a native outside its profile or leaves a path to the backend's full set open.
        if (this@subprojects.name in hostNativesModules) {
            // Matched by name rather than looked up: the publishing plugin registers the task
            // later than this block runs, and a lookup would find nothing to depend on.
            val generatePom = tasks.withType<GenerateMavenPom>()
                .matching { it.name == "generatePomFileForMavenPublication" }
            val checkHostNativesPom = tasks.register("checkHostNativesPom") {
                group = "verification"
                description = "Fails if the published POM names natives outside the platform profiles."
                dependsOn(generatePom)
                val pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
                val group = this@subprojects.group.toString()
                inputs.file(pomFile)
                doLast {
                    val pom = groovy.xml.XmlParser(false, false).parse(pomFile.get().asFile)
                    val plain = pom.child("dependencies")?.children()?.filterIsInstance<groovy.util.Node>().orEmpty()
                    val problems = mutableListOf<String>()
                    plain.filter { it.childText("classifier")?.startsWith("natives-") == true }
                        .forEach { problems += "${it.childText("artifactId")}:${it.childText("classifier")} sits outside every profile" }
                    plain.filter {
                        it.childText("groupId") == group && it.childText("artifactId") in publishedModules &&
                                it.child("exclusions") == null
                    }
                        .forEach { problems += "${it.childText("artifactId")} can still reach LWJGL's natives for every platform" }
                    val lwjglModules = plain.filter { it.childText("groupId") == "org.lwjgl" }.map { it.childText("artifactId") }
                    if (lwjglModules.isEmpty()) problems += "no LWJGL module is declared plain"
                    val profiles = pom.child("profiles")?.children()?.filterIsInstance<groovy.util.Node>().orEmpty()
                    val ids = profiles.map { it.childText("id") }
                    val expected = hostPlatforms.map { "natives-${it.id}" }
                    if (ids != expected) problems += "profiles are $ids, expected $expected"
                    profiles.forEach { profile ->
                        val natives = profile.child("dependencies")?.children()?.filterIsInstance<groovy.util.Node>().orEmpty()
                        val lwjglNatives = natives.filter { it.childText("groupId") == "org.lwjgl" }
                        if (lwjglNatives.map { it.childText("artifactId") } != lwjglModules) {
                            problems += "${profile.childText("id")} does not carry one native per plain LWJGL module"
                        }
                        if (natives.any { it.childText("classifier") == null }) {
                            problems += "${profile.childText("id")} carries a dependency without a classifier"
                        }
                    }
                    if (problems.isNotEmpty()) {
                        throw GradleException(
                            "${pomFile.get()} would not resolve one platform at a time:\n  " +
                                    problems.joinToString("\n  "))
                    }
                }
            }
            tasks.named("check") { dependsOn(checkHostNativesPom) }
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
