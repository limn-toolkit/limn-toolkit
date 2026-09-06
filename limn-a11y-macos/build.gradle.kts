// limn-a11y-macos: the NSAccessibility bridge, so that VoiceOver can read a Limn window.
//
// A module of its own for the reason its two siblings give (ADR 039, and the split that made the
// bridges their own artifacts). Like the Windows one and unlike the Linux one it calls native code:
// NSAccessibility is an Objective-C API, so this module reaches it through LWJGL's ObjC runtime
// bindings, its JNI trampoline and libffi rather than through a shim of our own (§10.2). That is
// the whole of its extra weight, and it is why an application that wants VoiceOver adds this jar
// and not a binary.
//
// It depends on the toolkit for one seam and on the backend for the window handle, and reads
// nothing else: the tree it is handed is immutable, and nothing here touches a widget or a scene.
// A toolkit that never sees this module gets AccessibilityBridge.NONE and pays nothing.

plugins {
    `java-library`
}

// The classifiers LWJGL publishes its core native under. The backend declares all six because it is
// a library an application deploys anywhere; this one is a macOS bridge and ships two.
//
// The test runtime gets all six anyway, for the reason the Windows module gives: what the tests
// here exercise is the machinery under the bridge -- a role table, a coordinate flip, a name's
// provenance, a libffi closure -- and none of that is macOS-specific. Being able to run it on
// whatever machine the repository is checked out on is the difference between a layer that is
// tested and one that is only compiled.
val lwjglNatives = listOf(
    "natives-macos", "natives-macos-arm64",
    "natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64",
)

dependencies {
    api(project(":limn-toolkit"))
    // For org.lwjgl.system: the ObjC runtime bindings, the JNI trampoline, the libffi closures and
    // the library loader. Not for GLFW -- the bridge is handed a window handle and never opens one.
    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)

    val lwjglVersion = libs.versions.lwjgl.get()
    lwjglNatives.take(2).forEach { runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$it") }

    // For the live probe only, which opens a real window on the guest and paints a real scene in
    // it so that a screen reader and a person are looking at the same thing. Nothing in the
    // module's own sources knows what a backend is.
    testImplementation(project(":limn-backend-lwjgl"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    lwjglNatives.forEach { testRuntimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$it") }
}

// One jar with the probe, the module and everything either needs, so the guest can be handed a
// single file. The probe is a test source because it is not part of what an application gets.
tasks.register<Jar>("probeJar") {
    description = "A runnable jar of LiveProbe and its dependencies, for a run on the macOS guest."
    group = "verification"
    archiveFileName.set("limn-a11y-macos-probe.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "limn.a11y.macos.LiveProbe") }
    from(sourceSets.main.get().output, sourceSets.test.get().output)
    from(configurations.named("testRuntimeClasspath").map { classpath ->
        classpath.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
