// limn-demo: the "kitchen sink" application and --screenshot mode for visual verification.

plugins {
    application
}

dependencies {
    implementation(project(":limn-components"))
    implementation(project(":limn-backend-lwjgl"))
    // The APPLICATION ships decoders, not the backend: limn-backend-lwjgl must never see these
    // modules, or which decoders exist would stop being the application's decision.
    implementation(project(":limn-icons-tabler"))
    // Same shape as the icon pack: a module nothing in the toolkit depends on, taken here
    // because the demo is an application and this one is a screen an application embeds.
    implementation(project(":limn-theme-editor"))
    implementation(project(":limn-video"))
    // The one dependency in this build with a native payload, and the demo is the only thing that
    // takes it. Its library is not committed and not built by Gradle, so on a machine that never
    // ran scripts/build-ffmpeg.sh this contributes some classes that report themselves
    // unavailable; the demo still builds, still runs, and still plays everything else.
    implementation(project(":limn-video-ffmpeg"))
    // (limn-components → limn-scene → limn-toolkit arrive via api)
}

val isMacOs = System.getProperty("os.name").lowercase().contains("mac")

// Both site tasks are deliberately NOT wired into `check` or into any assemble path: the
// site is a consumer of this repository, not a module of it, and `./gradlew check` has to
// stay exactly what it was.
//
// Renders every gallery entry in both palettes and writes the manifest the site's
// component pages are built from. Needs a GL context: on a headless Linux machine run it
// under `xvfb-run -a` with LIBGL_ALWAYS_SOFTWARE=1, which is what the workflow does.
tasks.register<JavaExec>("captureGallery") {
    group = "documentation"
    description = "Renders the component gallery captures to site/captures/ with a manifest."
    mainClass.set("limn.demo.site.Gallery")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    // The pristine captures, deliberately outside site/public/: `pnpm build:gallery` reads
    // them and writes only derivatives into public/gallery/. Pointing this back at
    // site/public/ would make the derivation's output its own input on the next run, and
    // every crop would run twice.
    args("site/captures")
    // macOS-only, exactly as the `run` task does it: GLFW/Cocoa need the event loop on the
    // process's first thread, and a JVM that does not know the flag refuses to start.
    if (isMacOs) {
        jvmArgs("-XstartOnFirstThread")
    }
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// The site's colours ARE this toolkit's palettes, so it reads them from the palettes rather
// than from a transcription. Needs no window and no GL context.
tasks.register<JavaExec>("exportThemeTokens") {
    group = "documentation"
    description = "Writes the Limn/Limn Light palettes to site/src/styles/tokens.generated.css."
    mainClass.set("limn.demo.site.ThemeTokens")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    args("site/src/styles/tokens.generated.css")
}

application {
    mainClass.set("limn.demo.Main")
    // Do not set -XstartOnFirstThread in applicationDefaultJvmArgs: the value would
    // be baked statically into the dist start scripts of ALL platforms
    // (the flag is macOS-only and aborts the JVM on Windows/Linux).
}

tasks.named<JavaExec>("run") {
    // Relative --screenshot paths resolve from the repository root.
    workingDir = rootDir
    // The video tab's caption tells the reader to set this, and a JavaExec starts a fresh JVM that
    // inherits nothing, so without this line the documented instruction silently does nothing and
    // the generated clip plays instead, which looks like the property being ignored on purpose.
    System.getProperty("limn.demo.video")?.let { systemProperty("limn.demo.video", it) }
    // GLFW/Cocoa require the event loop to run on the first thread of the process.
    if (isMacOs) {
        jvmArgs("-XstartOnFirstThread")
    }
    // JDK 24 restricted System.loadLibrary (JEP 472): today it prints four lines of warning on
    // every run, and a future release will refuse the call outright. Added only where the JVM
    // knows the option: passing it to a JDK that does not is not a warning, it is a JVM that
    // will not start, which is the same trap -XstartOnFirstThread carries on Windows and Linux.
    // Applications that ship this decoder need the same flag; see FfmpegLibrary's documentation.
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
