// limn-test: what an application needs to test its own Limn UI without a display, and what this
// repository's own tests use (ADR 046 §4).
//
//   SceneDriver         clicks, keys, text, focus and the close, delivered to a scene as a window
//                       would, through the input the scene hands its window and nothing else
//   HeadlessUi          the UI runtime on a thread and a clock the test owns
//   HeadlessBackend     windows with no platform behind them, whose bridge keeps the last
//                       accessible tree; dialogs and popups that open windows open here
//   StubWindow, NoopCanvas, TestRulers
//                       the doubles a scene is bound to, painted into and measured by
//   AccessibleHarness, RecordingAccessibilityBridge, limn.testing.a11y
//                       the accessibility contracts the toolkit's widgets are held to, for a
//                       widget of one's own
//
// It depends on the toolkit and nothing else: no JUnit, so it works under whichever test framework
// the application runs. It used to be limn-toolkit's test fixtures, used by this build and
// published nowhere; the scene's input leaving the public API is what made publishing it owed.

plugins {
    `java-library`
}

dependencies {
    api(project(":limn-toolkit"))
}
