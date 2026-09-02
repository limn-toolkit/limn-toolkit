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

    // The faces, as artifacts (ADR 036): each versions with its font, so a toolkit release
    // stops re-shipping megabytes that did not change and an application's cache keeps them
    // across upgrades.
    //
    // Roboto is REQUIRED — it is the default family and the last resort every resolve degrades
    // to, and FontStore fails construction with a message naming this artifact if a build
    // manages to exclude it. The complex-script faces come by default too, at 1.1 MB: without
    // them Arabic, Hebrew, Devanagari and Thai shape into .notdef boxes out of the box, which
    // is not a default worth shipping (ADR 006, ADR 032). The two heavyweights — pan-CJK at
    // 16 MB and colour emoji at 10 MB — are the application's choice, like the icon pack: add
    // limn-fonts-noto-cjk / limn-fonts-noto-emoji (or limn-fonts-all for everything at once).
    runtimeOnly(libs.limn.fonts.roboto)
    runtimeOnly(libs.limn.fonts.noto.scripts)

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
    testRuntimeOnly(libs.junit.platform.launcher)
    // The opt-in faces, for the tests only: the CJK/emoji fallback chain, the colour-emoji
    // pipeline and VendoredFontsTest's digests all exercise what an APPLICATION may add, and
    // somebody has to keep exercising it. An application that adds neither ships neither.
    testRuntimeOnly(libs.limn.fonts.noto.cjk)
    testRuntimeOnly(libs.limn.fonts.noto.emoji)
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

// The one font still vendored here is a binary, and a sources jar is not where a binary belongs.
//
// It is three kilobytes now — the menu key symbols, authored by this project, the only face that
// survived ADR 036's move of every other font into the limn-fonts artifacts — but the rule is
// about kind, not size, and keeping it means the next vendored binary is excluded before anyone
// re-learns why. The licence and the README beside it stay: those are text, and they are what a
// reader who opens the directory actually needs.
tasks.named<Jar>("sourcesJar") {
    exclude("limn/backend/lwjgl/fonts/*.ttf", "limn/backend/lwjgl/fonts/*.otf")
}
