// Limn UI: multi-module build root.
//
// Layers (conceptual, top to bottom):
//   limn-demo → limn-components → limn-toolkit ← limn-backend-lwjgl
//   limn-demo also depends on limn-video (decoders), which depends on limn-toolkit and
//   nothing else, so a codec dependency can never reach a base module.
//
// The backend module IMPLEMENTS the SPI (limn.backend.*) defined in limn-toolkit
// (dependency inversion): no module above it sees LWJGL/OpenGL.

plugins {
    base
}

allprojects {
    group = "dev.limn"
    version = "0.1.0-SNAPSHOT"
}

// What is published, and what a one-line description of it says in the POM.
//
// limn-demo is absent on purpose: it is the kitchen sink and the verification scenes, not a
// library, and publishing it would invite an application to depend on it. Everything else is
// something an application legitimately puts on its classpath, including the two it OPTS IN to
// (the icon pack and the theme editor), which are modules precisely so that choice exists.
val publishedModules = mapOf(
    "limn-toolkit" to "Widgets, layout, scene graph and the backend SPIs; depends on nothing.",
    "limn-components" to "The widget set: buttons, fields, lists, menus, dialogs, charts.",
    "limn-icons-tabler" to "The Tabler icon pack as Limn icons; an application opts in.",
    "limn-theme-editor" to "The screen that authors a Theme; an application opts in.",
    "limn-video" to "Pure-Java video decoders: no native, no third-party dependency.",
    "limn-video-ffmpeg" to
            "H.264/AAC in MP4 via FFmpeg. Carries native libraries for macOS (aarch64, x86_64) " +
            "under LGPL-2.1-or-later; see NOTICE-ffmpeg.txt in the jar. On any other platform " +
            "the decoder reports itself unavailable and the rest of the toolkit is unaffected.",
    "limn-backend-lwjgl" to "The LWJGL backend: GLFW, OpenGL and stb behind the toolkit's SPIs.",
)

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            // Only for what is published, and it is not a formality: this repository splits its
            // documentation on the premise that a member's contract stands alone because a user
            // with the artifact has no repository. The jar is where that promise is kept.
            if (name in publishedModules) {
                withJavadocJar()
            }
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
// Two repositories, both local, because there is no remote to publish to yet:
//
//   publishToMavenLocal        the ~/.m2 that another build on this machine resolves from. It is
//                              how a consuming project (a composite build, an experiment) gets
//                              Limn as a real dependency rather than as a project reference.
//   publishAllPublicationsToBuildDirRepository
//                              a plain file repository under build/repo, for looking at what
//                              would ship, or for a CI to attach.
//
// Adding a remote later is a `repositories { maven { url = ... } }` here and credentials; the
// POM below is already what a remote would want, minus the URL and SCM, which are omitted rather
// than invented because this repository has no remote.
subprojects {
    val description = publishedModules[name] ?: return@subprojects
    apply(plugin = "maven-publish")

    plugins.withId("java") {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(this@subprojects.name)
                        this.description.set(description)
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                name.set("Dyorgio Nascimento")
                            }
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "buildDir"
                    url = uri(rootProject.layout.buildDirectory.dir("repo"))
                }
            }
        }
    }
}

// One Javadoc across every module, for the site's /api/. Aggregate rather than seven
// separate trees because the modules link to each other constantly: a @link from
// limn-components to limn.scene.Widget resolves here and would be a dead word in a
// per-module build. Deliberately NOT wired into `check`: each module's own `javadoc`
// already is, and that is what guards the published jars.
val aggregateJavadoc = tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Javadoc for every module in one tree, for the public site's /api/."
    val documented = subprojects.filter { it.plugins.hasPlugin("java") }
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
