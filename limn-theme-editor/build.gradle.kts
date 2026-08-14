// limn-theme-editor: the screen an application embeds so its users can build a palette.
//
// It sits where limn-icons-tabler sits: it is built on a vocabulary the widget set
// publishes (limn.components.Theme, and the Theme.Builder that opened it up) and NOTHING
// depends on it, so an application that only wears a theme never ships the screen that
// authors one. The dependency can only point this way (see settings.gradle.kts).
//
// What comes out of the editor is a Theme, and ThemeFormat (in limn-components) is what
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
    api(project(":limn-components"))

    // The backend is for ThemeEditorApp, and for nothing else in this module. compileOnly plus
    // runtimeOnly rather than implementation, so the PUBLISHED jar still declares only
    // limn-components: an application that embeds ThemeEditor must not inherit a window toolkit
    // it already has, and the module keeps the property this file opens with. The consequence is
    // deliberate and small: ThemeEditorApp is a class that will not load without a backend on
    // the classpath, which is exactly what running it means.
    compileOnly(project(":limn-backend-lwjgl"))
    runtimeOnly(project(":limn-backend-lwjgl"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("limn.themeeditor.ThemeEditorApp")
    // Deliberately NOT applicationDefaultJvmArgs: -XstartOnFirstThread is macOS-only and would
    // be baked into the start scripts of every platform, where the JVM refuses to start on it.
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
