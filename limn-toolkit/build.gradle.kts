// limn-toolkit is the base of the stack: the 2D Canvas API and geometry primitives
// (limn.graphics), the widget/event/focus/layout tree (limn.scene), the concurrency
// runtime (limn.concurrent), the 3D scene vocabulary (limn.render3d), and the SPIs
// a backend implements, namely audio (limn.sound), video (limn.video) and the window and
// renderer contracts (limn.backend).
//
// It depends on NOTHING, and that is the boundary the whole stack rests on: no
// LWJGL, no AWT, no third-party anything. A dependency added here is a dependency
// every application carries, which is why checkArchitecture fails the build over it.

plugins {
    `java-library`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// RepositoryNoticeTest reads the repository's NOTICE, which is outside every input Gradle infers
// for a test task. Declared, so an edit to NOTICE reruns the test instead of replaying a green
// result from the build cache — on a runner with a warm cache that is the difference between a
// check and a memory of one.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("NOTICE")).withPathSensitivity(PathSensitivity.NONE)
}
