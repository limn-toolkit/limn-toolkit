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

dependencies {
    api(project(":limn-toolkit"))
    // For org.lwjgl.system: the JNI trampoline, the shared-library loader and MemoryStack. Not for
    // GLFW -- the bridge is handed a window handle and never opens one.
    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
