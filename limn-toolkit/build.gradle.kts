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
    // src/testFixtures holds what this repository's tests need and no application does:
    // RepositoryRoot, for a test that reads a file of the checkout's. The doubles every module's
    // tests share — the headless runtime and backend, the stub window, the null canvas, the fixed
    // ruler, the scene driver — are the published limn-test module since ADR 046 §4.
    `java-test-fixtures`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":limn-test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The fixtures jar must not reach Maven Central: the java component would otherwise publish it
// beside the toolkit as limn-toolkit-test-fixtures, a jar with no consumer outside this build.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
// The fixtures' sources jar is a variant the publishing plugin adds after this script has run,
// so it is skipped once the project is evaluated and only if that plugin added it.
afterEvaluate {
    configurations.findByName("testFixturesSourcesElements")?.let { sources ->
        javaComponent.withVariantsFromConfiguration(sources) { skip() }
    }
}

// RepositoryNoticeTest reads the repository's NOTICE, which is outside every input Gradle infers
// for a test task. Declared, so an edit to NOTICE reruns the test instead of replaying a green
// result from the build cache — on a runner with a warm cache that is the difference between a
// check and a memory of one.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("NOTICE")).withPathSensitivity(PathSensitivity.NONE)
}
