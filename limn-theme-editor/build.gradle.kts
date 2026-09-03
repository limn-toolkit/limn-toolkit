// limn-theme-editor: the screen an application embeds so its users can build a palette.
//
// It sits where the icon pack sat before it moved out (ADR 038): it is built on a vocabulary the widget set
// publishes (limn.components.Theme, and the Theme.Builder that opened it up) and NOTHING
// depends on it, so an application that only wears a theme never ships the screen that
// authors one. The dependency can only point this way (see settings.gradle.kts).
//
// What comes out of the editor is a Theme, and ThemeFormat (in limn-toolkit) is what
// writes one down. That split is the reason an application can load a palette its designer
// saved without this module anywhere near the build.

plugins {
    `java-library`
    // The editor is also a program: `./gradlew :limn-theme-editor:run` opens it in a window,
    // so a designer can build a palette without writing an application first. That does not
    // make this module an application; see the dependency note below.
    application
}

dependencies {
    api(project(":limn-toolkit"))

    // The backend is for ThemeEditorApp, and for nothing else in this module. compileOnly plus
    // runtimeOnly rather than implementation, so the PUBLISHED jar still declares only
    // limn-toolkit: an application that embeds ThemeEditor must not inherit a window toolkit
    // it already has, and the module keeps the property this file opens with. The consequence is
    // deliberate and small: ThemeEditorApp is a class that will not load without a backend on
    // the classpath, which is exactly what running it means.
    compileOnly(project(":limn-backend-lwjgl"))
    runtimeOnly(project(":limn-backend-lwjgl"))

    // The two opt-in faces, so the artifact is COMPLETE when it is run rather than embedded:
    // the family picker in this screen lists every face the machine has and previews the palette
    // in it, and a theme editor that draws Chinese, Japanese, Korean or an emoji as an empty box
    // is not previewing anything. Runtime only, and an application that embeds the editor and
    // never draws those scripts excludes the two by name; the backend's own note on them
    // (limn-backend-lwjgl/build.gradle.kts) says what each weighs.
    runtimeOnly(libs.limn.fonts.noto.cjk)
    runtimeOnly(libs.limn.fonts.noto.emoji)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
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
// makes the coordinate runnable rather than merely resolvable. A Main-Class costs an embedding
// application nothing: the attribute is read only by a launcher handed the jar itself.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "limn.themeeditor.ThemeEditorApp",
            "Implementation-Title" to "Limn theme editor",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.named<JavaExec>("run") {
    // GLFW and Cocoa need the event loop on the process's first thread.
    if (System.getProperty("os.name").startsWith("Mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
