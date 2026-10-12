// limn-toolkit is the base of the stack: the 2D Canvas API and geometry primitives
// (limn.graphics), the widget/event/focus/layout tree (limn.scene), the concurrency
// runtime (limn.concurrent), the 3D scene vocabulary (limn.render3d), and the SPIs
// a backend implements, namely audio (limn.sound), video (limn.video) and the window and
// renderer contracts (limn.backend).
//
// It depends on NOTHING that runs, and that is the boundary the whole stack rests on: no
// LWJGL, no AWT, no third-party code. A dependency added here is a dependency every
// application carries. The one exception is JSpecify, whose four annotations say where the
// public API takes or answers null: no class of it is loaded at run time, and module-info
// requires it `static`, so a modular application that leaves it off its path starts all the
// same.

plugins {
    `java-library`
    // src/testFixtures holds what this repository's tests need and no application does:
    // RepositoryRoot, for a test that reads a file of the checkout's. The doubles every module's
    // tests share — the headless runtime and backend, the stub window, the null canvas, the fixed
    // ruler, the scene driver — are the published limn-test module since ADR 046 §4.
    `java-test-fixtures`
}

dependencies {
    // api, so the POM names it at compile scope: an application's compiler, IDE, Kotlin or
    // NullAway reads @Nullable on this module's signatures only with the annotations on its own
    // compile path. module-info's `requires static transitive` is the module path's half of the
    // same promise.
    api(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":limn-test"))
    testFixturesImplementation(project(":limn-test"))
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
