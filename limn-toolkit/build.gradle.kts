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
