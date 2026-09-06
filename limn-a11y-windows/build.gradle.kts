// limn-a11y-windows: the UI Automation bridge, so that a screen reader on Windows can read a Limn
// window.
//
// A module of its own for the reason its Linux sibling gives, and one more of its own. Unlike that
// one it does call native code: UI Automation is a COM API, so this module reaches it through
// LWJGL's JNI trampoline and its shared-library loader rather than through a shim of our own (ADR
// 039 §10.2). That is the whole of its extra weight, and it is why an application that wants a
// reader on Windows adds this jar and not a binary.
//
// It depends on the toolkit for one seam and on the backend for the window handle, and reads
// nothing else: the tree it is handed is immutable, and nothing here touches a widget or a scene.
// A toolkit that never sees this module gets AccessibilityBridge.NONE and pays nothing.

plugins {
    `java-library`
}

// The classifiers LWJGL publishes its core native under. The backend declares all six because it
// is a library an application deploys anywhere; this one is a Windows bridge and ships two.
//
// The test runtime gets all six anyway, and that is not symmetry for its own sake. What the tests
// here exercise is the machinery under the bridge -- libffi closures, a vtable, a call through a
// function pointer -- and none of that is Windows-specific: a COM object is a structure whose
// first field points at an array of function pointers, and that is arithmetic and a calling
// convention. Being able to run it on whatever machine the repository is checked out on is the
// difference between a layer that is tested and one that is only compiled.
val lwjglNatives = listOf(
    "natives-windows", "natives-windows-arm64",
    "natives-macos", "natives-macos-arm64", "natives-linux", "natives-linux-arm64",
)

dependencies {
    api(project(":limn-toolkit"))
    // For org.lwjgl.system: the JNI trampoline, the libffi closures and the library loader. Not
    // for GLFW -- the bridge is handed a window handle and never opens one.
    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)

    val lwjglVersion = libs.versions.lwjgl.get()
    lwjglNatives.take(2).forEach { runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$it") }

    // For the live probe only: it opens a real window on the guest so that a real client can ask
    // this bridge for a tree. Nothing in the module's own sources knows what GLFW is.
    testImplementation(libs.lwjgl.glfw)
    lwjglNatives.forEach { testRuntimeOnly("org.lwjgl:lwjgl-glfw:${libs.versions.lwjgl.get()}:$it") }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    lwjglNatives.forEach { testRuntimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$it") }
}

// One jar with the probe, the module and everything either needs, so the guest can be handed a
// single file. The probe is a test source because it is not part of what an application gets.
tasks.register<Jar>("probeJar") {
    description = "A runnable jar of LiveProbe and its dependencies, for a run on the Windows guest."
    group = "verification"
    archiveFileName.set("limn-a11y-windows-probe.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "limn.a11y.windows.LiveProbe") }
    from(sourceSets.main.get().output, sourceSets.test.get().output)
    from(configurations.named("testRuntimeClasspath").map { classpath ->
        classpath.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
