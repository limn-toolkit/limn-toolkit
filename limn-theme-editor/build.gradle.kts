// limn-theme-editor: the program that authors a palette and saves it as a .limntheme file.
//
// A program, not a library: no application depends on it. What comes out of the editor is a
// Theme, and ThemeFormat (in limn-toolkit) is what writes one down, so an application loads a
// palette its designer saved without this module anywhere near its build. The classes here are
// public because the demo shows the editor as one of its screens, not because they are API, and
// /api/ leaves the package out.
//
// It sits where the icon pack sat before it moved out (ADR 038): it is built on a vocabulary the
// widget set publishes (limn.components.Theme, and the Theme.Builder that opened it up) and
// nothing in the toolkit or the backend depends on it. The dependency can only point this way
// (see settings.gradle.kts).

plugins {
    `java-library`
    // `./gradlew :limn-theme-editor:run` opens the editor in a window. java-library beside it
    // because the demo takes the editor as a project dependency, to show it as a screen.
    application
}

dependencies {
    api(project(":limn-toolkit"))

    // The backend is for ThemeEditorApp, and for nothing else in this module. compileOnly plus
    // runtimeOnly rather than implementation: the published POM names the backend at runtime
    // scope, which is what lets `jbang` run the editor from its coordinate, and the demo, which
    // shows the editor as a screen, brings its own. ThemeEditorApp is a class that will not load
    // without a backend on the classpath, which is exactly what running it means.
    compileOnly(project(":limn-backend-lwjgl"))
    runtimeOnly(project(":limn-backend-lwjgl"))

    // The two opt-in faces, so the program is COMPLETE: the family picker in this screen lists
    // every face the machine has and previews the palette in it, and a theme editor that draws
    // Chinese, Japanese, Korean or an emoji as an empty box is not previewing anything. Runtime
    // only; the backend's own note on them (limn-backend-lwjgl/build.gradle.kts) says what each
    // weighs.
    runtimeOnly(libs.limn.fonts.noto.cjk)
    runtimeOnly(libs.limn.fonts.noto.emoji)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":limn-test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("limn.themeeditor.ThemeEditorApp")
    // Deliberately NOT applicationDefaultJvmArgs: -XstartOnFirstThread is macOS-only and would
    // be baked into the start scripts of every platform, where the JVM refuses to start on it.
}

// The same main class, named in the PUBLISHED jar's manifest, so that the coordinate is enough:
// `jbang io.github.limn-toolkit:limn-theme-editor:<version>` opens the editor, with no --main to
// remember and nothing cloned. The POM that jar ships with names a backend for the machine
// resolving it and every fallback face (see the root build's host-natives note), which is what
// makes the coordinate runnable rather than merely resolvable.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "limn.themeeditor.ThemeEditorApp",
            "Implementation-Title" to "Limn theme editor",
            "Implementation-Version" to project.version,
        )
    }
}

val hostIsMacOs = rootProject.extra["hostIsMacOs"] as Boolean
tasks.named<JavaExec>("run") {
    // GLFW and Cocoa need the event loop on the process's first thread.
    if (hostIsMacOs) {
        jvmArgs("-XstartOnFirstThread")
    }
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // LWJGL still reads memory through sun.misc.Unsafe; without this every run on 24+ opens
        // with four lines of warning about it.
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
}
