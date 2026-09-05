// limn-a11y-linux: the AT-SPI2 bridge, so that a screen reader on Linux can read a Limn window.
//
// A module of its own, and not a package in the backend, because the platform accessibility API on
// this system is not a C API at all: it is a D-Bus protocol, and Java speaks it over
// java.nio.channels.SocketChannel and java.net.UnixDomainSocketAddress. So this module calls no
// native function, links no library and imports no org.lwjgl — which is why it can be tested on
// any machine and why an application that wants a reader on Linux adds one pure-Java jar.
//
// It depends on the toolkit for one seam and reads nothing else: limn.backend.AccessibilityBridge
// is ten members, the tree it is handed is immutable, and nothing here touches a widget or a
// scene. An application installs it by handing the bridge to its window; a toolkit that never
// sees this module gets AccessibilityBridge.NONE and pays nothing.

plugins {
    `java-library`
}

dependencies {
    api(project(":limn-toolkit"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
