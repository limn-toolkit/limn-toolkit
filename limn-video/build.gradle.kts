// limn-video: video decoders, and nothing else. The toolkit publishes the
// vocabulary (limn.video: frames, the pull source, the decoder facade); this module
// implements it.
//
// It exists so that a codec dependency has somewhere to land that is NOT a base
// module: limn-toolkit depends on nothing, limn-components and limn-backend-lwjgl
// do not depend on this, and an application that never plays video never pulls a
// decoder onto its classpath. The decoders here are pure Java and carry no native
// and no third-party dependency at all; the native ones arrive later, behind the
// same SPI, and land here rather than anywhere above.
//
// The application installs decoders, not the backend: limn-demo calls
// Videos.installDecoder at startup, the way any application would.

plugins {
    `java-library`
}

dependencies {
    api(project(":limn-toolkit"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
