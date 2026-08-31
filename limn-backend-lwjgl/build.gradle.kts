// limn-backend-lwjgl: the ONLY module allowed to import org.lwjgl.* (enforced
// by the checkArchitecture task). Implements the limn.backend SPI of limn-toolkit
// with GLFW (windows/input/clipboard), OpenGL 3.3 core (rendering) and stb (fonts, image decode).

plugins {
    `java-library`
}

// Every platform this backend runs on, and the classifier LWJGL publishes it under.
//
// The left column exists because the right one is a trap: LWJGL spells its 64-bit Intel builds
// with no architecture at all, so `natives-macos` IS x86_64 and reads like "macOS". Selecting by
// host used to hide that: an x86_64 JDK under Rosetta on an Apple Silicon Mac picked
// `natives-macos`, which is correct for that JVM and wrong for the machine, and nothing said the
// word "architecture" anywhere.
//
// All six are declared, always, rather than the host's. This is a library: an application adds
// one dependency and it has to work wherever it is deployed, including on a machine that is not
// the one that built it. LWJGL loads only the slice matching the running JVM; the others sit
// inert on the classpath and cost download size, not startup.
val lwjglTargets = mapOf(
    "macos-x64" to "natives-macos",
    "macos-arm64" to "natives-macos-arm64",
    "windows-x64" to "natives-windows",
    "windows-arm64" to "natives-windows-arm64",
    "linux-x64" to "natives-linux",
    "linux-arm64" to "natives-linux-arm64",
)

dependencies {
    api(project(":limn-toolkit"))

    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.openal)
    implementation(libs.lwjgl.stb)
    // Shaping. stb draws a glyph INDEX; HarfBuzz is what decides which indices and where, which
    // is the only way a script with contextual forms, ligatures or reordering is drawable at all.
    // Nothing is replaced: HarfBuzz chooses the glyphs, stb still rasterizes them. Confined here
    // like every other native, so nothing above this backend learns the word HarfBuzz.
    implementation(libs.lwjgl.harfbuzz)
    implementation(libs.lwjgl.nanovg) // NanoSVG (rasterizes SVG icons → RGBA, no AWT)
    implementation(libs.lwjgl.tinyfd) // native open/save/folder dialogs (no AWT)
    // MP3 decoding: JLayer is pure Java (no natives), the classic javazoom
    // decoder. NOTE: LGPL-licensed, kept as an isolated jar dependency.
    implementation(libs.jlayer)

    val lwjglVersion = libs.versions.lwjgl.get()
    listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb", "lwjgl-harfbuzz",
            "lwjgl-nanovg", "lwjgl-tinyfd").forEach { module ->
        lwjglTargets.values.forEach { classifier ->
            runtimeOnly("org.lwjgl:$module:$lwjglVersion:$classifier")
        }
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Test-only, and deliberately here rather than in the pack: the pack depends on the
    // toolkit alone and so cannot rasterize anything, while this module owns the only
    // rasterizer there is. Somebody has to draw all six thousand of them once.
    testImplementation(project(":limn-icons-tabler"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Some tests here verify GPU behaviour against a REAL context (the video colour
// conversion is pinned numerically to the CPU reference converter, which needs a
// device to run on). The context comes from a hidden GLFW window, and on macOS
// Cocoa only lets the process's FIRST thread create one; the Gradle test worker
// runs on the JVM's main thread, so the flag is all it takes. Without it LWJGL's
// own thread check throws, which the harness turns into a skipped test rather
// than the SIGABRT Cocoa would raise if that check were ever disabled. macOS
// only: no other JVM recognizes the flag, and one given it refuses to start.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}

// The fonts are vendored binaries, and a sources jar is not where a binary belongs.
//
// They reach the main source set as resources, which is exactly what a sources jar copies as
// well: 28 MB of faces beside 0.25 MB of source, the same type design twice, and none of it
// answers the question a sources jar exists to answer. The pan-CJK face alone is 16 MB and the
// colour emoji face 10 MB; see the README beside them.
//
// Matched by extension in that directory rather than by name, which is why the four
// complex-script faces needed no change here and the next one will not either. A face added under
// some other extension would slip through, and that is the trade: a name list misses the next
// file for certain, an extension list only for a format nothing here uses.
//
// The licences and that README stay, because those are text and they are what a reader who opens
// this directory actually needs. Same trade, same three lines, as limn-video-ffmpeg makes for its
// native payload.
tasks.named<Jar>("sourcesJar") {
    exclude("limn/backend/lwjgl/fonts/*.ttf", "limn/backend/lwjgl/fonts/*.otf")
}
